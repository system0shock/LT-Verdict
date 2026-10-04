import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { RULES_LABELS } from '../src/shell/labels.rules'
import { SHELL_LABELS } from '../src/shell/labels'

const run = { run_id: 'rules-run', source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'rules.jtl' }
const nextRun = { ...run, run_id: 'rules-run-next', original_filename: 'rules-next.jtl' }
const analysisId = 'a'.repeat(64)
const metric = (label: string, count: number, groupPath: string[] = []) => ({
  id: `metric-${label}-${groupPath.join('-')}`, type: 'metric_summary',
  scope: { kind: 'transaction', group_path: groupPath, label, sample_kind: 'JMETER_SAMPLER' },
  sample_count: count, error_count: 0, error_rate_ratio: { numerator: 0, denominator: count },
  throughput_rps: { numerator: count, denominator: 1 }, latency_ms: { p50: 10, p95: 20, p99: 30, max: 40 },
})
const result = (many = false) => ({
  schema_version: 'analysis-result.v1', run_id: run.run_id, analysis_mode: 'standard', run_validity: 'VALID',
  policy_verdict: 'NO_POLICY', analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: many ? Array.from({ length: 128 }, (_, i) => metric(`tx-${String(i).padStart(3, '0')}`, 30)) : [
    metric('GET /a', 30), metric('POST /b', 25), metric('GET /rare', 5),
    metric('GET /duplicate', 30, ['one']), metric('GET /duplicate', 30, ['two']),
  ],
})
const basicPolicy = { schema_version: 'policy.v1', policy_id: 'uploaded', rules: [
  { id: 'overall-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: '1000', scope: { kind: 'overall' } },
  { id: 'overall-errors', metric: 'error_rate_ratio', operator: 'lte', threshold: '0.05', scope: { kind: 'overall' } },
] }

async function fixtureApi(page: Page, validated: Array<Record<string, unknown>> = [], options: { error?: boolean; many?: boolean; twoRuns?: boolean } = {}) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1000000 } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [] } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) return route.fulfill({ json: { profiles: [] } })
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: options.twoRuns ? [run, nextRun] : [run], next_after: null } })
    if (path === '/api/policies/validate') {
      const draft = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      validated.push(draft)
      if (options.error) return route.fulfill({ status: 422, json: { valid: false, errors: [{ code: 'UNKNOWN_FIELD', json_pointer: '/rules/0/foo', message: 'Unknown field foo' }] } })
      return route.fulfill({ json: { valid: true, policy: draft, sha256: 'c'.repeat(64) } })
    }
    if (path.endsWith('/analyses')) return route.fulfill({ json: { analyses: [{ analysis_id: analysisId, policy_sha256: null, policy_verdict: 'NO_POLICY', run_validity: 'VALID' }], next_after: null } })
    if (path.endsWith('/result')) return route.fulfill({ json: options.twoRuns && path.includes(nextRun.run_id)
      ? { ...result(), run_id: nextRun.run_id, evidence: [metric('POST /next', 30)] }
      : result(options.many) })
    if (path.endsWith('/buckets')) return route.fulfill({ json: { buckets: [], next_from_ms: null } })
    if (path.endsWith('/advice')) return route.fulfill({ json: { advice: null, job: null } })
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })
}

async function openWithResult(page: Page, many = false, validated: Array<Record<string, unknown>> = []) {
  await fixtureApi(page, validated, { many })
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-rules').click()
}

test('template validation, editable thresholds, hints, summary link, and one editor', async ({ page }) => {
  const validated: Array<Record<string, unknown>> = []
  await fixtureApi(page, validated)
  await page.goto('/?shell=new')
  await expect(page.locator('#shell-tab-rules')).not.toContainText(SHELL_LABELS.pendingBadge)
  await page.locator('#shell-tab-rules').click()
  await expect(page.getByRole('button', { name: RULES_LABELS.perTxButton })).toBeDisabled()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxNoPolicy)
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxNoResult)
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('template-api-basic')
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.preview('5 %'))
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.hintMs)
  const idsBeforeResult = await page.locator('[id]').evaluateAll((nodes) => nodes.map((node) => node.id))
  expect(new Set(idsBeforeResult).size).toBe(idsBeforeResult.length)
  await page.getByLabel(RULES_LABELS.threshold).first().fill('800')
  await expect.poll(() => validated.length).toBeGreaterThan(1)
  expect(JSON.stringify(validated.at(-1))).toContain('800')
  expect(await page.locator('#policy-id').count()).toBe(1)
  await page.locator('#shell-tab-setup').click()
  await expect(page.locator('#run-setup')).toContainText(RULES_LABELS.summary('template-api-basic', 2, 0))
  await expect(page.locator('#run-setup #policy-id')).toHaveCount(0)
  await page.getByRole('button', { name: RULES_LABELS.openRules }).click()
  await expect(page.locator('#shell-tab-rules')).toHaveAttribute('aria-selected', 'true')
})

test('unknown draft fields survive an editor change', async ({ page }) => {
  const validated: Array<Record<string, unknown>> = []
  await fixtureApi(page, validated)
  await page.goto('/?shell=new')
  await page.locator('#shell-tab-rules').click()
  await page.getByTestId('rules-policy-file').setInputFiles({ name: 'policy.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ ...basicPolicy, defaults: { min_samples: 50 }, platform_extra: true })) })
  await page.getByLabel(RULES_LABELS.threshold).first().fill('900')
  await expect.poll(() => validated.length).toBeGreaterThan(1)
  expect(validated.at(-1)).toMatchObject({ defaults: { min_samples: 50 }, platform_extra: true })
})

test('server error pointer is marked English inside the rules panel', async ({ page }) => {
  await fixtureApi(page, [], { error: true })
  await page.goto('/?shell=new')
  await page.locator('#shell-tab-rules').click()
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  const error = page.locator('#rules-panel .field__errors li').filter({ hasText: '/rules/0/foo: Unknown field foo' })
  await expect(error).toBeVisible()
  await expect(error).toHaveAttribute('lang', 'en')
})

test('the old interface keeps the English editor', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=old')
  await page.getByTestId('policy-file').setInputFiles({ name: 'policy.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(basicPolicy)) })
  await expect(page.getByText('Policy draft')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add rule' })).toBeVisible()
  await expect(page.locator('[id^="rule-threshold-hint-"]')).toHaveCount(0)
})

test('expansion lists skipped labels, posts only new rules, and preserves unique ids', async ({ page }) => {
  const validated: Array<Record<string, unknown>> = []
  await openWithResult(page, false, validated)
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxDone(4, run.original_filename))
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxSkippedSmall(20, RULES_LABELS.perTxList([{ label: 'GET /rare', sampleCount: 5 }], true)))
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxSkippedAmbiguous(RULES_LABELS.perTxList([{ label: 'GET /duplicate', sampleCount: 60 }], false)))
  await expect.poll(() => validated.length).toBeGreaterThan(1)
  const expanded = validated.at(-1) as typeof basicPolicy
  expect(expanded.rules.filter((rule) => rule.scope.kind === 'transaction').map((rule) => rule.scope.name).sort()).toEqual(['GET /a', 'GET /a', 'POST /b', 'POST /b'])
  const ids = await page.locator('[id]').evaluateAll((nodes) => nodes.map((node) => node.id))
  expect(new Set(ids).size).toBe(ids.length)
  const before = validated.length
  await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxNothingNew)
  expect(validated.length).toBe(before)
})

test('expansion refuses a result that would exceed the rule limit', async ({ page }) => {
  const validated: Array<Record<string, unknown>> = []
  await openWithResult(page, true, validated)
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('template-api-basic')
  const before = validated.length
  await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxRefused.TOO_MANY_RULES)
  expect(validated.length).toBe(before)
  await expect(page.locator('#rules-panel .policy-rule')).toHaveCount(2)
})

test('expanding for a different run replaces the earlier generated rules', async ({ page }) => {
  const validated: Array<Record<string, unknown>> = []
  await fixtureApi(page, validated, { twoRuns: true })
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-rules').click()
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxDone(4, run.original_filename))

  await page.getByRole('button', { name: nextRun.original_filename }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-rules').click()
  await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxDone(2, nextRun.original_filename, 4))
  await expect.poll(() => validated.length).toBeGreaterThan(2)
  const latest = validated.at(-1) as typeof basicPolicy
  expect(latest.rules.filter((rule) => rule.scope.kind === 'transaction').map((rule) => rule.scope.name).sort()).toEqual(['POST /next', 'POST /next'])
  expect(latest.rules.filter((rule) => rule.scope.kind === 'overall').map((rule) => rule.id)).toEqual(['overall-p95', 'overall-errors'])
})

for (const theme of ['light', 'dark'] as const) {
  test(`rules tab has no serious axe violations in ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await fixtureApi(page)
    await page.goto('/?shell=new')
    await page.locator('#shell-tab-rules').click()
    await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
    const axe = await new AxeBuilder({ page }).analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const width of [320, 375, 1280]) {
  test(`expanded rules have no horizontal page scroll at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    await openWithResult(page)
    await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
    await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
    await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxDone(4, run.original_filename))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  })
}
