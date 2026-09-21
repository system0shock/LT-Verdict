import { expect, test, type Download, type Page } from '@playwright/test'
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
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
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

test('submits multiple online profiles on one shared time grid', async ({ page }) => {
  await fixtureApi(page)
  const context = JSON.parse(await readFile(new URL('../../docs/contracts/sources/v1/opensearch-errors.example.json', import.meta.url), 'utf8'))
  const sourceProfiles = [
    { ...result.evidence[0], profile_id: 'errors', source_kind: 'opensearch', queries: [{ id: 'errors', status: 'COMPLETE' }] },
    { ...result.evidence[0], profile_id: 'prom', queries: [{ id: 'cpu', status: 'COMPLETE' }] },
  ]
  await page.route('**/result', (route) => route.fulfill({ json: { ...result, evidence: [{
    ...result.evidence[0], profile_id: 'multiple', source_kind: 'multiple', transport: 'multiple', profiles: sourceProfiles,
  }, context] } }))
  await page.route('**/api/sources', (route) => route.fulfill({ json: { profiles: [
    { id: 'prom', source_kind: 'prometheus', transport: 'direct' },
    { id: 'errors', source_kind: 'opensearch', transport: 'direct' },
  ] } }))
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'multi.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByLabel('Online source profile').selectOption(['prom', 'errors'])
  await page.getByLabel('Source start (UTC epoch ms)').fill('1000')
  await page.getByLabel('Source end (UTC epoch ms)').fill('3000')
  await page.getByLabel('Source step (ms)').fill('1000')
  const request = page.waitForRequest((value) => new URL(value.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  expect((await request).postDataBuffer()!.toString()).toContain('"schema_version":"source-request.v2","profile_ids":["errors","prom"]')
  await expect(page.getByTestId('source-acquisition')).toContainText('errors')
  await expect(page.getByTestId('source-acquisition')).toContainText('prom')
  await expect(page.getByRole('link', { name: 'Download OpenSearch context' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/source-context`)
})

test('submits up to sixteen manual contexts and uses sorted indexed downloads', async ({ page }) => {
  await fixtureApi(page)
  const example = JSON.parse(await readFile(new URL('../../docs/contracts/sources/v1/opensearch-errors.example.json', import.meta.url), 'utf8'))
  const alpha = { ...example, id: 'opensearch-errors:alpha', profile_id: 'alpha' }
  const zeta = { ...example, id: 'opensearch-errors:zeta', profile_id: 'zeta' }
  await page.route('**/result', (route) => route.fulfill({ json: { ...result, evidence: [zeta, alpha] } }))
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'multi-context.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByLabel('OpenSearch context').setInputFiles(Array.from({ length: 17 }, (_, index) => ({
    name: `context-${index + 1}.json`, mimeType: 'application/json', buffer: Buffer.from('{}'),
  })))
  await expect(page.getByTestId('source-request-error')).toContainText('at most 16')
  await expect(page.getByRole('button', { name: 'Analyze run', exact: true })).toBeDisabled()
  await page.getByLabel('OpenSearch context').setInputFiles([
    { name: 'zeta.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(zeta)) },
    { name: 'alpha.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(alpha)) },
  ])
  const request = page.waitForRequest((value) => new URL(value.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  const submitted = (await request).postDataBuffer()!.toString()
  expect(submitted.match(/name="source_context"/g)).toHaveLength(2)
  expect(submitted).toContain('filename="zeta.json"')
  expect(submitted).toContain('filename="alpha.json"')
  const contexts = page.getByTestId('opensearch-context')
  await expect(contexts).toHaveCount(2)
  await expect(contexts.nth(0)).toHaveAttribute('aria-label', 'OpenSearch errors: alpha')
  await expect(contexts.nth(1)).toHaveAttribute('aria-label', 'OpenSearch errors: zeta')
  await expect(page.getByRole('link', { name: 'Download OpenSearch context — alpha' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/source-context/1`)
  await expect(page.getByRole('link', { name: 'Download OpenSearch context — zeta' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/source-context/2`)
})

test('captures exact PostgreSQL phases and attaches them without rendering the report HTML', async ({ page }) => {
  const prePhase = (await readFile(new URL('../../docs/contracts/sources/v1/postgres-phase.example.json', import.meta.url), 'utf8'))
    .trim()
    .replace('"calls": 1', '"calls": 9007199254740993')
  const postPhase = prePhase
    .replace('"phase": "pre"', '"phase": "post"')
    .replace('"pre_sha256": null', `"pre_sha256": "${'c'.repeat(64)}"`)
  const reportHtml = '<script>window.__pgProfileRan = true</script><h1>Unsafe report</h1>'
  const postgresContext = {
    schema_version: 'postgres-context.v1', type: 'postgres_context', profile_id: 'pg-main',
    load_input_sha256: 'd'.repeat(64), start_epoch_ms: 1000, end_epoch_ms: 3000,
    pre_sha256: 'a'.repeat(64), post_sha256: 'b'.repeat(64), pg_profile_html_sha256: 'e'.repeat(64),
    status: 'DEGRADED', reasons: ['PG_STATEMENTS_EVICTED'],
    configuration_changes: [{ name: 'work_mem', pre: '4096', post: '8192' }],
    tables: [{
      schema: 'public', table: 'orders', stable_key: ['id'], status: 'COMPLETE', reasons: [],
      row_count_delta: 2, inserted: 1, deleted: 0, updated: 1,
      changed_keys: [{ change: 'updated', key: ['42'] }], keys_truncated: false,
    }],
    statements: {
      status: 'DEGRADED', reasons: ['PG_STATEMENTS_EVICTED'],
      rows: [{
        dbid: '1', userid: '10', queryid: '42', toplevel: true, calls: 1, total_exec_time: 8.5,
        rows: 4, shared_blks_hit: 3, shared_blks_read: 2, temp_blks_written: 1,
      }],
      unmatched_pre: [], unmatched_post: [],
    },
    pg_profile: {
      status: 'COMPLETE', reasons: [], pre_report_sha256: null, post_report_sha256: null,
    },
  }
  const exactResult = JSON.stringify({ ...result, evidence: [...result.evidence, postgresContext] })
    .replace('"calls":1', '"calls":9007199254740993')

  await page.addInitScript(() => {
    const capturedTypes: string[] = []
    const original = URL.createObjectURL.bind(URL)
    ;(window as Window & { __downloadTypes: string[] }).__downloadTypes = capturedTypes
    URL.createObjectURL = (object) => {
      capturedTypes.push(object.type)
      return original(object)
    }
  })
  await fixtureApi(page)
  await page.route('**/api/sources', (route) => route.fulfill({ json: { profiles: [
    { id: 'pg-main', source_kind: 'postgresql', transport: 'jdbc' },
    { id: 'prom', source_kind: 'prometheus', transport: 'direct' },
  ] } }))
  const captureRequests: string[] = []
  await page.route('**/api/sources/postgresql/**', async (route) => {
    const phase = new URL(route.request().url()).pathname.endsWith('/pre') ? 'pre' : 'post'
    captureRequests.push(route.request().postDataBuffer()!.toString())
    await route.fulfill({ json: {
      schema_version: 'postgres-capture.v1',
      phase_json: phase === 'pre' ? prePhase : postPhase,
      pg_profile_html_base64: phase === 'post' ? Buffer.from(reportHtml).toString('base64') : null,
    } })
  })
  await page.route('**/result', (route) => route.fulfill({ contentType: 'application/json', body: exactResult }))

  const downloads: Download[] = []
  page.on('download', (download) => downloads.push(download))
  await page.goto('/')
  await expect(page.getByLabel('Online source profile').locator('option', { hasText: 'pg-main' })).toHaveCount(0)
  await page.getByLabel('PostgreSQL profile').selectOption('pg-main')
  await expect(page.getByLabel('PostgreSQL profile').locator('option', { hasText: 'pg-main' })).toHaveCount(1)

  await page.getByRole('button', { name: 'Capture pre' }).click()
  await expect.poll(() => downloads.length).toBe(1)
  expect(downloads[0].suggestedFilename()).toBe('postgres-pre.json')
  const prePath = await downloads[0].path()
  if (!prePath) throw new Error('PostgreSQL pre download path is unavailable')
  expect(await readFile(prePath, 'utf8')).toBe(prePhase)
  await page.getByLabel('PostgreSQL pre capture').setInputFiles({
    name: 'postgres-pre.json', mimeType: 'application/json', buffer: await readFile(prePath),
  })

  await page.getByRole('button', { name: 'Capture post' }).click()
  await expect.poll(() => downloads.length).toBe(3)
  expect(captureRequests[0]).toContain('name="profile_id"')
  expect(captureRequests[0]).toContain('pg-main')
  expect(captureRequests[0]).not.toContain('name="pre"')
  expect(captureRequests[1]).toContain('pg-main')
  expect(captureRequests[1]).toContain('name="pre"; filename="postgres-pre.json"')
  expect(downloads[1].suggestedFilename()).toBe('postgres-post.json')
  expect(downloads[2].suggestedFilename()).toBe('pg-profile.html')
  const postPath = await downloads[1].path()
  const htmlPath = await downloads[2].path()
  if (!postPath || !htmlPath) throw new Error('PostgreSQL post download path is unavailable')
  expect(await readFile(postPath, 'utf8')).toBe(postPhase)
  expect(await readFile(htmlPath, 'utf8')).toBe(reportHtml)
  expect(await page.evaluate(() => (window as Window & { __downloadTypes: string[] }).__downloadTypes)).toEqual([
    'application/json', 'application/json', 'application/octet-stream',
  ])
  expect(await page.evaluate(() => (window as Window & { __pgProfileRan?: boolean }).__pgProfileRan)).toBeUndefined()

  await page.getByLabel('PostgreSQL post capture').setInputFiles({
    name: 'postgres-post.json', mimeType: 'application/json', buffer: await readFile(postPath),
  })
  await page.getByLabel('pg_profile HTML').setInputFiles({
    name: 'pg-profile.html', mimeType: 'application/octet-stream', buffer: await readFile(htmlPath),
  })
  await page.getByTestId('input-file').setInputFiles({ name: 'postgres.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByLabel('Online source profile').selectOption('prom')
  await page.getByLabel('Source start (UTC epoch ms)').fill('1000')
  await page.getByLabel('Source end (UTC epoch ms)').fill('3000')
  await page.getByLabel('Source step (ms)').fill('1000')
  const request = page.waitForRequest((value) => new URL(value.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  const submitted = (await request).postDataBuffer()!.toString()
  expect(submitted).toContain('name="source_request"; filename="source-request.json"')
  expect(submitted).toContain('name="postgres_pre"; filename="postgres-pre.json"')
  expect(submitted).toContain('name="postgres_post"; filename="postgres-post.json"')
  expect(submitted).toContain('name="pg_profile_html"; filename="pg-profile.html"')
  expect(captureRequests).toHaveLength(2)

  const context = page.getByTestId('postgres-context')
  await expect(context).toContainText('DEGRADED')
  await expect(context).toContainText('PG_STATEMENTS_EVICTED')
  await expect(context.getByRole('region', { name: 'PostgreSQL configuration changes' })
    .getByRole('row', { name: 'work_mem 4096 8192' })).toBeVisible()
  await expect(context.getByRole('region', { name: 'PostgreSQL table changes' })).toBeVisible()
  await expect(context.getByRole('region', { name: 'PostgreSQL statement deltas' })).toBeVisible()
  await expect(context).toContainText('public.orders')
  await expect(context.getByRole('cell', { name: '9007199254740993', exact: true })).toBeVisible()
  const base = `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}`
  await expect(page.getByRole('link', { name: 'Download PostgreSQL pre capture' })).toHaveAttribute('href', `${base}/postgres-pre`)
  await expect(page.getByRole('link', { name: 'Download PostgreSQL post capture' })).toHaveAttribute('href', `${base}/postgres-post`)
  await expect(page.getByRole('link', { name: 'Download PostgreSQL context' })).toHaveAttribute('href', `${base}/postgres-context`)
  await expect(page.getByRole('link', { name: 'Download pg_profile report' })).toHaveAttribute('href', `${base}/pg-profile`)
  await expect(page.getByText('Unsafe report')).toHaveCount(0)
})
