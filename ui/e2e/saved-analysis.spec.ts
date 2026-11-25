import { expect, test } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { JOB_LABELS, SHELL_LABELS } from '../src/shell/labels'
import { NORMALIZED_LABELS } from '../src/shell/labels.tables'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))

test('keeps displayed bucket units until a new rollup page is loaded', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({
    name: 'rollup-display.jtl',
    mimeType: 'text/csv',
    buffer: Buffer.from('timeStamp,elapsed,label,success\n1767225600000,0,one,true\n1767225600500,0,two,true\n'),
  })
  await page.getByTestId('start-analysis').click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-tables').click()
  const rps = page.locator('#normalized-data tbody tr[data-status="available"]').first().locator('td').nth(1)
  await expect(rps).toHaveText('2,00')
  await expect(page.getByRole('img', { name: NORMALIZED_LABELS.charts.rpsTitle }).locator('circle')).toHaveCount(1)
  await page.getByLabel(NORMALIZED_LABELS.rollupAria).selectOption('10')
  await expect(rps).toHaveText('2,00')
  await page.getByRole('button', { name: NORMALIZED_LABELS.refresh }).click()
  await expect(rps).toHaveText('0,20')
  await expect(page.getByText(/не более 500 на странице/)).toBeVisible()
  await page.getByRole('button', { name: 'rollup-display.jtl' }).click()
  await expect(page.locator('#job-status')).toHaveCount(0)
})

test('loads a saved analysis after reload without creating a job', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
  await page.getByTestId('start-analysis').click()
  await expect(page.locator('#verdict')).toBeVisible()
  const verdict = await page.locator('#verdict h2').innerText()
  const analysisId = await page.locator('button[aria-pressed="true"][title]').getAttribute('title')
  const validity = await page.getByText('Валидность прогона', { exact: true }).locator('..').locator('dd').innerText()
  let jobRequests = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobRequests += 1
  })

  await page.reload()
  await page.getByRole('button', { name: 'input.xml' }).click()
  await page.locator(`button[title="${analysisId}"]`).click()

  await expect(page.locator('#verdict h2')).toHaveText(verdict)
  await expect(page.getByText('Валидность прогона', { exact: true }).locator('..').locator('dd')).toHaveText(validity)
  expect(jobRequests).toBe(0)
})

test('keeps a newer bucket refresh failure over an older response', async ({ page }) => {
  let requests = 0
  let firstStarted = false
  let firstFinished = false
  let releaseFirst: (() => void) | undefined
  const holdFirst = new Promise<void>((resolve) => (releaseFirst = resolve))

  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
  await page.getByTestId('start-analysis').click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-tables').click()
  await expect(page.locator('#normalized-data')).toBeVisible()
  await page.route(/\/buckets\?/, async (route) => {
    requests += 1
    if (requests === 1) {
      firstStarted = true
      await holdFirst
      await route.fulfill({
        contentType: 'application/json',
        body: '{"buckets":[{"bucket_start_ms":999,"sample_count":99,"error_count":0,"p95_latency_ms":1,"max_latency_ms":1,"hdr_v2_base64":"x"}],"next_from_ms":null}',
      })
      firstFinished = true
      return
    }
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: '{"error":{"code":"TEST_FAILURE","message":"Latest bucket request failed","details":[]}}',
    })
  })

  await page.getByRole('button', { name: NORMALIZED_LABELS.refresh }).click()
  await expect.poll(() => firstStarted).toBe(true)
  await page.getByRole('button', { name: NORMALIZED_LABELS.refresh }).click()
  await expect(page.getByRole('alert')).toContainText('Latest bucket request failed')
  releaseFirst?.()
  await expect.poll(() => firstFinished).toBe(true)
  await expect(page.getByText('999 мс', { exact: true })).toHaveCount(0)
})

for (const status of [200, 500]) {
  test(`ignores superseded completion listing with status ${status}`, async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
    await page.getByTestId('start-analysis').click()
    await expect(page.locator('#verdict')).toBeVisible()
    await page.locator('#shell-tab-tables').click()
    await expect(page.locator('#normalized-data tbody tr').first()).toBeVisible()
    let held = false
    let finished = false
    let release!: () => void
    const pending = new Promise<void>((resolve) => { release = resolve })
    await page.route(/\/analyses\?/, async (route) => {
      if (held) return route.continue()
      held = true
      const response = await route.fetch()
      await pending
      if (status === 200) await route.fulfill({ response })
      else await route.fulfill({ status, json: { error: { code: 'TEST_FAILURE', message: 'Stale completion failed', details: [] } } })
      finished = true
    })
    await page.locator('#shell-tab-setup').click()
    await page.getByTestId('input-file').setInputFiles({
      name: 'other.jtl',
      mimeType: 'text/csv',
      buffer: Buffer.from('timeStamp,elapsed,label,success\n1767225600000,10,other,true\n'),
    })
    await page.getByTestId('start-analysis').click()
    await expect.poll(() => held).toBe(true)
    await expect(page.locator('#verdict')).toBeVisible()
    await page.getByRole('button', { name: 'input.xml' }).click()
    release()
    await expect.poll(() => finished).toBe(true)
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(page.locator('.run-identity')).toContainText('input.xml')
  })
}

test('clears the previous run analyses when a new run is cancelled', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
  await page.getByTestId('start-analysis').click()
  const analyses = page.getByRole('region', { name: SHELL_LABELS.analysesTitle })
  await expect(analyses.getByRole('button', { name: new RegExp(`^${SHELL_LABELS.analysisItem} `) }).first()).toBeVisible()
  await page.locator('#shell-tab-setup').click()
  await page.getByTestId('input-file').setInputFiles({
    name: 'sustained.jtl',
    mimeType: 'text/csv',
    buffer: Buffer.from('timeStamp,elapsed,label,success\n1767225600000,321,cancel-selection,true\n'),
  })
  await page.getByTestId('start-analysis').click()
  await page.getByRole('button', { name: JOB_LABELS.cancel }).click()
  await expect(page.locator('#job-status')).toContainText(JOB_LABELS.states.CANCELLED)
  await expect(analyses.getByRole('button', { name: new RegExp(`^${SHELL_LABELS.analysisItem} `) })).toHaveCount(0)
})

test('renders separate chart segments for missing intervals', async ({ page }) => {
  const header = 'timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect'
  const rows = [
    '1767225600000,20,steady,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0',
    '1767225602000,30,steady,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0',
  ]
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({
    name: 'gapped.jtl',
    mimeType: 'text/csv',
    buffer: Buffer.from(`${header}\n${rows.join('\n')}\n`),
  })
  await page.getByTestId('start-analysis').click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-tables').click()
  await expect(page.locator('#normalized-data')).toBeVisible()
  await expect(page.getByRole('img', { name: NORMALIZED_LABELS.charts.rpsTitle })).toBeVisible()
  await expect(page.getByRole('img', { name: NORMALIZED_LABELS.charts.errorsTitle })).toBeVisible()
  await expect(page.getByRole('img', { name: NORMALIZED_LABELS.charts.p95Title })).toBeVisible()
  await expect(page.locator('#normalized-data svg polyline')).toHaveCount(6)
  await expect(page.locator('#normalized-data svg circle')).toHaveCount(6)
})
