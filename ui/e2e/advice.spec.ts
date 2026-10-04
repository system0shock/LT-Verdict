import { expect, test } from '@playwright/test'

test('AI starts only on click, sends no consent and renders stored model text without HTML execution', async ({ page }) => {
  let submitted = 0
  let complete = false
  let reference = { run_id: '', analysis_id: '' }
  const status = () => ({ job_id: 'fixture-advice', ...reference, state: complete ? 'COMPLETE' : 'PROCESSING', reused: false, failure: null, unavailable_reason: null })
  const malicious = '<img src=x onerror=alert(1)>'
  await page.route('**/api/runs/*/analyses/*/advice', async (route) => {
    const segments = new URL(route.request().url()).pathname.split('/')
    reference = { run_id: segments[3]!, analysis_id: segments[5]! }
    if (route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({})
      submitted++
      await route.fulfill({ status: 202, json: status() })
    } else {
      await route.fulfill({ json: {
        job: submitted ? status() : null,
        advice: complete ? { advisory: true, ...reference, output: {
          summary: malicious,
          hypotheses: [{ rank: 1, observation: 'Наблюдение', possible_explanation: 'Непроверенная гипотеза', recommended_check: 'Проверить метрики', evidence_refs: ['analysis-result.json#/evidence/0'] }],
          recommendations: [], caveats: ['Требуется проверка'],
        } } : null,
      } })
    }
  })
  await page.route('**/api/advice-jobs/fixture-advice', async (route) => {
    complete = true
    await route.fulfill({ json: status() })
  })
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'advice-fixture.jtl', mimeType: 'text/csv', buffer: Buffer.from('timeStamp,elapsed,label,success\n1767225600000,100,advice-test,true\n') })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  const start = page.getByRole('button', { name: 'Получить рекомендации', exact: true })
  await expect(start).toBeEnabled()
  await expect(page.locator('section[aria-labelledby="advice-title"]').getByRole('checkbox')).toHaveCount(0)
  expect(submitted).toBe(0)
  await start.click()
  await expect(page.getByText(malicious, { exact: true })).toBeVisible({ timeout: 15000 })
  await expect(page.locator('img[src=x]')).toHaveCount(0)
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  expect(submitted).toBe(1)
})
