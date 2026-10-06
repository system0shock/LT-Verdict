import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { EXPORT_LABELS } from '../src/shell/labels.export'
import { SHELL_TABS } from '../src/shell/labels'

// U7b: the header export action of the new shell. The API is replaced in the tests.
const reference = { run_id: 'export-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'export.jtl' }
const reportPath = `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/report`
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [overall],
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
    else if (method === 'GET' && path === '/api/releases') body = { releases: [], next_after: null, series_summary: [], corrupt_count: 0, corrupt_names: [] }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
}

const exportLink = (page: Page) => page.getByRole('link', { name: EXPORT_LABELS.html })

// The file itself is covered by the live spec (report-export.spec.ts). Here the activation is recorded and cancelled:
// the mocked API cannot serve a download, and a missing run on a real server would fail it.
async function recordActivations(page: Page) {
  await exportLink(page).evaluate((element) => {
    const seen: string[] = []
    ;(window as unknown as { exportClicks: string[] }).exportClicks = seen
    element.addEventListener('click', (event) => {
      seen.push((event.currentTarget as HTMLAnchorElement).getAttribute('href') ?? '')
      event.preventDefault()
    })
  })
  return () => page.evaluate(() => (window as unknown as { exportClicks: string[] }).exportClicks)
}

async function openRun(page: Page, shell: 'new' | 'old' = 'new') {
  await fixtureApi(page)
  await page.goto(`/?shell=${shell}`)
  await page.getByRole('button', { name: 'export.jtl' }).click()
}

async function openAnalysis(page: Page, shell: 'new' | 'old' = 'new') {
  await openRun(page, shell)
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
}

test('the header has no export action before an analysis is open', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await expect(exportLink(page)).toHaveCount(0)

  await page.getByRole('button', { name: 'export.jtl' }).click()
  await expect(page.locator('.run-identity')).toContainText('export.jtl')
  await expect(exportLink(page)).toHaveCount(0)
})

test('the export action in the header points to the HTML report of the open analysis', async ({ page }) => {
  await openAnalysis(page)

  const link = page.getByRole('banner').getByRole('link', { name: EXPORT_LABELS.html })
  await expect(link).toBeVisible()
  await expect(link).toHaveAttribute('href', `${reportPath}?format=html`)
  await expect(link).toHaveAttribute('download', '')
  await expect(link).toHaveClass(/button-secondary/)
  expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(44)
})

test('clicking the export action opens the report URL of the open analysis and starts no job', async ({ page }) => {
  await openAnalysis(page)
  let jobs = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobs += 1
  })
  const clicks = await recordActivations(page)

  await exportLink(page).click()

  expect(await clicks()).toEqual([`${reportPath}?format=html`])
  expect(jobs).toBe(0)
})

test('the export action stays in the header on every tab and works from the keyboard', async ({ page }) => {
  await openAnalysis(page)

  for (const tab of SHELL_TABS) {
    await page.locator(`#shell-tab-${tab.key}`).click()
    await expect(exportLink(page), `tab ${tab.label}`).toBeVisible()
  }

  const clicks = await recordActivations(page)
  await exportLink(page).focus()
  await expect(exportLink(page)).toBeFocused()
  await page.keyboard.press('Enter')
  expect(await clicks()).toEqual([`${reportPath}?format=html`])
})

test('the export action is reachable by Tab', async ({ page }) => {
  await openAnalysis(page)
  const reachable = await (async () => {
    for (let step = 0; step < 60; step += 1) {
      await page.keyboard.press('Tab')
      if (await exportLink(page).evaluate((element) => element === document.activeElement)) return true
    }
    return false
  })()
  expect(reachable).toBe(true)
  await expect(exportLink(page)).toHaveCSS('outline-style', 'solid')
})

test('the old interface keeps its English download links and has no header export', async ({ page }) => {
  await openAnalysis(page, 'old')

  await expect(exportLink(page)).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Download HTML' })).toHaveAttribute('href', `${reportPath}?format=html`)
})

for (const theme of ['light', 'dark'] as const) {
  test(`the header with the export action has no serious axe violations: ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openAnalysis(page)
    await expect(exportLink(page)).toBeVisible()

    const axe = await new AxeBuilder({ page }).analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const width of [1280, 375, 320]) {
  test(`the export action fits the header without horizontal scroll at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    await openAnalysis(page)

    const box = await exportLink(page).boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(width)
    expect(box!.height).toBeGreaterThanOrEqual(44)
    const scroll = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(scroll.scroll).toBeLessThanOrEqual(scroll.client)
  })
}
