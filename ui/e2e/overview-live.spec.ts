import { expect, test, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { SETUP_LABELS } from '../src/shell/labels'
import { SUMMARY_METRICS_LABELS } from '../src/shell/labels.tables'

// Сверка с настоящим сервером: числа вкладки «Обзор» совпадают с таблицами и нормализованными интервалами.
const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const input = fixture('slice1/jmeter/xml-5.6.3/input.xml')
const policy = fixture('slice1/policies/fail.json')
const number = (text: string | null) => Number((text ?? '').replace(/\s/g, '').replace(',', '.').replace(/[^0-9.-]/g, ''))

async function analyzeInDefaultShell(page: Page) {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(input)
  await page.getByTestId('policy-file').setInputFiles(policy)
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
}

test('overview numbers equal summary metrics and normalized buckets on a real server', async ({ page }) => {
  await analyzeInDefaultShell(page)

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
  const incidents = await page.getByTestId('incident-card').evaluateAll((nodes) => nodes.map((node) => ({
    priority: node.getAttribute('data-priority'),
    family: node.getAttribute('data-family'),
  })))
  const attention = await page.getByTestId('attention-item').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-kind')))

  await page.locator('#shell-tab-tables').click()
  await expect(page.locator('#summary-metrics')).toBeVisible()
  const card = (name: string) => page.locator('#summary-metrics .metric-card', { has: page.getByText(name, { exact: true }) }).locator('strong')
  expect(number(await card(SUMMARY_METRICS_LABELS.p95).textContent())).toBe(Number(tiles.p95))
  expect(number(await card(SUMMARY_METRICS_LABELS.p99).textContent())).toBe(Number(tiles.p99))
  expect(number(await card(SUMMARY_METRICS_LABELS.throughput).textContent())).toBe(Number(tiles.rps))

  const rows = page.locator('#normalized-data tbody tr[data-status="available"]')
  await expect(rows.first()).toBeVisible()
  expect(await rows.count()).toBe(count)
  const cells = async (row: ReturnType<typeof rows.nth>) => (await row.locator('td').allTextContents()).map(number)
  const firstRow = await cells(rows.first())
  const lastRow = await cells(rows.last())
  expect([Number(first[0]), Number(first[1]), Number(first[2])]).toEqual([firstRow[1], firstRow[2], firstRow[3]])
  expect([Number(last[0]), Number(last[1]), Number(last[2])]).toEqual([lastRow[1], lastRow[2], lastRow[3]])
  expect(incidents.length).toBeGreaterThan(0)
  expect(incidents[0].priority).toBe('HIGH')
  expect(attention).not.toContain('violation')
})
