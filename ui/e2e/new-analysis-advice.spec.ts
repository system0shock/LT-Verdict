import { expect, test, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { SETUP_LABELS } from '../src/shell/labels'

// Согласие на ИИ-разбор при запуске: анализ идёт на настоящем сервере, запросы совета подменены.
const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const input = fixture('slice1/jmeter/xml-5.6.3/input.xml')
const policy = fixture('slice1/policies/fail.json')

interface AdviceCalls {
  posts: Array<{ analysis_id: string; body: unknown }>
  gets: number
}

async function adviceApi(page: Page): Promise<AdviceCalls> {
  const calls: AdviceCalls = { posts: [], gets: 0 }
  const done = new Set<string>()
  const started = new Set<string>()
  const status = (analysisId: string) => ({ job_id: `advice-${analysisId}`, run_id: 'r', analysis_id: analysisId, state: done.has(analysisId) ? 'COMPLETE' : 'PROCESSING', reused: false, failure: null, unavailable_reason: null })
  await page.route('**/api/runs/*/analyses/*/advice', async (route) => {
    const segments = new URL(route.request().url()).pathname.split('/')
    const analysisId = segments[5]!
    const reference = { run_id: segments[3]!, analysis_id: analysisId }
    if (route.request().method() === 'POST') {
      calls.posts.push({ analysis_id: analysisId, body: route.request().postDataJSON() })
      started.add(analysisId)
      await route.fulfill({ status: 202, json: { ...status(analysisId), ...reference } })
      return
    }
    calls.gets += 1
    await route.fulfill({ json: {
      job: started.has(analysisId) ? { ...status(analysisId), ...reference } : null,
      advice: done.has(analysisId) ? { advisory: true, ...reference, output: {
        summary: 'Сводка из подменённого совета',
        hypotheses: [], recommendations: [], caveats: ['Требуется проверка'],
      } } : null,
    } })
  })
  await page.route('**/api/advice-jobs/*', async (route) => {
    const analysisId = new URL(route.request().url()).pathname.split('/').pop()!.replace('advice-', '')
    done.add(analysisId)
    await route.fulfill({ json: { ...status(analysisId), run_id: 'r' } })
  })
  return calls
}

test('a consent given at the start requests the advice once after the analysis, and is not kept for the next run', async ({ page }) => {
  const calls = await adviceApi(page)
  await page.goto('/?shell=new')
  await page.locator('#input-file').setInputFiles(input)
  await page.locator('#ai-consent').check()
  await expect(page.getByTestId('readiness-will')).toContainText(SETUP_LABELS.willAdvice)
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()

  await expect.poll(() => calls.posts.length).toBe(1)
  expect(calls.posts[0]!.body).toEqual({ confirm_external_transfer: true })
  await page.locator('#shell-tab-advice').click()
  await expect(page.getByText('Сводка из подменённого совета')).toBeVisible({ timeout: 15000 })
  expect(calls.posts).toHaveLength(1)

  await page.locator('#shell-tab-setup').click()
  await expect(page.locator('#ai-consent')).not.toBeChecked()
  const secondLookup = page.waitForResponse((response) => response.request().method() === 'GET' && /\/analyses\/[^/]+\/advice$/.test(response.url()))
  await page.locator('#input-file').setInputFiles(input)
  await page.locator('#policy-file').setInputFiles(policy)
  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toHaveAttribute('data-level', 'ok')
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await page.locator('#shell-tab-advice').click()
  await expect(page.getByRole('button', { name: 'Получить рекомендации', exact: true })).toBeDisabled()
  await expect(page.getByRole('checkbox', { name: /Разрешаю отправить evidence/ })).not.toBeChecked()
  await secondLookup
  // Даём панели обработать ответ: если бы согласие осталось, запрос ушёл бы сразу после него.
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  expect(calls.posts).toHaveLength(1)
})

test('without the consent nothing is sent and the advice tab keeps its own consent flow', async ({ page }) => {
  const calls = await adviceApi(page)
  await page.goto('/?shell=new')
  await page.locator('#input-file').setInputFiles(input)
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await page.locator('#shell-tab-advice').click()

  const request = page.getByRole('button', { name: 'Получить рекомендации', exact: true })
  await expect(request).toBeDisabled()
  expect(calls.posts).toHaveLength(0)
  await page.getByRole('checkbox', { name: /Разрешаю отправить evidence/ }).check()
  await request.click()
  await expect.poll(() => calls.posts.length).toBe(1)
  expect(calls.posts[0]!.body).toEqual({ confirm_external_transfer: true })
})

test('a refusal of the automatic request is shown on the advice tab', async ({ page }) => {
  await page.route('**/api/runs/*/analyses/*/advice', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({ status: 409, json: { error: { code: 'AI_BUSY', message: 'An AI task is already running' } } })
      return
    }
    await route.fulfill({ json: { advice: null, job: null } })
  })
  await page.goto('/?shell=new')
  await page.locator('#input-file').setInputFiles(input)
  await page.locator('#ai-consent').check()
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await page.locator('#shell-tab-advice').click()

  await expect(page.getByRole('alert').filter({ hasText: 'An AI task is already running' })).toBeVisible()
})
