import { expect, test } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))

test('loads a saved analysis after reload without creating a job', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.locator('#verdict')).toBeVisible()
  const verdict = await page.locator('#verdict').innerText()
  let jobRequests = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobRequests += 1
  })

  await page.reload()
  await page.getByRole('button', { name: 'input.xml' }).click()
  await page.getByRole('button', { name: /analysis/i }).first().click()

  await expect(page.locator('#verdict')).toContainText(verdict.split('\n')[0])
  expect(jobRequests).toBe(0)
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
