<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ApiError, cancelAdviceJob, getAdvice, getAdviceJob, startAdvice } from './api'
import { ADVICE_LABELS } from './shell/labels.advice'
import { apiFailureView, evidenceRef, jobView, provenanceLines, requestedModelId } from './shell/advice'
import ModelChoice from './shell/ModelChoice.vue'
import type { AttentionTarget } from './shell/overview'
import type { AdviceDocument, AdviceJob, AdvisoryAiConfig, AnalysisReference, AnalysisResult } from './types'

// linkable: ссылки на строки evidence есть только в новой оболочке (вкладка «Таблицы»).
// aiConfig и aiModel: список моделей из bootstrap и выбор (ADR 0023); выбор живёт в App.vue.
const props = defineProps<{ selection: AnalysisReference; autoStart?: boolean; result?: AnalysisResult | null; linkable?: boolean; aiConfig?: AdvisoryAiConfig | null; aiModel?: string }>()
const emit = defineEmits<{ 'auto-started': []; navigate: [target: AttentionTarget]; 'ai-model': [id: string] }>()
const advice = ref<AdviceDocument | null>(null)
const job = ref<AdviceJob | null>(null)
const sending = ref(false)
// Пока сохранённый совет не прочитан, выбор модели скрыт: совет уже мог быть создан, а выбор запуска не должен меняться.
const loaded = ref(false)
const error = ref('')
const errorCode = ref('')
const errorFromServer = ref(false)
const provenance = computed(() => (advice.value ? provenanceLines(advice.value) : []))
const jobInfo = computed(() => (job.value ? jobView(job.value) : null))
const errorInfo = computed(() => (errorCode.value && error.value ? apiFailureView(errorCode.value, error.value) : null))
const busy = computed(() => sending.value || job.value?.state === 'QUEUED' || job.value?.state === 'PROCESSING')
// Модель задания видна, пока совета нет (FAILED, UNAVAILABLE и т. д.); у готового совета её показывает происхождение.
const jobModel = computed(() => (job.value && job.value.state !== 'COMPLETE' && job.value.model_id ? job.value.model_id : ''))
const grounds = (references: string[]) => references.map((id) => ({ id, ...evidenceRef(props.result, id) }))
let revision = 0
let timer: ReturnType<typeof setTimeout> | undefined

function fail(failure: unknown, fallback: string, expected: number) {
  if (expected !== revision) return
  if (failure instanceof ApiError && (failure.code === 'AI_BUSY' || failure.code === 'AI_UNAVAILABLE')) {
    errorCode.value = failure.code
    errorFromServer.value = true
    error.value = failure.message
  } else {
    errorCode.value = ''
    errorFromServer.value = failure instanceof ApiError
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
  sending.value = false
  loaded.value = false
  error.value = ''
  errorCode.value = ''
  try {
    await loadAdvice(expected)
    if (expected === revision) loaded.value = true
    // ИИ-разбор запрошен при запуске анализа (новый экран): запрашиваем совет тем же вызовом, что и кнопка.
    if (expected === revision && props.autoStart && !advice.value && !job.value) {
      emit('auto-started')
      await start()
    }
  } catch (failure) {
    if (expected === revision) loaded.value = true
    fail(failure, 'Не удалось прочитать рекомендации.', expected)
  }
}, { immediate: true })

async function start() {
  if (busy.value) return
  const expected = revision
  sending.value = true
  error.value = ''
  errorCode.value = ''
  try {
    const status = await startAdvice(props.selection, { modelId: requestedModelId(props.aiConfig, props.aiModel ?? '') })
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
      ИИ-разбор
    </h2>
    <p class="muted">
      {{ ADVICE_LABELS.intro }}
    </p>
    <template v-if="!advice">
      <ModelChoice
        v-if="loaded"
        :config="aiConfig"
        :selected="aiModel ?? ''"
        :disabled="busy"
        @select="emit('ai-model', $event)"
      />
      <div class="bucket-controls">
        <button
          type="button"
          :disabled="busy"
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
      <span><strong>{{ jobInfo.state }}</strong><template v-if="jobInfo.text"> &mdash; {{ jobInfo.text }}</template><template v-if="jobInfo.code"> ({{ ADVICE_LABELS.codeLabel }}: <code>{{ jobInfo.code }}</code>)</template><template v-if="jobInfo.hint"><br>{{ ADVICE_LABELS.hintLabel }}: {{ jobInfo.hint }}</template><template v-if="jobModel"><br>{{ ADVICE_LABELS.modelChoice.jobModel }}: <code>{{ jobModel }}</code></template></span>
    </p>
    <p
      v-if="error"
      role="alert"
      class="notice notice-fail"
    >
      <span v-if="errorInfo">{{ errorInfo.text }} ({{ ADVICE_LABELS.codeLabel }}: <code>{{ errorInfo.code }}</code>) <span lang="en">{{ error }}</span><template v-if="errorInfo.hint"><br>{{ ADVICE_LABELS.hintLabel }}: {{ errorInfo.hint }}</template></span>
      <span
        v-else
        :lang="errorFromServer ? 'en' : undefined"
      >{{ error }}</span>
    </p>
    <button
      v-if="error && job"
      type="button"
      @click="poll(revision)"
    >
      Обновить статус
    </button>
    <template v-if="advice">
      <p
        v-if="aiConfig && aiConfig.models.length > 1"
        class="muted"
        data-testid="model-once"
      >
        {{ ADVICE_LABELS.modelChoice.once }}
      </p>
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
              v-for="ground in grounds(item.evidence_refs)"
              :key="ground.id"
            >
              <code>{{ ground.id }}</code>
              <template v-if="ground.label !== ground.id">
                &mdash; {{ ground.label }}
              </template>
              <button
                v-if="props.linkable && ground.target"
                type="button"
                :aria-label="ADVICE_LABELS.evidence.openFor(ground.label)"
                @click="emit('navigate', ground.target)"
              >
                {{ ADVICE_LABELS.evidence.open }}
              </button>
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
