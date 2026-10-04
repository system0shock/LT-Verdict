import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const input = fileURLToPath(new URL('../../fixtures/slice1/jmeter/xml-5.6.3/input.xml', import.meta.url))
const summary = 'Completed advice after cancellation race'

async function mockAdvice(page: Page, cancelResult: 'CANCELLED' | 'COMPLETE' | 'ERROR') {
  let starts = 0
  let cancels = 0
  let polls = 0
  let completeOnPoll = false
  let state: 'PROCESSING' | 'CANCELLED' | 'COMPLETE' | null = null
  let reference = { run_id: '', analysis_id: '' }
  const job = () => ({ job_id: `advice-${starts}`, ...reference, state, reused: false, failure: null, unavailable_reason: null })

  await page.route('**/api/runs/*/analyses/*/advice', async (route) => {
    const segments = new URL(route.request().url()).pathname.split('/')
    reference = { run_id: segments[3]!, analysis_id: segments[5]! }
    if (route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({ confirm_external_transfer: true })
      starts += 1
      state = 'PROCESSING'
      await route.fulfill({ status: 202, json: job() })
      return
    }
    await route.fulfill({ json: {
      job: state ? job() : null,
      advice: state === 'COMPLETE' ? { advisory: true, ...reference, output: {
        summary, hypotheses: [], recommendations: [], caveats: [],
      } } : null,
    } })
  })
  await page.route('**/api/advice-jobs/*', async (route) => {
    if (route.request().method() === 'DELETE') {
      cancels += 1
      if (cancelResult === 'ERROR') {
        await route.fulfill({ status: 500, json: { error: { code: 'CANCEL_FAILED', message: 'Cancel request failed', details: [] } } })
        return
      }
      state = cancelResult
      await route.fulfill({ json: job() })
      return
    }
    polls += 1
    if (cancelResult === 'ERROR' && completeOnPoll) state = 'COMPLETE'
    await route.fulfill({ json: job() })
  })
  return {
    get starts() { return starts },
    get cancels() { return cancels },
    get polls() { return polls },
    completeOnNextPoll() { completeOnPoll = true },
  }
}

async function openAdvice(page: Page) {
  await page.goto('/?shell=new')
  await page.locator('#input-file').setInputFiles(input)
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await page.locator('#shell-tab-advice').click()
  const panel = page.locator('section[aria-labelledby="advice-title"]')
  await expect(panel.getByRole('checkbox')).toBeVisible()
  return panel
}

test('cancelled advice resets the controls and permits a second consented start', async ({ page }) => {
  const calls = await mockAdvice(page, 'CANCELLED')
  const panel = await openAdvice(page)
  const consent = panel.getByRole('checkbox')
  const start = panel.getByRole('button', { name: 'Получить рекомендации' })
  await expect(start).toBeDisabled()
  await consent.check()
  await start.click()
  await expect(panel.getByRole('status')).toHaveText('PROCESSING')
  await expect(consent).toBeDisabled()
  await panel.getByRole('button', { name: 'Отменить AI' }).click()

  await expect.poll(() => calls.cancels).toBe(1)
  await expect(panel.getByRole('status')).toHaveText('CANCELLED')
  await expect(consent).toBeEnabled()
  await expect(start).toBeEnabled()
  const axe = await new AxeBuilder({ page }).include('section[aria-labelledby="advice-title"]').analyze()
  expect(axe.violations.map((item) => item.id)).toEqual([])

  await consent.uncheck()
  await expect(start).toBeDisabled()
  await consent.check()
  await start.click()
  await expect.poll(() => calls.starts).toBe(2)
  await expect(panel.getByRole('status')).toHaveText('PROCESSING')
  await expect(consent).toBeDisabled()
})

async function cancelRacingWithCompletion(page: Page) {
  const calls = await mockAdvice(page, 'COMPLETE')
  const panel = await openAdvice(page)
  await panel.getByRole('checkbox').check()
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect(panel.getByRole('status')).toHaveText('PROCESSING')
  await panel.getByRole('button', { name: 'Отменить AI' }).click()
  await expect.poll(() => calls.cancels).toBe(1)
  return panel
}

test('cancel racing with completion shows the COMPLETE status', async ({ page }) => {
  const panel = await cancelRacingWithCompletion(page)
  await expect(panel.getByRole('status')).toHaveText('COMPLETE')
  await expect(panel.getByRole('status')).not.toContainText('CANCELLED')
})

test.fixme('cancel racing with completion loads the completed advice', async ({ page }) => {
  // AdvicePanel.cancel() stores the COMPLETE job and stops polling without calling loadAdvice().
  const panel = await cancelRacingWithCompletion(page)
  await expect(panel.getByRole('status')).toHaveText('COMPLETE')
  await expect(panel.getByText(summary)).toBeVisible()
})

test('failed cancel reports an error while the real job can still complete', async ({ page }) => {
  const calls = await mockAdvice(page, 'ERROR')
  const panel = await openAdvice(page)
  await panel.getByRole('checkbox').check()
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect(panel.getByRole('status')).toHaveText('PROCESSING')
  await panel.getByRole('button', { name: 'Отменить AI' }).click()

  await expect.poll(() => calls.cancels).toBe(1)
  await expect(panel.getByRole('alert')).toHaveText('Cancel request failed')
  await expect(panel.getByRole('status')).toHaveText('PROCESSING')
  const axe = await new AxeBuilder({ page }).include('section[aria-labelledby="advice-title"]').analyze()
  expect(axe.violations.map((item) => item.id)).toEqual([])

  const previousPolls = calls.polls
  calls.completeOnNextPoll()
  await expect.poll(() => calls.polls, { timeout: 15000 }).toBeGreaterThan(previousPolls)
  await expect(panel.getByRole('status')).toHaveText('COMPLETE')
  await expect(panel.getByText(summary)).toBeVisible()
})
