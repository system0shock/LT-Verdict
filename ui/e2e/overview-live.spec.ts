import { expect, test, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'

// Сверка с настоящим сервером: числа вкладки «Обзор» новой оболочки совпадают с числами прежнего интерфейса.
const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const input = fixture('slice1/jmeter/xml-5.6.3/input.xml')
const policy = fixture('slice1/policies/fail.json')
const number = (text: string | null) => Number((text ?? '').replace(/[^0-9.]/g, ''))

async function analyzeInNewShell(page: Page) {
  await page.goto('/?shell=new')
  await page.getByTestId('input-file').setInputFiles(input)
  await page.getByTestId('policy-file').setInputFiles(policy)
  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
}

test('overview numbers equal the old interface on a real server', async ({ page }) => {
  await analyzeInNewShell(page)

  const tiles = Object.fromEntries(await page.getByTestId('metric-tile').evaluateAll((nodes) => nodes.map((node) => [node.getAttribute('data-metric'), node.getAttribute('data-value')])))
  const slider = page.getByTestId('chart-cursor')
  await expect(slider).toBeVisible()
  const count = Number(await slider.getAttribute('max')) + 1
  const readAt = async (key: 'Home' | 'End') => {
    await slider.focus()
    await page.keyboard.press(key)
    return Promise.all(['rps', 'errors', 'p95'].map((track) => page.getByTestId(`track-value-${track}`).getAttribute('data-value')))
  }
  const first = await readAt('Home')
  const last = await readAt('End')
  const attention = await page.getByTestId('attention-item').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-kind')))

  await page.goto('/')
  await page.getByRole('button', { name: 'input.xml' }).click()
  await page.locator('section[aria-labelledby="analysis-list-title"] li button').first().click()
  await expect(page.locator('#summary-metrics')).toBeVisible()
  const card = (name: string) => page.locator('#summary-metrics .metric-card', { has: page.getByText(name, { exact: true }) }).locator('strong')
  expect(number(await card('P95').textContent())).toBe(Number(tiles.p95))
  expect(number(await card('P99').textContent())).toBe(Number(tiles.p99))
  expect(number(await card('Throughput').textContent())).toBe(Number(tiles.rps))

  const rows = page.locator('#normalized-data tbody tr[data-status="available"]')
  await expect(rows.first()).toBeVisible()
  expect(await rows.count()).toBe(count)
  const cells = async (row: ReturnType<typeof rows.nth>) => (await row.locator('td').allTextContents()).map(number)
  const firstRow = await cells(rows.first())
  const lastRow = await cells(rows.last())
  expect([Number(first[0]), Number(first[1]), Number(first[2])]).toEqual([firstRow[1], firstRow[2], firstRow[3]])
  expect([Number(last[0]), Number(last[1]), Number(last[2])]).toEqual([lastRow[1], lastRow[2], lastRow[3]])
  expect(attention).toContain('violation')
})
