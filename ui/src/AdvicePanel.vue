<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ApiError, cancelAdviceJob, getAdvice, getAdviceJob, startAdvice } from './api'
import { ADVICE_LABELS } from './shell/labels.advice'
import { apiFailureView, jobView, provenanceLines } from './shell/advice'
import type { AdviceDocument, AdviceJob, AnalysisReference } from './types'

const props = defineProps<{ selection: AnalysisReference; autoStart?: boolean }>()
const emit = defineEmits<{ 'auto-started': [] }>()
const advice = ref<AdviceDocument | null>(null)
const job = ref<AdviceJob | null>(null)
const consent = ref(false)
const sending = ref(false)
const error = ref('')
const errorCode = ref('')
const provenance = computed(() => (advice.value ? provenanceLines(advice.value) : []))
const jobInfo = computed(() => (job.value ? jobView(job.value) : null))
const errorInfo = computed(() => (errorCode.value && error.value ? apiFailureView(errorCode.value, error.value) : null))
const busy = computed(() => sending.value || job.value?.state === 'QUEUED' || job.value?.state === 'PROCESSING')
let revision = 0
let timer: ReturnType<typeof setTimeout> | undefined

function fail(failure: unknown, fallback: string, expected: number) {
  if (expected !== revision) return
  if (failure instanceof ApiError && (failure.code === 'AI_BUSY' || failure.code === 'AI_UNAVAILABLE')) {
    errorCode.value = failure.code
    error.value = failure.message
  } else {
    errorCode.value = ''
    error.value = failure instanceof Error ? failure.message : fallback
  }
}

function stopPolling() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

async function loadAdvice(expected: number) {
  const value = await getAdvice(props.selection)
  if (expected === revision) {
    advice.value = value.advice
    if (value.job) {
      job.value = value.job
      if (value.job.state === 'QUEUED' || value.job.state === 'PROCESSING') schedule(expected)
    }
  }
}

function schedule(expected: number) {
  stopPolling()
  timer = setTimeout(() => { void poll(expected) }, 5000)
}

async function poll(expected: number) {
  const current = job.value
  if (!current || expected !== revision) return
  try {
    const status = await getAdviceJob(current.job_id)
    if (expected !== revision) return
    job.value = status
    if (status.state === 'COMPLETE') await loadAdvice(expected)
    else if (status.state === 'QUEUED' || status.state === 'PROCESSING') schedule(expected)
  } catch (failure) {
    fail(failure, 'Не удалось получить статус AI.', expected)
  }
}

watch(() => `${props.selection.run_id}/${props.selection.analysis_id}`, async () => {
  const expected = ++revision
  stopPolling()
  advice.value = null
  job.value = null
  consent.value = false
  sending.value = false
  error.value = ''
  errorCode.value = ''
  try {
    await loadAdvice(expected)
    // Согласие дано при запуске анализа (новый экран): запрашиваем совет тем же вызовом, что и кнопка.
    if (expected === revision && props.autoStart && !advice.value && !job.value) {
      consent.value = true
      emit('auto-started')
      await start()
    }
  } catch (failure) {
    fail(failure, 'Не удалось прочитать рекомендации.', expected)
  }
}, { immediate: true })

async function start() {
  if (!consent.value || busy.value) return
  const expected = revision
  sending.value = true
  error.value = ''
  errorCode.value = ''
  try {
    const status = await startAdvice(props.selection)
    if (expected !== revision) return
    job.value = status
    schedule(expected)
  } catch (failure) {
    fail(failure, 'AI недоступен.', expected)
  } finally {
    if (expected === revision) sending.value = false
  }
}

async function cancel() {
  if (!job.value) return
  const expected = revision
  try {
    const status = await cancelAdviceJob(job.value.job_id)
    if (expected === revision) { job.value = status; stopPolling(); if (status.state === 'COMPLETE') await loadAdvice(expected) }
  } catch (failure) {
    fail(failure, 'Не удалось отменить AI.', expected)
  }
}

onUnmounted(() => { revision++; stopPolling() })
</script>

<template>
  <section
    class="panel"
    aria-labelledby="advice-title"
  >
    <h2 id="advice-title">
      Рекомендации AI
    </h2>
    <p class="muted">
      {{ ADVICE_LABELS.intro }}
    </p>
    <template v-if="!advice">
      <label>
        <input
          v-model="consent"
          type="checkbox"
          :disabled="busy"
        >
        Разрешаю отправить evidence этого анализа и системный промпт в Alibaba ModelStudio (Singapore).
      </label>
      <div class="bucket-controls">
        <button
          type="button"
          :disabled="!consent || busy"
          @click="start"
        >
          Получить рекомендации
        </button>
        <button
          v-if="busy && job"
          type="button"
          @click="cancel"
        >
          Отменить AI
        </button>
      </div>
    </template>
    <p
      v-if="jobInfo"
      role="status"
      :class="jobInfo.tone === 'fail' ? 'notice notice-fail' : jobInfo.tone === 'warn' ? 'notice notice-warn' : undefined"
    >
      <span><strong>{{ jobInfo.state }}</strong><template v-if="jobInfo.text"> &mdash; {{ jobInfo.text }}</template><template v-if="jobInfo.code"> ({{ ADVICE_LABELS.codeLabel }}: <code>{{ jobInfo.code }}</code>)</template><template v-if="jobInfo.hint"><br>{{ ADVICE_LABELS.hintLabel }}: {{ jobInfo.hint }}</template></span>
    </p>
    <p
      v-if="error"
      role="alert"
      class="notice notice-fail"
    >
      <span v-if="errorInfo">{{ errorInfo.text }} ({{ ADVICE_LABELS.codeLabel }}: <code>{{ errorInfo.code }}</code>) <span lang="en">{{ error }}</span><template v-if="errorInfo.hint"><br>{{ ADVICE_LABELS.hintLabel }}: {{ errorInfo.hint }}</template></span>
      <span v-else>{{ error }}</span>
    </p>
    <button
      v-if="error && job"
      type="button"
      @click="poll(revision)"
    >
      Обновить статус
    </button>
    <template v-if="advice">
      <p>{{ advice.output.summary }}</p>
      <template v-if="provenance.length">
        <h3>{{ ADVICE_LABELS.provenanceTitle }}</h3>
        <dl
          class="advice-provenance"
          data-testid="advice-provenance"
        >
          <div
            v-for="line in provenance"
            :key="line.label"
          >
            <dt>{{ line.label }}</dt>
            <dd>
              {{ line.value }}<small
                v-if="line.hint"
                class="muted"
              > ({{ line.hint }})</small>
            </dd>
          </div>
        </dl>
      </template>
      <h3>Наблюдения и гипотезы</h3>
      <article
        v-for="item in advice.output.hypotheses"
        :key="item.rank"
      >
        <p><strong>Наблюдение:</strong> {{ item.observation }}</p>
        <p><strong>Гипотеза:</strong> {{ item.possible_explanation }}</p>
        <p><strong>Проверка:</strong> {{ item.recommended_check }}</p>
        <details>
          <summary>Основания</summary><ul>
            <li
              v-for="reference in item.evidence_refs"
              :key="reference"
            >
              <code>{{ reference }}</code>
            </li>
          </ul>
        </details>
      </article>
      <h3>Рекомендуемые действия</h3>
      <ol>
        <li
          v-for="item in advice.output.recommendations"
          :key="item.rank"
        >
          <p>{{ item.action }}</p><p class="muted">
            {{ item.rationale }}
          </p>
        </li>
      </ol>
      <h3>Ограничения</h3>
      <ul>
        <li
          v-for="(caveat, index) in advice.output.caveats"
          :key="index"
        >
          {{ caveat }}
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.advice-provenance { display: grid; gap: 4px; margin: 8px 0 }
.advice-provenance > div { display: flex; flex-wrap: wrap; gap: 0 12px }
.advice-provenance dt { color: var(--text-muted) }
.advice-provenance dd { margin: 0; overflow-wrap: anywhere; font-family: "Cascadia Mono", Consolas, monospace }
[role='status'] code, [role='alert'] code { overflow-wrap: anywhere }
</style>
