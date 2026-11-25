import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { SETUP_LABELS } from '../src/shell/labels'
import { DOWNLOAD_LABELS } from '../src/shell/labels.export'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const input = fixture('slice1/jmeter/xml-5.6.3/input.xml')
const policy = fixture('slice1/policies/fail.json')
const inputRunId = `jmeter_jtl_xml-${createHash('sha256').update(readFileSync(input)).digest('hex')}`
const number = (text: string) => Number(text.replace(/\s/g, '').replace(',', '.').match(/-?\d+(?:\.\d+)?/)?.[0])

async function analyzeInDefaultShell(page: Page) {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(input)
  await page.getByTestId('policy-file').setInputFiles(policy)
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
}

test('tables match the real-server analysis result', async ({ page }) => {
  await analyzeInDefaultShell(page)
  await page.locator('#shell-tab-tables').click()
  const newRules = page.getByTestId('rule-row')
  const newTransactions = page.getByTestId('tx-row')
  const ruleCount = await newRules.count()
  const transactionCount = await newTransactions.count()
  const rule = newRules.filter({ hasText: 'overall-errors' })
  await expect(rule).toHaveCount(1)
  const newThreshold = number(await rule.locator('td').nth(2).innerText())
  const newObserved = number(await rule.locator('td').nth(3).innerText())

  await page.locator('#shell-tab-history').click()
  const href = await page.getByRole('link', { name: DOWNLOAD_LABELS.format('json'), exact: true }).getAttribute('href')
  const analysisId = href?.match(/\/analyses\/([a-f0-9]{64})\/report/)?.[1]
  expect(analysisId).toBeTruthy()

  const stored = await page.evaluate(async ({ runId, id }) => {
    return fetch(`/api/runs/${runId}/analyses/${id}/result`).then((response) => response.json())
  }, { runId: inputRunId, id: analysisId! }) as { evidence: Array<{ type: string; rule_id?: string; threshold?: number; observed?: { numerator: number; denominator: number }; scope?: { kind?: string } }> }

  const checks = stored.evidence.filter((item) => item.type === 'policy_check')
  const transactions = stored.evidence.filter((item) => item.type === 'metric_summary' && item.scope?.kind === 'transaction')
  expect(ruleCount).toBe(checks.length)
  expect(transactionCount).toBe(transactions.length)
  expect(checks).toHaveLength(1)
  const check = checks[0]!
  expect(newThreshold).toBeCloseTo((check.threshold ?? 0) * 100, 1)
  expect(newObserved).toBeCloseTo(((check.observed!.numerator / check.observed!.denominator) * 100), 1)
})
