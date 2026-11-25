import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { SETUP_MESSAGES } from '../src/shell/labels'
import { COMPARE_LABELS } from '../src/shell/labels.compare'
import { DIAGNOSTICS_LABELS } from '../src/shell/labels.tables'

// The fixture uses finite plain decimals and sorted keys for the snapshot's canonical hash.
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value !== null && typeof value === 'object') return `{${Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(',')}}`
  return JSON.stringify(value)
}

async function analyzeDiagnostics(page: Page, extraLatency: number) {
  const start = 1767225600000
  const rows = ['timeStamp,elapsed,label,success']
  for (let cell = 0; cell < 60; cell += 1) {
    for (let sample = 0; sample < 20; sample += 1) {
      rows.push(`${start + cell * 1000 + sample},${100 + extraLatency + Math.max(0, cell - 30) * 10},checkout,true`)
    }
  }
  rows.push(`${start + 59500},500,last,true`)
  const load = Buffer.from(`${rows.join('\n')}\n`)
  const snapshot = {
    schema_version: 'resource-snapshot.v1', load_input_sha256: createHash('sha256').update(load).digest('hex'),
    start_epoch_ms: start, step_ms: 1000, point_count: 60,
    series: [
      { id: 'cpu', metric: 'cpu_used', unit: 'ratio', entity: 'server-1', role: 'system', aggregation: 'interval_mean', labels: {}, values: Array.from({ length: 60 }, (_, cell) => cell < 30 ? (19 + cell % 3) / 100 : (50 + cell - 30) / 100) },
      { id: 'target', metric: 'target_load', unit: 'requests/s', entity: 'generator-1', role: 'generator', aggregation: 'interval_rate', labels: {}, values: Array.from({ length: 60 }, () => 20) },
    ],
    windows: [{ id: 'reference', from_epoch_ms: start, to_epoch_ms: start + 30000 }, { id: 'steady', from_epoch_ms: start + 30000, to_epoch_ms: start + 60000 }],
    rules: [],
  }
  const plan = {
    schema_version: 'correlation-plan.v1', resource_snapshot_sha256: createHash('sha256').update(canonical(snapshot)).digest('hex'),
    pairs: [{ id: 'cpu-latency', resource_series_id: 'cpu', load_metric: 'response_time_p95_ms', window_ids: ['steady'], min_resource_delta: 0.1, min_load_delta: 20, topology_basis: 'node serving checkout', controls: [{ meaning: 'target_rps', series_id: 'target' }] }],
    anomalies: [{ id: 'cpu-episode', signal: { series_id: 'cpu' }, reference_window_id: 'reference', window_id: 'steady', direction: 'increase', min_abs_delta: 0.2, min_duration_ms: 15000 }],
  }
  await page.getByTestId('input-file').setInputFiles({ name: `diagnostics-${extraLatency}.jtl`, mimeType: 'text/csv', buffer: load })
  await page.getByTestId('resource-snapshot-file').setInputFiles({ name: 'resource.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(snapshot)) })
  await page.getByTestId('correlation-plan-file').setInputFiles({ name: 'correlation-plan.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(plan)) })
  // A baseline must be a PASS analysis (ADR 0019), so the runs are judged by a permissive policy.
  const policy = { schema_version: 'policy.v1', policy_id: 'permissive', rules: [{ id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 100000, scope: { kind: 'overall' } }] }
  await page.getByTestId('policy-file').setInputFiles({ name: 'policy.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(policy)) })
  await expect(page.locator('#run-setup')).toContainText(SETUP_MESSAGES.policyValid('permissive'))
  await page.getByTestId('start-analysis').click()
  await expect(page.locator('#verdict')).toContainText('PASS')
  await page.locator('#shell-tab-tables').click()
  await expect(page.locator('#diagnostic-results')).toContainText('COMPLETE')
  await expect(page.locator('#diagnostic-results')).toContainText('NOT_ESTIMATED')
  await expect(page.getByTestId('anomaly-episodes').locator('tbody tr')).toHaveCount(1)
  await expect(page.getByTestId('anomaly-episodes')).toContainText(DIAGNOSTICS_LABELS.durationSec('30,0'))
  const analysisId = await page.locator('button[aria-pressed="true"][title]').getAttribute('title')
  expect(analysisId).toMatch(/^[a-f0-9]{64}$/)
  return analysisId!
}

test('persists diagnostic evidence and compares two saved windows through the real backend', async ({ page }) => {
  await page.goto('/')
  await analyzeDiagnostics(page, 0)
  await page.locator('#shell-tab-compare').click()
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(COMPARE_LABELS.mode('manual'))
  await page.locator('#shell-tab-setup').click()
  const currentId = await analyzeDiagnostics(page, 100)
  await page.locator('#shell-tab-compare').click()
  await page.getByLabel(COMPARE_LABELS.baselineWindow, { exact: true }).fill('steady')
  await page.getByLabel(COMPARE_LABELS.currentWindow, { exact: true }).fill('steady')
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  const compared = page.getByTestId('window-comparison')
  await expect(compared).toContainText('CONDITIONS_UNCONFIRMED')
  await expect(compared).toContainText(COMPARE_LABELS.windowStats('baseline', 601, 30000))
  await expect(compared).toContainText('server-1')
  await expect(compared.getByRole('row', { name: new RegExp(COMPARE_LABELS.metric('response_time_p95_ms')) }).locator('td').nth(3)).toHaveText('100')
  await page.reload()
  await page.getByRole('button', { name: 'diagnostics-100.jtl' }).click()
  await page.locator(`button[title="${currentId}"]`).click()
  await page.locator('#shell-tab-tables').click()
  await expect(page.getByTestId('anomaly-episodes').locator('tbody tr')).toHaveCount(1)
  await expect(page.locator('#diagnostic-results')).toContainText('NOT_ESTIMATED')
})
