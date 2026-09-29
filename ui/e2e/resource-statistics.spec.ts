import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const resourceInput = Buffer.from('timeStamp,elapsed,label,success\n1000,1000,checkout,false\n2000,1000,checkout,false\n3000,1000,checkout,false\n4000,1000,checkout,false\n')
const resourceInputFile = { name: 'resource-input.jtl', mimeType: 'text/csv', buffer: resourceInput }

test('shows resource failures and missing cells after reloading a saved analysis', async ({ page }) => {
  const snapshot = {
    schema_version: 'resource-snapshot.v1',
    load_input_sha256: createHash('sha256').update(resourceInput).digest('hex'),
    start_epoch_ms: 1000,
    step_ms: 1000,
    point_count: 4,
    series: [
      { id: 'cpu', metric: 'cpu_utilization', unit: 'ratio', entity: 'server-1', role: 'system', aggregation: 'interval_mean', values: [0.9, 0.9, 0.9, 0.9] },
      { id: 'memory', metric: 'memory_utilization', unit: 'ratio', entity: 'server-1', role: 'system', aggregation: 'interval_mean', values: [null, null, null, null] },
    ],
    windows: [{ id: 'evaluation', from_epoch_ms: 1000, to_epoch_ms: 5000 }],
    rules: [
      { id: 'cpu-limit', series_id: 'cpu', unit: 'ratio', operator: 'gt', threshold: 0.8, min_consecutive_cells: 2, effect: 'sla' },
      { id: 'memory-limit', series_id: 'memory', unit: 'ratio', operator: 'gt', threshold: 0.8, min_consecutive_cells: 1, effect: 'sla' },
    ],
  }

  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(resourceInputFile)
  await page.getByTestId('policy-file').setInputFiles(fixture('slice1/policies/fail.json'))
  await page.getByTestId('resource-snapshot-file').setInputFiles({
    name: 'resource-snapshot.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(snapshot)),
  })
  await page.getByRole('button', { name: 'Analyze run' }).click()

  await expect(page.locator('#resource-results')).toBeVisible()
  await expect(page.locator('#resource-results')).toContainText('cpu-limit')
  await expect(page.locator('#resource-results')).toContainText('FAIL')
  await expect(page.locator('#resource-results')).toContainText('memory-limit')
  await expect(page.locator('#resource-results')).toContainText('NO_VERDICT')
  await expect(page.locator('#resource-results')).toContainText('Missing cells')
  await expect(page.locator('#resource-results')).toContainText('4')
  await expect(page.locator('#resource-results')).toContainText('Business verdict')
  await expect(page.locator('#resource-results')).toContainText('Resource verdict')
  await expect(page.locator('#resource-results')).toContainText('evaluation')
  await expect(page.locator('#resource-results')).toContainText('server-1')
  await expect(page.locator('#resource-results')).toContainText('system')
  await expect(page.locator('#resource-results')).toContainText('interval_mean')
  await expect(page.locator('#resource-results')).toContainText('Resource binding')
  await expect(page.locator('#resource-results')).toContainText('not_verified_by_core')
  await expect(page.locator('#policy-results')).toContainText('evaluation')
  await expect(page.locator('#policy-results')).toContainText('Overall')

  const analysis = page.locator('button[aria-pressed="true"][title]')
  const analysisId = await analysis.getAttribute('title')
  await page.reload()
  await page.getByRole('button', { name: 'resource-input.jtl' }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#resource-results')).toContainText('memory-limit')
  await expect(page.locator('#resource-results')).toContainText('NO_VERDICT')
})

test('counts an SLA resource failure in the verdict without a business policy', async ({ page }) => {
  const snapshot = {
    schema_version: 'resource-snapshot.v1',
    load_input_sha256: createHash('sha256').update(resourceInput).digest('hex'),
    start_epoch_ms: 1000,
    step_ms: 1000,
    point_count: 4,
    series: [{ id: 'cpu', metric: 'cpu_utilization', unit: 'ratio', entity: 'server-1', role: 'system', aggregation: 'interval_mean', values: [0.9, 0.9, 0.9, 0.9] }],
    windows: [{ id: 'evaluation', from_epoch_ms: 1000, to_epoch_ms: 5000 }],
    rules: [{ id: 'cpu-limit', series_id: 'cpu', unit: 'ratio', operator: 'gt', threshold: 0.8, min_consecutive_cells: 2, effect: 'sla' }],
  }

  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(resourceInputFile)
  await page.getByTestId('resource-snapshot-file').setInputFiles({
    name: 'resource-only.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(snapshot)),
  })
  await page.getByRole('button', { name: 'Analyze run' }).click()

  await expect(page.locator('#verdict h2')).toHaveText('Прогон не проходит — нарушено проверок: 1 из 1')
  await expect(page.locator('#resource-results')).toContainText('cpu-limit')
})
