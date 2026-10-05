<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import AnalysisView from './AnalysisView.vue'
import AdvicePanel from './AdvicePanel.vue'
import JenkinsPanel from './JenkinsPanel.vue'
import AnalyticsPanel from './AnalyticsPanel.vue'
import GrafanaPanel from './GrafanaPanel.vue'
import BaselinePanel from './BaselinePanel.vue'
import JobStatusView from './JobStatus.vue'
import RunSetup from './RunSetup.vue'
import VerdictCard from './VerdictCard.vue'
import NewAnalysisPanel from './shell/NewAnalysisPanel.vue'
import RulesPanel from './shell/RulesPanel.vue'
import OverviewPanel from './shell/OverviewPanel.vue'
import DeepAnalysisPanel from './shell/DeepAnalysisPanel.vue'
import CapacityTable from './shell/CapacityTable.vue'
import RuleChecksTable from './shell/RuleChecksTable.vue'
import TransactionsTable from './shell/TransactionsTable.vue'
import TrendTable from './shell/TrendTable.vue'
import ShellPanel from './shell/ShellPanel.vue'
import ShellTabs from './shell/ShellTabs.vue'
import { JOB_LABELS, SETUP_MESSAGES, SHELL_DEFAULT_TAB, SHELL_LABELS, UPLOAD_LABELS, type ShellTabKey } from './shell/labels'
import { browserStorage, resolveNewShell } from './shell/shell'
import {
  ApiError,
  bootstrap,
  cancelJob,
  capturePostgresPhase,
  createJob,
  getBuckets,
  getJob,
  getResult,
  listActiveJobs,
  listAnalyses,
  listRuns,
  listSources,
  uploadInput,
  validatePolicy,
} from './api'
import { summarizeVerdict } from './verdictSummary'
import type { AttentionTarget } from './shell/overview'
import type { AnalysisResult, AnalysisSummary, Bucket, JobStatus, OpenSearchEvidence, Policy, PolicyError, PostgresContextEvidence, RunSummary, SourceProfile, SourceRequest, Theme } from './types'
import { COMPARE_LABELS } from './shell/labels.compare'

const theme = ref<Theme>(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
const shellNew = resolveNewShell(window.location.search, browserStorage())
const uploadLabels = shellNew
  ? UPLOAD_LABELS
  : { cancel: 'Cancel upload', cancelled: 'Upload cancelled. No analysis was started; choose the file again if needed.' }
const activeTab = ref<ShellTabKey>(SHELL_DEFAULT_TAB)
const legacyHref = window.location.pathname + '?shell=old'
const chrome = shellNew
  ? { noRun: SHELL_LABELS.noRun, completed: SHELL_LABELS.completed, toDark: SHELL_LABELS.themeToDark, toLight: SHELL_LABELS.themeToLight, runsTitle: SHELL_LABELS.runsTitle, runsEmpty: SHELL_LABELS.runsEmpty, runsMore: SHELL_LABELS.runsMore, analysesTitle: SHELL_LABELS.analysesTitle, analysisItem: SHELL_LABELS.analysisItem, analysesEmpty: SHELL_LABELS.analysesEmpty, analysesMore: SHELL_LABELS.analysesMore }
  : { noRun: 'No run selected', completed: 'Completed', toDark: 'Dark theme', toLight: 'Light theme', runsTitle: 'Accepted runs', runsEmpty: 'No runs yet', runsMore: 'More runs', analysesTitle: 'Saved analyses', analysisItem: 'Analysis', analysesEmpty: 'No saved analyses for this run.', analysesMore: 'More analyses' }
const jobLabels = shellNew
  ? JOB_LABELS
  : { retrying: 'Connection problem. Retrying the job status request...', lost: 'Connection lost. The job status is no longer updating, but the job may still be running on the server.', retry: 'Retry' }
type SetupMessages = { [K in keyof typeof SETUP_MESSAGES]: (typeof SETUP_MESSAGES)[K] extends string ? string : (id: string) => string }
const legacySetupMessages: SetupMessages = {
  contextTooMany: 'OpenSearch context accepts at most 16 files.',
  profilesTooMany: 'Online source accepts at most 16 profiles.',
  autoRequired: 'Online source requires step, margin, and max idle gap in milliseconds.',
  autoNotInteger: 'Step, margin, and max idle gap must be safe integer milliseconds.',
  stepWholeSeconds: 'Source step must be whole seconds from 1000 to 60000 ms.',
  marginRange: 'Margin must be at most 3 600 000 ms and a multiple of the step.',
  idleGap: 'Max idle gap must be at least the step and a multiple of it.',
  explicitRequired: 'Online source requires start, end, and step in UTC epoch milliseconds.',
  explicitNotInteger: 'Source times and step must be safe integer milliseconds.',
  explicitOrder: 'Source end must be after a non-negative start.',
  explicitDivisible: 'Source range must be divisible by its step.',
  explicitTooManyCells: 'Source range may hold at most 100000 cells (range divided by step): increase the step or shorten the period.',
  diagnosticNeedsSnapshot: 'Correlation plan requires a matching resource snapshot.',
  capacityNeedsSnapshot: 'Capacity plan requires a matching resource snapshot.',
  trendNeedsSnapshot: 'Trend plan requires a matching resource snapshot.',
  policyValidating: 'Validating policy…',
  policyInvalid: 'Policy is invalid',
  policyMalformed: 'Policy is not valid JSON.',
  policyValid: (id) => `Policy is valid — ${id}`,
}
const setupMsg: SetupMessages = shellNew ? SETUP_MESSAGES : legacySetupMessages
const MAX_SOURCE_CELLS = 100_000
const POLL_FAST_WINDOW_MS = 10_000
const POLL_FAST_DELAY_MS = 500
const POLL_NORMAL_DELAY_MS = 1_000
const POLL_BACKOFF_BASE_MS = 500
const POLL_MAX_BACKOFF_MS = 10_000
const POLL_MAX_FAILURES = 10
const POLL_REQUEST_TIMEOUT_MS = 10_000
const POLL_MAX_FAILURE_DURATION_MS = 120_000
const shownIn = (tab: ShellTabKey) => !shellNew || activeTab.value === tab
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
const aiRequested = ref(false)
const adviceAutoFor = ref<string | null>(null)
const policy = ref<Policy | null>(null)
const policyStatus = ref('')
const policyErrors = ref<PolicyError[]>([])
const uploadProgress = ref(0)
const uploading = ref(false)
const uploadCancelled = ref(false)
const job = ref<JobStatus | null>(null)
const pollIssue = ref<'none' | 'retrying' | 'lost'>('none')
const queueBusy = ref(false)
const trialBusy = ref(false)
const trialAnalysisId = ref<string | null>(null)
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
let policyFileCheck: Promise<unknown> | null = null
let policyEdits = 0
let adviceRevision = 0
let uploadAbort: AbortController | null = null

const verdictSummary = computed(() => {
  if (!result.value) return null
  const analysis = analyses.value.find((item) => item.analysis_id === selectedAnalysisId.value)
  return summarizeVerdict(result.value, { policySha256: analysis?.policy_sha256, policyId: analysis?.policy_id })
})
watch(result, (value) => { if (shellNew && value && !trialBusy.value) activeTab.value = 'overview' })
const working = computed(() => job.value?.state === 'QUEUED' || job.value?.state === 'PROCESSING')
const selectedReference = computed(() => result.value && selectedAnalysisId.value
  ? { run_id: result.value.run_id, analysis_id: selectedAnalysisId.value }
  : null)
watch(selectedReference, () => { chartMarkers.value = [] })
const httpSourceProfiles = computed(() => sourceProfiles.value.filter((profile) => profile.source_kind !== 'postgresql'))
const postgresProfiles = computed(() => sourceProfiles.value.filter((profile) => profile.source_kind === 'postgresql' && profile.transport === 'jdbc'))
const sourceRequestState = computed<{ request: SourceRequest | null; error: string }>(() => {
  if (sourceContextFiles.value.length > 16) return { request: null, error: setupMsg.contextTooMany }
  const profileIds = [...sourceProfileIds.value].sort()
  if (!profileIds.length) return { request: null, error: '' }
  if (profileIds.length > 16) return { request: null, error: setupMsg.profilesTooMany }
  if (sourceWindowOrigin.value === 'auto') {
    if (!sourceStep.value || !sourceMargin.value || !sourceMaxIdleGap.value) return { request: null, error: setupMsg.autoRequired }
    const step = Number(sourceStep.value)
    const margin = Number(sourceMargin.value)
    const maxIdleGap = Number(sourceMaxIdleGap.value)
    if (![step, margin, maxIdleGap].every(Number.isSafeInteger)) return { request: null, error: setupMsg.autoNotInteger }
    if (!wholeSeconds(step)) return { request: null, error: setupMsg.stepWholeSeconds }
    if (margin < 0 || margin > 3_600_000 || margin % step !== 0) return { request: null, error: setupMsg.marginRange }
    if (maxIdleGap < step || maxIdleGap % step !== 0) return { request: null, error: setupMsg.idleGap }
    const request: SourceRequest = { schema_version: 'source-request.v3', profile_ids: profileIds, window: { origin: 'auto', step_ms: step, margin_ms: margin, max_idle_gap_ms: maxIdleGap } }
    return { request, error: '' }
  }
  if (!sourceStart.value || !sourceEnd.value || !sourceStep.value) return { request: null, error: setupMsg.explicitRequired }
  const start = Number(sourceStart.value)
  const end = Number(sourceEnd.value)
  const step = Number(sourceStep.value)
  if (![start, end, step].every(Number.isSafeInteger)) return { request: null, error: setupMsg.explicitNotInteger }
  if (start < 0 || end <= start) return { request: null, error: setupMsg.explicitOrder }
  if (!wholeSeconds(step)) return { request: null, error: setupMsg.stepWholeSeconds }
  if ((end - start) % step !== 0) return { request: null, error: setupMsg.explicitDivisible }
  if ((end - start) / step > MAX_SOURCE_CELLS) return { request: null, error: setupMsg.explicitTooManyCells }
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
    await restoreActiveJob()
  } catch (failure) {
    showError(failure)
  }
})

function selectInput(file: File | null) {
  inputFile.value = file
  queueBusy.value = false
  uploadCancelled.value = false
  errorMessage.value = ''
}

function selectResources(file: File | null) {
  resourceFile.value = file
  queueBusy.value = false
  uploadCancelled.value = false
  errorMessage.value = ''
}

function selectDiagnostics(file: File | null) {
  diagnosticFile.value = file
  queueBusy.value = false
  uploadCancelled.value = false
  errorMessage.value = ''
}

function selectCapacity(file: File | null) {
  capacityFile.value = file
  queueBusy.value = false
  uploadCancelled.value = false
  errorMessage.value = ''
}

function selectTrend(file: File | null) {
  trendFile.value = file
  queueBusy.value = false
  uploadCancelled.value = false
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
  uploadCancelled.value = false
  errorMessage.value = ''
}

function selectSourceContexts(files: File[]) {
  sourceContextFiles.value = files
  queueBusy.value = false
  uploadCancelled.value = false
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
  policyEdits += 1
  trialAnalysisId.value = null
  policyErrors.value = []
  if (!file) {
    policyRevision += 1
    policy.value = null
    policyStatus.value = ''
    return
  }
  policy.value = null
  const check = validateDraft(file)
  policyFileCheck = check
  await check
  if (policyFileCheck === check) policyFileCheck = null
}

function updatePolicy(draft: Policy) {
  policyEdits += 1
  trialAnalysisId.value = null
  policy.value = draft
  void validateDraft(draft)
}

async function validateDraft(draft: Policy | File): Promise<Policy | null> {
  const revision = ++policyRevision
  policyStatus.value = setupMsg.policyValidating
  try {
    const validation = await validatePolicy(draft)
    if (revision !== policyRevision) return null
    if (!validation.valid) {
      policyErrors.value = validation.errors
      policyStatus.value = setupMsg.policyInvalid
      return null
    }
    policy.value = validation.policy
    policyErrors.value = []
    policyStatus.value = setupMsg.policyValid(validation.policy.policy_id)
    return validation.policy
  } catch (failure) {
    if (revision === policyRevision) {
      policyStatus.value = setupMsg.policyInvalid
      if (failure instanceof ApiError && failure.code === 'MALFORMED_JSON') {
        policyErrors.value = [{ code: 'MALFORMED_JSON', json_pointer: '', message: setupMsg.policyMalformed }]
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
  if (diagnosticFile.value && !resourceFile.value) {
    errorMessage.value = setupMsg.diagnosticNeedsSnapshot
    return
  }
  if (capacityFile.value && !resourceFile.value) {
    errorMessage.value = setupMsg.capacityNeedsSnapshot
    return
  }
  if (trendFile.value && !resourceFile.value) {
    errorMessage.value = setupMsg.trendNeedsSnapshot
    return
  }
  const revision = ++analysisRevision
  adviceAutoFor.value = null
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
  uploadCancelled.value = false

  try {
    // Файл правил, выбранный мгновенно перед запуском, ещё проверяется: без ожидания запуск уходил без правил.
    await policyFileCheck
    const activePolicy = policy.value ? await validateDraft(policy.value) : null
    if (policy.value && !activePolicy) {
      uploadProgress.value = 0
      return
    }
    if (revision !== analysisRevision) return
    const controller = new AbortController()
    uploadAbort = controller
    uploading.value = true
    let accepted: RunSummary
    try {
      accepted = await uploadInput(
        inputFile.value,
        (value) => (uploadProgress.value = Math.max(1, value)),
        controller.signal,
      )
    } finally {
      if (uploadAbort === controller) {
        uploadAbort = null
        uploading.value = false
      }
    }
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
    // Запрос ИИ-разбора относится к этому запуску: запоминаем его и сбрасываем переключатель.
    adviceRevision = aiRequested.value ? revision : 0
    aiRequested.value = false
    uploadProgress.value = 100
    await pollJob(revision)
  } catch (failure) {
    if (revision !== analysisRevision) return
    uploadProgress.value = 0
    if (failure instanceof ApiError && failure.code === 'BUSY') queueBusy.value = true
    else showError(failure)
  }
}

async function trialRun() {
  const run = currentRun.value
  const draft = policy.value
  // Синхронные условия: `working` станет истинным только после ответа createJob, поэтому двойной клик ловит trialBusy.
  if (trialBusy.value || !run || !draft || policyErrors.value.length || working.value || postgresCapturePhase.value) return
  trialBusy.value = true
  trialAnalysisId.value = null
  const edits = policyEdits
  adviceAutoFor.value = null
  queueBusy.value = false
  errorMessage.value = ''
  const revision = ++analysisRevision
  try {
    const validated = await validateDraft(draft)
    if (!validated || revision !== analysisRevision) return
    const accepted = await createJob(run.run_id, validated)
    if (revision !== analysisRevision) return
    job.value = accepted
    await pollJob(revision)
    // Итог только у завершённого пробного задания: при FAILED, CANCELLED или потере связи `result` ещё прежний.
    // Правка черновика во время проверки: итог относится к прежней политике и не показывается.
    if (revision === analysisRevision && edits === policyEdits && job.value?.state === 'COMPLETE' && job.value.analysis_id && job.value.analysis_id === selectedAnalysisId.value && result.value) {
      trialAnalysisId.value = job.value.analysis_id
    }
  } catch (failure) {
    if (revision !== analysisRevision) return
    if (failure instanceof ApiError && failure.code === 'BUSY') queueBusy.value = true
    else showError(failure)
  } finally {
    trialBusy.value = false
  }
}

async function pollJob(revision: number) {
  const startedAt = Date.now()
  let failures = 0
  let firstFailureAt: number | null = null
  pollIssue.value = 'none'
  while (revision === analysisRevision && working.value && job.value) {
    const delay = failures > 0
      ? Math.min(POLL_BACKOFF_BASE_MS * 2 ** (failures - 1), POLL_MAX_BACKOFF_MS)
      : Date.now() - startedAt < POLL_FAST_WINDOW_MS ? POLL_FAST_DELAY_MS : POLL_NORMAL_DELAY_MS
    await new Promise((resolve) => window.setTimeout(resolve, delay))
    if (revision !== analysisRevision) return
    try {
      const polled = await getJob(job.value.job_id, AbortSignal.timeout(POLL_REQUEST_TIMEOUT_MS))
      if (revision !== analysisRevision) return
      job.value = polled
      failures = 0
      firstFailureAt = null
      pollIssue.value = 'none'
    } catch (failure) {
      if (revision !== analysisRevision) return
      const transient = failure instanceof TypeError || failure instanceof DOMException
        || (failure instanceof ApiError && (failure.status >= 500 || failure.status === 408 || failure.status === 429))
      if (!transient) {
        pollIssue.value = 'none'
        throw failure
      }
      failures += 1
      if (firstFailureAt === null) firstFailureAt = Date.now()
      if (failures >= POLL_MAX_FAILURES || Date.now() - firstFailureAt >= POLL_MAX_FAILURE_DURATION_MS) {
        pollIssue.value = 'lost'
        return
      }
      pollIssue.value = 'retrying'
    }
  }
  if (revision !== analysisRevision || job.value?.state !== 'COMPLETE' || !job.value.analysis_id) return
  selectedAnalysisId.value = job.value.analysis_id
  const loaded = await getResult(job.value.run_id, selectedAnalysisId.value)
  if (revision !== analysisRevision) return
  if (adviceRevision === revision) adviceAutoFor.value = selectedAnalysisId.value
  result.value = loaded
  completedAt.value = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date())
  await refreshAnalyses(job.value.run_id)
  if (revision !== analysisRevision || !result.value) return
  if (result.value.run_validity !== 'INVALID') await refreshBuckets()
}

async function restoreActiveJob() {
  const initialRevision = analysisRevision
  const { jobs } = await listActiveJobs()
  const active = jobs[0]
  if (!active || initialRevision !== analysisRevision || job.value || uploadProgress.value > 0) return
  const revision = ++analysisRevision
  job.value = active
  try {
    while (!runs.value.some((run) => run.run_id === active.run_id) && nextRunAfter.value) {
      await refreshRuns(nextRunAfter.value)
      if (revision !== analysisRevision) return
    }
  } catch (failure) {
    if (revision === analysisRevision) showError(failure)
  }
  if (revision !== analysisRevision) return
  currentRun.value = runs.value.find((run) => run.run_id === active.run_id) ?? null
  await pollJob(revision)
}

async function cancel() {
  if (!job.value || !working.value) return
  const revision = ++analysisRevision
  pollIssue.value = 'none'
  try {
    const status = await cancelJob(job.value.job_id)
    if (revision !== analysisRevision) return
    job.value = status
    await pollJob(revision)
  } catch (failure) {
    if (revision === analysisRevision) {
      showError(failure)
      if (job.value && working.value) pollIssue.value = 'lost'
    }
  }
}

async function retryPoll() {
  if (pollIssue.value !== 'lost' || !job.value || !working.value) return
  const revision = analysisRevision
  try {
    await pollJob(revision)
  } catch (failure) {
    if (revision === analysisRevision) showError(failure)
  }
}

function cancelUpload() {
  if (!uploadAbort) return
  const controller = uploadAbort
  analysisRevision += 1
  uploadProgress.value = 0
  uploadCancelled.value = true
  uploadAbort = null
  uploading.value = false
  controller.abort()
  if (shellNew) activeTab.value = 'setup'
  void nextTick(() => document.getElementById('input-file')?.focus())
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
  adviceAutoFor.value = null
  job.value = null
  uploadProgress.value = 0
  queueBusy.value = false
  uploadCancelled.value = false
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
  adviceAutoFor.value = null
  job.value = null
  uploadProgress.value = 0
  queueBusy.value = false
  uploadCancelled.value = false
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

async function showVerdict() {
  if (!shellNew) return
  activeTab.value = 'overview'
  await nextTick()
  const verdict = document.getElementById('verdict')
  verdict?.scrollIntoView()
  verdict?.focus({ preventScroll: true })
}

async function jumpTo(target: AttentionTarget) {
  activeTab.value = target.tab
  await nextTick()
  const element = document.getElementById(target.targetId)
  if (!element) return
  element.scrollIntoView()
  const focusable = element.matches('input, select, textarea, button, [tabindex]')
    ? element
    : element.querySelector<HTMLElement>('[tabindex="0"]')
  focusable?.focus({ preventScroll: true })
}

function focusPolicy() {
  document.getElementById('policy-file')?.focus()
}
</script>

<template>
  <div
    class="app-shell"
    :class="{ shell: shellNew }"
  >
    <aside class="sidebar side-navigation">
      <h1>LT Verdict</h1>
      <nav
        v-if="!shellNew"
        aria-label="Application"
      >
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
      <ShellTabs
        v-else
        v-model="activeTab"
      />
      <section
        class="run-list-section"
        aria-labelledby="run-list-title"
        :lang="shellNew ? 'ru' : undefined"
      >
        <h2 id="run-list-title">
          {{ chrome.runsTitle }}
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
              :disabled="working || (uploadProgress > 0 && !job) || trialBusy"
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
            {{ chrome.runsEmpty }}
          </li>
        </ul>
        <button
          v-if="nextRunAfter"
          type="button"
          @click="refreshRuns(nextRunAfter ?? undefined)"
        >
          {{ chrome.runsMore }}
        </button>
      </section>
      <section
        v-if="currentRun"
        class="run-list-section"
        aria-labelledby="analysis-list-title"
        :lang="shellNew ? 'ru' : undefined"
      >
        <h2 id="analysis-list-title">
          {{ chrome.analysesTitle }}
        </h2>
        <ul class="run-list">
          <li
            v-for="analysis in analyses"
            :key="analysis.analysis_id"
          >
            <button
              type="button"
              :disabled="working || (uploadProgress > 0 && !job) || trialBusy"
              :title="analysis.analysis_id"
              :aria-pressed="selectedAnalysisId === analysis.analysis_id"
              @click="selectAnalysis(analysis)"
            >
              <span>{{ chrome.analysisItem }} {{ analysis.analysis_id.slice(0, 12) }}</span>
              <small>{{ analysis.policy_verdict }} · {{ analysis.run_validity }}<span v-if="analysis.policy_id"> · {{ analysis.policy_id }}</span></small>
            </button>
          </li>
          <li
            v-if="analyses.length === 0"
            class="muted"
          >
            {{ chrome.analysesEmpty }}
          </li>
        </ul>
        <button
          v-if="nextAnalysisAfter"
          type="button"
          @click="refreshAnalyses(undefined, nextAnalysisAfter ?? undefined)"
        >
          {{ chrome.analysesMore }}
        </button>
      </section>
    </aside>

    <div class="workspace">
      <header
        class="app-header top-header"
        :lang="shellNew ? 'ru' : undefined"
      >
        <div class="run-identity">
          <strong>{{ currentRun?.original_filename ?? chrome.noRun }}</strong>
          <span
            v-if="currentRun"
            class="mono"
          >{{ currentRun.source_type }} · {{ currentRun.run_id.slice(0, 24) }}…</span>
          <span v-if="completedAt">{{ chrome.completed }} {{ completedAt }}</span>
        </div>
        <a
          v-if="verdictSummary"
          href="#verdict"
          class="verdict-chip"
          data-testid="verdict-chip"
          :data-verdict="verdictSummary.verdict"
          :aria-label="`Вердикт ${verdictSummary.verdict} ${verdictSummary.chip}, перейти к описанию`"
          @click="showVerdict"
        ><strong>{{ verdictSummary.verdict }}</strong> <span>{{ verdictSummary.chip }}</span></a>
        <button
          type="button"
          class="theme-toggle"
          :aria-label="theme === 'light' ? chrome.toDark : chrome.toLight"
          @click="theme = theme === 'light' ? 'dark' : 'light'"
        >
          <span aria-hidden="true">{{ theme === 'light' ? '◐' : '◑' }}</span>
          {{ theme === 'light' ? chrome.toDark : chrome.toLight }}
        </button>
        <a
          v-if="shellNew"
          class="shell-legacy-link"
          :href="legacyHref"
        >{{ SHELL_LABELS.legacyLink }}</a>
      </header>

      <main>
        <ShellPanel
          :enabled="shellNew"
          :active-tab="activeTab"
        >
          <VerdictCard
            v-if="verdictSummary && shownIn('overview')"
            :summary="verdictSummary"
          />

          <OverviewPanel
            v-if="shellNew && result && selectedAnalysisId && shownIn('overview')"
            :key="result.run_id + ':' + selectedAnalysisId"
            :result="result"
            :run-id="result.run_id"
            :analysis-id="selectedAnalysisId"
            @navigate="jumpTo"
          />

          <DeepAnalysisPanel
            v-if="shellNew && result && selectedAnalysisId && shownIn('deep')"
            :key="result.run_id + ':' + selectedAnalysisId"
            :result="result"
            :run-id="result.run_id"
            :analysis-id="selectedAnalysisId"
          />

          <p
            v-if="shellNew && shownIn('overview') && !result"
            class="notice notice-info"
            lang="ru"
          >
            {{ SHELL_LABELS.overviewEmpty }}
          </p>

          <p
            v-if="shellNew && shownIn('deep') && !result"
            class="notice notice-info"
            lang="ru"
          >
            {{ SHELL_LABELS.overviewEmpty }}
          </p>

          <component
            :is="shellNew ? NewAnalysisPanel : RunSetup"
            v-show="shownIn('setup')"
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
            :busy="working || (uploadProgress > 0 && !job) || !!postgresCapturePhase || trialBusy"
            :ai-requested="shellNew ? aiRequested : undefined"

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
            @open-rules="activeTab = 'rules'"
            @ai-requested="aiRequested = $event"
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
            :uploading="uploading"
            :upload-cancelled="uploadCancelled"
            :upload-labels="uploadLabels"
            :upload-lang="shellNew ? 'ru' : undefined"
            :poll-issue="pollIssue"
            :labels="jobLabels"
            :notice-lang="shellNew ? 'ru' : undefined"
            @cancel="cancel"
            @cancel-upload="cancelUpload"
            @retry="retryPoll"
          />

          <JenkinsPanel
            v-if="apiReady && shownIn('setup')"
            @imported="selectRun($event); refreshRuns()"
          />

          <BaselinePanel
            v-if="apiReady"
            v-show="shownIn('compare')"
            :selection="selectedReference"
            :filename="currentRun?.original_filename ?? ''"
            :working="working"
            :labels="shellNew ? COMPARE_LABELS : undefined"
            :lang="shellNew ? 'ru' : undefined"
          />

          <div
            v-if="result && selectedAnalysisId"
            v-show="shownIn('overview')"
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
            v-show="shownIn('overview')"
            :selection="selectedReference"
            :working="working"
            @loaded="chartMarkers = $event?.overlay?.markers ?? []"
          />

          <AdvicePanel
            v-if="selectedReference"
            v-show="shownIn('advice')"
            :selection="selectedReference"
            :auto-start="adviceAutoFor !== null && adviceAutoFor === selectedAnalysisId"
            :result="result"
            :linkable="shellNew"
            @auto-started="adviceAutoFor = null"
            @navigate="jumpTo"
          />

          <GrafanaPanel
            v-if="selectedReference && shownIn('overview')"
            :selection="selectedReference"
          />

          <template v-if="shellNew && result && shownIn('tables')">
            <CapacityTable :result="result" />
            <RuleChecksTable :result="result" />
            <TransactionsTable :result="result" />
            <TrendTable :result="result" />
          </template>
          <AnalysisView
            v-if="result && shownIn('tables')"
            :result="result"
            :shell-tables="shellNew"
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
            v-if="result && result.run_validity !== 'INVALID' && shownIn('tables')"
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
          <RulesPanel
            v-if="shellNew"
            v-show="shownIn('rules')"
            :policy="policy"
            :policy-status="policyStatus"
            :policy-errors="policyErrors"
            :busy="working || (uploadProgress > 0 && !job) || !!postgresCapturePhase || trialBusy"
            :result="result"
            :run-name="currentRun?.original_filename ?? ''"
            :can-trial="!!currentRun && !!policy && !policyErrors.length"
            :trial-busy="trialBusy"
            :summary="trialAnalysisId && trialAnalysisId === selectedAnalysisId ? verdictSummary : null"
            @policy-file="selectPolicyFile"
            @update-policy="updatePolicy"
            @trial="trialRun"
            @open-overview="showVerdict"
          />
        </ShellPanel>
      </main>
    </div>
  </div>
</template>
