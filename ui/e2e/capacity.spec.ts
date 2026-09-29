import { expect, test, type Page } from '@playwright/test'

const reference = { run_id: 'capacity-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'capacity.jtl' }
const capacity = {
  schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'BOUNDED', lower_inclusive: 296, upper_exclusive: 344,
  policy_verdict: 'NO_VERDICT', reasons: [], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED',
  stages: [
    { id: 'ramp-300', target: 300, achieved: 296, achieved_statistic: 'p05_10s', observed_min: 296, observed_max: 296, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: 296, verdict: 'PASS', reasons: [], evidence_refs: ['window-policy-summary-1111111111111111111111111111111111111111111111111111111111111111'] },
    { id: 'ramp-350', target: 350, achieved: 344, achieved_statistic: 'p05_10s', observed_min: 344, observed_max: 344, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: 344, verdict: 'FAIL', reasons: [], evidence_refs: ['window-policy-summary-2222222222222222222222222222222222222222222222222222222222222222'] },
  ],
}
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'capacity_step', run_validity: 'VALID', policy_verdict: 'NO_VERDICT',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [], capacity_summary: capacity,
}

async function fixtureApi(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'PASS', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else if (path === '/api/inputs') body = run
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (path === '/api/jobs') body = { job_id: 'job-1', state: 'COMPLETE', processed_bytes: 100, total_bytes: 100, ...reference, diagnostic: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

test.beforeEach(async ({ page }) => { await fixtureApi(page) })

test('submits capacity with its resource snapshot and blocks a plan without one', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'capacity.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByTestId('capacity-plan-file').setInputFiles({ name: 'capacity.json', mimeType: 'application/json', buffer: Buffer.from('{"capacity":"selected"}') })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Capacity plan requires a matching resource snapshot.')

  await page.getByTestId('resource-snapshot-file').setInputFiles({ name: 'resource.json', mimeType: 'application/json', buffer: Buffer.from('{"resource":"selected"}') })
  const request = page.waitForRequest((value) => value.method() === 'POST' && new URL(value.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  const submitted = (await request).postDataBuffer()!.toString()
  expect(submitted).toContain('name="resource_snapshot"; filename="resource.json"')
  expect(submitted).toContain('name="capacity_plan"; filename="capacity.json"')
})

test('renders saved capacity facts unchanged after reload', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'capacity.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  const saved = page.getByTestId('capacity-results')
  await expect(page.locator('#verdict h2')).toHaveText('Вердикт по ёмкости не выдан — границы недостаточно')
  await expect(saved).toContainText('BOUNDED [296, 344)')
  await expect(saved).toContainText('KNEE_DETECTOR_NOT_IMPLEMENTED')
  await expect(saved.getByRole('row', { name: /ramp-300/ })).toContainText('296')
  await expect(saved.getByRole('row', { name: /ramp-350/ })).toContainText('344')
  await expect(page.getByRole('link', { name: 'Download capacity plan' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/capacity-plan`)
  await expect(page.getByRole('link', { name: 'Download capacity result' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/capacity`)

  await page.reload()
  await page.getByRole('button', { name: 'capacity.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.getByTestId('capacity-results')).toContainText('BOUNDED [296, 344)')
})
