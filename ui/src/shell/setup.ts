import { SETUP_LABELS } from './labels'

export type ReadinessKey = 'input' | 'busy' | 'policy' | 'resources' | 'plans' | 'sources' | 'postgres'
export type ReadinessLevel = 'ok' | 'info' | 'warn' | 'block'

export interface ReadinessInput {
  busy: boolean
  aiRequested: boolean
  inputName: string | null
  policyId: string | null
  policyHasErrors: boolean
  resourceName: string | null
  plans: { diagnostic: boolean; capacity: boolean; trend: boolean }
  onlineProfileCount: number
  sourceRequestError: string
  contextCount: number
  postgres: { pre: boolean; post: boolean; html: boolean }
}

export interface ReadinessItem {
  key: ReadinessKey
  level: ReadinessLevel
  title: string
  detail: string
}

export interface Readiness {
  items: ReadinessItem[]
  blockers: ReadinessKey[]
  canStart: boolean
  will: string[]
}

export function buildReadiness(input: ReadinessInput): Readiness {
  const planNames = [
    input.plans.diagnostic && SETUP_LABELS.planNames.diagnostic,
    input.plans.capacity && SETUP_LABELS.planNames.capacity,
    input.plans.trend && SETUP_LABELS.planNames.trend,
  ].filter(Boolean).join(', ')
  const postgresNames = [
    input.postgres.pre && SETUP_LABELS.postgresNames.pre,
    input.postgres.post && SETUP_LABELS.postgresNames.post,
    input.postgres.html && SETUP_LABELS.postgresNames.html,
  ].filter(Boolean).join(', ')
  const items: ReadinessItem[] = [
    input.inputName
      ? { key: 'input', level: 'ok', title: SETUP_LABELS.itemInput, detail: input.inputName }
      : { key: 'input', level: 'block', title: SETUP_LABELS.itemInput, detail: SETUP_LABELS.inputMissing },
  ]

  if (input.busy) {
    items.push({ key: 'busy', level: 'block', title: SETUP_LABELS.itemBusy, detail: SETUP_LABELS.busy })
  }

  items.push(
    input.policyId !== null && !input.policyHasErrors
      ? { key: 'policy', level: 'ok', title: SETUP_LABELS.itemPolicy, detail: SETUP_LABELS.policyOk(input.policyId) }
      : input.policyId !== null
        ? { key: 'policy', level: 'block', title: SETUP_LABELS.itemPolicy, detail: SETUP_LABELS.policyInvalid }
        : input.policyHasErrors
          ? { key: 'policy', level: 'warn', title: SETUP_LABELS.itemPolicy, detail: SETUP_LABELS.policyRejected }
          : { key: 'policy', level: 'info', title: SETUP_LABELS.itemPolicy, detail: SETUP_LABELS.policyNoneItem },
    input.onlineProfileCount > 0
      ? { key: 'resources', level: 'ok', title: SETUP_LABELS.itemResources, detail: SETUP_LABELS.resourcesOnline }
      : input.resourceName
        ? { key: 'resources', level: 'ok', title: SETUP_LABELS.itemResources, detail: input.resourceName }
        : planNames
          ? { key: 'resources', level: 'block', title: SETUP_LABELS.itemResources, detail: SETUP_LABELS.resourcesRequired(planNames) }
          : { key: 'resources', level: 'info', title: SETUP_LABELS.itemResources, detail: SETUP_LABELS.resourcesNoneItem },
    planNames
      ? { key: 'plans', level: 'ok', title: SETUP_LABELS.itemPlans, detail: planNames }
      : { key: 'plans', level: 'info', title: SETUP_LABELS.itemPlans, detail: SETUP_LABELS.plansNoneItem },
    input.sourceRequestError
      ? { key: 'sources', level: 'block', title: SETUP_LABELS.itemSources, detail: input.sourceRequestError }
      : input.onlineProfileCount > 0
        ? { key: 'sources', level: 'ok', title: SETUP_LABELS.itemSources, detail: SETUP_LABELS.sourcesOnline(input.onlineProfileCount) }
        : input.contextCount > 0
          ? { key: 'sources', level: 'ok', title: SETUP_LABELS.itemSources, detail: SETUP_LABELS.sourcesContext(input.contextCount) }
          : { key: 'sources', level: 'info', title: SETUP_LABELS.itemSources, detail: SETUP_LABELS.sourcesNoneItem },
    postgresNames
      ? { key: 'postgres', level: 'ok', title: SETUP_LABELS.itemPostgres, detail: postgresNames }
      : { key: 'postgres', level: 'info', title: SETUP_LABELS.itemPostgres, detail: SETUP_LABELS.postgresNoneItem },
  )

  const blockers = items.filter((item) => item.level === 'block').map((item) => item.key)
  const will = [
    input.policyId !== null && !input.policyHasErrors ? SETUP_LABELS.willVerdict(input.policyId) : SETUP_LABELS.willNoVerdict,
    input.onlineProfileCount > 0 ? SETUP_LABELS.willResourcesOnline : input.resourceName ? SETUP_LABELS.willResourcesFile : null,
    planNames ? SETUP_LABELS.willPlans(planNames) : null,
    input.contextCount > 0 ? SETUP_LABELS.willContext : null,
    postgresNames ? SETUP_LABELS.willPostgres(postgresNames) : null,
    input.aiRequested ? SETUP_LABELS.willAdvice : null,
  ].filter((line): line is string => line !== null)

  return { items, blockers, canStart: blockers.length === 0, will }
}

export function msToSeconds(ms: string): string {
  return ms === '' ? '' : String(Number(ms) / 1000)
}

export function secondsToMs(seconds: string): string {
  if (seconds === '') return ''
  const value = Number(seconds)
  return Number.isFinite(value) ? String(Math.round(value * 1000)) : ''
}
