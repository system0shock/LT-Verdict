import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'

const input = Buffer.from('timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,bytes,sentBytes,grpThreads,allThreads,Latency,IdleTime,Connect\n1767225600000,1000,request,200,OK,thread,true,1,1,1,1,1,0,0\n')

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
