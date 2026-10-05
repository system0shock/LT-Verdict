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
const jtlNext = Buffer.from([header, ...Array.from({ length: 30 }, (_, index) =>
  `${1788211917499 + index * 1000},100,GET /c,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0`)].join('\n') + '\n')

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
  // The expanded rules really reached the core: 2 overall + 4 transaction rule checks.
  await page.locator('#shell-tab-tables').click()
  await expect(page.getByTestId('rule-row')).toHaveCount(6)

  await page.locator('#shell-tab-setup').click()
  await page.getByTestId('input-file').setInputFiles({ name: 'rules-next.jtl', mimeType: 'text/csv', buffer: jtlNext })
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.locator('#verdict')).toHaveAttribute('data-verdict', 'NO_VERDICT')

  await page.locator('#shell-tab-rules').click()
  const nextValidation = page.waitForResponse((response) => response.url().endsWith('/api/policies/validate') && response.request().method() === 'POST')
  await page.getByRole('button', { name: RULES_LABELS.perTxButton }).click()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.perTxDone(2, 'rules-next.jtl', 4))
  const nextBody = await (await nextValidation).json() as { valid: boolean; policy: { rules: Array<{ scope: { kind: string; name?: string } }> } }
  expect(nextBody.valid).toBe(true)
  expect(nextBody.policy.rules.filter((rule) => rule.scope.kind === 'transaction').map((rule) => rule.scope.name).sort()).toEqual(['GET /c', 'GET /c'])

  await page.locator('#shell-tab-setup').click()
  await page.getByTestId('input-file').setInputFiles({ name: 'rules-next.jtl', mimeType: 'text/csv', buffer: jtlNext })
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.locator('#verdict')).not.toHaveAttribute('data-verdict', 'NO_VERDICT')
  await page.locator('#shell-tab-tables').click()
  await expect(page.getByTestId('rule-row')).toHaveCount(4)
})

test('live trial run applies a draft policy to the open run through the core without uploading again', async ({ page }) => {
  const policyDir = fileURLToPath(new URL('../../fixtures/slice1/policies/', import.meta.url))
  // A unique input: the e2e server shares one data directory across tests, so analysis counts must not depend on other specs.
  const unique = Date.now()
  const rows = Array.from({ length: 30 }, (_, index) =>
    `${1788211917499 + index * 1000},50,GET /trial-${unique},${index < 6 ? 500 : 200},${index < 6 ? 'Err' : 'OK'},fixture 1-1,text,${index < 6 ? 'false' : 'true'},,0,0,1,1,null,0,0,0`)
  const trialJtl = Buffer.from([header, ...rows].join('\n') + '\n')
  const inputs: string[] = []
  page.on('request', (request) => { if (request.method() === 'POST' && request.url().endsWith('/api/inputs')) inputs.push(request.url()) })
  await page.goto('/?shell=new')
  const upload = page.waitForResponse((response) => response.url().endsWith('/api/inputs') && response.request().method() === 'POST')
  await page.getByTestId('input-file').setInputFiles({ name: 'trial.jtl', mimeType: 'text/csv', buffer: trialJtl })
  await page.getByRole('button', { name: SETUP_LABELS.startButton }).click()
  await expect(page.locator('#verdict')).toHaveAttribute('data-verdict', 'NO_POLICY')
  const runId = ((await (await upload).json()) as { run_id: string }).run_id
  expect(inputs.length).toBe(1)

  const analysesCount = () => page.evaluate(async (id) => ((await (await fetch(`/api/runs/${id}/analyses?limit=50`)).json()) as { analyses: unknown[] }).analyses.length, runId)
  expect(await analysesCount()).toBe(1)
  await page.locator('#shell-tab-rules').click()
  const trial = page.getByRole('button', { name: RULES_LABELS.trialButton })
  await page.getByTestId('rules-policy-file').setInputFiles(`${policyDir}fail.json`)
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('slice1-fail')
  await trial.click()
  await expect(page.getByTestId('trial-summary')).toContainText('Прогон не проходит')
  await expect(page.locator('#shell-tab-rules')).toHaveAttribute('aria-selected', 'true')
  expect(await analysesCount()).toBe(2)

  await page.getByTestId('rules-policy-file').setInputFiles(`${policyDir}pass.json`)
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('slice1-pass')
  await trial.click()
  await expect(page.getByTestId('trial-summary')).toContainText('Прогон проходит')
  expect(await analysesCount()).toBe(3)

  // Same policy and same input again: the server returns the existing analysis, nothing new is stored.
  await page.getByTestId('rules-policy-file').setInputFiles(`${policyDir}fail.json`)
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('slice1-fail')
  await trial.click()
  await expect(page.getByTestId('trial-summary')).toContainText('Прогон не проходит')
  expect(await analysesCount()).toBe(3)

  await page.getByRole('button', { name: RULES_LABELS.openOverview }).click()
  await expect(page.locator('#verdict')).toHaveAttribute('data-verdict', 'FAIL')
  expect(inputs.length).toBe(1)
})

test('live server validates the sample minimum fields of the editor', async ({ page }) => {
  await page.goto('/?shell=new')
  await page.locator('#shell-tab-rules').click()
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-throughput') }).click()
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('template-api-throughput')
  // Match the validation of this very edit: the validation of the template may still be in flight.
  type Draft = { defaults?: { sample_floor?: number }; rules: Array<{ min_samples?: number }> }
  const validated = (matches: (draft: Draft) => boolean) => page.waitForResponse((response) =>
    response.url().endsWith('/api/policies/validate') && response.request().method() === 'POST' && matches(response.request().postDataJSON() as Draft))
  type Validation = { valid: boolean; policy?: { defaults?: Record<string, number>; rules: Array<{ min_samples?: number }> }; errors?: Array<{ code: string; json_pointer: string }> }

  // The throughput rule (index 2) has no field: the core would answer FIELD_NOT_APPLICABLE.
  await expect(page.locator('#rule-min-samples-2')).toHaveCount(0)
  let response = validated((draft) => draft.rules[0].min_samples === 50)
  await page.locator('#rule-min-samples-0').fill('50')
  let body = await (await response).json() as Validation
  expect(body.valid).toBe(true)
  expect(body.policy?.rules[0].min_samples).toBe(50)

  response = validated((draft) => draft.rules[0].min_samples === 0)
  await page.locator('#rule-min-samples-0').fill('0')
  body = await (await response).json() as Validation
  expect(body.valid).toBe(false)
  expect(body.errors).toEqual([expect.objectContaining({ code: 'MIN_SAMPLES_OUT_OF_RANGE', json_pointer: '/rules/0/min_samples' })])
  await expect(page.locator('#rules-panel .field__errors')).toContainText('/rules/0/min_samples')

  response = validated((draft) => draft.rules[0].min_samples === 10)
  await page.locator('#rule-min-samples-0').fill('10')
  body = await (await response).json() as Validation
  expect(body.errors).toEqual([expect.objectContaining({ code: 'MIN_SAMPLES_BELOW_FLOOR', json_pointer: '/rules/0/min_samples' })])

  response = validated((draft) => draft.defaults?.sample_floor === 5)
  await page.getByLabel(RULES_LABELS.samples.floorField).fill('5')
  body = await (await response).json() as Validation
  expect(body.valid).toBe(true)
  expect(body.policy?.defaults).toEqual({ sample_floor: 5 })
  expect(body.policy?.rules[0].min_samples).toBe(10)
})
