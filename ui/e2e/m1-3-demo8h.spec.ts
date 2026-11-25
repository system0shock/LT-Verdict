import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { COMPARE_LABELS } from '../src/shell/labels.compare'
import { HISTORY_LABELS } from '../src/shell/labels.history'
import { NORMALIZED_LABELS, RESOURCE_CHECK_LABELS, RESOURCE_TABLES_LABELS, TABLES_LABELS, TABLES_NAV_LABELS, TREND_LABELS } from '../src/shell/labels.tables'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const SERIES = 'Checkout Demo8h M13'
let demoDir = ''
let currentRunId = ''
let baselineRunId = ''

async function clearSeriesReleasesAndBaselines(page: Page) {
  await page.goto('/?shell=new')
  const bootstrap = await (await page.request.get('/api/bootstrap')).json() as { csrf_token: string }
  const headers = { Origin: new URL(page.url()).origin, 'X-LTV-CSRF': bootstrap.csrf_token }
  for (;;) {
    const listed = await (await page.request.get(`/api/releases?series=${encodeURIComponent(SERIES)}&limit=100`)).json() as { releases: Array<{ release_id: string }> }
    if (!listed.releases.length) break
    for (const item of listed.releases) await page.request.delete(`/api/releases/${item.release_id}`, { headers })
  }
  await page.request.delete(`/api/baseline?series=${encodeURIComponent(SERIES)}`, { headers })
}

async function analyzeDemoRelease(page: Page, releaseId: string) {
  await page.goto('/?shell=new')
  const base = join(demoDir, releaseId)
  await page.getByTestId('input-file').setInputFiles(join(base, 'load.jtl'))
  await page.getByTestId('policy-file').setInputFiles(join(demoDir, 'policy.json'))
  await page.getByTestId('resource-snapshot-file').setInputFiles(join(base, 'arm-B', 'resource-snapshot.json'))
  await page.getByTestId('trend-plan-file').setInputFiles(join(base, 'arm-B', 'trend-plan.json'))
  await page.locator('#source-context-file').setInputFiles(join(base, 'opensearch-errors.json'))
  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toHaveAttribute('data-level', 'ok')
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible({ timeout: 120_000 })
  const runLi = page.locator('[data-testid="run-list"] li').filter({ has: page.locator('button[aria-pressed="true"]') })
  return (await runLi.getAttribute('title'))!
}

async function saveReleaseAndOpenHistory(page: Page, label: string) {
  await page.locator('#shell-tab-history').click()
  await page.getByLabel(HISTORY_LABELS.formSeries, { exact: true }).fill(SERIES)
  await page.getByLabel(HISTORY_LABELS.formLabel, { exact: true }).fill(label)
  await page.getByRole('button', { name: HISTORY_LABELS.save, exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: HISTORY_LABELS.saved(label) })).toBeVisible()
}

test.describe.serial('M1.3 demo-8h real server verification (Slices 4, 6, 7)', () => {
  test.beforeAll(() => {
    demoDir = mkdtempSync(join(tmpdir(), 'ltv-demo8h-m13-'))
    execFileSync('python', ['-m', 'tools.demo_dataset', 'generate', '--out', demoDir, '--profile', 'tiny'], { cwd: repoRoot })
  })

  test.afterAll(async ({ browser }) => {
    if (demoDir) rmSync(demoDir, { recursive: true, force: true })
    const page = await browser.newPage()
    try {
      await clearSeriesReleasesAndBaselines(page)
    } finally {
      await page.close()
    }
  })

  test('uploads demo-8h baseline (2026.09.2) and current (2026.09.3) runs and saves releases', async ({ page }) => {
    await clearSeriesReleasesAndBaselines(page)
    baselineRunId = await analyzeDemoRelease(page, '2026.09.2')
    await expect(page.locator('#verdict')).toContainText('PASS')
    await saveReleaseAndOpenHistory(page, '2026.09.2')
    await page.getByTestId('release-row').first().getByRole('button', { name: HISTORY_LABELS.actionAria(HISTORY_LABELS.makeBaseline, '2026.09.2', null) }).click()
    await expect(page.getByTestId('baseline-badge')).toBeVisible()

    currentRunId = await analyzeDemoRelease(page, '2026.09.3')
    expect(currentRunId).not.toBe(baselineRunId)
    await expect(page.locator('#verdict')).toContainText('FAIL')
    await saveReleaseAndOpenHistory(page, '2026.09.3')
  })

  test('Slice 7: 1-click opens demo-8h run in new shell, while ?shell=old keeps 2-click', async ({ page }) => {
    await page.goto('/?shell=new')
    await expect(page.locator('#shell-tab-setup')).toHaveAttribute('aria-selected', 'true')
    await page.locator(`[data-testid="run-list"] li[title="${currentRunId}"] button`).click()
    await expect(page.locator('#shell-tab-overview')).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('#verdict')).toContainText('FAIL')
    await expect(page.getByTestId('overview-panel')).toBeVisible()

    await page.goto('/?shell=old')
    await page.locator(`[data-testid="run-list"] li[title="${currentRunId}"] button`).click()
    await expect(page.getByRole('heading', { name: 'Saved analyses' })).toBeVisible()
    await expect(page.locator('#verdict')).toHaveCount(0)
    await page.locator('section[aria-labelledby="analysis-list-title"] li button').first().click()
    await expect(page.locator('#verdict')).toBeVisible()
  })

  test('Slice 4: Compare and History render CSP-safe ECharts on demo-8h data', async ({ page }) => {
    await page.addInitScript(() => {
      (window as never as { csp: string[] }).csp = []
      document.addEventListener('securitypolicyviolation', (event) => (window as never as { csp: string[] }).csp.push(`${event.violatedDirective} ${event.blockedURI}`))
    })
    await page.goto('/?shell=new')
    await page.locator(`[data-testid="run-list"] li[title="${currentRunId}"] button`).click()
    await expect(page.getByTestId('overview-panel')).toBeVisible()
    await expect(page.getByTestId('verdict-kpis')).toBeVisible()
    const incidentCount = await page.getByTestId('incident-card').count()
    expect(incidentCount).toBeGreaterThan(0)
    expect(incidentCount).toBeLessThanOrEqual(7)
    await expect(page.getByTestId('overview-platform')).toBeVisible()
    expect(await page.getByTestId('overview-platform-tile').count()).toBeGreaterThan(0)
    expect(await page.getByTestId('overview-platform-tile').count()).toBeLessThanOrEqual(8)
    await expect(page.getByTestId('overview-trends')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Saved-run analytics' })).toHaveCount(0)

    // Compare ECharts
    await page.locator('#shell-tab-compare').click()
    await page.getByLabel(COMPARE_LABELS.seriesLabel, { exact: true }).fill(SERIES)
    await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
    await expect(page.getByTestId('compare-charts')).toBeVisible()
    await expect(page.getByTestId('compare-cursor-chart')).toBeVisible()
    for (const key of ['rps', 'errors', 'p95']) {
      await expect(page.getByTestId(`compare-track-${key}`).getByTestId('time-chart')).toBeVisible()
      await expect(page.getByTestId(`compare-track-${key}`).locator('svg').first()).toBeVisible()
      await expect(page.getByTestId(`compare-track-${key}`).locator('svg text').first()).toBeVisible()
      expect(await page.getByTestId(`compare-track-${key}`).locator('svg path').count()).toBeGreaterThan(0)
    }
    const cursor = page.getByTestId('compare-chart-cursor')
    await cursor.focus()
    await page.keyboard.press('End')
    await expect(page.getByTestId('compare-cursor-time')).not.toHaveText(COMPARE_LABELS.chartsCursorNone)

    // History ECharts
    await page.locator('#shell-tab-history').click()
    await page.getByLabel(HISTORY_LABELS.seriesLabel, { exact: true }).selectOption(SERIES)
    const dynamics = page.getByTestId('history-dynamics')
    await expect(dynamics.locator('svg').first()).toBeVisible()
    await expect(dynamics.locator('svg text').first()).toBeVisible()
    expect(await dynamics.locator('svg path').count()).toBeGreaterThan(0)
    await expect(dynamics).toContainText(HISTORY_LABELS.dynamicsLegend)
    await expect(dynamics).toContainText(HISTORY_LABELS.dynamicsBaselineLegend)
    await page.getByTestId('history-dynamics-cursor').focus()
    await page.keyboard.press('Home')
    await expect(page.getByTestId('history-dynamics-readout')).toContainText('2026.09.2')
    await expect(page.getByTestId('history-dynamics-readout')).toContainText('PASS')
    expect(await page.evaluate(() => (window as never as { csp: string[] }).csp)).toEqual([])
  })

  test('Slice 6: Tables has sub-tabs, sorting and 100% Russian labels on demo-8h data', async ({ page }) => {
    await page.goto('/?shell=new')
    await page.locator(`[data-testid="run-list"] li[title="${currentRunId}"] button`).click()
    await expect(page.getByTestId('overview-panel')).toBeVisible()
    await expect(page.getByTestId('verdict-kpis')).toBeVisible()
    const incidentCount = await page.getByTestId('incident-card').count()
    expect(incidentCount).toBeGreaterThan(0)
    expect(incidentCount).toBeLessThanOrEqual(7)
    await expect(page.getByTestId('overview-platform')).toBeVisible()
    expect(await page.getByTestId('overview-platform-tile').count()).toBeGreaterThan(0)
    expect(await page.getByTestId('overview-platform-tile').count()).toBeLessThanOrEqual(8)
    await expect(page.getByTestId('overview-trends')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Saved-run analytics' })).toHaveCount(0)
    await page.locator('#shell-tab-tables').click()

    const subtabs = page.getByTestId('tables-subtabs')
    await expect(subtabs).toBeVisible()
    await expect(subtabs.getByRole('tab', { name: TABLES_NAV_LABELS.tabs.all })).toHaveAttribute('aria-selected', 'true')
    for (const id of ['summary-metrics', 'policy-results', 'transaction-metrics', 'resource-results', 'trend-results', 'normalized-data']) {
      await expect(page.locator(`#${id}`)).toBeVisible()
    }

    const panelText = await page.locator('#tables-subpanel').innerText()
    for (const english of [
      'Summary metrics', 'Latency percentile', 'Throughput', 'Resource statistics and SLA', 'Resource binding',
      'Source acquisition', 'OpenSearch errors', 'PostgreSQL changes', 'Run diagnostics',
      'Raw diagnostic evidence', 'Requests per second', 'Errors per bin', 'P95 latency', 'Next bucket page',
      'Showing ', 'Not available',
    ]) {
      expect(panelText).not.toContain(english)
    }

    // Sub-tab filtering
    await subtabs.getByRole('tab', { name: TABLES_NAV_LABELS.tabs.trends }).click()
    await expect(page.locator('#trend-results')).toBeVisible()
    await expect(page.locator('#policy-results')).toHaveCount(0)
    await subtabs.getByRole('tab', { name: TABLES_NAV_LABELS.tabs.all }).click()
    await expect(page.locator('#policy-results')).toBeVisible()

    // Sorting checks
    await page.locator('#policy-results').getByRole('button', { name: TABLES_LABELS.sortBy(TABLES_LABELS.ruleHeads[0]) }).click()
    await expect(page.locator('#policy-results th').first()).toHaveAttribute('aria-sort', 'ascending')
    await page.locator('#transaction-metrics').getByRole('button', { name: TABLES_LABELS.sortBy(TABLES_LABELS.txHeads.p50) }).click()
    await expect(page.locator('#transaction-metrics th[aria-sort="descending"]')).toHaveCount(1)
    await page.locator('#trend-results').getByRole('button', { name: TABLES_LABELS.sortBy(TREND_LABELS.heads[0]) }).click()
    await expect(page.locator('#trend-results th').first()).toHaveAttribute('aria-sort', 'ascending')
    await page.locator('#resource-results').getByRole('button', { name: TABLES_LABELS.sortBy(RESOURCE_CHECK_LABELS.heads.rule) }).click()
    await expect(page.locator('#resource-results').getByRole('region', { name: RESOURCE_TABLES_LABELS.checksRegion }).locator('th[aria-sort="ascending"]')).toHaveCount(1)
    await expect(page.locator('#normalized-data')).toContainText(NORMALIZED_LABELS.title)
  })

  for (const theme of ['light', 'dark'] as const) {
    for (const size of [{ width: 640, height: 360 }, { width: 320, height: 568 }] as const) {
      test(`demo-8h Compare, History, Tables pass axe-core and 200% zoom (${size.width}x${size.height}, ${theme})`, async ({ page }) => {
        await page.setViewportSize(size)
        await page.emulateMedia({ colorScheme: theme })
        await page.goto('/?shell=new')
        await page.locator(`[data-testid="run-list"] li[title="${currentRunId}"] button`).click()
        await expect(page.getByTestId('overview-panel')).toBeVisible()

        // Overview
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
        let axe = await new AxeBuilder({ page }).analyze()
        expect(axe.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious').map((v) => v.id)).toEqual([])

        // Tables
        await page.locator('#shell-tab-tables').click()
        await expect(page.getByTestId('tables-subtabs')).toBeVisible()
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
        axe = await new AxeBuilder({ page }).analyze()
        expect(axe.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious').map((v) => v.id)).toEqual([])

        // Compare
        await page.locator('#shell-tab-compare').click()
        await page.getByLabel(COMPARE_LABELS.seriesLabel, { exact: true }).fill(SERIES)
        await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
        await expect(page.getByTestId('compare-charts')).toBeVisible()
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
        axe = await new AxeBuilder({ page }).analyze()
        expect(axe.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious').map((v) => v.id)).toEqual([])

        // History
        await page.locator('#shell-tab-history').click()
        await page.getByLabel(HISTORY_LABELS.seriesLabel, { exact: true }).selectOption(SERIES)
        await expect(page.getByTestId('history-dynamics').locator('svg').first()).toBeVisible()
        await expect(page.getByTestId('history-dynamics').locator('svg text').first()).toBeVisible()
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
        axe = await new AxeBuilder({ page }).analyze()
        expect(axe.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious').map((v) => v.id)).toEqual([])
      })
    }
  }
})
