import { expect, test } from '@playwright/test'
import { SETUP_LABELS } from '../src/shell/labels'
import { buildReadiness, msToSeconds, secondsToMs, type ReadinessInput, type ReadinessKey } from '../src/shell/setup'

// Чистая функция готовности вкладки «Новый анализ»: состояние формы на входе, условия запуска на выходе.
const base: ReadinessInput = {
  busy: false,
  inputName: 'run.jtl',
  policyId: null,
  policyHasErrors: false,
  resourceName: null,
  plans: { diagnostic: false, capacity: false, trend: false },
  onlineProfileCount: 0,
  sourceRequestError: '',
  contextCount: 0,
  postgres: { pre: false, post: false, html: false },
  aiRequested: false,
}
const build = (over: Partial<ReadinessInput>) => buildReadiness({ ...base, ...over })
const item = (readiness: ReturnType<typeof build>, key: ReadinessKey) => readiness.items.find((entry) => entry.key === key)!

test('only the load file is required: without it the start is blocked and says why', () => {
  const missing = build({ inputName: null })

  expect(missing.canStart).toBe(false)
  expect(missing.blockers).toEqual(['input'])
  expect(item(missing, 'input')).toMatchObject({ level: 'block', detail: SETUP_LABELS.inputMissing })
  const ready = build({})
  expect(ready.canStart).toBe(true)
  expect(ready.blockers).toEqual([])
  expect(item(ready, 'input').level).toBe('ok')
  expect(ready.items.map((entry) => entry.key)).toEqual(['input', 'policy', 'resources', 'plans', 'sources', 'postgres'])
})

test('without a policy the result is NO_POLICY and the start stays available', () => {
  const none = build({})

  expect(item(none, 'policy')).toMatchObject({ level: 'info', detail: SETUP_LABELS.policyNoneItem })
  expect(none.will).toEqual([SETUP_LABELS.willNoVerdict])
})

test('a valid policy gives a verdict by its rules', () => {
  const ready = build({ policyId: 'release-gate' })

  expect(item(ready, 'policy')).toMatchObject({ level: 'ok', detail: SETUP_LABELS.policyOk('release-gate') })
  expect(ready.will[0]).toBe(SETUP_LABELS.willVerdict('release-gate'))
})

test('an invalid policy draft blocks the start, a rejected policy file only warns', () => {
  const draft = build({ policyId: 'broken', policyHasErrors: true })
  const rejected = build({ policyId: null, policyHasErrors: true })

  expect(draft.canStart).toBe(false)
  expect(draft.blockers).toEqual(['policy'])
  expect(item(draft, 'policy')).toMatchObject({ level: 'block', detail: SETUP_LABELS.policyInvalid })
  expect(rejected.canStart).toBe(true)
  expect(item(rejected, 'policy')).toMatchObject({ level: 'warn', detail: SETUP_LABELS.policyRejected })
  expect(rejected.will).toEqual([SETUP_LABELS.willNoVerdict])
})

test('capacity and trend plans need a file snapshot, as the existing preflight requires', () => {
  const both = build({ plans: { diagnostic: false, capacity: true, trend: true } })
  const withSnapshot = build({ plans: { diagnostic: false, capacity: true, trend: true }, resourceName: 'snapshot.json' })

  expect(both.canStart).toBe(false)
  expect(both.blockers).toEqual(['resources'])
  expect(item(both, 'resources').level).toBe('block')
  expect(item(both, 'resources').detail).toBe(SETUP_LABELS.resourcesRequired(`${SETUP_LABELS.planNames.capacity}, ${SETUP_LABELS.planNames.trend}`))
  expect(withSnapshot.canStart).toBe(true)
  expect(item(withSnapshot, 'resources')).toMatchObject({ level: 'ok', detail: 'snapshot.json' })
  expect(item(withSnapshot, 'plans').detail).toBe(`${SETUP_LABELS.planNames.capacity}, ${SETUP_LABELS.planNames.trend}`)
})

test('a correlation plan without a snapshot blocks the start like the server check does', () => {
  const risky = build({ plans: { diagnostic: true, capacity: false, trend: false } })
  const all = build({ plans: { diagnostic: true, capacity: true, trend: true } })
  const withSnapshot = build({ plans: { diagnostic: true, capacity: false, trend: false }, resourceName: 'snapshot.json' })
  const online = build({ plans: { diagnostic: true, capacity: false, trend: false }, onlineProfileCount: 1 })

  expect(risky.canStart).toBe(false)
  expect(risky.blockers).toEqual(['resources'])
  expect(item(risky, 'resources')).toMatchObject({ level: 'block', detail: SETUP_LABELS.resourcesRequired(SETUP_LABELS.planNames.diagnostic) })
  expect(item(all, 'resources').detail).toBe(SETUP_LABELS.resourcesRequired(`${SETUP_LABELS.planNames.diagnostic}, ${SETUP_LABELS.planNames.capacity}, ${SETUP_LABELS.planNames.trend}`))
  expect(withSnapshot.canStart).toBe(true)
  expect(item(withSnapshot, 'resources').level).toBe('ok')
  expect(online.canStart).toBe(true)
})

test('without any plan an absent snapshot is just not chosen', () => {
  expect(item(build({}), 'resources')).toMatchObject({ level: 'info', detail: SETUP_LABELS.resourcesNoneItem })
  expect(item(build({}), 'plans')).toMatchObject({ level: 'info', detail: SETUP_LABELS.plansNoneItem })
})

test('while busy the start is blocked by a separate item', () => {
  const busy = build({ busy: true })

  expect(busy.canStart).toBe(false)
  expect(busy.blockers).toEqual(['busy'])
  expect(busy.items.map((entry) => entry.key).slice(0, 2)).toEqual(['input', 'busy'])
  expect(item(busy, 'busy').detail).toBe(SETUP_LABELS.busy)
})

test('online profiles replace the file snapshot, and a source request error blocks the start', () => {
  const online = build({ onlineProfileCount: 2 })
  const failed = build({ onlineProfileCount: 1, sourceRequestError: 'Margin must be at most 3 600 000 ms and a multiple of the step.' })

  expect(item(online, 'resources')).toMatchObject({ level: 'ok', detail: SETUP_LABELS.resourcesOnline })
  expect(item(online, 'sources')).toMatchObject({ level: 'ok', detail: SETUP_LABELS.sourcesOnline(2) })
  expect(online.will).toContain(SETUP_LABELS.willResourcesOnline)
  expect(failed.canStart).toBe(false)
  expect(failed.blockers).toEqual(['sources'])
  expect(item(failed, 'sources')).toMatchObject({ level: 'block', detail: 'Margin must be at most 3 600 000 ms and a multiple of the step.' })
})

test('contexts and PostgreSQL files are listed by what they are', () => {
  const ready = build({ contextCount: 3, resourceName: 's.json', postgres: { pre: true, post: false, html: true } })

  expect(item(ready, 'sources')).toMatchObject({ level: 'ok', detail: SETUP_LABELS.sourcesContext(3) })
  expect(item(ready, 'postgres')).toMatchObject({ level: 'ok', detail: `${SETUP_LABELS.postgresNames.pre}, ${SETUP_LABELS.postgresNames.html}` })
  expect(ready.will).toEqual([
    SETUP_LABELS.willNoVerdict,
    SETUP_LABELS.willResourcesFile,
    SETUP_LABELS.willContext,
    SETUP_LABELS.willPostgres(`${SETUP_LABELS.postgresNames.pre}, ${SETUP_LABELS.postgresNames.html}`),
  ])
  expect(item(build({}), 'postgres')).toMatchObject({ level: 'info', detail: SETUP_LABELS.postgresNoneItem })
})

test('the AI request only adds a line to what will happen and never blocks the start', () => {
  const asked = build({ aiRequested: true })

  expect(asked.canStart).toBe(true)
  expect(asked.will).toEqual([SETUP_LABELS.willNoVerdict, SETUP_LABELS.willAdvice])
  expect(build({}).will).not.toContain(SETUP_LABELS.willAdvice)
})

test('source window values are shown in seconds and stored in milliseconds without float artifacts', () => {
  expect(msToSeconds('')).toBe('')
  expect(msToSeconds('1000')).toBe('1')
  expect(msToSeconds('60000')).toBe('60')
  expect(msToSeconds('1500')).toBe('1.5')
  expect(secondsToMs('')).toBe('')
  expect(secondsToMs('1')).toBe('1000')
  expect(secondsToMs('60')).toBe('60000')
  expect(secondsToMs('1.001')).toBe('1001')
  expect(secondsToMs('1.1')).toBe('1100')
  expect(secondsToMs('0')).toBe('0')
})
