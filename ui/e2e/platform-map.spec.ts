import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import type { AnalysisResult, AnalysisSummary } from '../src/types'
import { PLATFORM_MAP_LABELS as LABELS } from '../src/shell/platformLabels'
import { armAnalyses, buildServiceArmMap, pickArmAnalysis, problemServices } from '../src/shell/platformMap'

// Карта «сервис x плечо» (платформа, P4b): чистые функции без сервера и страница на подставных ответах API.
function result(evidence: unknown[]): AnalysisResult {
  return {
    schema_version: 'analysis-result.v1',
    run_id: 'run-1',
    analysis_mode: 'standard',
    run_validity: 'VALID',
    policy_verdict: 'NO_POLICY',
    analysis_coverage: { status: 'COMPLETE', reasons: [] },
    findings: [],
    evidence,
  } as unknown as AnalysisResult
}

let counter = 0
const check = (service: string, ruleId: string, status: string, reason: string | null = null, effect = 'sla') => ({
  id: `rc-${counter++}`, type: 'resource_policy_check', window_id: 'full', rule_id: `${ruleId}/${service}`, series_id: `${ruleId}-${service}`,
  unit: 'ratio', operator: 'gt', threshold: '0.8', effect, status, reason, platform_rule_id: ruleId, service,
})
const healthy = (service: string) => [check(service, 'memory-limit', 'PASS'), check(service, 'cpu-limit', 'PASS')]

const cell = (map: ReturnType<typeof buildServiceArmMap>, service: string, arm: string) => map.cells.find((item) => item.service === service && item.arm === arm)

test('a failure on arm B does not change the cells of arm A', () => {
  const map = buildServiceArmMap([
    { arm: 'A', result: result(healthy('payments-svc')) },
    { arm: 'B', result: result([check('payments-svc', 'memory-limit', 'FAIL'), check('payments-svc', 'cpu-limit', 'PASS')]) },
  ])

  expect(cell(map, 'payments-svc', 'A')).toEqual({ service: 'payments-svc', arm: 'A', state: 'PASS', failedRuleIds: [], reasons: [] })
  expect(cell(map, 'payments-svc', 'B')).toEqual({ service: 'payments-svc', arm: 'B', state: 'FAIL', failedRuleIds: ['memory-limit'], reasons: [] })
})

test('a service with no series on one arm is NO_VERDICT with its reason, not an empty cell', () => {
  const map = buildServiceArmMap([
    { arm: 'A', result: result(healthy('orders-svc')) },
    { arm: 'B', result: result([check('orders-svc', 'memory-limit', 'NO_VERDICT', 'RESOURCE_SERIES_NOT_FOUND')]) },
  ])

  expect(cell(map, 'orders-svc', 'B')).toMatchObject({ state: 'NO_VERDICT', reasons: ['RESOURCE_SERIES_NOT_FOUND'] })
})

test('a service excluded on one arm is NOT_CHECKED there, never PASS', () => {
  const map = buildServiceArmMap([
    { arm: 'A', result: result([...healthy('orders-svc'), ...healthy('gateway')]) },
    { arm: 'B', result: result(healthy('orders-svc')) },
  ])

  expect(cell(map, 'gateway', 'B')).toEqual({ service: 'gateway', arm: 'B', state: 'NOT_CHECKED', failedRuleIds: [], reasons: [] })
  expect(cell(map, 'gateway', 'A')?.state).toBe('PASS')
})

test('a service whose arm has only diagnostic checks is NOT_CHECKED and a diagnostic failure does not colour the cell', () => {
  const map = buildServiceArmMap([
    { arm: 'A', result: result([check('orders-svc', 'cpu-throttling', 'FAIL', null, 'diagnostic')]) },
  ])

  expect(cell(map, 'orders-svc', 'A')).toMatchObject({ state: 'NOT_CHECKED', failedRuleIds: [] })
})

test('three arms make three columns and an arm without an analysis is "no analysis", not normal', () => {
  const map = buildServiceArmMap([
    { arm: 'C', result: result(healthy('orders-svc')) },
    { arm: 'A', result: result(healthy('orders-svc')) },
    { arm: 'B', result: null },
  ])

  expect(map.arms).toEqual(['A', 'B', 'C'])
  expect(map.missingArms).toEqual(['B'])
  expect(cell(map, 'orders-svc', 'B')).toBeUndefined()
})

test('rows list violations first, then no-verdict services, then the rest', () => {
  const map = buildServiceArmMap([
    {
      arm: 'A',
      result: result([...healthy('alpha'), check('beta', 'memory-limit', 'NO_VERDICT', 'RESOURCE_GAPS'), check('gamma', 'oom', 'FAIL'), ...healthy('delta')]),
    },
  ])

  expect(map.services).toEqual(['gamma', 'beta', 'alpha', 'delta'])
  expect(problemServices(map)).toEqual(['gamma', 'beta'])
})

test('a failure outranks no verdict within one cell and keeps the reasons', () => {
  const map = buildServiceArmMap([
    { arm: 'A', result: result([check('orders-svc', 'oom', 'FAIL'), check('orders-svc', 'memory-limit', 'NO_VERDICT', 'RESOURCE_GAPS')]) },
  ])

  expect(cell(map, 'orders-svc', 'A')).toMatchObject({ state: 'FAIL', failedRuleIds: ['oom'], reasons: ['RESOURCE_GAPS'] })
})

test('checks of other effects without a service never create a row', () => {
  const plain = { id: 'x', type: 'resource_policy_check', window_id: 'w', rule_id: 'cpu-high', series_id: 'cpu', unit: 'ratio', operator: 'gt', threshold: '1', effect: 'sla', status: 'FAIL', reason: null }
  const map = buildServiceArmMap([{ arm: 'A', result: result([plain]) }])

  expect(map.services).toEqual([])
  expect(map.cells).toEqual([])
})

test('one analysis per arm: the selected one wins, analyses without an arm are left out', () => {
  const summary = (id: string, arm?: string): AnalysisSummary => ({ analysis_id: id, policy_sha256: 'p', policy_verdict: 'PASS', run_validity: 'VALID', ...(arm ? { resource_arm: arm } : {}) })
  const picks = armAnalyses([summary('a1', 'A'), summary('b1', 'B'), summary('b2', 'B'), summary('x')], 'b2')

  expect(picks).toEqual([
    { arm: 'A', analysisId: 'a1', analysesOfArm: 1 },
    { arm: 'B', analysisId: 'b2', analysesOfArm: 2 },
  ])
})

test('the arm pick rule shared with the pod view: selected first, else the first usable one', () => {
  const summary = (id: string, hash?: string): AnalysisSummary => ({ analysis_id: id, policy_sha256: 'p', policy_verdict: 'PASS', run_validity: 'VALID', resource_arm: 'B', ...(hash ? { resource_snapshot_sha256: hash } : {}) })
  const items = [summary('b1'), summary('b2', 'h'), summary('b3', 'h')]
  const hasHash = (item: AnalysisSummary) => Boolean(item.resource_snapshot_sha256)

  expect(pickArmAnalysis(items, 'b3')?.analysis_id).toBe('b3')
  expect(pickArmAnalysis(items, 'other')?.analysis_id).toBe('b1')
  expect(pickArmAnalysis(items, 'b3', hasHash)?.analysis_id).toBe('b3')
  expect(pickArmAnalysis(items, 'b1', hasHash)?.analysis_id).toBe('b2')
  expect(pickArmAnalysis([summary('b1')], 'b1', hasHash)).toBeUndefined()
})

const run = { run_id: 'map-run', source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'map.jtl' }
const armA = 'a'.repeat(64)
const armB = 'b'.repeat(64)
const resultA = result([...healthy('payments-svc'), ...healthy('orders-svc'), ...healthy('gateway')])
const resultB = result([
  check('payments-svc', 'memory-limit', 'FAIL'),
  check('orders-svc', 'memory-limit', 'NO_VERDICT', 'RESOURCE_SERIES_NOT_FOUND'),
  ...healthy('gateway'),
])

async function openMap(page: Page, options: { analyses?: unknown[]; selected?: string; failB?: boolean } = {}) {
  const requested: string[] = []
  const analyses = options.analyses ?? [
    { analysis_id: armA, policy_sha256: 'c'.repeat(64), policy_verdict: 'PASS', run_validity: 'VALID', resource_arm: 'A' },
    { analysis_id: armB, policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: 'VALID', resource_arm: 'B' },
  ]
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) body = { profiles: [] }
    else if (method === 'GET' && /\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses, next_after: null }
    else if (path.endsWith('/result')) {
      requested.push(path.split('/')[5])
      if (path.includes(armB) && options.failB) {
        await route.fulfill({ status: 500, json: { error: { code: 'INTERNAL', message: 'boom' } } })
        return
      }
      body = path.includes(armB) ? resultB : resultA
    } else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'map.jtl' }).click()
  await page.locator(`button[title="${options.selected ?? armA}"]`).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  return requested
}

test('the map shows one column per arm with the state of each service on that arm', async ({ page }) => {
  const requested = await openMap(page)

  await expect(page.getByTestId('platform-map')).toBeVisible()
  await expect(page.getByTestId('platform-map-arm')).toHaveText([LABELS.arm('A'), LABELS.arm('B')])
  await expect(page.getByTestId('platform-map-row')).toHaveCount(3)
  const rows = page.getByTestId('platform-map-row')
  await expect(rows.nth(0)).toHaveAttribute('data-service', 'payments-svc')
  await expect(rows.nth(0).locator('[data-arm="A"] [data-state]')).toHaveAttribute('data-state', 'PASS')
  await expect(rows.nth(0).locator('[data-arm="B"] [data-state]')).toHaveAttribute('data-state', 'FAIL')
  await expect(rows.nth(0).locator('[data-arm="B"]')).toContainText('memory-limit')
  await expect(rows.nth(1).locator('[data-arm="B"] [data-state]')).toHaveAttribute('data-state', 'NO_VERDICT')
  await expect(rows.nth(1).locator('[data-arm="B"]')).toContainText('RESOURCE_SERIES_NOT_FOUND')
  await expect(page.getByTestId('platform-map-summary')).toContainText(LABELS.summary(2, 3))
  await expect(page.getByTestId('platform-map')).not.toContainText(/все плечи/i)
  expect(requested.filter((id) => id === armB)).toHaveLength(1)
})

test('the problems-only filter hides services that are normal on every arm', async ({ page }) => {
  await openMap(page, { selected: armB })
  await expect(page.getByTestId('platform-map-row')).toHaveCount(3)

  await page.getByTestId('platform-map-only-problems').check()
  await expect(page.getByTestId('platform-map-row')).toHaveCount(2)
  await expect(page.locator('[data-testid="platform-map-row"][data-service="gateway"]')).toHaveCount(0)
  await page.getByTestId('platform-map-only-problems').uncheck()
  await expect(page.getByTestId('platform-map-row')).toHaveCount(3)
})

test('an arm whose result cannot be loaded is "no analysis" with a visible error, not normal', async ({ page }) => {
  await openMap(page, { failB: true })

  await expect(page.getByTestId('platform-map-error')).toContainText('B')
  await expect(page.getByTestId('platform-map-row').first().locator('[data-arm="B"]')).toContainText(LABELS.noAnalysis)
  await expect(page.getByTestId('platform-map-row').first().locator('[data-arm="B"] [data-state]')).toHaveCount(0)
})

test('an analysis without an arm label shows no map and requests no other result', async ({ page }) => {
  const requested = await openMap(page, {
    analyses: [{ analysis_id: armA, policy_sha256: 'c'.repeat(64), policy_verdict: 'PASS', run_validity: 'VALID' }],
  })

  await expect(page.getByTestId('platform-map')).toHaveCount(0)
  expect(requested).toEqual([armA])
})

for (const theme of ['light', 'dark'] as const) {
  test(`the map has no serious axe violations and its table region takes keyboard focus: ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openMap(page)
    await expect(page.getByTestId('platform-map-table')).toBeVisible()

    const region = page.getByRole('region', { name: LABELS.region })
    await region.focus()
    await expect(region).toBeFocused()
    await page.getByTestId('platform-map-only-problems').focus()
    await page.keyboard.press('Space')
    await expect(page.getByTestId('platform-map-only-problems')).toBeChecked()

    const axe = await new AxeBuilder({ page }).include('#overview-platform-map').analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

test('the map fits a 375px screen without horizontal page scroll', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 })
  await openMap(page)
  await expect(page.getByTestId('platform-map-table')).toBeVisible()

  const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
  expect(width.scroll).toBeLessThanOrEqual(width.client)
})
