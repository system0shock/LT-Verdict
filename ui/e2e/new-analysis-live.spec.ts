import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { SETUP_LABELS } from '../src/shell/labels'

// Сверка с настоящим сервером: запуск из нового экрана даёт тот же результат, что запуск из прежней формы.
const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const input = fixture('slice1/jmeter/xml-5.6.3/input.xml')
const policy = fixture('slice1/policies/fail.json')
// The list is newest accepted first and the server's data is shared by the whole suite, so the run is named by its content, not by position.
const inputRunId = `jmeter_jtl_xml-${createHash('sha256').update(readFileSync(input)).digest('hex')}`

async function storedResults(page: Page) {
  return page.evaluate(async (runId) => {
    const { analyses } = await fetch(`/api/runs/${runId}/analyses`).then((response) => response.json())
    const results = await Promise.all((analyses as Array<{ analysis_id: string }>).map((analysis) =>
      fetch(`/api/runs/${runId}/analyses/${analysis.analysis_id}/result`).then((response) => response.json())))
    return { runId, results }
  }, inputRunId)
}

test('the new setup screen runs the same analysis as the old form on a real server', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(input)
  await page.getByTestId('policy-file').setInputFiles(policy)
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.locator('#verdict')).toBeVisible()
  const oldChip = await page.getByTestId('verdict-chip').textContent()
  const before = await storedResults(page)

  await page.goto('/?shell=new')
  await page.locator('#input-file').setInputFiles(input)
  await page.locator('#policy-file').setInputFiles(policy)
  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toHaveAttribute('data-level', 'ok')
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.locator('#shell-tab-overview')).toHaveAttribute('aria-selected', 'true')
  const newChip = await page.getByTestId('verdict-chip').textContent()
  const after = await storedResults(page)

  expect(newChip).toBe(oldChip)
  expect(after.runId).toBe(before.runId)
  expect(after.results).toEqual(before.results)
  expect(after.results.length).toBeGreaterThan(0)
})

test('the new screen starts a load-file-only analysis and reports NO_POLICY in the readiness block', async ({ page }) => {
  await page.goto('/?shell=new')
  await page.locator('#input-file').setInputFiles(input)

  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toContainText(SETUP_LABELS.policyNoneItem)
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.getByTestId('verdict-chip')).toContainText('NO_POLICY')
})
