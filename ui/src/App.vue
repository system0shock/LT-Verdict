<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AnalysisView from './AnalysisView.vue'
import BaselinePanel from './BaselinePanel.vue'
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
  listAnalyses,
  listRuns,
  listSources,
  uploadInput,
  validatePolicy,
} from './api'
import type { AnalysisResult, AnalysisSummary, Bucket, JobStatus, Policy, PolicyError, RunSummary, SourceProfile, SourceRequest, Theme } from './types'

const theme = ref<Theme>(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
const apiReady = ref(false)
const inputFile = ref<File | null>(null)
const resourceFile = ref<File | null>(null)
const diagnosticFile = ref<File | null>(null)
const sourceContextFile = ref<File | null>(null)
const sourceProfiles = ref<SourceProfile[]>([])
const sourceProfileId = ref('')
const sourceStart = ref('')
const sourceEnd = ref('')
const sourceStep = ref('')
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
const analyses = ref<AnalysisSummary[]>([])
const selectedAnalysisId = ref<string | null>(null)
const nextRunAfter = ref<string | null>(null)
const nextAnalysisAfter = ref<string | null>(null)
const bucketNextFrom = ref<number | null>(null)
const bucketPageFrom = ref(0)
const bucketRollup = ref(1)
const completedAt = ref('')
const errorMessage = ref('')
const rollup = ref(1)
const rangeStart = ref('')
const rangeEnd = ref('')
let analysisRevision = 0
let bucketRevision = 0
let policyRevision = 0

const working = computed(() => job.value?.state === 'QUEUED' || job.value?.state === 'PROCESSING')
const selectedReference = computed(() => result.value && selectedAnalysisId.value
  ? { run_id: result.value.run_id, analysis_id: selectedAnalysisId.value }
  : null)
const sourceRequestState = computed<{ request: SourceRequest | null; error: string }>(() => {
  if (!sourceProfileId.value) return { request: null, error: '' }
  if (!sourceStart.value || !sourceEnd.value || !sourceStep.value) return { request: null, error: 'Online source requires start, end, and step in UTC epoch milliseconds.' }
  const start = Number(sourceStart.value)
  const end = Number(sourceEnd.value)
  const step = Number(sourceStep.value)
  if (![start, end, step].every(Number.isSafeInteger)) return { request: null, error: 'Source times and step must be safe integer milliseconds.' }
  if (start < 0 || end <= start) return { request: null, error: 'Source end must be after a non-negative start.' }
  if (step < 1_000) return { request: null, error: 'Source step must be at least 1000 ms.' }
  if ((end - start) % step !== 0) return { request: null, error: 'Source range must be divisible by its step.' }
  return { request: { schema_version: 'source-request.v1', profile_id: sourceProfileId.value, start_epoch_ms: start, end_epoch_ms: end, step_ms: step }, error: '' }
})

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
    apiReady.value = true
    await refreshRuns()
    try {
      await refreshSources()
    } catch {
      sourceProfiles.value = []
    }
  } catch (failure) {
    showError(failure)
  }
})

function selectInput(file: File | null) {
  inputFile.value = file
  queueBusy.value = false
  errorMessage.value = ''
}

function selectResources(file: File | null) {
  resourceFile.value = file
  queueBusy.value = false
  errorMessage.value = ''
}

function selectDiagnostics(file: File | null) {
  diagnosticFile.value = file
  queueBusy.value = false
  errorMessage.value = ''
}

function selectSourceProfile(id: string) {
  sourceProfileId.value = id
  if (id) {
    resourceFile.value = null
    diagnosticFile.value = null
    sourceContextFile.value = null
  }
  queueBusy.value = false
  errorMessage.value = ''
}

function selectSourceContext(file: File | null) {
  sourceContextFile.value = file
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
  policy.value = null
  await validateDraft(file)
}

function updatePolicy(draft: Policy) {
  policy.value = draft
  void validateDraft(draft)
}

async function validateDraft(draft: Policy | File): Promise<Policy | null> {
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
    if (revision === policyRevision) {
      policyStatus.value = 'Policy is invalid'
      if (failure instanceof ApiError && failure.code === 'MALFORMED_JSON') {
        policyErrors.value = [{ code: 'MALFORMED_JSON', json_pointer: '', message: 'Policy is not valid JSON.' }]
      } else {
        showError(failure)
      }
    }
    return null
  }
}

async function analyze() {
  if (!inputFile.value || working.value) return
  if (sourceRequestState.value.error) {
    errorMessage.value = sourceRequestState.value.error
    return
  }
  const revision = ++analysisRevision
  queueBusy.value = false
  errorMessage.value = ''
  result.value = null
  buckets.value = []
  selectedAnalysisId.value = null
  analyses.value = []
  nextAnalysisAfter.value = null
  bucketNextFrom.value = null
  bucketPageFrom.value = 0
  completedAt.value = ''
  job.value = null
  uploadProgress.value = 1

  try {
    const activePolicy = policy.value ? await validateDraft(policy.value) : null
    if (policy.value && !activePolicy) {
      uploadProgress.value = 0
      return
    }
    const accepted = await uploadInput(inputFile.value, (value) => (uploadProgress.value = Math.max(1, value)))
    if (revision !== analysisRevision) return
    currentRun.value = accepted
    await refreshRuns()
    job.value = await createJob(accepted.run_id, activePolicy, resourceFile.value, diagnosticFile.value, sourceRequestState.value.request, sourceContextFile.value)
    uploadProgress.value = 100
    await pollJob(revision)
  } catch (failure) {
    if (revision !== analysisRevision) return
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
  selectedAnalysisId.value = job.value.analysis_id
  const loaded = await getResult(job.value.run_id, selectedAnalysisId.value)
  if (revision !== analysisRevision) return
  result.value = loaded
  completedAt.value = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date())
  await refreshAnalyses(job.value.run_id)
  if (revision !== analysisRevision || !result.value) return
  if (result.value.run_validity !== 'INVALID') await refreshBuckets()
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

async function refreshRuns(after?: string) {
  const page = await listRuns(after)
  runs.value = after ? [...runs.value, ...page.runs] : page.runs
  nextRunAfter.value = page.next_after
}

async function refreshSources() {
  sourceProfiles.value = (await listSources()).profiles
}

async function selectRun(run: RunSummary) {
  const revision = ++analysisRevision
  job.value = null
  uploadProgress.value = 0
  queueBusy.value = false
  currentRun.value = run
  selectedAnalysisId.value = null
  analyses.value = []
  nextAnalysisAfter.value = null
  result.value = null
  buckets.value = []
  completedAt.value = ''
  bucketNextFrom.value = null
  errorMessage.value = ''
  try {
    await refreshAnalyses(run.run_id)
    if (revision !== analysisRevision) return
  } catch (failure) {
    if (revision === analysisRevision) showError(failure)
  }
}

async function refreshAnalyses(runId = currentRun.value?.run_id, after?: string) {
  if (!runId) return
  const revision = analysisRevision
  const page = await listAnalyses(runId, after)
  if (revision !== analysisRevision || currentRun.value?.run_id !== runId) return
  analyses.value = after ? [...analyses.value, ...page.analyses] : page.analyses
  nextAnalysisAfter.value = page.next_after
}

async function selectAnalysis(analysis: AnalysisSummary) {
  const runId = currentRun.value?.run_id
  if (!runId) return
  const revision = ++analysisRevision
  job.value = null
  uploadProgress.value = 0
  queueBusy.value = false
  selectedAnalysisId.value = analysis.analysis_id
  result.value = null
  buckets.value = []
  completedAt.value = ''
  bucketNextFrom.value = null
  errorMessage.value = ''
  try {
    const loaded = await getResult(runId, analysis.analysis_id)
    if (revision !== analysisRevision || selectedAnalysisId.value !== analysis.analysis_id) return
    result.value = loaded
    if (loaded.run_validity !== 'INVALID') await refreshBuckets()
  } catch (failure) {
    if (revision === analysisRevision) showError(failure)
  }
}

async function refreshBuckets(nextFrom?: number) {
  const runId = currentRun.value?.run_id
  const analysisId = selectedAnalysisId.value
  if (!runId || !analysisId) return
  const from = nextFrom ?? optionalNumber(rangeStart.value)
  const to = optionalNumber(rangeEnd.value)
  if (from === null || to === null) {
    errorMessage.value = 'Normalized-data range must use non-negative offsets from run start in milliseconds.'
    return
  }
  const selectionRevision = analysisRevision
  const revision = ++bucketRevision
  const requestedRollup = rollup.value
  try {
    const page = await getBuckets(runId, analysisId, requestedRollup, from, to)
    if (selectionRevision !== analysisRevision || revision !== bucketRevision || selectedAnalysisId.value !== analysisId) return
    buckets.value = page.buckets
    bucketRollup.value = requestedRollup
    bucketNextFrom.value = page.next_from_ms
    bucketPageFrom.value = from ?? 0
  } catch (failure) {
    if (selectionRevision === analysisRevision && revision === bucketRevision) showError(failure)
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
          tabindex="0"
        >
          <li
            v-for="run in runs"
            :key="run.run_id"
            :title="run.run_id"
          >
            <button
              type="button"
              :disabled="working || (uploadProgress > 0 && !job)"
              :aria-pressed="currentRun?.run_id === run.run_id"
              @click="selectRun(run)"
            >
              <span>{{ run.original_filename }}</span>
              <small>{{ run.source_type }}</small>
            </button>
          </li>
          <li
            v-if="runs.length === 0"
            class="muted"
          >
            No runs yet
          </li>
        </ul>
        <button
          v-if="nextRunAfter"
          type="button"
          @click="refreshRuns(nextRunAfter ?? undefined)"
        >
          More runs
        </button>
      </section>
      <section
        v-if="currentRun"
        class="run-list-section"
        aria-labelledby="analysis-list-title"
      >
        <h2 id="analysis-list-title">
          Saved analyses
        </h2>
        <ul class="run-list">
          <li
            v-for="analysis in analyses"
            :key="analysis.analysis_id"
          >
            <button
              type="button"
              :disabled="working || (uploadProgress > 0 && !job)"
              :title="analysis.analysis_id"
              :aria-pressed="selectedAnalysisId === analysis.analysis_id"
              @click="selectAnalysis(analysis)"
            >
              <span>Analysis {{ analysis.analysis_id.slice(0, 12) }}</span>
              <small>{{ analysis.policy_verdict }} · {{ analysis.run_validity }}</small>
            </button>
          </li>
          <li
            v-if="analyses.length === 0"
            class="muted"
          >
            No saved analyses for this run.
          </li>
        </ul>
        <button
          v-if="nextAnalysisAfter"
          type="button"
          @click="refreshAnalyses(undefined, nextAnalysisAfter ?? undefined)"
        >
          More analyses
        </button>
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
          :resource-file="resourceFile"
          :diagnostic-file="diagnosticFile"
          :source-context-file="sourceContextFile"
          :source-profiles="sourceProfiles"
          :source-profile-id="sourceProfileId"
          :source-start="sourceStart"
          :source-end="sourceEnd"
          :source-step="sourceStep"
          :source-request-error="sourceRequestState.error"
          :policy="policy"
          :policy-status="policyStatus"
          :policy-errors="policyErrors"
          :busy="working"
          @input="selectInput"
          @resources="selectResources"
          @diagnostics="selectDiagnostics"
          @source-context="selectSourceContext"
          @source-profile="selectSourceProfile"
          @source-start="sourceStart = $event"
          @source-end="sourceEnd = $event"
          @source-step="sourceStep = $event"
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

        <BaselinePanel
          v-if="apiReady"
          :selection="selectedReference"
          :filename="currentRun?.original_filename ?? ''"
          :working="working"
        />

        <div
          v-if="result && selectedAnalysisId"
          class="bucket-controls"
          aria-label="Analysis downloads"
        >
          <a
            v-for="format in ['json', 'html', 'asciidoc']"
            :key="format"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/report?format=${format}`"
            download
          >Download {{ format === 'asciidoc' ? 'AsciiDoc' : format.toUpperCase() }}</a>
          <a
            v-if="result.evidence.some(item => item.type === 'resource_binding')"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/resource-snapshot`"
            download
          >Download resource snapshot</a>
          <a
            v-if="result.evidence.some(item => item.type === 'opensearch_errors')"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/source-context`"
            download
          >Download OpenSearch context</a>
        </div>

        <AnalysisView
          v-if="result"
          :result="result"
          :buckets="buckets"
          :rollup="rollup"
          :bucket-rollup="bucketRollup"
          :range-start="rangeStart"
          :range-end="rangeEnd"
          @update:rollup="rollup = $event"
          @update:range-start="rangeStart = $event"
          @update:range-end="rangeEnd = $event"
          @refresh-buckets="refreshBuckets"
        />
        <p
          v-if="result && result.run_validity !== 'INVALID'"
          class="muted"
        >
          Showing {{ buckets.length }} buckets from {{ bucketPageFrom.toLocaleString() }} ms
          ({{ bucketRollup }} s rollup; maximum 500 per page).
          <button
            v-if="bucketNextFrom !== null"
            type="button"
            @click="refreshBuckets(bucketNextFrom)"
          >
            Next bucket page
          </button>
        </p>
      </main>
    </div>
  </div>
</template>
