<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AnalysisView from './AnalysisView.vue'
import JobStatusView from './JobStatus.vue'
import RunSetup from './RunSetup.vue'
import {
  ApiError,
  bootstrap,
  cancelJob,
  createJob,
  getBuckets,
  getJob,
  getResult,
  listRuns,
  parsePolicy,
  uploadInput,
  validatePolicy,
} from './api'
import type { AnalysisResult, Bucket, JobStatus, Policy, PolicyError, RunSummary, Theme } from './types'

const theme = ref<Theme>(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
const inputFile = ref<File | null>(null)
const policy = ref<Policy | null>(null)
const policyStatus = ref('')
const policyErrors = ref<PolicyError[]>([])
const uploadProgress = ref(0)
const job = ref<JobStatus | null>(null)
const queueBusy = ref(false)
const result = ref<AnalysisResult | null>(null)
const buckets = ref<Bucket[]>([])
const runs = ref<RunSummary[]>([])
const currentRun = ref<RunSummary | null>(null)
const completedAt = ref('')
const errorMessage = ref('')
const rollup = ref(1)
const rangeStart = ref('')
const rangeEnd = ref('')
let analysisRevision = 0
let policyRevision = 0

const working = computed(() => job.value?.state === 'QUEUED' || job.value?.state === 'PROCESSING')

watch(
  theme,
  (value) => {
    document.documentElement.dataset.theme = value
    document.documentElement.style.colorScheme = value
  },
  { immediate: true },
)

onMounted(async () => {
  try {
    await bootstrap()
    await refreshRuns()
  } catch (failure) {
    showError(failure)
  }
})

function selectInput(file: File | null) {
  inputFile.value = file
  queueBusy.value = false
  errorMessage.value = ''
}

async function selectPolicyFile(file: File | null) {
  policyErrors.value = []
  if (!file) {
    policy.value = null
    policyStatus.value = ''
    return
  }
  try {
    const parsed = parsePolicy(await file.text())
    policy.value = parsed
    await validateDraft(parsed)
  } catch (failure) {
    policy.value = null
    policyStatus.value = 'Policy is invalid'
    policyErrors.value = [{ code: 'MALFORMED_JSON', json_pointer: '', message: 'Policy is not valid JSON.' }]
    if (!(failure instanceof SyntaxError)) showError(failure)
  }
}

function updatePolicy(draft: Policy) {
  policy.value = draft
  void validateDraft(draft)
}

async function validateDraft(draft: Policy): Promise<Policy | null> {
  const revision = ++policyRevision
  policyStatus.value = 'Validating policy…'
  try {
    const validation = await validatePolicy(draft)
    if (revision !== policyRevision) return null
    if (!validation.valid) {
      policyErrors.value = validation.errors
      policyStatus.value = 'Policy is invalid'
      return null
    }
    policy.value = validation.policy
    policyErrors.value = []
    policyStatus.value = `Policy is valid — ${validation.policy.policy_id}`
    return validation.policy
  } catch (failure) {
    if (revision === policyRevision) showError(failure)
    return null
  }
}

async function analyze() {
  if (!inputFile.value || working.value) return
  const revision = ++analysisRevision
  queueBusy.value = false
  errorMessage.value = ''
  result.value = null
  buckets.value = []
  completedAt.value = ''
  job.value = null
  uploadProgress.value = 1

  try {
    const activePolicy = policy.value ? await validateDraft(policy.value) : null
    if (policy.value && !activePolicy) {
      uploadProgress.value = 0
      return
    }
    const accepted = await uploadInput(inputFile.value, (value) => (uploadProgress.value = value))
    if (revision !== analysisRevision) return
    currentRun.value = accepted
    await refreshRuns()
    job.value = await createJob(accepted.run_id, activePolicy)
    uploadProgress.value = 100
    await pollJob(revision)
  } catch (failure) {
    uploadProgress.value = 0
    if (failure instanceof ApiError && failure.code === 'BUSY') queueBusy.value = true
    else showError(failure)
  }
}

async function pollJob(revision: number) {
  while (revision === analysisRevision && working.value && job.value) {
    await new Promise((resolve) => window.setTimeout(resolve, 50))
    job.value = await getJob(job.value.job_id)
  }
  if (revision !== analysisRevision || job.value?.state !== 'COMPLETE' || !job.value.analysis_id) return
  result.value = await getResult(job.value.run_id, job.value.analysis_id)
  completedAt.value = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date())
  await refreshBuckets()
}

async function cancel() {
  if (!job.value || !working.value) return
  analysisRevision += 1
  try {
    job.value = await cancelJob(job.value.job_id)
  } catch (failure) {
    showError(failure)
  }
}

async function refreshRuns() {
  runs.value = (await listRuns()).runs
}

async function refreshBuckets() {
  if (!job.value?.analysis_id) return
  const from = optionalNumber(rangeStart.value)
  const to = optionalNumber(rangeEnd.value)
  if (from === null || to === null) {
    errorMessage.value = 'Normalized-data range must use epoch milliseconds.'
    return
  }
  try {
    buckets.value = (await getBuckets(job.value.run_id, job.value.analysis_id, rollup.value, from, to)).buckets
  } catch (failure) {
    showError(failure)
  }
}

function optionalNumber(value: string): number | undefined | null {
  if (!value) return undefined
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

function showError(failure: unknown) {
  errorMessage.value =
    failure instanceof ApiError || failure instanceof Error ? failure.message : 'Unexpected local application error.'
}

function focusPolicy() {
  document.getElementById('policy-file')?.focus()
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar side-navigation">
      <h1>LT Verdict</h1>
      <nav aria-label="Application">
        <button
          type="button"
          class="nav-item nav-item--active"
          aria-current="page"
        >
          Runs
        </button>
        <button
          type="button"
          class="nav-item"
          @click="focusPolicy"
        >
          Policies
        </button>
      </nav>
      <section
        class="run-list-section"
        aria-labelledby="run-list-title"
      >
        <h2 id="run-list-title">
          Accepted runs
        </h2>
        <ul
          data-testid="run-list"
          class="run-list"
        >
          <li
            v-for="run in runs"
            :key="run.run_id"
            :title="run.run_id"
          >
            <span>{{ run.original_filename }}</span>
            <small>{{ run.source_type }}</small>
          </li>
          <li
            v-if="runs.length === 0"
            class="muted"
          >
            No runs yet
          </li>
        </ul>
      </section>
    </aside>

    <div class="workspace">
      <header class="app-header top-header">
        <div class="run-identity">
          <strong>{{ currentRun?.original_filename ?? 'No run selected' }}</strong>
          <span
            v-if="currentRun"
            class="mono"
          >{{ currentRun.source_type }} · {{ currentRun.run_id.slice(0, 24) }}…</span>
          <span v-if="completedAt">Completed {{ completedAt }}</span>
        </div>
        <button
          type="button"
          class="theme-toggle"
          :aria-label="theme === 'light' ? 'Dark theme' : 'Light theme'"
          @click="theme = theme === 'light' ? 'dark' : 'light'"
        >
          <span aria-hidden="true">{{ theme === 'light' ? '◐' : '◑' }}</span>
          {{ theme === 'light' ? 'Dark theme' : 'Light theme' }}
        </button>
      </header>

      <main>
        <RunSetup
          :input-file="inputFile"
          :policy="policy"
          :policy-status="policyStatus"
          :policy-errors="policyErrors"
          :busy="working"
          @input="selectInput"
          @policy-file="selectPolicyFile"
          @update-policy="updatePolicy"
          @analyze="analyze"
        />

        <p
          v-if="errorMessage"
          class="notice notice-fail"
          role="alert"
        >
          ✕ {{ errorMessage }}
        </p>

        <JobStatusView
          :job="job"
          :upload-progress="uploadProgress"
          :busy="queueBusy"
          @cancel="cancel"
        />

        <AnalysisView
          v-if="result"
          :result="result"
          :buckets="buckets"
          :rollup="rollup"
          :range-start="rangeStart"
          :range-end="rangeEnd"
          @update:rollup="rollup = $event"
          @update:range-start="rangeStart = $event"
          @update:range-end="rangeEnd = $event"
          @refresh-buckets="refreshBuckets"
        />
      </main>
    </div>
  </div>
</template>
