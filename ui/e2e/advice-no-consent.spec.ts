import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { SETUP_LABELS } from '../src/shell/labels'

// ADR 0023 (CM1): согласия на отправку в ИИ нет ни на вкладке «ИИ-разбор», ни на экране «Новый анализ».
// Анализ идёт на настоящем сервере, запросы совета подменены.
const input = fileURLToPath(new URL('../../fixtures/slice1/jmeter/xml-5.6.3/input.xml', import.meta.url))
const banned = ['Разрешаю', 'разрешаю', 'ModelStudio', 'Singapore', 'согласи']

async function mockAdvice(page: import('@playwright/test').Page) {
  const posts: unknown[] = []
  await page.route('**/api/runs/*/analyses/*/advice', async (route) => {
    if (route.request().method() === 'POST') {
      posts.push(route.request().postDataJSON())
      await route.fulfill({ status: 409, json: { error: { code: 'AI_BUSY', message: 'An AI task is already running' } } })
      return
    }
    await route.fulfill({ json: { advice: null, job: null } })
  })
  return posts
}

test('the new analysis screen has a toggle off by default and no consent wording', async ({ page }) => {
  await page.goto('/?shell=new')
  const section = page.locator('section[aria-labelledby="setup-ai-title"]')
  await expect(section).toBeVisible()
  await expect(section.getByRole('checkbox')).toHaveCount(0)
  const toggle = section.getByRole('switch', { name: SETUP_LABELS.aiRequestedLabel })
  await expect(toggle).not.toBeChecked()
  await expect(page.locator('#ai-consent')).toHaveCount(0)
  const text = await section.innerText()
  for (const word of banned) expect(text).not.toContain(word)
  expect(SETUP_LABELS.aiRequestedLabel).toBe('Запросить ИИ-разбор после анализа')
})

test('the advice tab has no checkbox, an enabled button and one request without a consent field', async ({ page }) => {
  const posts = await mockAdvice(page)
  await page.goto('/?shell=new')
  await page.locator('#input-file').setInputFiles(input)
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await page.locator('#shell-tab-advice').click()

  const panel = page.locator('section[aria-labelledby="advice-title"]')
  const start = panel.getByRole('button', { name: 'Получить рекомендации', exact: true })
  await expect(start).toBeEnabled()
  await expect(panel.getByRole('checkbox')).toHaveCount(0)
  const text = await panel.innerText()
  for (const word of banned) expect(text).not.toContain(word)
  expect(posts).toHaveLength(0)
  await start.click()
  await expect.poll(() => posts.length).toBe(1)
  expect(posts[0]).toEqual({})
  // Отказ сервера читается как раньше и не блокирует повтор запроса.
  await expect(panel.getByRole('alert')).toContainText('AI_BUSY')
  await expect(start).toBeEnabled()
})

for (const theme of ['light', 'dark'] as const) {
  test(`both AI sections have no serious axe violations in ${theme}`, async ({ page }) => {
    await mockAdvice(page)
    await page.emulateMedia({ colorScheme: theme })
    await page.goto('/?shell=new')
    const setup = await new AxeBuilder({ page }).include('section[aria-labelledby="setup-ai-title"]').analyze()
    expect(setup.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
    await page.locator('#input-file').setInputFiles(input)
    await page.getByTestId('start-analysis').click()
    await expect(page.getByTestId('overview-panel')).toBeVisible()
    await page.locator('#shell-tab-advice').click()
    await expect(page.getByRole('button', { name: 'Получить рекомендации', exact: true })).toBeEnabled()
    const advice = await new AxeBuilder({ page }).include('section[aria-labelledby="advice-title"]').analyze()
    expect(advice.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}
