import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function analyze(page: Page, name: string, elapsed: number, timestamp: number) {
  await page.getByTestId('input-file').setInputFiles({
    name,
    mimeType: 'text/csv',
    buffer: Buffer.from(`timeStamp,elapsed,label,success\n${timestamp},${elapsed},baseline-test,true\n`),
  })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  const href = await page.getByRole('link', { name: 'Download JSON', exact: true }).getAttribute('href')
  const match = href?.match(/^\/api\/runs\/([^/]+)\/analyses\/([a-f0-9]{64})\/report/)
  expect(match).toBeTruthy()
  return { run_id: match![1]!, analysis_id: match![2]! }
}

test.beforeEach(async ({ page }) => {
  await page.goto('/')
  const bootstrap = await (await page.request.get('/api/bootstrap')).json() as { csrf_token: string }
  await page.request.delete('/api/baseline', {
    headers: { Origin: new URL(page.url()).origin, 'X-LTV-CSRF': bootstrap.csrf_token },
  })
  await page.reload()
})

test('pins manual baseline across reload and compares changed achieved load without another job', async ({ page }, testInfo) => {
  const baseline = await analyze(page, 'baseline-manual.jtl', 100, 1767225600000)
  await page.getByLabel('Comparison series', { exact: true }).fill('Checkout / test environment')
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  await page.reload()
  await expect(page.getByTestId('baseline-selection')).toContainText('manual')
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  await analyze(page, 'baseline-current.jtl', 200, 1767225601000)
  let jobs = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobs += 1
  })
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  const p95 = page.getByTestId('comparison-response_time_p95_ms')
  await expect(p95).toContainText('100')
  await expect(p95).toContainText('200')
  await expect(p95.locator('td').nth(3)).toHaveText('100')
  await expect(p95.locator('td').nth(4)).toHaveText('100%')
  const throughput = page.getByTestId('comparison-throughput_rps')
  await expect(throughput.locator('td').nth(3)).toHaveText('-5')
  await expect(throughput.locator('td').nth(4)).toHaveText('-50%')
  await expect(page.getByTestId('comparison-error_rate_ratio')).toContainText('ZERO_BASELINE')
  await expect(page.getByTestId('baseline-comparison')).toContainText('UNCONFIRMED')
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  expect(jobs).toBe(0)
  const audit = await new AxeBuilder({ page }).include('#baseline-panel').analyze()
  expect(audit.violations).toEqual([])
  await page.locator('#baseline-panel').screenshot({ path: testInfo.outputPath('baseline-desktop.png') })
  await page.getByRole('button', { name: 'Dark theme', exact: true }).click()
  await page.setViewportSize({ width: 500, height: 1000 })
  const layout = await page.evaluate(() => ({
    viewport: window.innerWidth,
    width: document.documentElement.scrollWidth,
    panel: [...document.querySelectorAll('#baseline-panel, #baseline-panel > *')].map((element) => ({
      tag: element.tagName,
      width: element.getBoundingClientRect().width,
      right: element.getBoundingClientRect().right,
      columns: getComputedStyle(element).gridTemplateColumns,
    })),
  }))
  expect(layout.width, JSON.stringify(layout)).toBeLessThanOrEqual(layout.viewport)
  await page.locator('#baseline-panel').screenshot({ path: testInfo.outputPath('baseline-dark-mobile.png') })
  await page.getByRole('button', { name: 'Clear baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toHaveCount(0)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
})

test('selects the middle real run statistically and does not replace it after another run', async ({ page }) => {
  await page.locator('#baseline-panel summary').click()
  await analyze(page, 'stat-fast.jtl', 100, 1767225700000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  const middle = await analyze(page, 'stat-middle.jtl', 110, 1767225701000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  await page.getByLabel('Same planned test conditions', { exact: true }).check()
  await analyze(page, 'stat-slow.jtl', 1000, 1767225702000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  await expect(page.getByLabel('Same planned test conditions', { exact: true })).not.toBeChecked()
  await expect(page.getByRole('button', { name: 'Select statistically', exact: true })).toBeDisabled()
  await page.getByLabel('Same planned test conditions', { exact: true }).check()
  await page.getByRole('button', { name: 'Select statistically', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
  await expect(page.getByTestId('baseline-selection')).toContainText('statistical')
  await expect(page.getByTestId('baseline-selection')).toContainText('median-rank-v1')
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText('USER_CONFIRMED')
  await expect(page.getByTestId('comparison-response_time_p95_ms').locator('td').nth(3)).toHaveText('890')
  await analyze(page, 'stat-new.jtl', 2000, 1767225703000)
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
  await expect(page.getByTestId('baseline-candidates').locator('li')).toHaveCount(3)
  await page.reload()
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
})

test('failed replacement keeps the last confirmed baseline visible', async ({ page }) => {
  const original = await analyze(page, 'baseline-keep.jtl', 50, 1767225800000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(original.analysis_id)
  await analyze(page, 'baseline-rejected.jtl', 60, 1767225801000)
  await page.route('**/api/baseline', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    await route.fulfill({ status: 403, json: { error: { code: 'FORBIDDEN', message: 'Replacement was rejected', details: [] } } })
  })
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.locator('#baseline-panel [role="alert"]')).toContainText('Replacement was rejected')
  await expect(page.getByTestId('baseline-selection')).toContainText(original.analysis_id)
})

test('ignores a comparison response after selecting another run', async ({ page }) => {
  await analyze(page, 'baseline-stale.jtl', 70, 1767225900000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText('manual')
  let started = false
  let finished = false
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  await page.route('**/comparison', async (route) => {
    const response = await route.fetch()
    started = true
    await held
    await route.fulfill({ response })
    finished = true
  })
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await page.getByRole('button', { name: 'baseline-stale.jtl' }).click()
  release()
  await expect.poll(() => finished).toBe(true)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
})
