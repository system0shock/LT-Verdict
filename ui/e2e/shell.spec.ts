import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { SHELL_DEFAULT_TAB, SHELL_LABELS, SHELL_TABS } from '../src/shell/labels'
import { RULES_LABELS } from '../src/shell/labels.rules'

const reference = { run_id: 'shell-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'shell.jtl' }
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const checkout = {
  id: 'm-checkout', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' },
  sample_count: 900, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 900 }, throughput_rps: { numerator: 900000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const failing = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'FAIL',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [
    overall, checkout,
    { id: 'c1', type: 'policy_check', rule_id: 'checkout-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000, status: 'FAIL', metric_evidence_id: 'm-checkout', observed: 2340 },
    { id: 'c2', type: 'policy_check', rule_id: 'global-errors', metric: 'error_rate_ratio', operator: 'lte', threshold: 0.05, status: 'PASS', metric_evidence_id: 'm-overall', observed: { numerator: 88, denominator: 3650 } },
  ],
}

async function fixtureApi(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = failing
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

async function pickSavedAnalysis(page: Page) {
  await page.getByRole('button', { name: 'shell.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
}

async function openNewShellWithResult(page: Page) {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await pickSavedAnalysis(page)
  await expect(page.locator('#verdict')).toBeVisible()
}

const tabByKey = (page: Page, key: string) => page.locator(`#shell-tab-${key}`)
const label = (key: string) => SHELL_TABS.find((tab) => tab.key === key)!.label

test('the new interface exposes eight tabs, New analysis first and selected, with a labelled panel', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')

  const tablist = page.getByRole('tablist', { name: SHELL_LABELS.navLabel })
  await expect(tablist).toBeVisible()
  await expect(page.getByRole('tab')).toHaveText(
    SHELL_TABS.map((tab) => new RegExp(`^${tab.label}`)),
  )
  await expect(page.getByRole('navigation', { name: 'Application' })).toHaveCount(0)
  expect(SHELL_TABS[0].key).toBe('setup')
  expect(SHELL_DEFAULT_TAB).toBe('setup')
  await expect(tabByKey(page, 'setup')).toHaveAttribute('aria-selected', 'true')
  for (const tab of SHELL_TABS.filter((item) => item.key !== 'setup')) {
    await expect(tabByKey(page, tab.key)).toHaveAttribute('aria-selected', 'false')
  }
  const panel = page.getByRole('tabpanel')
  await expect(panel).toHaveCount(1)
  await expect(panel).toHaveAttribute('aria-labelledby', 'shell-tab-setup')
  await expect(tabByKey(page, 'setup')).toHaveAttribute('aria-controls', await panel.getAttribute('id') ?? '')
  await expect(tablist.getByText(SHELL_LABELS.pendingBadge)).toHaveCount(SHELL_TABS.filter((tab) => tab.pending).length)
})

test('arrow keys, Home and End move between tabs with a roving tabindex', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  const tabs = page.getByRole('tab')
  await tabs.first().focus()

  await expect(tabs.first()).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('ArrowDown')
  await expect(tabs.nth(1)).toBeFocused()
  await expect(tabs.nth(1)).toHaveAttribute('aria-selected', 'true')
  await expect(tabs.nth(1)).toHaveAttribute('tabindex', '0')
  await expect(tabs.first()).toHaveAttribute('tabindex', '-1')
  await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'shell-tab-overview')

  await page.keyboard.press('ArrowRight')
  await expect(tabs.nth(2)).toBeFocused()
  await page.keyboard.press('ArrowLeft')
  await expect(tabs.nth(1)).toBeFocused()
  await page.keyboard.press('ArrowUp')
  await expect(tabs.first()).toBeFocused()
  await page.keyboard.press('ArrowUp')
  await expect(tabs.last()).toBeFocused()
  await expect(tabs.last()).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('ArrowDown')
  await expect(tabs.first()).toBeFocused()
  await page.keyboard.press('End')
  await expect(tabs.last()).toBeFocused()
  await page.keyboard.press('Home')
  await expect(tabs.first()).toBeFocused()
  await expect(tabs.first()).toHaveAttribute('aria-selected', 'true')
})

test('Tab leaves the tab list instead of walking through every tab', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await tabByKey(page, 'setup').focus()

  await page.keyboard.press('Tab')

  expect(await page.evaluate(() => document.activeElement?.getAttribute('role'))).not.toBe('tab')
  await page.keyboard.press('Shift+Tab')
  await expect(tabByKey(page, 'setup')).toBeFocused()
})

test('tabs show the existing panels and never fake data', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')

  await expect(page.locator('#run-setup')).toBeVisible()
  await expect(page.getByText(SHELL_LABELS.overviewEmpty)).toBeHidden()

  await tabByKey(page, 'overview').click()
  await expect(page.getByText(SHELL_LABELS.overviewEmpty)).toBeVisible()
  await expect(page.locator('#run-setup')).toBeHidden()

  await tabByKey(page, 'rules').click()
  await expect(page.getByRole('heading', { name: RULES_LABELS.title, exact: true })).toBeVisible()
  await expect(page.locator('#run-setup')).toBeHidden()

  await tabByKey(page, 'compare').click()
  await expect(page.locator('#baseline-panel')).toBeVisible()
  await expect(page.getByRole('heading', { name: RULES_LABELS.title, exact: true })).toBeHidden()
})

test('choosing a saved analysis opens the overview and every tab keeps its own content', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await expect(tabByKey(page, 'setup')).toHaveAttribute('aria-selected', 'true')

  await pickSavedAnalysis(page)

  await expect(tabByKey(page, 'overview')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(page.locator('#run-setup')).toBeHidden()
  await expect(page.locator('#summary-metrics')).toBeHidden()
  await expect(page.getByText(SHELL_LABELS.overviewEmpty)).toBeHidden()

  await tabByKey(page, 'tables').click()
  await expect(page.locator('#summary-metrics')).toBeVisible()
  await expect(page.locator('#verdict')).toBeHidden()

  await tabByKey(page, 'advice').click()
  await expect(page.locator('#advice-title')).toBeVisible()
  await expect(page.locator('#summary-metrics')).toBeHidden()
})

test('the header chip stays visible on every tab and returns to the verdict card', async ({ page }) => {
  await openNewShellWithResult(page)
  const chip = page.getByTestId('verdict-chip')
  await expect(chip).toContainText('FAIL')

  await tabByKey(page, 'compare').click()
  await expect(page.locator('#verdict')).toBeHidden()
  await expect(chip).toBeVisible()
  await chip.click()

  await expect(tabByKey(page, 'overview')).toHaveAttribute('aria-selected', 'true')
  await expect(page).toHaveURL(/#verdict$/)
  await expect(page.locator('#verdict')).toBeInViewport()
})

test('the header shows the run in Russian and links back to the old interface', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  const header = page.getByRole('banner')

  await expect(header).toContainText(SHELL_LABELS.noRun)
  await pickSavedAnalysis(page)
  await expect(header).toContainText('shell.jtl')

  await page.getByRole('link', { name: SHELL_LABELS.legacyLink }).click()
  await expect(page).toHaveURL(/\?shell=old$/)
  await expect(page.getByRole('navigation', { name: 'Application' })).toBeVisible()
  await expect(page.getByRole('tablist')).toHaveCount(0)
})

test('the theme follows the system, toggles in memory and is never stored', async ({ page }) => {
  await fixtureApi(page)
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/?shell=new')
  const shell = page.locator('.shell')

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(shell).toHaveCSS('background-color', 'rgb(15, 23, 31)')
  await page.getByRole('button', { name: SHELL_LABELS.themeToLight }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await expect(shell).toHaveCSS('background-color', 'rgb(238, 241, 244)')
  await expect(page.getByRole('button', { name: SHELL_LABELS.themeToDark })).toBeVisible()
  await tabByKey(page, 'compare').click()

  const stored = await page.evaluate(() => ({ local: window.localStorage.length, session: window.sessionStorage.length, cookie: document.cookie }))
  expect(stored).toEqual({ local: 0, session: 0, cookie: '' })
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(tabByKey(page, SHELL_DEFAULT_TAB)).toHaveAttribute('aria-selected', 'true')
})

for (const theme of ['light', 'dark'] as const) {
  for (const key of ['overview', 'tables', 'compare', 'advice', 'setup', 'rules'] as const) {
    test(`new shell has no serious axe violations: ${key}, ${theme}`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: theme })
      await openNewShellWithResult(page)
      await tabByKey(page, key).click()
      await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', `shell-tab-${key}`)

      const axe = await new AxeBuilder({ page }).analyze()
      expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
    })
  }
}

for (const size of [{ width: 1280, height: 800 }, { width: 375, height: 800 }]) {
  test(`the page has no horizontal scroll and tabs are at least 44px at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    await openNewShellWithResult(page)

    for (const key of ['overview', 'tables', 'compare', 'advice', 'rules', 'setup']) {
      await tabByKey(page, key).click()
      const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
      expect(width.scroll, `tab ${label(key)}`).toBeLessThanOrEqual(width.client)
    }
    for (const tab of await page.getByRole('tab').all()) {
      const box = await tab.boundingBox()
      expect(box!.height).toBeGreaterThanOrEqual(44)
      expect(box!.width).toBeGreaterThanOrEqual(44)
    }
  })
}

test('the run lists are in Russian in the new shell and unchanged in the old one', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await expect(page.getByRole('heading', { name: SHELL_LABELS.runsTitle })).toBeVisible()
  await expect(page.getByText('Accepted runs')).toHaveCount(0)
  await page.getByRole('button', { name: 'shell.jtl' }).click()
  await expect(page.getByRole('heading', { name: SHELL_LABELS.analysesTitle })).toBeVisible()
  await expect(page.locator(`button[title="${reference.analysis_id}"]`)).toContainText(SHELL_LABELS.analysisItem)
  await expect(page.getByText('Saved analyses')).toHaveCount(0)

  await page.goto('/?shell=old')
  await expect(page.getByRole('heading', { name: 'Accepted runs' })).toBeVisible()
  await page.getByRole('button', { name: 'shell.jtl' }).click()
  await expect(page.getByRole('heading', { name: 'Saved analyses' })).toBeVisible()
  await expect(page.locator(`button[title="${reference.analysis_id}"]`)).toContainText('Analysis')
  await expect(page.getByText(SHELL_LABELS.runsTitle)).toHaveCount(0)
})

test('the sticky header does not cover the verdict card after the chip jump', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 500 })
  await openNewShellWithResult(page)
  const header = page.getByRole('banner')
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  expect((await header.boundingBox())!.y).toBeGreaterThanOrEqual(0)
  await expect(header).toBeInViewport()

  await page.getByTestId('verdict-chip').click()

  const headerBox = await header.boundingBox()
  const verdictBox = await page.locator('#verdict').boundingBox()
  expect(verdictBox!.y).toBeGreaterThanOrEqual(headerBox!.y + headerBox!.height - 1)
})
