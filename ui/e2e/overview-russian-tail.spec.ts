import { expect, test, type Page } from '@playwright/test'
import { JOB_LABELS } from '../src/shell/labels'
import { ANALYTICS_LABELS, DOWNLOAD_LABELS } from '../src/shell/labels.export'

// Хвост «Обзора» новой оболочки: статус задачи, ссылки скачивания и панель сохранённой аналитики по-русски.
// API целиком подменяется; прежний интерфейс остаётся английским (его проверяют header-export и local-flow).
const reference = { run_id: 'tail-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'tail.jtl' }
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [overall],
}
const job = (state: string) => ({
  job_id: 'job-1', state, processed_bytes: 1234, total_bytes: 1234, run_id: reference.run_id,
  analysis_id: state === 'COMPLETE' ? reference.analysis_id : null, diagnostic: null,
})

async function fixtureApi(page: Page, state: string) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [job(state)] }
    else if (method === 'GET' && path === '/api/jobs/job-1') body = job(state)
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (method === 'GET' && path === '/api/releases') body = { releases: [], next_after: null, series_summary: [], corrupt_count: 0, corrupt_names: [] }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
}

test('a finished analysis shows no English status, download links or saved-analytics panel on the overview', async ({ page }) => {
  await fixtureApi(page, 'COMPLETE')
  await page.goto('/?shell=new')
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(page.getByTestId('overview-panel')).toBeVisible()

  const status = page.locator('#job-status')
  await expect(status).toBeVisible()
  await expect(status).not.toContainText(/COMPLETE|bytes/)
  await expect(status).toContainText(JOB_LABELS.states.COMPLETE)
  await expect(status).toContainText(JOB_LABELS.bytes('1,234', '1,234'))
  await expect(page.getByRole('link', { name: /^Download / })).toHaveCount(0)
  await expect(page.locator(`[aria-label="${DOWNLOAD_LABELS.group}"]`)).toHaveAttribute('lang', 'ru')
  await expect(page.getByRole('link', { name: DOWNLOAD_LABELS.format('json'), exact: true })).toHaveAttribute('href', /format=json$/)
  await expect(page.getByRole('heading', { name: 'Saved-run analytics' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Refresh analytics' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: ANALYTICS_LABELS.title })).toBeVisible()
  await expect(page.getByRole('button', { name: ANALYTICS_LABELS.refresh, exact: true })).toBeVisible()
})

test('the old interface keeps the English download links and saved-analytics panel', async ({ page }) => {
  await fixtureApi(page, 'COMPLETE')
  await page.goto('/?shell=old')
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()

  await expect(page.getByRole('link', { name: 'Download JSON', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Saved-run analytics' })).toBeVisible()
})
