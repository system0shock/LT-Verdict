import { expect, test, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { SETUP_LABELS } from '../src/shell/labels'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const input = fixture('slice1/jmeter/xml-5.6.3/input.xml')
const policy = fixture('slice1/policies/fail.json')
const number = (text: string) => Number(text.replace(/\s/g, '').replace(',', '.').match(/-?\d+(?:\.\d+)?/)?.[0])

async function analyzeInNewShell(page: Page) {
  await page.goto('/?shell=new')
  await page.getByTestId('input-file').setInputFiles(input)
  await page.getByTestId('policy-file').setInputFiles(policy)
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
}

test('new tables match the old interface on a real server', async ({ page }) => {
  await analyzeInNewShell(page)
  await page.locator('#shell-tab-tables').click()
  const newRules = page.getByTestId('rule-row')
  const newTransactions = page.getByTestId('tx-row')
  const ruleCount = await newRules.count()
  const transactionCount = await newTransactions.count()
  const rule = newRules.filter({ hasText: 'overall-errors' })
  await expect(rule).toHaveCount(1)
  const newThreshold = number(await rule.locator('td').nth(2).innerText())
  const newObserved = number(await rule.locator('td').nth(3).innerText())

  await page.goto('/?shell=old')
  await page.getByRole('button', { name: 'input.xml' }).click()
  await page.locator('section[aria-labelledby="analysis-list-title"] li button').first().click()
  await expect(page.locator('#policy-results').getByRole('region', { name: 'Policy results' })).toBeVisible()
  const oldRules = page.locator('#policy-results tbody tr')
  const oldTransactions = page.locator('#transaction-metrics tbody tr')
  await expect(oldRules).toHaveCount(ruleCount)
  await expect(oldTransactions).toHaveCount(transactionCount)
  await expect(oldRules).toHaveCount(1)
  const oldRule = oldRules.first()
  const oldThreshold = number(await oldRule.locator('td').nth(4).innerText())
  const oldObserved = number(await oldRule.locator('td').nth(5).innerText())
  expect(newThreshold).toBeCloseTo(oldThreshold, 1)
  expect(newObserved).toBeCloseTo(oldObserved, 1)
})
