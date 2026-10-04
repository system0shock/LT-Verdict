<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { cancelAdviceJob, getAdvice, getAdviceJob, startAdvice } from './api'
import type { AdviceDocument, AdviceJob, AnalysisReference } from './types'

const props = defineProps<{ selection: AnalysisReference; autoStart?: boolean }>()
const emit = defineEmits<{ 'auto-started': [] }>()
const advice = ref<AdviceDocument | null>(null)
const job = ref<AdviceJob | null>(null)
const consent = ref(false)
const sending = ref(false)
const error = ref('')
const busy = computed(() => sending.value || job.value?.state === 'QUEUED' || job.value?.state === 'PROCESSING')
let revision = 0
let timer: ReturnType<typeof setTimeout> | undefined

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
    if (expected === revision) error.value = failure instanceof Error ? failure.message : 'Не удалось получить статус AI.'
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
  try {
    await loadAdvice(expected)
    // Согласие дано при запуске анализа (новый экран): запрашиваем совет тем же вызовом, что и кнопка.
    if (expected === revision && props.autoStart && !advice.value && !job.value) {
      consent.value = true
      emit('auto-started')
      await start()
    }
  } catch (failure) {
    if (expected === revision) error.value = failure instanceof Error ? failure.message : 'Не удалось прочитать рекомендации.'
  }
}, { immediate: true })

async function start() {
  if (!consent.value || busy.value) return
  const expected = revision
  sending.value = true
  error.value = ''
  try {
    const status = await startAdvice(props.selection)
    if (expected !== revision) return
    job.value = status
    schedule(expected)
  } catch (failure) {
    if (expected === revision) error.value = failure instanceof Error ? failure.message : 'AI недоступен.'
  } finally {
    if (expected === revision) sending.value = false
  }
}

async function cancel() {
  if (!job.value) return
  const expected = revision
  try {
    const status = await cancelAdviceJob(job.value.job_id)
    if (expected === revision) { job.value = status; stopPolling() }
  } catch (failure) {
    if (expected === revision) error.value = failure instanceof Error ? failure.message : 'Не удалось отменить AI.'
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
      DeepSeek V4 Flash через ModelStudio. Рекомендации могут содержать ошибки; гипотезы требуют проверки. Вердикт SLA не меняется.
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
      v-if="job"
      role="status"
    >
      {{ job.state }}<span v-if="job.failure || job.unavailable_reason"> — {{ job.failure || job.unavailable_reason }}</span>
    </p>
    <p
      v-if="error"
      role="alert"
      class="notice notice-fail"
    >
      {{ error }}
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
