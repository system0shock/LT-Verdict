<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { advanceJenkins, listJenkins, listJenkinsAttempts, triggerJenkins } from './api'
import type { JenkinsAttempt, JenkinsProfile, RunSummary } from './types'

const emit = defineEmits<{ imported: [run: RunSummary] }>()
const profiles = ref<JenkinsProfile[]>([])
const profileId = ref('')
const parameters = ref<Record<string, string>>({})
const artifactPath = ref('')
const attempts = ref<JenkinsAttempt[]>([])
const active = ref<JenkinsAttempt | null>(null)
const busy = ref(false)
const historyUnknown = ref(true)
const error = ref('')
const profile = computed(() => profiles.value.find(item => item.id === profileId.value))
const unresolved = computed(() => historyUnknown.value || attempts.value.some(item => ['TRIGGER_INTENT', 'TRIGGERING', 'RECONCILING', 'QUEUED', 'RUNNING', 'TRIGGER_UNKNOWN'].includes(item.status)))
let revision = 0
let timer: ReturnType<typeof setTimeout> | undefined
function stop() { if (timer) clearTimeout(timer); timer = undefined }
function remember(value: JenkinsAttempt) {
  active.value = value
  attempts.value = [value, ...attempts.value.filter(item => item.attempt_id !== value.attempt_id)].slice(0, 20)
}
function schedule(expected: number) {
  stop()
  if (active.value && ['QUEUED', 'RUNNING', 'RECONCILING'].includes(active.value.status)) {
    timer = setTimeout(() => { if (expected === revision) void advance(active.value?.status === 'RECONCILING' ? 'reconcile' : 'advance') }, 5000)
  }
}
onMounted(async () => {
  try { profiles.value = (await listJenkins()).profiles; profileId.value = profiles.value[0]?.id ?? '' }
  catch (failure) { error.value = failure instanceof Error ? failure.message : 'Jenkins недоступен.' }
})
watch(profileId, async () => {
  const expected = ++revision
  stop()
  parameters.value = {}
  active.value = null
  historyUnknown.value = true
  attempts.value = []
  artifactPath.value = profile.value?.artifact_paths[0] ?? ''
  error.value = ''
  if (!profileId.value) return
  try {
    const result = await listJenkinsAttempts(profileId.value)
    if (expected === revision) { attempts.value = result.attempts; active.value = result.attempts[0] ?? null; historyUnknown.value = false }
  } catch (failure) { if (expected === revision) error.value = failure instanceof Error ? failure.message : 'Не удалось прочитать историю Jenkins.' }
})
async function trigger() {
  if (!profile.value || busy.value || unresolved.value) return
  const expected = revision
  busy.value = true
  error.value = ''
  try {
    const value = await triggerJenkins(profileId.value, parameters.value)
    if (expected !== revision) return
    parameters.value = {}
    remember(value)
    schedule(expected)
  } catch (failure) {
    if (expected === revision) {
      historyUnknown.value = true
      error.value = 'Ответ на запуск не получен. Сначала обновите историю; не запускайте job повторно. ' + (failure instanceof Error ? failure.message : '')
      // A browser/network failure may hide a persisted trigger. Recover without another POST.
      try { const recovered = await listJenkinsAttempts(profileId.value); if (expected === revision) { attempts.value = recovered.attempts; active.value = recovered.attempts[0] ?? null; historyUnknown.value = false } } catch { /* Explicit recovery remains available. */ }
    }
  } finally { if (expected === revision) busy.value = false }
}
async function advance(operation: 'advance' | 'reconcile' | 'collect') {
  if (!active.value || busy.value) return
  const expected = revision
  stop()
  busy.value = true
  error.value = ''
  try {
    const result = await advanceJenkins(profileId.value, active.value.attempt_id, operation, artifactPath.value)
    if (expected !== revision) return
    remember(result.attempt)
    if (result.run) emit('imported', result.run)
    if (operation !== 'collect' && result.attempt.status === 'AWAITING_ARTIFACT' && artifactPath.value) {
      busy.value = false
      await advance('collect')
    } else schedule(expected)
  } catch (failure) { if (expected === revision) error.value = failure instanceof Error ? failure.message : 'Операция Jenkins не выполнена.' }
  finally { if (expected === revision) busy.value = false }
}
async function refresh() {
  const expected = revision
  try { const result = await listJenkinsAttempts(profileId.value); if (expected === revision) { attempts.value = result.attempts; active.value = result.attempts[0] ?? null; historyUnknown.value = false } }
  catch (failure) { if (expected === revision) error.value = failure instanceof Error ? failure.message : 'Не удалось обновить историю.' }
}
onUnmounted(() => { revision++; stop() })
</script>

<template>
  <section
    v-if="profiles.length || error"
    class="panel"
    aria-labelledby="jenkins-title"
  >
    <h2 id="jenkins-title">
      Jenkins
    </h2>
    <p class="muted">
      Запуск существующей job и получение JTL/Gatling artifact. Новый анализ после импорта запускается отдельно.
    </p>
    <label>Профиль Jenkins <select
      v-model="profileId"
      :disabled="busy"
    ><option
      v-for="item in profiles"
      :key="item.id"
      :value="item.id"
    >{{ item.id }} — {{ item.job_path }}</option></select></label>
    <template v-if="profile">
      <p>{{ profile.controller }} / {{ profile.job_path }}</p>
      <label
        v-for="name in profile.parameter_names"
        :key="name"
      >{{ name }} <input
        v-model="parameters[name]"
        :type="/password|secret|token/i.test(name) ? 'password' : 'text'"
        autocomplete="off"
        :disabled="busy"
      ></label>
      <label>Артефакт <select
        v-model="artifactPath"
        :disabled="busy"
      ><option
        v-for="path in profile.artifact_paths"
        :key="path"
        :value="path"
      >{{ path }}</option></select></label>
      <div class="bucket-controls">
        <button
          type="button"
          :disabled="busy || !!unresolved"
          @click="trigger"
        >
          Запустить Jenkins job
        </button>
        <button
          type="button"
          :disabled="busy"
          @click="refresh"
        >
          Обновить историю Jenkins
        </button>
      </div>
    </template>
    <p
      v-if="error"
      role="alert"
      class="notice notice-fail"
    >
      {{ error }}
    </p>
    <template v-if="active">
      <p role="status">
        {{ active.status }} · {{ active.attempt_id }} <span v-if="active.build_number">· build {{ active.build_number }}</span>
      </p>
      <p v-if="active.failure_code">
        {{ active.failure_code }}
      </p>
      <p
        v-if="active.status === 'TRIGGER_UNKNOWN'"
        class="notice"
      >
        Исход запуска неизвестен. Проверьте Jenkins вручную; автоматического повторного запуска нет.
      </p>
      <div class="bucket-controls">
        <button
          v-if="['TRIGGER_INTENT', 'TRIGGER_UNKNOWN', 'RECONCILING'].includes(active.status)"
          type="button"
          :disabled="busy"
          @click="advance('reconcile')"
        >
          Сверить запуск с Jenkins
        </button>
        <button
          v-if="['QUEUED', 'RUNNING'].includes(active.status)"
          type="button"
          :disabled="busy"
          @click="advance('advance')"
        >
          Проверить build
        </button>
        <button
          v-if="['AWAITING_ARTIFACT', 'ARTIFACT_READY'].includes(active.status)"
          type="button"
          :disabled="busy || !artifactPath"
          @click="advance('collect')"
        >
          Получить артефакт
        </button>
      </div>
    </template>
    <details v-if="attempts.length">
      <summary>Сохранённые попытки</summary><ul>
        <li
          v-for="item in attempts"
          :key="item.attempt_id"
        >
          <button
            type="button"
            :disabled="busy"
            @click="stop(); active = item"
          >
            {{ item.attempt_id }} — {{ item.status }}
          </button>
        </li>
      </ul>
    </details>
  </section>
</template>
