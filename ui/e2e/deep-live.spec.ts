import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { DEEP_LABELS } from '../src/shell/labels'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
type OracleSeries = { id: string; values: Array<number | null>; observed?: number[] }
type OraclePage = { grid: { first_cell_start_ms: number; step_ms: number }; series: OracleSeries[] }
const expected = JSON.parse(readFileSync(fixture('resource-series/live-expected.json'), 'utf8')) as {
  start_epoch_ms: number; step_ms: number; point_count: number; ids: string[]; full: OraclePage; narrow: OraclePage
}
const csv = Buffer.from('timeStamp,elapsed,label,success\n' + Array.from({ length: 3000 }, (_, i) => `${expected.start_epoch_ms + i * 1000},100,checkout,true\n`).join(''))
const snapshot = JSON.parse(readFileSync(fixture('resource-series/live-snapshot.template.json'), 'utf8')) as Record<string, unknown>
snapshot.load_input_sha256 = createHash('sha256').update(csv).digest('hex')

async function assertResources(page: Page, oracle: OraclePage) {
  const epoch = Number(await page.getByTestId('deep-cursor-time').getAttribute('data-epoch-ms'))
  const index = Math.floor((epoch - oracle.grid.first_cell_start_ms) / oracle.grid.step_ms)
  expect(index).toBeGreaterThanOrEqual(0)
  for (const id of expected.ids) {
    const value = oracle.series.find((series) => series.id === id)!.values[index]
    const raw = await page.getByTestId(`track-value-${id}`).getAttribute('data-value')
    if (value === null) {
      expect(raw).toBe('')
      await expect(page.getByTestId(`track-value-${id}`)).toHaveText(DEEP_LABELS.gap)
    } else expect(Number(raw)).toBe(value)
  }
}

async function moveTo(page: Page, position: 'Home' | 'End' | number, testId = 'deep-cursor') {
  const slider = page.getByTestId(testId)
  if (typeof position === 'number') {
    await slider.evaluate((node, value) => {
      const input = node as HTMLInputElement
      input.value = String(value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }, position)
  } else {
    await slider.focus()
    await page.keyboard.press(position)
  }
}

test('deep cursor matches the independent resource-series oracle on a real server', async ({ page }) => {
  await page.goto('/?shell=new')
  await page.getByTestId('input-file').setInputFiles({ name: 'deep-live.csv', mimeType: 'text/csv', buffer: csv })
  await page.getByTestId('resource-snapshot-file').setInputFiles({ name: 'deep-live-snapshot.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(snapshot)) })
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible({ timeout: 120_000 })
  await page.locator('#shell-tab-deep').click()
  await expect(page.getByTestId('deep-panel')).toBeVisible()
  await expect(page.getByTestId('deep-step-note')).toHaveText(DEEP_LABELS.cellStep(3))
  for (const id of expected.ids) {
    const checkbox = page.getByTestId(`deep-series-${id}`)
    await expect(checkbox).toBeVisible()
    if (!await checkbox.isChecked()) await checkbox.check()
    await expect(page.getByTestId(`deep-track-${id}`)).toBeVisible()
  }
  const slider = page.getByTestId('deep-cursor')
  const full = expected.full.grid
  const lastIndex = full.cell_count - 1
  const middle = Math.floor(lastIndex / 2)
  expect(Number(await slider.getAttribute('max'))).toBe(lastIndex)
  for (const [position, index] of [['Home', 0], [middle, middle], ['End', lastIndex]] as const) {
    await moveTo(page, position)
    await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(full.first_cell_start_ms + index * full.step_ms))
    await assertResources(page, expected.full)
  }

  await page.getByTestId('deep-period-from').fill('5')
  await page.getByTestId('deep-period-to').fill('5.2')
  await page.getByTestId('deep-period-apply').click()
  await expect(page.getByTestId('deep-step-note')).toHaveText(DEEP_LABELS.cellStep(1))
  await expect(page.getByTestId('deep-panel').getByRole('status')).toHaveCount(0)
  const narrow = expected.narrow.grid
  const narrowLast = narrow.cell_count - 1
  expect(Number(await slider.getAttribute('max'))).toBe(narrowLast)
  const narrowMiddle = Math.floor(narrowLast / 2)
  for (const [position, index] of [['Home', 0], [narrowMiddle, narrowMiddle], ['End', narrowLast]] as const) {
    await moveTo(page, position)
    await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(narrow.first_cell_start_ms + index * narrow.step_ms))
    await assertResources(page, expected.narrow)
  }

  // Load at the first narrow cell (1 s buckets) must equal the overview value of the same second.
  await moveTo(page, 'Home')
  const relativeSecond = (narrow.first_cell_start_ms - expected.start_epoch_ms) / 1000
  const deepLoad = await Promise.all(['load-rps', 'load-p95'].map((key) => page.getByTestId(`track-value-${key}`).getAttribute('data-value')))
  await page.locator('#shell-tab-overview').click()
  await moveTo(page, relativeSecond, 'chart-cursor')
  const overviewLoad = await Promise.all(['rps', 'p95'].map((key) => page.getByTestId(`track-value-${key}`).getAttribute('data-value')))
  expect(Number(deepLoad[0])).toBe(Number(overviewLoad[0]))
  expect(Number(deepLoad[1])).toBe(Number(overviewLoad[1]))
  expect(Number(deepLoad[0])).toBe(1)
})
