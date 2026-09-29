<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AnalysisView from './AnalysisView.vue'
import AdvicePanel from './AdvicePanel.vue'
import JenkinsPanel from './JenkinsPanel.vue'
import AnalyticsPanel from './AnalyticsPanel.vue'
import GrafanaPanel from './GrafanaPanel.vue'
import BaselinePanel from './BaselinePanel.vue'
import JobStatusView from './JobStatus.vue'
import RunSetup from './RunSetup.vue'
import {
  ApiError,
  bootstrap,
  cancelJob,
  capturePostgresPhase,
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
import type { AnalysisResult, AnalysisSummary, Bucket, JobStatus, OpenSearchEvidence, Policy, PolicyError, PostgresContextEvidence, RunSummary, SourceProfile, SourceRequest, Theme } from './types'

const theme = ref<Theme>(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
const apiReady = ref(false)
const inputFile = ref<File | null>(null)
const resourceFile = ref<File | null>(null)
const diagnosticFile = ref<File | null>(null)
const capacityFile = ref<File | null>(null)
const trendFile = ref<File | null>(null)
const sourceContextFiles = ref<File[]>([])
const sourceProfiles = ref<SourceProfile[]>([])
const sourceProfileIds = ref<string[]>([])
const postgresProfileId = ref('')
const postgresPreFile = ref<File | null>(null)
const postgresPostFile = ref<File | null>(null)
const pgProfileHtmlFile = ref<File | null>(null)
const postgresCapturePhase = ref<'pre' | 'post' | null>(null)
const sourceWindowOrigin = ref<'auto' | 'explicit'>('auto')
const sourceStart = ref('')
const sourceEnd = ref('')
const sourceStep = ref('')
const sourceMargin = ref('0')
const sourceMaxIdleGap = ref('60000')
const policy = ref<Policy | null>(null)
const policyStatus = ref('')
const policyErrors = ref<PolicyError[]>([])
const uploadProgress = ref(0)
const job = ref<JobStatus | null>(null)
const queueBusy = ref(false)
const result = ref<AnalysisResult | null>(null)
const buckets = ref<Bucket[]>([])
const chartMarkers = ref<Array<{ at_ms: number; service: string; error_type: string; message: string }>>([])
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
watch(selectedReference, () => { chartMarkers.value = [] })
const httpSourceProfiles = computed(() => sourceProfiles.value.filter((profile) => profile.source_kind !== 'postgresql'))
const postgresProfiles = computed(() => sourceProfiles.value.filter((profile) => profile.source_kind === 'postgresql' && profile.transport === 'jdbc'))
const sourceRequestState = computed<{ request: SourceRequest | null; error: string }>(() => {
  if (sourceContextFiles.value.length > 16) return { request: null, error: 'OpenSearch context accepts at most 16 files.' }
  const profileIds = [...sourceProfileIds.value].sort()
  if (!profileIds.length) return { request: null, error: '' }
  if (profileIds.length > 16) return { request: null, error: 'Online source accepts at most 16 profiles.' }
  if (sourceWindowOrigin.value === 'auto') {
    if (!sourceStep.value || !sourceMargin.value || !sourceMaxIdleGap.value) return { request: null, error: 'Online source requires step, margin, and max idle gap in milliseconds.' }
    const step = Number(sourceStep.value)
    const margin = Number(sourceMargin.value)
    const maxIdleGap = Number(sourceMaxIdleGap.value)
    if (![step, margin, maxIdleGap].every(Number.isSafeInteger)) return { request: null, error: 'Step, margin, and max idle gap must be safe integer milliseconds.' }
    if (!wholeSeconds(step)) return { request: null, error: 'Source step must be whole seconds from 1000 to 60000 ms.' }
    if (margin < 0 || margin > 3_600_000 || margin % step !== 0) return { request: null, error: 'Margin must be at most 3 600 000 ms and a multiple of the step.' }
    if (maxIdleGap < step || maxIdleGap % step !== 0) return { request: null, error: 'Max idle gap must be at least the step and a multiple of it.' }
    const request: SourceRequest = { schema_version: 'source-request.v3', profile_ids: profileIds, window: { origin: 'auto', step_ms: step, margin_ms: margin, max_idle_gap_ms: maxIdleGap } }
    return { request, error: '' }
  }
  if (!sourceStart.value || !sourceEnd.value || !sourceStep.value) return { request: null, error: 'Online source requires start, end, and step in UTC epoch milliseconds.' }
  const start = Number(sourceStart.value)
  const end = Number(sourceEnd.value)
  const step = Number(sourceStep.value)
  if (![start, end, step].every(Number.isSafeInteger)) return { request: null, error: 'Source times and step must be safe integer milliseconds.' }
  if (start < 0 || end <= start) return { request: null, error: 'Source end must be after a non-negative start.' }
  if (!wholeSeconds(step)) return { request: null, error: 'Source step must be whole seconds from 1000 to 60000 ms.' }
  if ((end - start) % step !== 0) return { request: null, error: 'Source range must be divisible by its step.' }
  const request: SourceRequest = { schema_version: 'source-request.v3', profile_ids: profileIds, window: { origin: 'explicit', start_epoch_ms: start, end_epoch_ms: end, step_ms: step } }
  return { request, error: '' }
})
const downloadableSourceContexts = computed(() => result.value?.evidence
  .filter((item): item is OpenSearchEvidence => item.type === 'opensearch_errors')
  .sort((left, right) => left.profile_id < right.profile_id ? -1 : left.profile_id > right.profile_id ? 1 : 0) ?? [])
const postgresContext = computed(() => result.value?.evidence
  .find((item): item is PostgresContextEvidence => item.type === 'postgres_context'))

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

function selectCapacity(file: File | null) {
  capacityFile.value = file
  queueBusy.value = false
  errorMessage.value = ''
}

function selectTrend(file: File | null) {
  trendFile.value = file
  queueBusy.value = false
  errorMessage.value = ''
}

function selectSourceProfiles(ids: string[]) {
  sourceProfileIds.value = ids
  if (ids.length) {
    resourceFile.value = null
    diagnosticFile.value = null
    capacityFile.value = null
    trendFile.value = null
    sourceContextFiles.value = []
  }
  queueBusy.value = false
  errorMessage.value = ''
}

function selectSourceContexts(files: File[]) {
  sourceContextFiles.value = files
  queueBusy.value = false
  errorMessage.value = ''
}

function selectPostgresProfile(id: string) {
  postgresProfileId.value = id
  errorMessage.value = ''
}

function selectPostgresPre(file: File | null) {
  postgresPreFile.value = file
  errorMessage.value = ''
}

function selectPostgresPost(file: File | null) {
  postgresPostFile.value = file
  errorMessage.value = ''
}

function selectPgProfileHtml(file: File | null) {
  pgProfileHtmlFile.value = file
  errorMessage.value = ''
}

async function capturePostgres(phase: 'pre' | 'post') {
  if (!postgresProfileId.value || postgresCapturePhase.value) return
  postgresCapturePhase.value = phase
  errorMessage.value = ''
  try {
    const capture = await capturePostgresPhase(
      postgresProfileId.value,
      phase,
      phase === 'post' ? postgresPreFile.value : null,
    )
    downloadBlob(new Blob([capture.phase_json], { type: 'application/json' }), `postgres-${phase}.json`)
    if (capture.pg_profile_html_base64 !== null) {
      const bytes = Uint8Array.from(atob(capture.pg_profile_html_base64), (character) => character.charCodeAt(0))
      downloadBlob(new Blob([bytes], { type: 'application/octet-stream' }), 'pg-profile.html')
    }
  } catch (failure) {
    showError(failure)
  } finally {
    postgresCapturePhase.value = null
  }
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
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
  if (!inputFile.value || working.value || postgresCapturePhase.value) return
  if (sourceRequestState.value.error) {
    errorMessage.value = sourceRequestState.value.error
    return
  }
  if (capacityFile.value && !resourceFile.value) {
    errorMessage.value = 'Capacity plan requires a matching resource snapshot.'
    return
  }
  if (trendFile.value && !resourceFile.value) {
    errorMessage.value = 'Trend plan requires a matching resource snapshot.'
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
    job.value = await createJob(
      accepted.run_id,
      activePolicy,
      resourceFile.value,
      diagnosticFile.value,
      sourceRequestState.value.request,
      sourceContextFiles.value,
      postgresPreFile.value,
      postgresPostFile.value,
      pgProfileHtmlFile.value,
      capacityFile.value,
      trendFile.value,
    )
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

function wholeSeconds(value: number) {
  return value >= 1_000 && value <= 60_000 && value % 1_000 === 0
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
              <small>{{ run.source_type }} · {{ run.sha256.slice(0, 8) }}</small>
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
          :capacity-file="capacityFile"
          :trend-file="trendFile"
          :source-context-files="sourceContextFiles"
          :source-profiles="httpSourceProfiles"
          :source-profile-ids="sourceProfileIds"
          :postgres-profiles="postgresProfiles"
          :postgres-profile-id="postgresProfileId"
          :postgres-pre-file="postgresPreFile"
          :postgres-post-file="postgresPostFile"
          :pg-profile-html-file="pgProfileHtmlFile"
          :source-window-origin="sourceWindowOrigin"
          :source-start="sourceStart"
          :source-end="sourceEnd"
          :source-step="sourceStep"
          :source-margin="sourceMargin"
          :source-max-idle-gap="sourceMaxIdleGap"
          :source-request-error="sourceRequestState.error"
          :policy="policy"
          :policy-status="policyStatus"
          :policy-errors="policyErrors"
          :busy="working || !!postgresCapturePhase"
          @input="selectInput"
          @resources="selectResources"
          @diagnostics="selectDiagnostics"
          @capacity="selectCapacity"
          @trend="selectTrend"
          @source-contexts="selectSourceContexts"
          @source-profiles="selectSourceProfiles"
          @postgres-profile="selectPostgresProfile"
          @postgres-pre="selectPostgresPre"
          @postgres-post="selectPostgresPost"
          @pg-profile-html="selectPgProfileHtml"
          @capture-postgres="capturePostgres"
          @source-window-origin="sourceWindowOrigin = $event"
          @source-start="sourceStart = $event"
          @source-end="sourceEnd = $event"
          @source-step="sourceStep = $event"
          @source-margin="sourceMargin = $event"
          @source-max-idle-gap="sourceMaxIdleGap = $event"
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

        <JenkinsPanel
          v-if="apiReady"
          @imported="selectRun($event); refreshRuns()"
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
            v-for="format in ['json', 'html', 'asciidoc', 'confluence', 'svg']"
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
            v-if="result.capacity_summary"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/capacity-plan`"
            download
          >Download capacity plan</a>
          <a
            v-if="result.capacity_summary"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/capacity`"
            download
          >Download capacity result</a>
          <a
            v-if="result.evidence.some(item => item.type === 'trend_summary')"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/trend-plan`"
            download
          >Download trend plan</a>
          <a
            v-if="result.evidence.some(item => item.type === 'trend_summary')"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/trend`"
            download
          >Download trend result</a>
          <a
            v-for="(context, index) in downloadableSourceContexts"
            :key="context.profile_id"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/source-context${downloadableSourceContexts.length === 1 ? '' : `/${index + 1}`}`"
            download
          >Download OpenSearch context{{ downloadableSourceContexts.length === 1 ? '' : ` — ${context.profile_id}` }}</a>
          <a
            v-if="postgresContext?.pre_sha256"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/postgres-pre`"
            download
          >Download PostgreSQL pre capture</a>
          <a
            v-if="postgresContext?.post_sha256"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/postgres-post`"
            download
          >Download PostgreSQL post capture</a>
          <a
            v-if="postgresContext"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/postgres-context`"
            download
          >Download PostgreSQL context</a>
          <a
            v-if="postgresContext?.pg_profile_html_sha256"
            class="button-secondary"
            :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/pg-profile`"
            download
          >Download pg_profile report</a>
        </div>

        <AnalyticsPanel
          v-if="selectedReference"
          :selection="selectedReference"
          :working="working"
          @loaded="chartMarkers = $event?.overlay?.markers ?? []"
        />

        <AdvicePanel
          v-if="selectedReference"
          :selection="selectedReference"
        />

        <GrafanaPanel
          v-if="selectedReference"
          :selection="selectedReference"
        />

        <AnalysisView
          v-if="result"
          :result="result"
          :buckets="buckets"
          :markers="chartMarkers"
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
