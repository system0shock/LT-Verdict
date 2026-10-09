import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { EXPORT_LABELS } from '../src/shell/labels.export'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const number = (text: string) => Number(text.replace(/\s/g, '').replace(',', '.').match(/-?\d+(?:\.\d+)?/)?.[0])

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

  const asciidocDownload = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Download AsciiDoc' }).click()
  const asciidoc = await asciidocDownload
  expect(asciidoc.suggestedFilename()).toMatch(/^lt-verdict-[a-f0-9]{64}\.adoc$/)
  const asciidocText = await readFile((await asciidoc.path())!, 'utf8')
  expect(asciidocText).toContain('= LT Verdict report')
  expect(asciidocText).toContain('Analysis ID')

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
  await expect(reportPage.getByRole('heading', { name: 'Отчёт LT Verdict' })).toBeVisible()
  await expect(reportPage.locator('body')).toContainText('NO_POLICY')
  await expect(reportPage.locator('body')).toContainText(label)
  await expect(reportPage.locator('script, img, form, base')).toHaveCount(0)
  await expect(reportPage.locator('body')).toHaveCSS('color', 'rgb(23, 32, 51)')
  // The machine tail sits in the closed appendix; its blocks open from the keyboard.
  await expect(reportPage.getByRole('heading', { name: 'Приложение' })).toBeVisible()
  const rawJson = reportPage.locator('details').filter({ hasText: 'Канонический JSON' })
  await expect(rawJson).not.toHaveAttribute('open', '')
  const rawSummary = rawJson.locator('summary')
  for (let i = 0; i < 60 && !(await rawSummary.evaluate((el) => el === document.activeElement)); i++) await reportPage.keyboard.press('Tab')
  await expect(rawSummary).toBeFocused()
  await reportPage.keyboard.press('Enter')
  await expect(rawJson).toHaveAttribute('open', '')
  await expect(rawJson.locator('pre')).toBeVisible()
  await reportPage.keyboard.press('Space')
  await expect(rawJson).not.toHaveAttribute('open', '')
  expect(network).toEqual([])
  expect(dialogs).toEqual([])
  expect(jobs).toBe(0)
})

test('the HTML report shows the failed rule with the same measured value as the interface and the core result', async ({ page, context }, testInfo) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
  await page.getByTestId('policy-file').setInputFiles(fixture('slice1/policies/fail.json'))
  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.locator('#verdict')).toContainText('FAIL')
  const interfaceRule = page.locator('#policy-results tbody tr').filter({ hasText: 'FAIL' })
  await expect(interfaceRule).toHaveCount(1)
  const interfaceThreshold = number(await interfaceRule.locator('td').nth(4).innerText())
  const interfaceMeasured = number(await interfaceRule.locator('td').nth(5).innerText())

  const href = (await page.getByRole('link', { name: 'Download HTML' }).getAttribute('href'))!
  const result = await (await context.request.get(href.replace('/report?format=html', '/result'))).json()
  const check = result.evidence.find((item: { type: string; rule_id?: string }) => item.type === 'policy_check' && item.rule_id === 'overall-errors')
  const coreMeasured = (check.observed.numerator / check.observed.denominator) * 100

  const download = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Download HTML' }).click()
  const htmlPath = testInfo.outputPath('report-fail.html')
  await (await download).saveAs(htmlPath)
  const reportPage = await context.newPage()
  const network: string[] = []
  reportPage.on('request', (request) => {
    if (/^https?:/.test(request.url())) network.push(request.url())
  })
  await reportPage.goto(pathToFileURL(htmlPath).href)
  await expect(reportPage.locator('html')).toHaveAttribute('lang', 'ru')
  await expect(reportPage.getByRole('heading', { name: 'Отчёт LT Verdict' })).toBeVisible()
  await expect(reportPage.getByRole('heading', { name: 'Вердикт и причины' })).toBeVisible()
  await expect(reportPage.getByRole('columnheader', { name: 'Порог' })).toBeVisible()
  const rule = reportPage.locator('tr').filter({ hasText: 'overall-errors' })
  await expect(rule).toHaveCount(1)
  await expect(rule).toContainText('Нарушение')
  const reportThreshold = number(await rule.locator('td').nth(4).innerText())
  const reportMeasured = number(await rule.locator('td').nth(5).innerText())
  expect(reportThreshold).toBe(interfaceThreshold)
  expect(reportMeasured).toBe(interfaceMeasured)
  expect(reportMeasured).toBeCloseTo(coreMeasured, 2)
  await expect(reportPage.locator('script, img, form, base')).toHaveCount(0)
  expect(network).toEqual([])
})

test('the header export of the new interface downloads exactly the HTML report of the open analysis', async ({ page, context }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/xml-5.6.3/input.xml'))
  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  const href = (await page.getByRole('link', { name: 'Download HTML' }).getAttribute('href'))!

  await page.goto('/?shell=new')
  await expect(page.getByRole('link', { name: EXPORT_LABELS.html })).toHaveCount(0)
  const [, runId, analysisId] = href.match(/^\/api\/runs\/([^/]+)\/analyses\/([a-f0-9]{64})\/report\?format=html$/)!
  await page.getByTestId('run-list').locator(`li[title="${runId}"] button`).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  const exportLink = page.getByRole('link', { name: EXPORT_LABELS.html })
  await expect(exportLink).toHaveAttribute('href', href)
  await expect(exportLink).toHaveAttribute('download', '')

  const expected = await context.request.get(href)
  const download = page.waitForEvent('download')
  await exportLink.click()
  const file = await download
  expect(file.suggestedFilename()).toMatch(/^lt-verdict-[a-f0-9]{64}\.html$/)
  expect(await readFile((await file.path())!)).toEqual(await expected.body())
})
