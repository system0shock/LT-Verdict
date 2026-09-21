import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'

const input = Buffer.from('timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,bytes,sentBytes,grpThreads,allThreads,Latency,IdleTime,Connect\n1767225600000,1000,request,200,OK,thread,true,1,1,1,1,1,0,0\n')

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value !== null && typeof value === 'object') return `{${Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(',')}}`
  return JSON.stringify(value)
}

test('acquires a configured online source, downloads its snapshot, and reloads the saved status', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'online-source.jtl', mimeType: 'text/csv', buffer: input })
  await page.getByLabel('Online source profile').selectOption('local')
  await page.getByLabel('Source start (UTC epoch ms)').fill('1767225600000')
  await page.getByLabel('Source end (UTC epoch ms)').fill('1767225601000')
  await page.getByLabel('Source step (ms)').fill('1000')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()

  const acquisition = page.getByTestId('source-acquisition')
  await expect(acquisition).toContainText('COMPLETE')
  const snapshot = page.getByRole('link', { name: 'Download resource snapshot' })
  const response = await page.request.get(await snapshot.getAttribute('href') ?? '')
  expect(response.ok()).toBe(true)
  expect(await response.json()).toMatchObject({ schema_version: 'resource-snapshot.v1' })

  await page.reload()
  await page.getByRole('button', { name: 'online-source.jtl' }).click()
  await page.getByRole('button', { name: /^Analysis / }).click()
  await expect(page.getByTestId('source-acquisition')).toContainText('COMPLETE')
})

test('imports error context with resource metrics and reloads both saved artifacts', async ({ page }) => {
  const load = Buffer.from(input.toString().replace('1767225600000,1000', '1000,2000'))
  const hash = createHash('sha256').update(load).digest('hex')
  const context = (await readFile(new URL('../../docs/contracts/sources/v1/opensearch-errors.example.json', import.meta.url), 'utf8'))
    .replace('a'.repeat(64), hash)
  const resources = {
    schema_version: 'resource-snapshot.v1', load_input_sha256: hash, start_epoch_ms: 1000, step_ms: 1000, point_count: 2,
    series: [{ id: 'cpu', metric: 'cpu_used', unit: 'ratio', entity: 'vm', role: 'system', aggregation: 'interval_mean', values: [0.1, 0.9] }],
    windows: [{ id: 'full', from_epoch_ms: 1000, to_epoch_ms: 3000 }],
  }
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'errors-resources.jtl', mimeType: 'text/csv', buffer: load })
  await page.getByTestId('resource-snapshot-file').setInputFiles({ name: 'resources.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(resources)) })
  await page.getByLabel('OpenSearch context').setInputFiles({ name: 'context.json', mimeType: 'application/json', buffer: Buffer.from(context) })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.getByTestId('opensearch-context')).toContainText('3 errors · 90 errors/min · COMPLETE')
  const contextLink = page.getByRole('link', { name: 'Download OpenSearch context' })
  const response = await page.request.get(await contextLink.getAttribute('href') ?? '')
  expect(response.ok()).toBe(true)
  expect(await response.json()).toMatchObject({ schema_version: 'opensearch-errors.v1', total_errors: 3 })
  await expect(page.getByRole('link', { name: 'Download resource snapshot' })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: 'errors-resources.jtl' }).click()
  await page.getByRole('button', { name: /^Analysis / }).click()
  await expect(page.getByTestId('opensearch-context')).toContainText('Timeout')
  await page.getByTestId('opensearch-context').screenshot({ path: test.info().outputPath('opensearch-context.png') })
})

test('imports PostgreSQL phases and inert HTML through the real backend and reloads fixed downloads', async ({ page }, testInfo) => {
  const postgresLoad = Buffer.from(input.toString().replace(',request,', ',postgres-request,'))
  const pre = JSON.parse(await readFile(new URL('../../docs/contracts/sources/v1/postgres-phase.example.json', import.meta.url), 'utf8'))
  pre.capture_started_epoch_ms = 1767225599000
  pre.capture_ended_epoch_ms = 1767225599999
  pre.tables[0].rows = [['1', 'new']]
  pre.tables[0].row_count = 1
  pre.configuration.work_mem = '4096'
  const reportHtml = Buffer.from('<!doctype html><script>window.__pgProfileRan=true</script><h1>Unsafe pg_profile report</h1>')
  const post = JSON.parse(JSON.stringify(pre))
  post.phase = 'post'
  post.pre_sha256 = createHash('sha256').update(canonical(pre)).digest('hex')
  post.capture_started_epoch_ms = 1767225601000
  post.capture_ended_epoch_ms = 1767225601500
  post.configuration.work_mem = '8192'
  post.tables[0].rows = [['1', 'done'], ['2', 'new']]
  post.tables[0].row_count = 2
  post.statements.rows[0] = {
    ...post.statements.rows[0], calls: 4, total_exec_time: 10.5, rows: 5,
    shared_blks_hit: 7, shared_blks_read: 2, temp_blks_written: 1,
  }
  post.pg_profile.report_sha256 = createHash('sha256').update(reportHtml).digest('hex')

  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'postgres-offline.jtl', mimeType: 'text/csv', buffer: postgresLoad })
  await page.getByLabel('PostgreSQL pre capture').setInputFiles({
    name: 'postgres-pre.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(pre)),
  })
  await page.getByLabel('PostgreSQL post capture').setInputFiles({
    name: 'postgres-post.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(post)),
  })
  await page.getByLabel('pg_profile HTML').setInputFiles({ name: 'pg-profile.html', mimeType: 'text/html', buffer: reportHtml })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()

  const postgres = page.getByTestId('postgres-context')
  await expect(postgres).toContainText('DEGRADED · PG_PROFILE_RESET_UNKNOWN, PG_PROFILE_UNAVAILABLE')
  await expect(postgres.getByRole('region', { name: 'PostgreSQL configuration changes' })
    .getByRole('row', { name: 'work_mem 4096 8192' })).toBeVisible()
  const table = postgres.getByRole('region', { name: 'PostgreSQL table changes' }).getByRole('row', { name: /public\.orders/ })
  await expect(table.getByRole('cell').nth(2)).toHaveText('1')
  await expect(table.getByRole('cell').nth(3)).toHaveText('1 / 0 / 1')
  await expect(table.getByRole('cell').nth(4)).toHaveText('updated: 1, inserted: 2')
  const statement = postgres.getByRole('region', { name: 'PostgreSQL statement deltas' }).getByRole('row', { name: /1 \/ 10 \/ 42/ })
  await expect(statement.getByRole('cell').nth(1)).toHaveText('3')
  await expect(statement.getByRole('cell').nth(2)).toHaveText('8')
  await expect(statement.getByRole('cell').nth(3)).toHaveText('4')
  await expect(statement.getByRole('cell').nth(4)).toHaveText('5 / 2')
  await expect(statement.getByRole('cell').nth(5)).toHaveText('1')
  await expect(postgres).toContainText('Attached HTML is download-only and is never rendered here.')
  await expect(page.getByText('Unsafe pg_profile report')).toHaveCount(0)
  expect(await page.evaluate(() => (window as Window & { __pgProfileRan?: boolean }).__pgProfileRan)).toBeUndefined()

  const preLink = page.getByRole('link', { name: 'Download PostgreSQL pre capture' })
  const postLink = page.getByRole('link', { name: 'Download PostgreSQL post capture' })
  const contextLink = page.getByRole('link', { name: 'Download PostgreSQL context' })
  const profileLink = page.getByRole('link', { name: 'Download pg_profile report' })
  const preHref = await preLink.getAttribute('href')
  expect(preHref).toMatch(/^\/api\/runs\/jmeter_jtl_csv-[a-f0-9]{64}\/analyses\/[a-f0-9]{64}\/postgres-pre$/)
  const base = preHref!.slice(0, -'/postgres-pre'.length)
  await expect(postLink).toHaveAttribute('href', `${base}/postgres-post`)
  await expect(contextLink).toHaveAttribute('href', `${base}/postgres-context`)
  await expect(profileLink).toHaveAttribute('href', `${base}/pg-profile`)
  await expect(profileLink).toHaveAttribute('download', '')

  const preResponse = await page.request.get(preHref!)
  const postResponse = await page.request.get(`${base}/postgres-post`)
  const contextResponse = await page.request.get(`${base}/postgres-context`)
  const profileResponse = await page.request.get(`${base}/pg-profile`)
  expect(preResponse.ok()).toBe(true)
  expect(preResponse.headers()['content-type']).toContain('application/json')
  expect(preResponse.headers()['content-disposition']).toContain('filename="postgres-pre.json"')
  expect(await preResponse.text()).toBe(canonical(pre))
  expect(postResponse.ok()).toBe(true)
  expect(postResponse.headers()['content-type']).toContain('application/json')
  expect(await postResponse.text()).toBe(canonical(post))
  expect(contextResponse.ok()).toBe(true)
  expect(contextResponse.headers()['content-type']).toContain('application/json')
  expect(await contextResponse.json()).toMatchObject({
    schema_version: 'postgres-context.v1', profile_id: 'pg', load_input_sha256: createHash('sha256').update(postgresLoad).digest('hex'),
    start_epoch_ms: 1767225600000, end_epoch_ms: 1767225601000,
    pg_profile_html_sha256: createHash('sha256').update(reportHtml).digest('hex'),
    configuration_changes: [{ name: 'work_mem', pre: '4096', post: '8192' }],
    tables: [{ schema: 'public', table: 'orders', row_count_delta: 1, inserted: 1, deleted: 0, updated: 1 }],
    statements: { rows: [{ dbid: '1', userid: '10', queryid: '42', calls: 3, total_exec_time: 8, rows: 4 }] },
  })
  expect(profileResponse.ok()).toBe(true)
  expect(profileResponse.headers()['content-type']).toContain('application/octet-stream')
  expect(profileResponse.headers()['content-disposition']).toContain('attachment; filename="pg-profile.html"')
  expect(await profileResponse.body()).toEqual(reportHtml)

  await page.reload()
  await page.getByRole('button', { name: 'postgres-offline.jtl' }).click()
  await page.getByRole('button', { name: /^Analysis / }).click()
  await expect(page.getByTestId('postgres-context')).toContainText('updated: 1, inserted: 2')
  await expect(page.getByRole('link', { name: 'Download pg_profile report' })).toHaveAttribute('href', `${base}/pg-profile`)
  await expect(page.getByText('Unsafe pg_profile report')).toHaveCount(0)
  expect(await page.evaluate(() => (window as Window & { __pgProfileRan?: boolean }).__pgProfileRan)).toBeUndefined()
  await page.getByTestId('postgres-context').screenshot({ path: testInfo.outputPath('postgres-context.png') })
})
