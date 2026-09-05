import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const reference = { run_id: 'online-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'online.jtl' }
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [{
    id: 'source-summary', type: 'source_summary', status: 'PARTIAL', profile_id: 'prod-prometheus', source_kind: 'prometheus', transport: 'direct',
    queries: [{ id: 'cpu', status: 'COMPLETE' }, { id: 'memory', status: 'FAILED', reason: 'HTTP_FAILURE' }], request_count: 3, retries: 1, throttle_wait_ms: 250, cap_exceeded: false,
  }, { id: 'resource-binding', type: 'resource_binding' }],
}

async function fixtureApi(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (path === '/api/sources') body = { profiles: [{ id: 'prod-prometheus', source_kind: 'prometheus', transport: 'direct' }] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path.endsWith('/analyses')) body = { analyses: [], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path === '/api/inputs') body = run
    else if (path === '/api/jobs') body = { job_id: 'job-1', state: 'COMPLETE', processed_bytes: 100, total_bytes: 100, ...reference, diagnostic: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

test('submits a validated online source request and renders its saved acquisition status', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'online.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByLabel('Online source profile').selectOption('prod-prometheus')
  await expect(page.getByTestId('resource-snapshot-file')).toBeDisabled()
  await expect(page.getByTestId('correlation-plan-file')).toBeDisabled()
  await page.getByLabel('Source start (UTC epoch ms)').fill('1000')
  await page.getByLabel('Source end (UTC epoch ms)').fill('4000')
  await page.getByLabel('Source step (ms)').fill('999')
  await expect(page.getByTestId('source-request-error')).toContainText('at least 1000')
  await expect(page.getByRole('button', { name: 'Analyze run', exact: true })).toBeDisabled()
  await page.getByLabel('Source step (ms)').fill('1000')
  const request = page.waitForRequest((value) => new URL(value.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  const submitted = (await request).postDataBuffer()!.toString()
  expect(submitted).toContain('name="source_request"; filename="source-request.json"')
  expect(submitted).toContain('{"schema_version":"source-request.v1","profile_id":"prod-prometheus","start_epoch_ms":1000,"end_epoch_ms":4000,"step_ms":1000}')
  expect(submitted).not.toContain('resource_snapshot')
  expect(submitted).not.toContain('correlation_plan')
  await expect(page.getByTestId('source-acquisition')).toContainText('PARTIAL')
  await expect(page.getByTestId('source-acquisition')).toContainText('memory')
  await expect(page.getByRole('link', { name: 'Download resource snapshot' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/resource-snapshot`)
})

test('does not offer a missing snapshot for load-only analysis', async ({ page }) => {
  await fixtureApi(page)
  await page.route('**/result', (route) => route.fulfill({ json: { ...result, evidence: [] } }))
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'online.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  await expect(page.getByRole('link', { name: 'Download resource snapshot' })).toHaveCount(0)
})

test('imports OpenSearch context and shows counts without a resource download', async ({ page }) => {
  await fixtureApi(page)
  const context = JSON.parse(await readFile(new URL('../../docs/contracts/sources/v1/opensearch-errors.example.json', import.meta.url), 'utf8'))
  context.groups[0].samples = [{ timestamp_epoch_ms: 1000, index: 'logs-1', document_id: '1', message: '<script>unsafe()</script>', message_truncated: false, source_url: 'https://logs.example/logs-1/_doc/1' }]
  await page.route('**/result', (route) => route.fulfill({ json: { ...result, evidence: [result.evidence[0], context] } }))
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'errors.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByLabel('OpenSearch context').setInputFiles({ name: 'context.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(context)) })
  const request = page.waitForRequest((value) => new URL(value.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  expect((await request).postDataBuffer()!.toString()).toContain('name="source_context"; filename="context.json"')
  const errors = page.getByTestId('opensearch-context')
  await expect(errors).toContainText('90')
  await expect(errors).toContainText('Timeout')
  await expect(errors).toContainText('COMPLETE')
  await expect(errors).toContainText('<script>unsafe()</script>')
  await expect(errors.locator('script')).toHaveCount(0)
  await expect(errors.getByRole('link', { name: 'Source document' })).toHaveAttribute('rel', 'noopener noreferrer')
  await expect(page.getByRole('link', { name: 'Download resource snapshot' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Download OpenSearch context' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/source-context`)
})

test('renders OpenSearch integer counts without JavaScript rounding', async ({ page }) => {
  await fixtureApi(page)
  const context = JSON.parse(await readFile(new URL('../../docs/contracts/sources/v1/opensearch-errors.example.json', import.meta.url), 'utf8'))
  const body = JSON.stringify({ ...result, evidence: [context] })
    .replace('"total_errors":3', '"total_errors":9007199254740993')
    .replace('"count":3', '"count":9007199254740993')
  await page.route('**/result', (route) => route.fulfill({ contentType: 'application/json', body }))
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'errors.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.getByTestId('opensearch-context')).toContainText('9007199254740993 errors')
  await expect(page.getByTestId('opensearch-context').getByRole('cell', { name: '9007199254740993', exact: true })).toBeVisible()
})
