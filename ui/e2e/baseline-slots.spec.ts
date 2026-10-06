import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { BASELINE_ERROR_LABELS } from '../src/shell/labels'
import { COMPARE_LABELS, EN_COMPARE_LABELS } from '../src/shell/labels.compare'
import type { BaselineSelection, BaselineSlotView } from '../src/types'

const current = { run_id: 'slot-cur', analysis_id: 'a'.repeat(64) }
const run = { ...current, source_type: 'jmeter', sha256: 'd'.repeat(64), size_bytes: 100, original_filename: 'slots.jtl' }
const references: Record<string, { run_id: string; analysis_id: string }> = {
  A: { run_id: 'slot-run-a', analysis_id: '1'.repeat(64) },
  B: { run_id: 'slot-run-b', analysis_id: '2'.repeat(64) },
}

type Failure = { status: number; code: string; message: string; limit?: number }
type Options = { series?: string[]; shell?: 'old'; failures?: Record<string, Failure> }

const selection = (series: string, mode: 'manual' | 'statistical' = 'manual'): BaselineSelection => ({
  schema_version: 'local-baseline.v1', series, mode,
  reference: references[series] ?? { run_id: `run-${series.length}`, analysis_id: 'e'.repeat(64) },
  algorithm: null, candidates: [], scores: [],
})

async function openCompare(page: Page, opts: Options = {}) {
  const requests: Array<{ method: string; path: string; search: string }> = []
  let slots: BaselineSlotView[] = (opts.series ?? ['A', 'B']).map((series) => ({ series, arm: null, source: 'SLOT', baseline: selection(series) }))
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    requests.push({ method, path, search: url.search })
    const key = path === '/api/baseline' ? `${method} baseline` : `${method} ${path.split('/').pop()}`
    const failure = opts.failures?.[key]
    if (failure) {
      await route.fulfill({ status: failure.status, json: { error: { code: failure.code, message: failure.message, limit: failure.limit } } })
      return
    }
    let body: unknown
    const requested = url.searchParams.get('series') ?? ''
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources') body = { profiles: [] }
    else if (path.endsWith('/advice')) body = { advice: null, job: null }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline' && method === 'GET') body = { baseline: null, baselines: slots }
    else if (path === '/api/baseline' && method === 'POST') {
      const posted = route.request().postDataJSON() as { series: string }
      slots = [...slots.filter((slot) => slot.series !== posted.series), { series: posted.series, arm: null, source: 'SLOT', baseline: selection(posted.series) }]
      body = { baseline: selection(posted.series) }
    } else if (path === '/api/baseline' && method === 'DELETE') {
      slots = slots.filter((slot) => slot.series !== requested)
      body = { baseline: null }
    } else if (path.endsWith('/baseline-conditions')) {
      body = method === 'GET' ? { conditions: null } : {
        conditions: {
          schema_version: 'local-baseline-conditions.v1', baseline: references[requested] ?? references.A, current, windows: null,
          decision: 'CONFIRMED', provenance: 'EXPLICIT_LOCAL_ACTION', updated_at: '2026-10-04T10:00:00Z',
        },
      }
    } else if (path.endsWith('/comparison')) {
      body = {
        baseline: selection(requested), current, comparability: 'UNCONFIRMED', warnings: [], conditions: null,
        metrics: [{ metric: 'response_time_p95_ms', unit: 'ms', baseline: '100', current: '200', delta: '100', delta_percent: '100', reason: null, percent_reason: null }],
      }
    } else if (path.endsWith('/analytics')) {
      body = {
        schema_version: 'saved-analytics.v1', history_scan_truncated: false, history_scan_limit: 1000, history_metadata_byte_limit: 16777216,
        history_integrity: 'SAVED_DOCUMENT_HASHES', dynamics: null, transactions: null, overlay: null,
        metric_packs: { schema_version: 'metric-packs.v1', packs: [] },
      }
    } else if (path.endsWith('/analyses')) {
      body = { analyses: [{ analysis_id: current.analysis_id, policy_sha256: 'e'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }], next_after: null }
    } else if (path.endsWith('/result')) {
      body = {
        schema_version: 'analysis-result.v1', run_id: current.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
        analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [],
      }
    } else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
  await page.goto(opts.shell === 'old' ? '/?shell=old' : '/?shell=new')
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator(`button[title="${current.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  if (opts.shell !== 'old') await page.locator('#shell-tab-compare').click()
  return requests
}

const seriesField = (page: Page) => page.getByLabel(COMPARE_LABELS.seriesLabel, { exact: true })
const slotRadio = (page: Page, series: string) => page.getByTestId('baseline-slots').getByRole('radio', { name: new RegExp(`^${series}\\b`) })

test('every active baseline is listed and the panel works on the chosen series', async ({ page }) => {
  const requests = await openCompare(page)
  await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(2)
  await expect(slotRadio(page, 'A')).toBeChecked()
  await expect(seriesField(page)).toHaveValue('A')
  await expect(page.getByTestId('baseline-selection')).toContainText(references.A.analysis_id)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toBeVisible()
  expect(requests.some((entry) => entry.path.endsWith('/comparison') && entry.search === '?series=A')).toBe(true)

  await slotRadio(page, 'B').check()
  await expect(seriesField(page)).toHaveValue('B')
  await expect(page.getByTestId('baseline-selection')).toContainText(references.B.analysis_id)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText(COMPARE_LABELS.conditionSaved('CONFIRMED', '2026-10-04T10:00:00Z'))
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(references.B.analysis_id)
  expect(requests.filter((entry) => entry.method === 'POST' && entry.path.endsWith('/baseline-conditions')).map((entry) => entry.search)).toEqual(['?series=B'])
  expect(requests.filter((entry) => entry.path.endsWith('/comparison')).map((entry) => entry.search)).toEqual(['?series=A', '?series=B'])
})

test('clearing one series keeps the others and says which series has no baseline', async ({ page }) => {
  const requests = await openCompare(page)
  await page.getByRole('button', { name: COMPARE_LABELS.clearBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toHaveCount(0)
  await expect(page.locator('#baseline-panel')).toContainText(COMPARE_LABELS.noBaselineFor('A'))
  await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(1)
  expect(requests.filter((entry) => entry.method === 'DELETE').map((entry) => entry.search)).toEqual(['?series=A'])
  await slotRadio(page, 'B').check()
  await expect(page.getByTestId('baseline-selection')).toContainText(references.B.analysis_id)
})

test('a new series is added next to the existing ones', async ({ page }) => {
  await openCompare(page)
  await seriesField(page).fill('C')
  await expect(page.getByTestId('baseline-selection')).toHaveCount(0)
  await expect(page.locator('#baseline-panel')).toContainText(COMPARE_LABELS.noBaselineFor('C'))
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(3)
  await expect(slotRadio(page, 'C')).toBeChecked()
  await expect(page.getByTestId('baseline-selection')).toContainText('C')
})

test('the 422 codes of baseline slots are Russian phrases, not marked as English', async ({ page }) => {
  await openCompare(page, {
    failures: {
      'GET comparison': { status: 422, code: 'BASELINE_SERIES_CONFLICT', message: 'Series differs from the release series' },
      'POST baseline': { status: 422, code: 'BASELINE_SLOTS_LIMIT_REACHED', message: 'Baseline slot limit is reached', limit: 64 },
      'POST baseline-conditions': { status: 422, code: 'BASELINE_CONDITIONS_LIMIT_REACHED', message: 'Condition limit is reached', limit: 4096 },
    },
  })
  const alert = page.getByRole('alert')
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(alert).toHaveText(BASELINE_ERROR_LABELS.BASELINE_SERIES_CONFLICT!(null))
  await expect(alert).not.toHaveAttribute('lang', 'en')
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(alert).toHaveText(BASELINE_ERROR_LABELS.BASELINE_SLOTS_LIMIT_REACHED!(64))
  expect(await alert.innerText()).toContain('64')
  await expect(alert).not.toHaveAttribute('lang', 'en')
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect(alert).toHaveText(BASELINE_ERROR_LABELS.BASELINE_CONDITIONS_LIMIT_REACHED!(4096))
  expect(await alert.innerText()).toContain('4096')
})

test('an unknown code keeps the server text marked as English, the old interface keeps the server text', async ({ page }) => {
  await openCompare(page, { failures: { 'GET comparison': { status: 422, code: 'FUTURE_CODE', message: 'Server says no.' } } })
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('Server says no.')
  await expect(page.getByRole('alert')).toHaveAttribute('lang', 'en')
})

test('the old interface shows the server text of a slot code', async ({ page }) => {
  await openCompare(page, { shell: 'old', failures: { 'GET comparison': { status: 422, code: 'BASELINE_SERIES_CONFLICT', message: 'Series differs from the release series' } } })
  await page.getByRole('button', { name: EN_COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('Series differs from the release series')
})

test('the old interface lists the slots in English', async ({ page }) => {
  await openCompare(page, { shell: 'old' })
  await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(2)
  await expect(page.getByTestId('baseline-slots')).toContainText(EN_COMPARE_LABELS.slotsLegend)
})

test('saved analytics ask for the series of the active baseline', async ({ page }) => {
  const requests = await openCompare(page)
  await slotRadio(page, 'B').check()
  await expect(page.getByTestId('baseline-selection')).toContainText(references.B.analysis_id)
  await page.locator('#shell-tab-overview').click()
  await page.getByRole('button', { name: 'Refresh analytics', exact: true }).click()
  await expect.poll(() => requests.filter((entry) => entry.path.endsWith('/analytics')).map((entry) => new URLSearchParams(entry.search).get('series'))).toEqual(['B'])
  await expect(page.getByRole('link', { name: 'Export analytics JSON' })).toHaveAttribute('href', /[?&]series=B(&|$)/)
})

for (const theme of ['light', 'dark'] as const) {
  test(`the slot list has no serious axe violations, ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openCompare(page)
    const audit = await new AxeBuilder({ page }).include('#baseline-panel').analyze()
    expect(audit.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

test('the slot list is reachable and operable with the keyboard', async ({ page }) => {
  await openCompare(page)
  await slotRadio(page, 'A').focus()
  await page.keyboard.press('ArrowDown')
  await expect(slotRadio(page, 'B')).toBeChecked()
  await expect(seriesField(page)).toHaveValue('B')
})

for (const width of [1280, 375, 320]) {
  test(`the slot list does not overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await openCompare(page, { series: [`A${'x'.repeat(100)}`, `B${'y'.repeat(100)}`] })
    await page.evaluate(() => document.documentElement.style.setProperty('letter-spacing', '0.15em'))
    const size = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth }))
    expect(size.scrollWidth).toBeLessThanOrEqual(size.innerWidth)
    await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(2)
  })
}
