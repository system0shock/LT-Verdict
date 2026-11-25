<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import AnalysisView from './AnalysisView.vue'
import AdvicePanel from './AdvicePanel.vue'
import JenkinsPanel from './JenkinsPanel.vue'
import AnalyticsPanel from './AnalyticsPanel.vue'
import BaselinePanel from './BaselinePanel.vue'
import HistoryPanel from './shell/HistoryPanel.vue'
import { analysisSummary, runSummaryFromId } from './shell/history'
import JobStatusView from './JobStatus.vue'
import VerdictCard from './VerdictCard.vue'
import NewAnalysisPanel from './shell/NewAnalysisPanel.vue'
import RulesPanel from './shell/RulesPanel.vue'
import OverviewPanel from './shell/OverviewPanel.vue'
import DeepAnalysisPanel from './shell/DeepAnalysisPanel.vue'
import PodHeatmap from './shell/PodHeatmap.vue'
import CapacityTable from './shell/CapacityTable.vue'
import RuleChecksTable from './shell/RuleChecksTable.vue'
import TablesPanel from './shell/TablesPanel.vue'
import TransactionsTable from './shell/TransactionsTable.vue'
import TrendTable from './shell/TrendTable.vue'
import ShellPanel from './shell/ShellPanel.vue'
import ShellTabs from './shell/ShellTabs.vue'
import { JOB_LABELS, SETUP_LABELS, SETUP_MESSAGES, SHELL_DEFAULT_TAB, SHELL_LABELS, UPLOAD_LABELS, type ShellTabKey } from './shell/labels'
import { NORMALIZED_LABELS, type TablesSubTabKey } from './shell/labels.tables'
import { tablesTargetSubTab } from './shell/tables'
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
  probeSourceQuery,
  proposeStages,
  reloadSources,
  uploadInput,
  validatePolicy,
  validateSources,
} from './api'
import { pairSourceRequests } from './shell/setup'
import { summarizeVerdict } from './verdictSummary'
import type { AttentionTarget } from './shell/overview'
import type { AdvisoryAiConfig, AnalysisResult, AnalysisSummary, Bucket, JobStatus, OpenSearchEvidence, Policy, PolicyError, PostgresContextEvidence, Release, ReleaseAnalysis, RunSummary, SourceCheckResponse, SourceProbeResponse, SourceProfile, SourceRequest, SourceStepMode, StageProposalResponse, Theme } from './types'
import { COMPARE_LABELS } from './shell/labels.compare'
import { ANALYTICS_LABELS, DOWNLOAD_LABELS, EXPORT_LABELS } from './shell/labels.export'

const theme = ref<Theme>(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
const activeTab = ref<ShellTabKey>(SHELL_DEFAULT_TAB)
const tablesSubTab = ref<TablesSubTabKey>('all')
const MAX_SOURCE_CELLS = 100_000
const POLL_FAST_WINDOW_MS = 10_000
const POLL_FAST_DELAY_MS = 500
const POLL_NORMAL_DELAY_MS = 1_000
const POLL_BACKOFF_BASE_MS = 500
const POLL_MAX_BACKOFF_MS = 10_000
const POLL_MAX_FAILURES = 10
const POLL_REQUEST_TIMEOUT_MS = 10_000
const POLL_MAX_FAILURE_DURATION_MS = 120_000
const apiReady = ref(false)
const inputFile = ref<File | null>(null)
const resourceFile = ref<File | null>(null)
const diagnosticFile = ref<File | null>(null)
const capacityFile = ref<File | null>(null)
const trendFile = ref<File | null>(null)
const sourceContextFiles = ref<File[]>([])
const sourceProfiles = ref<SourceProfile[]>([])
const sourceProfileIds = ref<string[]>([])
const reloadingSources = ref(false)
const sourceReloadStatus = ref('')
const sourceReloadError = ref('')
const validatingSources = ref(false)
const sourceValidation = ref<SourceCheckResponse | null>(null)
const sourceValidationError = ref('')
const probingSource = ref(false)
const sourceProbeResult = ref<SourceProbeResponse | null>(null)
const sourceProbeError = ref('')
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
const sourceStepMode = ref<SourceStepMode>('fixed')
const aiRequested = ref(false)
const aiConfig = ref<AdvisoryAiConfig | null>(null)
const aiModel = ref('')
const adviceAutoFor = ref<string | null>(null)
const policy = ref<Policy | null>(null)
const policyStatus = ref('')
const policyErrors = ref<PolicyError[]>([])
const stagesJson = ref('')
const stageProposal = ref<StageProposalResponse | null>(null)
const stageProposalError = ref('')
const stagesErrors = ref<PolicyError[]>([])
const proposingStages = ref(false)
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
let tabRevision = 0
let activeAnalysisLoad: { runId: string; analysisId: string; promise: Promise<void> } | null = null
let bucketRevision = 0
let policyRevision = 0
let policyFileCheck: Promise<unknown> | null = null
let policyEdits = 0
let adviceRevision = 0
let sourceValidationRevision = 0
let sourceProbeRevision = 0
let stageProposalRevision = 0
let uploadedInput: { file: File; run: RunSummary } | null = null
let uploadAbort: AbortController | null = null

const verdictSummary = computed(() => {
  if (!result.value) return null
  const analysis = analyses.value.find((item) => item.analysis_id === selectedAnalysisId.value)
  return summarizeVerdict(result.value, { policySha256: analysis?.policy_sha256, policyId: analysis?.policy_id })
})
watch(activeTab, () => { tabRevision += 1 })
// Under 960 px the side column stacks above the workspace: scroll to the workspace, not to the page top.
watch([activeTab, selectedAnalysisId], () => {
  window.scrollTo(0, window.scrollY + (document.querySelector('.workspace')?.getBoundingClientRect().top ?? -window.scrollY))
})
const working = computed(() => job.value?.state === 'QUEUED' || job.value?.state === 'PROCESSING')
const selectedReference = computed(() => result.value && selectedAnalysisId.value
  ? { run_id: result.value.run_id, analysis_id: selectedAnalysisId.value }
  : null)
watch(selectedReference, () => { chartMarkers.value = [] })
const baselineSeries = ref<string>()
const baselineFacts = computed(() => (result.value
  ? {
    run_validity: result.value.run_validity,
    policy_verdict: result.value.policy_verdict,
    coverage_status: result.value.analysis_coverage?.status ?? '',
    coverage_reasons: result.value.analysis_coverage?.reasons ?? [],
  }
  : null))
const baselineVersion = ref(0)
const preferredSeries = ref<string>()
const httpSourceProfiles = computed(() => sourceProfiles.value.filter((profile) => profile.source_kind !== 'postgresql'))
const postgresProfiles = computed(() => sourceProfiles.value.filter((profile) => profile.source_kind === 'postgresql' && profile.transport === 'jdbc'))
const sourceRequestState = computed<{ request: SourceRequest | null; error: string }>(() => {
  if (sourceContextFiles.value.length > 16) return { request: null, error: SETUP_MESSAGES.contextTooMany }
  const profileIds = [...sourceProfileIds.value].sort()
  if (!profileIds.length) return { request: null, error: '' }
  if (profileIds.length > 16) return { request: null, error: SETUP_MESSAGES.profilesTooMany }
  const stepAuto = sourceStepMode.value === 'auto'
  if (sourceWindowOrigin.value === 'auto') {
    if (!sourceStep.value || !sourceMargin.value || !sourceMaxIdleGap.value) return { request: null, error: SETUP_MESSAGES.autoRequired }
    const step = Number(sourceStep.value)
    const margin = Number(sourceMargin.value)
    const maxIdleGap = Number(sourceMaxIdleGap.value)
    if (![step, margin, maxIdleGap].every(Number.isSafeInteger)) return { request: null, error: SETUP_MESSAGES.autoNotInteger }
    if (!wholeSeconds(step)) return { request: null, error: SETUP_MESSAGES.stepWholeSeconds }
    // ADR 0014: в режиме auto запас и простой не обязаны быть кратны шагу, простой остаётся целым числом секунд.
    if (margin < 0 || margin > 3_600_000 || (!stepAuto && margin % step !== 0)) return { request: null, error: stepAuto ? SETUP_MESSAGES.marginRangeAuto : SETUP_MESSAGES.marginRange }
    if (maxIdleGap < step || maxIdleGap % (stepAuto ? 1000 : step) !== 0) return { request: null, error: stepAuto ? SETUP_MESSAGES.idleGapAuto : SETUP_MESSAGES.idleGap }
    const request: SourceRequest = stepAuto
      ? { schema_version: 'source-request.v4', profile_ids: profileIds, window: { origin: 'auto', step_ms: step, step_mode: 'auto', margin_ms: margin, max_idle_gap_ms: maxIdleGap } }
      : { schema_version: 'source-request.v3', profile_ids: profileIds, window: { origin: 'auto', step_ms: step, margin_ms: margin, max_idle_gap_ms: maxIdleGap } }
    return { request, error: '' }
  }
  if (!sourceStart.value || !sourceEnd.value || !sourceStep.value) return { request: null, error: SETUP_MESSAGES.explicitRequired }
  const start = Number(sourceStart.value)
  const end = Number(sourceEnd.value)
  const step = Number(sourceStep.value)
  if (![start, end, step].every(Number.isSafeInteger)) return { request: null, error: SETUP_MESSAGES.explicitNotInteger }
  if (start < 0 || end <= start) return { request: null, error: SETUP_MESSAGES.explicitOrder }
  if (!wholeSeconds(step)) return { request: null, error: SETUP_MESSAGES.stepWholeSeconds }
  if ((end - start) % step !== 0) return { request: null, error: SETUP_MESSAGES.explicitDivisible }
  // Сервер не ограничивает число ячеек явного окна при step_mode auto: шаг подберёт планировщик.
  if (!stepAuto && (end - start) / step > MAX_SOURCE_CELLS) return { request: null, error: SETUP_MESSAGES.explicitTooManyCells }
  const request: SourceRequest = stepAuto
    ? { schema_version: 'source-request.v4', profile_ids: profileIds, window: { origin: 'explicit', start_epoch_ms: start, end_epoch_ms: end, step_ms: step, step_mode: 'auto' } }
    : { schema_version: 'source-request.v3', profile_ids: profileIds, window: { origin: 'explicit', start_epoch_ms: start, end_epoch_ms: end, step_ms: step } }
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
    const started = await bootstrap()
    // Список моделей ИИ-разбора (ADR 0023); без поля или с null селектора нет. Выбор живёт только в состоянии страницы.
    aiConfig.value = started.advisory_ai ?? null
    aiModel.value = aiConfig.value?.default_model_id ?? ''
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
  if (file !== uploadedInput?.file) uploadedInput = null
  inputFile.value = file
  stageProposalRevision += 1
  proposingStages.value = false
  stageProposal.value = null
  stageProposalError.value = ''
  stagesErrors.value = []
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
  sourceValidationRevision += 1
  sourceProbeRevision += 1
  sourceProfileIds.value = ids
  validatingSources.value = false
  sourceValidation.value = null
  sourceValidationError.value = ''
  probingSource.value = false
  sourceProbeResult.value = null
  sourceProbeError.value = ''
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

function sourceOperationErrorText(failure: unknown): string {
  const code = failure instanceof ApiError ? failure.code : 'REQUEST_FAILED'
  const text = SETUP_LABELS.sourceOperationErrors[code]
  return text ? `${text} (${code})` : SETUP_LABELS.sourceErrorFallback(code)
}

function sourcesBusy() {
  return working.value || (uploadProgress.value > 0 && !job.value) || !!postgresCapturePhase.value || trialBusy.value || proposingStages.value
}

async function reloadSourceProfiles() {
  if (reloadingSources.value || sourcesBusy()) return
  reloadingSources.value = true
  sourceReloadStatus.value = ''
  sourceReloadError.value = ''
  try {
    const reloaded = await reloadSources()
    await refreshSources()
    const httpIds = new Set(httpSourceProfiles.value.map((profile) => profile.id))
    sourceProfileIds.value = sourceProfileIds.value.filter((id) => httpIds.has(id))
    if (postgresProfileId.value && !postgresProfiles.value.some((profile) => profile.id === postgresProfileId.value)) {
      postgresProfileId.value = ''
    }
    sourceValidationRevision += 1
    sourceProbeRevision += 1
    validatingSources.value = false
    sourceValidation.value = null
    sourceValidationError.value = ''
    probingSource.value = false
    sourceProbeResult.value = null
    sourceProbeError.value = ''
    sourceReloadStatus.value = SETUP_LABELS.reloadProfilesSummary(
      reloaded.profile_count,
      reloaded.added.length,
      reloaded.changed.length,
      reloaded.removed.length,
    )
  } catch (failure) {
    sourceReloadError.value = sourceOperationErrorText(failure)
  } finally {
    reloadingSources.value = false
  }
}

async function validateSourceProfiles() {
  if (validatingSources.value || reloadingSources.value || sourcesBusy()) return
  const revision = ++sourceValidationRevision
  validatingSources.value = true
  sourceValidation.value = null
  sourceValidationError.value = ''
  try {
    const selectedId =
      sourceProfileIds.value.length === 1
        ? sourceProfileIds.value[0]
        : sourceProfileIds.value.length === 0 && postgresProfileId.value
          ? postgresProfileId.value
          : undefined
    const response = await validateSources(selectedId)
    if (revision !== sourceValidationRevision) return
    sourceValidation.value = response
  } catch (failure) {
    if (revision !== sourceValidationRevision) return
    sourceValidationError.value = sourceOperationErrorText(failure)
  } finally {
    if (revision === sourceValidationRevision) validatingSources.value = false
  }
}

async function probeSelectedSource(rawQueryId: string) {
  if (probingSource.value || reloadingSources.value || sourcesBusy()) return
  const profileId = sourceProfileIds.value.length === 1 ? sourceProfileIds.value[0] : ''
  const queryId = rawQueryId.trim()
  if (!profileId || !queryId) {
    sourceProbeResult.value = null
    sourceProbeError.value = SETUP_LABELS.probeQueryRequired
    return
  }
  const revision = ++sourceProbeRevision
  probingSource.value = true
  sourceProbeResult.value = null
  sourceProbeError.value = ''
  try {
    const response = await probeSourceQuery({ profile_id: profileId, query_id: queryId })
    if (revision !== sourceProbeRevision) return
    sourceProbeResult.value = response
  } catch (failure) {
    if (revision !== sourceProbeRevision) return
    sourceProbeError.value = sourceOperationErrorText(failure)
  } finally {
    if (revision === sourceProbeRevision) probingSource.value = false
  }
}

function selectSourceContexts(files: File[]) {
  sourceContextFiles.value = files
  queueBusy.value = false
  uploadCancelled.value = false
  errorMessage.value = ''
}

function selectPostgresProfile(id: string) {
  if (sourceProfileIds.value.length === 0) {
    sourceValidationRevision += 1
    validatingSources.value = false
    sourceValidation.value = null
    sourceValidationError.value = ''
  }
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
  policyStatus.value = SETUP_MESSAGES.policyValidating
  try {
    const validation = await validatePolicy(draft)
    if (revision !== policyRevision) return null
    if (!validation.valid) {
      policyErrors.value = validation.errors
      policyStatus.value = SETUP_MESSAGES.policyInvalid
      return null
    }
    policy.value = validation.policy
    policyErrors.value = []
    policyStatus.value = SETUP_MESSAGES.policyValid(validation.policy.policy_id)
    return validation.policy
  } catch (failure) {
    if (revision === policyRevision) {
      policyStatus.value = SETUP_MESSAGES.policyInvalid
      if (failure instanceof ApiError && failure.code === 'MALFORMED_JSON') {
        policyErrors.value = [{ code: 'MALFORMED_JSON', json_pointer: '', message: SETUP_MESSAGES.policyMalformed }]
      } else {
        showError(failure)
      }
    }
    return null
  }
}

async function ensureUploadedInput(file: File, isCurrent: () => boolean): Promise<RunSummary> {
  if (uploadedInput && uploadedInput.file === file) {
    currentRun.value = uploadedInput.run
    return uploadedInput.run
  }
  const controller = new AbortController()
  uploadAbort = controller
  uploading.value = true
  uploadProgress.value = 1
  let accepted: RunSummary
  try {
    accepted = await uploadInput(
      file,
      (value) => (uploadProgress.value = Math.max(1, value)),
      controller.signal,
    )
  } finally {
    if (uploadAbort === controller) {
      uploadAbort = null
      uploading.value = false
    }
  }
  if (inputFile.value === file) uploadedInput = { file, run: accepted }
  if (!isCurrent()) return accepted
  currentRun.value = accepted
  await refreshRuns()
  return accepted
}

async function requestStageProposal() {
  if (!inputFile.value || proposingStages.value || working.value || (uploadProgress.value > 0 && !job.value) || postgresCapturePhase.value || trialBusy.value) return
  const file = inputFile.value
  const revision = ++stageProposalRevision
  proposingStages.value = true
  queueBusy.value = false
  uploadCancelled.value = false
  errorMessage.value = ''
  stageProposalError.value = ''
  stagesErrors.value = []
  try {
    const run = await ensureUploadedInput(file, () => revision === stageProposalRevision)
    if (revision !== stageProposalRevision) return
    uploadProgress.value = 0
    const proposal = await proposeStages(run.run_id)
    if (revision !== stageProposalRevision) return
    stageProposal.value = proposal
    if (proposal.status === 'DETECTED' && proposal.proposal) {
      stagesJson.value = JSON.stringify(proposal.proposal, null, 2)
    }
  } catch (failure) {
    if (revision !== stageProposalRevision) return
    uploadProgress.value = 0
    if (failure instanceof DOMException && failure.name === 'AbortError') {
      uploadCancelled.value = true
      return
    }
    if (failure instanceof ApiError && failure.code === 'BUSY') {
      stageProposalError.value = SETUP_LABELS.stagesBusy
    } else {
      stageProposalError.value = failure instanceof ApiError || failure instanceof Error ? failure.message : 'Unexpected local application error.'
    }
  } finally {
    if (revision === stageProposalRevision) proposingStages.value = false
  }
}

async function analyze(pairSlots?: string[][]) {
  if (!inputFile.value || working.value || postgresCapturePhase.value || proposingStages.value) return
  if (pairSlots && !sourceRequestState.value.request) return
  if (sourceRequestState.value.error) {
    errorMessage.value = sourceRequestState.value.error
    return
  }
  if (diagnosticFile.value && !resourceFile.value) {
    errorMessage.value = SETUP_MESSAGES.diagnosticNeedsSnapshot
    return
  }
  if (capacityFile.value && !resourceFile.value) {
    errorMessage.value = SETUP_MESSAGES.capacityNeedsSnapshot
    return
  }
  if (trendFile.value && !resourceFile.value) {
    errorMessage.value = SETUP_MESSAGES.trendNeedsSnapshot
    return
  }
  const file = inputFile.value
  const revision = ++analysisRevision
  const tabAtStart = tabRevision
  adviceAutoFor.value = null
  queueBusy.value = false
  errorMessage.value = ''
  stagesErrors.value = []
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
    const accepted = await ensureUploadedInput(file, () => revision === analysisRevision)
    if (revision !== analysisRevision) return
    const stagesText = stagesJson.value.trim()
    const stagesBlob = stagesText ? new Blob([stagesText], { type: 'application/json' }) : null
    // Пара плеч: тот же загруженный вход, по заданию на плечо, строго по очереди; одиночный запуск - один проход цикла.
    const requests = pairSlots ? pairSourceRequests(sourceRequestState.value.request!, pairSlots) : [sourceRequestState.value.request]
    for (const [index, request] of requests.entries()) {
      job.value = await createJob(
        accepted.run_id,
        activePolicy,
        resourceFile.value,
        diagnosticFile.value,
        request,
        sourceContextFiles.value,
        postgresPreFile.value,
        postgresPostFile.value,
        pgProfileHtmlFile.value,
        capacityFile.value,
        trendFile.value,
        stagesBlob,
      )
      if (index === 0) {
        // Запрос ИИ-разбора относится к этому запуску: запоминаем его и сбрасываем переключатель.
        adviceRevision = aiRequested.value ? revision : 0
        aiRequested.value = false
        uploadProgress.value = 100
      }
      await pollJob(revision, tabAtStart)
      if (revision !== analysisRevision || job.value?.state !== 'COMPLETE') break
    }
  } catch (failure) {
    if (revision !== analysisRevision) return
    uploadProgress.value = 0
    if (failure instanceof ApiError && failure.code === 'INVALID_STAGES') {
      stagesErrors.value = failure.details.length
        ? failure.details
        : [{ code: failure.code, json_pointer: '/stages', message: failure.message }]
    } else if (failure instanceof ApiError && failure.code === 'BUSY') {
      queueBusy.value = true
    } else {
      showError(failure)
    }
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

async function pollJob(revision: number, tabAtStart = tabRevision) {
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
  tablesSubTab.value = 'all'
  result.value = loaded
  if (!trialBusy.value && tabRevision === tabAtStart) activeTab.value = 'overview'
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
  stageProposalRevision += 1
  proposingStages.value = false
  uploadProgress.value = 0
  uploadCancelled.value = true
  uploadAbort = null
  uploading.value = false
  controller.abort()
  activeTab.value = 'setup'
  void nextTick(() => document.getElementById('input-file')?.focus())
}

const acceptedAtFormat = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'medium' })
// A file name repeated by every listed run tells nothing, the acceptance time stands in for it.
const fileNameTellsRunsApart = computed(() => runs.value.length < 2 || new Set(runs.value.map((run) => run.original_filename)).size > 1)
const timeInTitle = (run: RunSummary) => !fileNameTellsRunsApart.value && !!run.accepted_at
const runTitle = (run: RunSummary) => timeInTitle(run) ? `${SHELL_LABELS.runAccepted} ${acceptedAtFormat.format(new Date(run.accepted_at!))}` : run.original_filename
const selectedAnalysis = computed(() => analyses.value.find((item) => item.analysis_id === selectedAnalysisId.value))
const analysisContext = computed(() => {
  const analysis = selectedAnalysis.value
  if (!analysis) return ''
  const parts = analysis.policy_verdict === 'NO_POLICY' ? [] : [`${SHELL_LABELS.runPolicy} ${analysis.policy_id ?? analysis.policy_sha256.slice(0, 8)}`]
  if (analysis.resource_arm) parts.push(`${SHELL_LABELS.runArm} ${analysis.resource_arm}`)
  return parts.join(' · ')
})

async function refreshRuns(after?: string) {
  const page = await listRuns(after)
  runs.value = after ? [...runs.value, ...page.runs] : page.runs
  nextRunAfter.value = page.next_after
}

async function refreshSources() {
  sourceProfiles.value = (await listSources()).profiles
}

async function selectRun(run: RunSummary, options: { autoOpen?: boolean } = {}) {
  const revision = ++analysisRevision
  const tabAtStart = tabRevision
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
    if (options.autoOpen !== false && analyses.value.length > 0) {
      await selectAnalysis(analyses.value[0], { tabAtStart })
    }
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

async function selectAnalysisById(analysisId: string) {
  const found = analyses.value.find((item) => item.analysis_id === analysisId)
  if (found) await selectAnalysis(found, { switchTab: false })
}

async function selectAnalysis(analysis: AnalysisSummary, options: { tabAtStart?: number; switchTab?: boolean } = {}) {
  const runId = currentRun.value?.run_id
  if (!runId) return
  const tabAtStart = options.tabAtStart ?? tabRevision
  if (selectedAnalysisId.value === analysis.analysis_id) {
    if (result.value) {
      if (options.switchTab !== false) activeTab.value = 'overview'
      return
    }
    if (activeAnalysisLoad && activeAnalysisLoad.runId === runId && activeAnalysisLoad.analysisId === analysis.analysis_id) {
      await activeAnalysisLoad.promise
      if (options.switchTab !== false && result.value && selectedAnalysisId.value === analysis.analysis_id && tabRevision === tabAtStart) {
        activeTab.value = 'overview'
      }
      return
    }
  }
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
  const loadPromise = (async () => {
    try {
      const loaded = await getResult(runId, analysis.analysis_id)
      if (revision !== analysisRevision || selectedAnalysisId.value !== analysis.analysis_id) return
      tablesSubTab.value = 'all'
      result.value = loaded
      if (options.switchTab !== false && !trialBusy.value && tabRevision === tabAtStart) {
        activeTab.value = 'overview'
      }
      if (loaded.run_validity !== 'INVALID') await refreshBuckets()
    } catch (failure) {
      if (revision === analysisRevision) showError(failure)
    }
  })()
  const entry = { runId, analysisId: analysis.analysis_id, promise: loadPromise }
  activeAnalysisLoad = entry
  try {
    await loadPromise
  } finally {
    if (activeAnalysisLoad === entry) activeAnalysisLoad = null
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

// Opens the analysis of a history row; the baseline panel shows the baseline of the release series.
async function openReference({ release, analysis }: { release: Release; analysis: ReleaseAnalysis }, tab: ShellTabKey) {
  const tabAtStart = tabRevision
  showBaselineSeries(release.series)
  const runPromise = selectRun(runs.value.find((run) => run.run_id === release.run_id) ?? runSummaryFromId(release.run_id), { autoOpen: false })
  const runRevision = analysisRevision
  await runPromise
  if (runRevision !== analysisRevision || currentRun.value?.run_id !== release.run_id) return
  const analysisPromise = selectAnalysis(analysisSummary(analysis), { switchTab: false })
  const loadRevision = analysisRevision
  await analysisPromise
  if (loadRevision !== analysisRevision || currentRun.value?.run_id !== release.run_id || selectedAnalysisId.value !== analysis.analysis_id) return
  if (result.value !== null && tabRevision === tabAtStart) activeTab.value = tab
}

function showBaselineSeries(series: string) {
  preferredSeries.value = series
  baselineVersion.value += 1
}

async function showVerdict() {
  activeTab.value = 'overview'
  await nextTick()
  const verdict = document.getElementById('verdict')
  verdict?.scrollIntoView()
  verdict?.focus({ preventScroll: true })
}

const deepFocus = ref<{ stageId?: string; seriesId?: string; nonce: number } | null>(null)
let deepFocusNonce = 0

async function jumpTo(target: AttentionTarget) {
  if (target.tab === 'tables' && tablesSubTab.value !== 'all') {
    tablesSubTab.value = tablesTargetSubTab(result.value, target.targetId)
  }
  if (target.tab === 'deep' && (target.stageId || target.seriesId)) {
    deepFocusNonce += 1
    deepFocus.value = { stageId: target.stageId, seriesId: target.seriesId, nonce: deepFocusNonce }
  }
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
</script>

<template>
  <div class="app-shell shell">
    <aside class="sidebar side-navigation">
      <h1>LT Verdict</h1>
      <ShellTabs v-model="activeTab" />
      <section
        class="run-list-section"
        aria-labelledby="run-list-title"
        lang="ru"
      >
        <h2 id="run-list-title">
          {{ SHELL_LABELS.runsTitle }}
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
              <span>{{ runTitle(run) }}</span>
              <small>{{ run.source_type }} · {{ run.sha256.slice(0, 8) }}<template v-if="run.accepted_at && !timeInTitle(run)"> · <time :datetime="run.accepted_at">{{ acceptedAtFormat.format(new Date(run.accepted_at)) }}</time></template></small>
            </button>
          </li>
          <li
            v-if="runs.length === 0"
            class="muted"
          >
            {{ SHELL_LABELS.runsEmpty }}
          </li>
        </ul>
        <button
          v-if="nextRunAfter"
          type="button"
          @click="refreshRuns(nextRunAfter ?? undefined)"
        >
          {{ SHELL_LABELS.runsMore }}
        </button>
      </section>
      <section
        v-if="currentRun"
        class="run-list-section"
        aria-labelledby="analysis-list-title"
        lang="ru"
      >
        <h2 id="analysis-list-title">
          {{ SHELL_LABELS.analysesTitle }}
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
              <span>{{ SHELL_LABELS.analysisItem }} {{ analysis.analysis_id.slice(0, 12) }}</span>
              <small>{{ analysis.policy_verdict }} · {{ analysis.run_validity }}<span v-if="analysis.policy_id"> · {{ analysis.policy_id }}</span></small>
            </button>
          </li>
          <li
            v-if="analyses.length === 0"
            class="muted"
          >
            {{ SHELL_LABELS.analysesEmpty }}
          </li>
        </ul>
        <button
          v-if="nextAnalysisAfter"
          type="button"
          @click="refreshAnalyses(undefined, nextAnalysisAfter ?? undefined)"
        >
          {{ SHELL_LABELS.analysesMore }}
        </button>
      </section>
    </aside>

    <div class="workspace">
      <header
        class="app-header top-header"
        lang="ru"
      >
        <div class="run-identity">
          <strong>{{ currentRun ? runTitle(currentRun) : SHELL_LABELS.noRun }}</strong>
          <span
            v-if="currentRun"
            class="mono"
          >{{ currentRun.source_type }} · {{ currentRun.run_id.slice(0, 24) }}…</span>
          <span v-if="analysisContext">{{ analysisContext }}</span>
          <span v-if="completedAt">{{ SHELL_LABELS.completed }} {{ completedAt }}</span>
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
          :aria-label="theme === 'light' ? SHELL_LABELS.themeToDark : SHELL_LABELS.themeToLight"
          @click="theme = theme === 'light' ? 'dark' : 'light'"
        >
          <span aria-hidden="true">{{ theme === 'light' ? '◐' : '◑' }}</span>
          {{ theme === 'light' ? SHELL_LABELS.themeToDark : SHELL_LABELS.themeToLight }}
        </button>
        <a
          v-if="result && selectedAnalysisId"
          class="button-secondary shell-export-link"
          :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/report?format=html`"
          download
        >{{ EXPORT_LABELS.html }}</a>
      </header>

      <main>
        <ShellPanel :active-tab="activeTab">
          <VerdictCard
            v-if="verdictSummary && activeTab === 'overview'"
            :summary="verdictSummary"
          />

          <OverviewPanel
            v-if="result && selectedAnalysisId && activeTab === 'overview'"
            :key="result.run_id + ':' + selectedAnalysisId"
            :result="result"
            :run-id="result.run_id"
            :analysis-id="selectedAnalysisId"
            :analyses="analyses"
            @navigate="jumpTo"
          />

          <DeepAnalysisPanel
            v-if="result && selectedAnalysisId && activeTab === 'deep'"
            :key="result.run_id + ':' + selectedAnalysisId"
            :result="result"
            :run-id="result.run_id"
            :analysis-id="selectedAnalysisId"
            :analyses="analyses"
            :focus="deepFocus"
            @select-analysis="selectAnalysisById"
          />

          <PodHeatmap
            v-if="result && selectedAnalysisId && activeTab === 'deep'"
            :key="'pods:' + result.run_id + ':' + selectedAnalysisId"
            :run-id="result.run_id"
            :analyses="analyses"
            :analysis-id="selectedAnalysisId"
          />

          <p
            v-if="activeTab === 'overview' && !result"
            class="notice notice-info"
            lang="ru"
          >
            {{ SHELL_LABELS.overviewEmpty }}
          </p>

          <p
            v-if="activeTab === 'deep' && !result"
            class="notice notice-info"
            lang="ru"
          >
            {{ SHELL_LABELS.overviewEmpty }}
          </p>

          <NewAnalysisPanel
            v-show="activeTab === 'setup'"
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
            :source-step-mode="sourceStepMode"
            :source-margin="sourceMargin"
            :source-max-idle-gap="sourceMaxIdleGap"
            :source-request-error="sourceRequestState.error"
            :reloading-sources="reloadingSources"
            :source-reload-status="sourceReloadStatus"
            :source-reload-error="sourceReloadError"
            :validating-sources="validatingSources"
            :source-validation="sourceValidation"
            :source-validation-error="sourceValidationError"
            :probing-source="probingSource"
            :source-probe-result="sourceProbeResult"
            :source-probe-error="sourceProbeError"
            :policy="policy"
            :policy-status="policyStatus"
            :policy-errors="policyErrors"
            :busy="working || (uploadProgress > 0 && !job) || !!postgresCapturePhase || trialBusy || proposingStages"
            :ai-requested="aiRequested"
            :ai-config="aiConfig"
            :ai-model="aiModel"
            :stages-json="stagesJson"
            :stage-proposal="stageProposal"
            :stage-proposal-error="stageProposalError"
            :stages-errors="stagesErrors"
            :proposing-stages="proposingStages"
            @input="selectInput"
            @resources="selectResources"
            @diagnostics="selectDiagnostics"
            @capacity="selectCapacity"
            @trend="selectTrend"
            @source-contexts="selectSourceContexts"
            @source-profiles="selectSourceProfiles"
            @reload-sources="reloadSourceProfiles"
            @validate-sources="validateSourceProfiles"
            @probe-source="probeSelectedSource"
            @postgres-profile="selectPostgresProfile"
            @postgres-pre="selectPostgresPre"
            @postgres-post="selectPostgresPost"
            @pg-profile-html="selectPgProfileHtml"
            @capture-postgres="capturePostgres"
            @source-window-origin="sourceWindowOrigin = $event"
            @source-start="sourceStart = $event"
            @source-end="sourceEnd = $event"
            @source-step="sourceStep = $event"
            @source-step-mode="sourceStepMode = $event"
            @source-margin="sourceMargin = $event"
            @source-max-idle-gap="sourceMaxIdleGap = $event"
            @policy-file="selectPolicyFile"
            @open-rules="activeTab = 'rules'"
            @ai-requested="aiRequested = $event"
            @ai-model="aiModel = $event"
            @stages-json="stagesJson = $event; stagesErrors = []"
            @propose-stages="requestStageProposal"
            @analyze="analyze"
            @analyze-pair="analyze"
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
            :upload-labels="UPLOAD_LABELS"
            upload-lang="ru"
            :poll-issue="pollIssue"
            :labels="JOB_LABELS"
            notice-lang="ru"
            @cancel="cancel"
            @cancel-upload="cancelUpload"
            @retry="retryPoll"
          />

          <JenkinsPanel
            v-if="apiReady && activeTab === 'setup'"
            @imported="selectRun($event); refreshRuns()"
          />

          <BaselinePanel
            v-if="apiReady"
            v-show="activeTab === 'compare'"
            :selection="selectedReference"
            :filename="currentRun?.original_filename ?? ''"
            :working="working"
            :labels="COMPARE_LABELS"
            lang="ru"
            :version="baselineVersion"
            :preferred-series="preferredSeries"
            :facts="baselineFacts"
            :result="result"
            @active-series="baselineSeries = $event"
          />

          <HistoryPanel
            v-if="apiReady"
            v-show="activeTab === 'history'"
            :selection="selectedReference"
            :active="activeTab === 'history'"
            :version="baselineVersion"
            :working="working"
            @open="openReference($event, 'overview')"
            @compare="openReference($event, 'compare')"
            @baseline-changed="showBaselineSeries"
            @statistical="showBaselineSeries($event); activeTab = 'compare'"
          />

          <div
            v-if="result && selectedAnalysisId"
            v-show="activeTab === 'history'"
            class="bucket-controls"
            :aria-label="DOWNLOAD_LABELS.group"
            lang="ru"
          >
            <a
              v-for="format in ['json', 'html', 'asciidoc', 'confluence', 'svg']"
              :key="format"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/report?format=${format}`"
              download
            >{{ DOWNLOAD_LABELS.format(format) }}</a>
            <a
              v-if="result.evidence.some(item => item.type === 'resource_binding')"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/resource-snapshot`"
              download
            >{{ DOWNLOAD_LABELS.resourceSnapshot }}</a>
            <a
              v-if="result.capacity_summary"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/capacity-plan`"
              download
            >{{ DOWNLOAD_LABELS.capacityPlan }}</a>
            <a
              v-if="result.capacity_summary"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/capacity`"
              download
            >{{ DOWNLOAD_LABELS.capacityResult }}</a>
            <a
              v-if="result.evidence.some(item => item.type === 'trend_summary')"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/trend-plan`"
              download
            >{{ DOWNLOAD_LABELS.trendPlan }}</a>
            <a
              v-if="result.evidence.some(item => item.type === 'trend_summary')"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/trend`"
              download
            >{{ DOWNLOAD_LABELS.trendResult }}</a>
            <a
              v-for="(context, index) in downloadableSourceContexts"
              :key="context.profile_id"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/source-context${downloadableSourceContexts.length === 1 ? '' : `/${index + 1}`}`"
              download
            >{{ DOWNLOAD_LABELS.openSearchContext(downloadableSourceContexts.length === 1 ? null : context.profile_id) }}</a>
            <a
              v-if="postgresContext?.pre_sha256"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/postgres-pre`"
              download
            >{{ DOWNLOAD_LABELS.postgresPre }}</a>
            <a
              v-if="postgresContext?.post_sha256"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/postgres-post`"
              download
            >{{ DOWNLOAD_LABELS.postgresPost }}</a>
            <a
              v-if="postgresContext"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/postgres-context`"
              download
            >{{ DOWNLOAD_LABELS.postgresContext }}</a>
            <a
              v-if="postgresContext?.pg_profile_html_sha256"
              class="button-secondary"
              :href="`/api/runs/${encodeURIComponent(result.run_id)}/analyses/${selectedAnalysisId}/pg-profile`"
              download
            >{{ DOWNLOAD_LABELS.pgProfile }}</a>
          </div>

          <AnalyticsPanel
            v-if="selectedReference"
            v-show="activeTab === 'history'"
            :selection="selectedReference"
            :working="working"
            :series="baselineSeries"
            :labels="ANALYTICS_LABELS"
            lang="ru"
            :whole-run="result?.evidence.some((item) => item.type === 'stage_binding')"
            @loaded="chartMarkers = $event?.overlay?.markers ?? []"
          />

          <AdvicePanel
            v-if="selectedReference"
            v-show="activeTab === 'advice'"
            :selection="selectedReference"
            :auto-start="adviceAutoFor !== null && adviceAutoFor === selectedAnalysisId"
            :result="result"
            :ai-config="aiConfig"
            :ai-model="aiModel"
            @ai-model="aiModel = $event"
            @auto-started="adviceAutoFor = null"
            @navigate="jumpTo"
          />

          <TablesPanel
            v-if="result && activeTab === 'tables'"
            v-model="tablesSubTab"
            :result="result"
          />
          <div
            v-if="result && activeTab === 'tables'"
            id="tables-subpanel"
            class="tables-subpanel"
          >
            <CapacityTable
              v-if="tablesSubTab === 'all' || tablesSubTab === 'capacity'"
              :result="result"
            />
            <RuleChecksTable
              v-if="tablesSubTab === 'all' || tablesSubTab === 'rules'"
              :result="result"
            />
            <TransactionsTable
              v-if="tablesSubTab === 'all' || tablesSubTab === 'transactions'"
              :result="result"
            />
            <TrendTable
              v-if="tablesSubTab === 'all' || tablesSubTab === 'trends'"
              :result="result"
            />
            <AnalysisView
              :result="result"
              :visible-group="tablesSubTab"
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
              v-if="result.run_validity !== 'INVALID' && (tablesSubTab === 'all' || tablesSubTab === 'normalized')"
              class="muted"
              lang="ru"
            >
              {{ NORMALIZED_LABELS.pageSummary(buckets.length, bucketPageFrom, bucketRollup) }}
              <button
                v-if="bucketNextFrom !== null"
                type="button"
                @click="refreshBuckets(bucketNextFrom)"
              >
                {{ NORMALIZED_LABELS.nextPage }}
              </button>
            </p>
          </div>
          <RulesPanel
            v-show="activeTab === 'rules'"
            :policy="policy"
            :policy-status="policyStatus"
            :policy-errors="policyErrors"
            :busy="working || (uploadProgress > 0 && !job) || !!postgresCapturePhase || trialBusy"
            :result="result"
            :run-name="currentRun?.original_filename ?? ''"
            :run-hash="currentRun?.sha256.slice(0, 8) ?? ''"
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
