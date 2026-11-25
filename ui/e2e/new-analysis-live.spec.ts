import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { SETUP_LABELS } from '../src/shell/labels'

// Сверка с настоящим сервером: запуск анализа с политикой и без политики сохраняет результат.
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

test('runs an analysis with policy on a real server and stores the FAIL result', async ({ page }) => {
  await page.goto('/')
  await page.locator('#input-file').setInputFiles(input)
  await page.locator('#policy-file').setInputFiles(policy)
  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toHaveAttribute('data-level', 'ok')
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.locator('#shell-tab-overview')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByTestId('verdict-chip')).toContainText('FAIL')

  const stored = await storedResults(page) as { runId: string; results: Array<{ policy_verdict: string }> }
  expect(stored.runId).toBe(inputRunId)
  expect(stored.results.length).toBeGreaterThan(0)
  expect(stored.results.some((item) => item.policy_verdict === 'FAIL')).toBe(true)
})

test('starts a load-file-only analysis and reports NO_POLICY in the readiness block', async ({ page }) => {
  await page.goto('/')
  await page.locator('#input-file').setInputFiles(input)

  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toContainText(SETUP_LABELS.policyNoneItem)
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.getByTestId('verdict-chip')).toContainText('NO_POLICY')
})
