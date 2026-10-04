import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'
import { RULES_LABELS } from '../src/shell/labels.rules'
import { SETUP_LABELS } from '../src/shell/labels'

const input = fileURLToPath(new URL('../../fixtures/slice1/jmeter/csv-5.6.3/input.jtl', import.meta.url))
const header = readFileSync(input, 'utf8').split(/\r?\n/, 1)[0]
const labels = [...Array(30).fill('GET /a'), ...Array(25).fill('POST /b'), ...Array(5).fill('GET /rare')]
const jtl = Buffer.from([header, ...labels.map((label, index) =>
  `${1788211917499 + index * 1000},100,${label},200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0`)].join('\n') + '\n')

test('live rules template and transaction expansion validate and affect the next analysis', async ({ page }) => {
  await page.goto('/?shell=new')
  await page.locator('#shell-tab-rules').click()
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('template-api-basic')

  await page.locator('#shell-tab-setup').click()
  await page.getByTestId('input-file').setInputFiles({ name: 'rules.jtl', mimeType: 'text/csv', buffer: jtl })
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.locator('#verdict')).not.toHaveAttribute('data-verdict', 'NO_POLICY')

  await page.locator('#shell-tab-rules').click()
  const validation = page.waitForResponse((response) => response.url().endsWith('/api/policies/validate') && response.request().method() === 'POST')
  await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxSkippedSmall(20, RULES_LABELS.perTxList([{ label: 'GET /rare', sampleCount: 5 }], true)))
  const body = await (await validation).json() as { valid: boolean; policy: { rules: Array<{ scope: { kind: string; name?: string } }> } }
  expect(body.valid).toBe(true)
  expect(body.policy.rules.filter((rule) => rule.scope.kind === 'transaction').map((rule) => rule.scope.name).sort()).toEqual(['GET /a', 'GET /a', 'POST /b', 'POST /b'])

  await page.locator('#shell-tab-setup').click()
  await page.getByTestId('input-file').setInputFiles({ name: 'rules.jtl', mimeType: 'text/csv', buffer: jtl })
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.locator('#verdict')).not.toHaveAttribute('data-verdict', 'NO_VERDICT')
})
