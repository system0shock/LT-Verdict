import { expect, test } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))

test('loads a saved analysis after reload without creating a job', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.locator('#verdict')).toBeVisible()
  const verdict = await page.locator('#verdict h2').innerText()
  const validity = await page.getByText('Run validity', { exact: true }).locator('..').locator('dd').innerText()
  let jobRequests = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobRequests += 1
  })

  await page.reload()
  await page.getByRole('button', { name: 'input.xml' }).click()
  await page.getByRole('button', { name: /analysis/i }).first().click()

  await expect(page.locator('#verdict h2')).toHaveText(verdict)
  await expect(page.getByText('Run validity', { exact: true }).locator('..').locator('dd')).toHaveText(validity)
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
  await page.getByRole('button', { name: 'Analyze run' }).click()
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

  await page.getByRole('button', { name: 'Refresh data' }).click()
  await expect.poll(() => firstStarted).toBe(true)
  await page.getByRole('button', { name: 'Refresh data' }).click()
  await expect(page.getByRole('alert')).toContainText('Latest bucket request failed')
  releaseFirst?.()
  await expect.poll(() => firstFinished).toBe(true)
  await expect(page.getByText('999 ms', { exact: true })).toHaveCount(0)
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
  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.locator('#normalized-data')).toBeVisible()
  await expect(page.getByRole('img', { name: 'Requests per second' })).toBeVisible()
  await expect(page.getByRole('img', { name: 'Errors per bin' })).toBeVisible()
  await expect(page.getByRole('img', { name: 'P95 latency' })).toBeVisible()
  await expect(page.locator('#normalized-data svg polyline')).toHaveCount(6)
  await expect(page.locator('#normalized-data svg circle')).toHaveCount(6)
})
