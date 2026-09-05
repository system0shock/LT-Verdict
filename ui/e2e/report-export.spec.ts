import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'

test('downloads exact JSON and a safe offline HTML report without another job', async ({ page, context }, testInfo) => {
  const label = '</pre><script>alert(1)</script><img src="https://example.invalid/image" onerror="alert(2)">&'
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({
    name: 'report-escaping.jtl',
    mimeType: 'text/csv',
    buffer: Buffer.from(`timeStamp,elapsed,label,success\n1767225600000,20,"${label.replaceAll('"', '""')}",true\n`),
  })
  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  let jobs = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobs += 1
  })

  const jsonLink = page.getByRole('link', { name: 'Download JSON' })
  const href = await jsonLink.getAttribute('href')
  expect(href).toMatch(/^\/api\/runs\/[^/]+\/analyses\/[a-f0-9]{64}\/report\?format=json$/)
  const result = await context.request.get(href!.replace('/report?format=json', '/result'))
  const jsonDownload = page.waitForEvent('download')
  await jsonLink.click()
  const json = await jsonDownload
  expect(json.suggestedFilename()).toMatch(/^lt-verdict-[a-f0-9]{64}\.json$/)
  expect(await readFile((await json.path())!)).toEqual(await result.body())

  const htmlDownload = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Download HTML' }).click()
  const html = await htmlDownload
  expect(html.suggestedFilename()).toMatch(/^lt-verdict-[a-f0-9]{64}\.html$/)
  const htmlPath = testInfo.outputPath('report.html')
  await html.saveAs(htmlPath)
  const reportPage = await context.newPage()
  const network: string[] = []
  const dialogs: string[] = []
  reportPage.on('request', (request) => {
    if (/^https?:/.test(request.url())) network.push(request.url())
  })
  reportPage.on('dialog', async (dialog) => {
    dialogs.push(dialog.message())
    await dialog.dismiss()
  })
  await reportPage.goto(pathToFileURL(htmlPath).href)
  await expect(reportPage.getByRole('heading', { name: 'LT Verdict report' })).toBeVisible()
  await expect(reportPage.locator('body')).toContainText('NO_POLICY')
  await expect(reportPage.locator('body')).toContainText(label)
  await expect(reportPage.locator('script, img, form, base')).toHaveCount(0)
  await expect(reportPage.locator('body')).toHaveCSS('color', 'rgb(23, 32, 51)')
  expect(network).toEqual([])
  expect(dialogs).toEqual([])
  expect(jobs).toBe(0)
})
