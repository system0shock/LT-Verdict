import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const reference = { run_id: 'verdict-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'verdict.jtl' }
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const checkout = {
  id: 'm-checkout', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' },
  sample_count: 900, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 900 }, throughput_rps: { numerator: 900000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const failing = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'FAIL',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [
    overall, checkout,
    { id: 'c1', type: 'policy_check', rule_id: 'checkout-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000, status: 'FAIL', metric_evidence_id: 'm-checkout', observed: 2340 },
    { id: 'c2', type: 'policy_check', rule_id: 'global-errors', metric: 'error_rate_ratio', operator: 'lte', threshold: 0.05, status: 'PASS', metric_evidence_id: 'm-overall', observed: { numerator: 88, denominator: 3650 } },
  ],
}
const missing = {
  ...failing, policy_verdict: 'NO_VERDICT', analysis_coverage: { status: 'INCOMPLETE', reasons: ['TRANSACTION_NOT_FOUND'] },
  evidence: [overall, { id: 'c1', type: 'policy_check', rule_id: 'missing-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 100, status: 'NO_VERDICT', reason_code: 'TRANSACTION_NOT_FOUND' }],
}

async function fixtureApi(page: Page, result: unknown) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

async function openSaved(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'verdict.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
}

test('shows no verdict block and no header chip before a result exists', async ({ page }) => {
  await fixtureApi(page, failing)
  await page.goto('/')

  await expect(page.locator('#verdict')).toHaveCount(0)
  await expect(page.getByTestId('verdict-chip')).toHaveCount(0)
})

test('puts the verdict above the run form and explains FAIL in words', async ({ page }) => {
  await fixtureApi(page, failing)
  await openSaved(page)

  const verdict = await page.locator('#verdict').boundingBox()
  const form = await page.locator('#run-setup').boundingBox()
  expect(verdict!.y).toBeLessThan(form!.y)
  await expect(page.getByTestId('verdict-badge')).toHaveText('FAIL')
  await expect(page.locator('#verdict h2')).toHaveText('Прогон не проходит — нарушено проверок: 1 из 2')
  const lines = page.getByTestId('verdict-lines')
  await expect(lines).toContainText('Правило checkout-p95 · p95 отклика · POST /checkout')
  await expect(lines).toContainText(/2\s340 мс при пороге ≤ 2\s000 мс/)
  await expect(page.locator('.verdict-facts div').filter({ hasText: 'Политика (хэш)' }).locator('dd')).toHaveText('cccccccccccc')
})

test('keeps a verdict chip in the header while scrolling and jumps back to the card', async ({ page }) => {
  await fixtureApi(page, failing)
  await openSaved(page)
  const chip = page.getByTestId('verdict-chip')

  await expect(chip).toContainText('FAIL')
  await expect(chip).toContainText('нарушено 1 из 2')
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  await expect(chip).toBeInViewport()
  await chip.click()
  await expect(page).toHaveURL(/#verdict$/)
  await expect(page.locator('#verdict')).toBeInViewport()
})

test('explains NO_VERDICT with words and keeps the raw code for support', async ({ page }) => {
  await fixtureApi(page, missing)
  await openSaved(page)

  await expect(page.getByTestId('verdict-badge')).toHaveText('NO_VERDICT')
  await expect(page.locator('#verdict h2')).toHaveText('Вердикт не выдан — не удалось проверить: 1 из 1')
  const causes = page.getByTestId('verdict-causes')
  await expect(causes).toContainText('Транзакция из области правила не найдена в результатах нагрузки.')
  await expect(causes).toContainText('Правила: missing-p95')
  await expect(causes).toContainText('TRANSACTION_NOT_FOUND')
  await expect(page.getByTestId('verdict-chip')).toContainText('не проверено: 1')
})

for (const [name, result] of [['FAIL', failing], ['NO_VERDICT', missing]] as const) {
  for (const theme of ['light', 'dark'] as const) {
    test(`verdict card and chip have no serious axe violations: ${name}, ${theme}`, async ({ page }) => {
      await fixtureApi(page, result)
      await page.emulateMedia({ colorScheme: theme })
      await openSaved(page)

      const chip = await page.getByTestId('verdict-chip').boundingBox()
      expect(chip!.width).toBeGreaterThanOrEqual(44)
      expect(chip!.height).toBeGreaterThanOrEqual(44)
      const axe = await new AxeBuilder({ page }).include('#verdict').include('header').analyze()
      expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
    })
  }
}
