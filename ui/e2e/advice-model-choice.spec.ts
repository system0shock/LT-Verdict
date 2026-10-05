import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { modelCaption, modelOptions, requestedModelId } from '../src/shell/advice'
import { ADVICE_LABELS } from '../src/shell/labels.advice'
import type { AdvisoryAiConfig } from '../src/types'

// Выбор модели ИИ-разбора (срез CM5 ADR 0023). Список моделей приходит в `advisory_ai` ответа bootstrap.
// Решение владельца 2026-10-06: подписи назначения и строки «данные отправляются в ...» нет; поле endpoint_label не читается.
const runId = `jmeter_jtl_csv-${'e'.repeat(64)}`
const run = { run_id: runId, source_type: 'jmeter_jtl_csv', sha256: 'e'.repeat(64), size_bytes: 100, original_filename: 'cm5.jtl' }
const analysisA = 'a'.repeat(64)
const result = {
  schema_version: 'analysis-result.v1',
  run_id: runId,
  analysis_mode: 'standard',
  run_validity: 'VALID',
  analysis_coverage: { status: 'COMPLETE', reasons: [] },
  findings: [],
  policy_verdict: 'NO_POLICY',
  evidence: [{
    id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 100, error_count: 0,
    error_rate_ratio: { numerator: 0, denominator: 100 }, throughput_rps: { numerator: 100000, denominator: 1000 },
    latency_ms: { p50: 10, p95: 20, p99: 30, max: 40 },
  }],
}
const input = fileURLToPath(new URL('../../fixtures/slice1/jmeter/xml-5.6.3/input.xml', import.meta.url))

const one: AdvisoryAiConfig = { default_model_id: 'deepseek-v4-flash-0731', models: [{ id: 'deepseek-v4-flash-0731', label: 'DeepSeek V4 Flash', measured: true }] }
const two: AdvisoryAiConfig = {
  default_model_id: 'deepseek-v4-flash-0731',
  models: [
    { id: 'deepseek-v4-flash-0731', label: 'DeepSeek V4 Flash', measured: true },
    { id: 'qwen3.8-max', label: 'Qwen 3.8 Max', measured: false },
  ],
}
const oneUnmeasured: AdvisoryAiConfig = { default_model_id: 'qwen3.8-max', models: [{ id: 'qwen3.8-max', label: 'Qwen 3.8 Max', measured: false }] }

interface Setup {
  config?: unknown
  advice?: Record<string, unknown> | null
  job?: Record<string, unknown> | null
  postJob?: Record<string, unknown>
  holdAdvice?: Promise<void>
}

async function mockApi(page: Page, setup: Setup) {
  const posts: unknown[] = []
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    let status = 200
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000, ...(setup.config === undefined ? {} : { advisory_ai: setup.config }) }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana')) body = { profiles: [] }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (method === 'GET' && path === '/api/runs') body = { runs: [run], next_after: null }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses$/.test(path)) {
      body = { analyses: [{ analysis_id: analysisA, policy_sha256: 'c'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }], next_after: null }
    } else if (method === 'GET' && /\/analyses\/[^/]+\/result$/.test(path)) body = result
    else if (method === 'GET' && /\/analyses\/[^/]+\/buckets$/.test(path)) body = { buckets: [], next_from_ms: null }
    else if (/\/analyses\/[^/]+\/advice$/.test(path)) {
      if (method === 'POST') {
        posts.push(route.request().postDataJSON())
        status = 202
        body = setup.postJob ?? { job_id: 'advice-job', run_id: runId, analysis_id: analysisA, state: 'PROCESSING', reused: false, failure: null, unavailable_reason: null }
      } else {
        if (setup.holdAdvice) await setup.holdAdvice
        body = { advice: setup.advice ?? null, job: setup.job ?? null }
      }
    } else if (path.startsWith('/api/advice-jobs/')) {
      body = setup.postJob ?? setup.job ?? { job_id: 'advice-job', run_id: runId, analysis_id: analysisA, state: 'PROCESSING', reused: false, failure: null, unavailable_reason: null }
    } else {
      status = 404
      body = { error: { code: 'NOT_FOUND', message: 'not mocked', details: [] } }
    }
    await route.fulfill({ status, json: body })
  })
  return posts
}

const job = (state: string, extra: Record<string, unknown> = {}) => ({
  job_id: 'advice-job', run_id: runId, analysis_id: analysisA, state, reused: false, failure: null, unavailable_reason: null, ...extra,
})

async function openAdvice(page: Page, setup: Setup, shell: 'new' | 'old' = 'new') {
  const posts = await mockApi(page, setup)
  await page.goto(shell === 'new' ? '/?shell=new' : '/?shell=old')
  await page.getByRole('button', { name: 'cm5.jtl' }).click()
  await page.locator(`button[title="${analysisA}"]`).click()
  if (shell === 'new') await page.locator('#shell-tab-advice').click()
  const panel = page.locator('section[aria-labelledby="advice-title"]')
  await expect(panel).toBeVisible()
  return { posts, panel }
}

// Анализ на настоящем сервере; ответ bootstrap дополняется полем advisory_ai, остальное в нём настоящее (csrf нужен для загрузки).
async function realServerWithConfig(page: Page, config: unknown) {
  await page.route('**/api/bootstrap', async (route) => {
    const response = await route.fetch()
    const json = await response.json() as Record<string, unknown>
    if (config !== undefined) json.advisory_ai = config
    await route.fulfill({ response, json })
  })
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

test('the pure helpers: option words, and model_id only when there is a choice', () => {
  expect(modelOptions(two)).toEqual([
    { id: 'deepseek-v4-flash-0731', text: `DeepSeek V4 Flash (${ADVICE_LABELS.modelChoice.measured})` },
    { id: 'qwen3.8-max', text: `Qwen 3.8 Max (${ADVICE_LABELS.modelChoice.notMeasured})` },
  ])
  expect(ADVICE_LABELS.modelChoice.measured).toBe('модель измерена')
  expect(ADVICE_LABELS.modelChoice.notMeasured).toBe('не измерена')
  expect(requestedModelId(null, '')).toBeUndefined()
  expect(requestedModelId(undefined, 'x')).toBeUndefined()
  expect(requestedModelId(one, 'deepseek-v4-flash-0731')).toBeUndefined()
  expect(requestedModelId(two, 'qwen3.8-max')).toBe('qwen3.8-max')
  expect(requestedModelId(two, '')).toBe('deepseek-v4-flash-0731')
  expect(requestedModelId(two, 'not-in-the-list')).toBe('deepseek-v4-flash-0731')
})

test('one model: a caption instead of a selector, the request has no model_id', async ({ page }) => {
  const { posts, panel } = await openAdvice(page, { config: one })
  await expect(panel.getByTestId('model-choice')).toBeVisible()
  await expect(panel.getByRole('combobox')).toHaveCount(0)
  await expect(panel.getByTestId('model-caption')).toHaveText(modelCaption(one.models[0]!))
  await expect(panel.getByTestId('model-unmeasured')).toHaveCount(0)
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect.poll(() => posts.length).toBe(1)
  expect(posts[0]).toEqual({})
})

test('several models: the default is preselected, the chosen one goes to the request, the wording says measured or not', async ({ page }) => {
  const { posts, panel } = await openAdvice(page, { config: two })
  const select = panel.getByRole('combobox', { name: ADVICE_LABELS.modelChoice.label })
  await expect(select).toHaveValue('deepseek-v4-flash-0731')
  await expect(select.locator('option')).toHaveText([
    `DeepSeek V4 Flash (${ADVICE_LABELS.modelChoice.measured})`,
    `Qwen 3.8 Max (${ADVICE_LABELS.modelChoice.notMeasured})`,
  ])
  await expect(panel.getByTestId('model-unmeasured')).toHaveCount(0)
  await select.selectOption('qwen3.8-max')
  await expect(panel.getByTestId('model-unmeasured')).toHaveText(ADVICE_LABELS.modelChoice.unmeasuredNote)
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect.poll(() => posts.length).toBe(1)
  expect(posts[0]).toEqual({ model_id: 'qwen3.8-max' })
  // Пока идёт задание, выбор заблокирован.
  await expect(select).toBeDisabled()
})

test('the default model is sent by id when there is a choice', async ({ page }) => {
  const { posts, panel } = await openAdvice(page, { config: two })
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect.poll(() => posts.length).toBe(1)
  expect(posts[0]).toEqual({ model_id: 'deepseek-v4-flash-0731' })
})

test('one model that is not measured shows the caption and the warning line', async ({ page }) => {
  const { panel } = await openAdvice(page, { config: oneUnmeasured })
  await expect(panel.getByTestId('model-caption')).toHaveText(modelCaption(oneUnmeasured.models[0]!))
  await expect(panel.getByTestId('model-unmeasured')).toHaveText(ADVICE_LABELS.modelChoice.unmeasuredNote)
})

for (const [name, config] of [['null', null], ['absent', undefined]] as const) {
  test(`advisory_ai ${name}: no selector and no caption, the request has no model_id and the page works`, async ({ page }) => {
    const { posts, panel } = await openAdvice(page, { config })
    await expect(panel.getByTestId('model-choice')).toHaveCount(0)
    await expect(panel.getByRole('combobox')).toHaveCount(0)
    await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
    await expect.poll(() => posts.length).toBe(1)
    expect(posts[0]).toEqual({})
  })
}

test('an existing advice hides the selector and says that it is created once; the model comes from provenance', async ({ page }) => {
  const stored = {
    advisory: true, run_id: runId, analysis_id: analysisA,
    provenance: { model_id: 'qwen3.8-max', prompt_version: 'advisory-system.v1' },
    output: { summary: 'Сводка совета', hypotheses: [], recommendations: [], caveats: ['Проверить'] },
  }
  const { panel } = await openAdvice(page, { config: two, advice: stored })
  await expect(panel.getByTestId('model-choice')).toHaveCount(0)
  await expect(panel.getByRole('combobox')).toHaveCount(0)
  await expect(panel.getByTestId('model-once')).toHaveText(ADVICE_LABELS.modelChoice.once)
  await expect(page.getByTestId('advice-provenance')).toContainText('qwen3.8-max')
})

test('the selector waits for the saved advice lookup, so a saved advice never flashes it', async ({ page }) => {
  let release: () => void = () => {}
  const holdAdvice = new Promise<void>((resolve) => { release = resolve })
  const stored = {
    advisory: true, run_id: runId, analysis_id: analysisA,
    provenance: { model_id: 'qwen3.8-max', prompt_version: 'advisory-system.v1' },
    output: { summary: 'Сводка совета', hypotheses: [], recommendations: [], caveats: ['Проверить'] },
  }
  const { panel } = await openAdvice(page, { config: two, advice: stored, holdAdvice })
  await expect(panel.getByRole('button', { name: 'Получить рекомендации' })).toBeVisible()
  await expect(panel.getByTestId('model-choice')).toHaveCount(0)
  release()
  await expect(panel.getByText('Сводка совета')).toBeVisible()
  await expect(panel.getByTestId('model-choice')).toHaveCount(0)
})

test('FAILED and UNAVAILABLE show the model of the job; a job without the field prints nothing about it', async ({ page }) => {
  const failed = await openAdvice(page, { config: two, job: job('FAILED', { failure: 'TIMEOUT', model_id: 'qwen3.8-max' }) })
  await expect(failed.panel.getByRole('status')).toContainText(`${ADVICE_LABELS.modelChoice.jobModel}: qwen3.8-max`)
  await failed.panel.getByRole('combobox').selectOption('deepseek-v4-flash-0731')
  await expect(failed.panel.getByRole('button', { name: 'Получить рекомендации' })).toBeEnabled()
})

test('UNAVAILABLE shows the model of the job and the Russian MODEL_CONFIG_INVALID text', async ({ page }) => {
  const { panel } = await openAdvice(page, { config: null, job: job('UNAVAILABLE', { unavailable_reason: 'MODEL_CONFIG_INVALID', model_id: null }) })
  const status = panel.getByRole('status')
  await expect(status).toContainText(ADVICE_LABELS.unavailable.MODEL_CONFIG_INVALID!)
  await expect(status).toContainText('MODEL_CONFIG_INVALID')
  await expect(status).toContainText(ADVICE_LABELS.unavailableHint.MODEL_CONFIG_INVALID!)
  await expect(status).not.toContainText(ADVICE_LABELS.modelChoice.jobModel)
  await expect(panel.getByTestId('model-choice')).toHaveCount(0)
})

test('a job without model_id shows no model line and no "undefined"', async ({ page }) => {
  const { panel } = await openAdvice(page, { config: one, job: job('FAILED', { failure: 'TIMEOUT' }) })
  const status = panel.getByRole('status')
  await expect(status).toContainText(ADVICE_LABELS.failure.TIMEOUT!)
  await expect(status).not.toContainText(ADVICE_LABELS.modelChoice.jobModel)
  await expect(panel).not.toContainText('undefined')
})

test('labels from the configuration are rendered as text and no destination or consent wording appears', async ({ page }) => {
  const evil = '<img src=x onerror=alert(1)>'
  const config = { default_model_id: 'a', endpoint_label: 'Внешний шлюз', models: [{ id: 'a', label: evil, measured: false }, { id: 'b', label: 'B', measured: true }] }
  const { panel } = await openAdvice(page, { config })
  await expect(panel.getByRole('combobox').locator('option').first()).toContainText(evil)
  await expect(page.locator('img[src=x]')).toHaveCount(0)
  const text = await panel.innerText()
  for (const word of ['Разрешаю', 'разрешаю', 'ModelStudio', 'Singapore', 'Внешний шлюз', 'отправляются в']) expect(text).not.toContain(word)
  await expect(panel.getByRole('checkbox')).toHaveCount(0)
})

test('the old interface shows the same selector because the panel is shared', async ({ page }) => {
  const { posts, panel } = await openAdvice(page, { config: two }, 'old')
  await panel.getByRole('combobox').selectOption('qwen3.8-max')
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect.poll(() => posts.length).toBe(1)
  expect(posts[0]).toEqual({ model_id: 'qwen3.8-max' })
  await expect(page.locator('#run-setup')).not.toHaveAttribute('ai-config', /.*/)
})

test('new analysis: the selector appears only when the toggle is on, and the chosen model goes to the request after the analysis', async ({ page }) => {
  const posts = await realServerWithConfig(page, two)
  await page.goto('/?shell=new')
  const section = page.locator('section[aria-labelledby="setup-ai-title"]')
  await expect(section.getByRole('switch')).not.toBeChecked()
  await expect(section.getByTestId('model-choice')).toHaveCount(0)
  await section.getByRole('switch').check()
  const select = section.getByRole('combobox', { name: ADVICE_LABELS.modelChoice.label })
  await expect(select).toHaveValue('deepseek-v4-flash-0731')
  await select.selectOption('qwen3.8-max')
  await expect(section.getByTestId('model-unmeasured')).toBeVisible()
  const text = await section.innerText()
  for (const word of ['Разрешаю', 'ModelStudio', 'Singapore', 'отправляются в']) expect(text).not.toContain(word)

  await page.locator('#input-file').setInputFiles(input)
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect.poll(() => posts.length).toBe(1)
  expect(posts[0]).toEqual({ model_id: 'qwen3.8-max' })
  // Выбор общий: на вкладке «ИИ-разбор» стоит та же модель.
  await page.locator('#shell-tab-advice').click()
  await expect(page.locator('section[aria-labelledby="advice-title"]').getByRole('combobox')).toHaveValue('qwen3.8-max')
})

test('new analysis with one model or without configuration: no selector, the request has no model_id', async ({ page }) => {
  for (const config of [one, null]) {
    const fresh = await page.context().newPage()
    const posts = await realServerWithConfig(fresh, config)
    await fresh.goto('/?shell=new')
    const section = fresh.locator('section[aria-labelledby="setup-ai-title"]')
    await section.getByRole('switch').check()
    await expect(section.getByRole('combobox')).toHaveCount(0)
    await fresh.locator('#input-file').setInputFiles(input)
    await fresh.getByTestId('start-analysis').click()
    await expect(fresh.getByTestId('overview-panel')).toBeVisible()
    await expect.poll(() => posts.length).toBe(1)
    expect(posts[0]).toEqual({})
    await fresh.close()
  }
})

for (const theme of ['light', 'dark'] as const) {
  for (const width of [1280, 375]) {
    test(`the selector has no serious axe violations in ${theme} at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.emulateMedia({ colorScheme: theme })
      const { panel } = await openAdvice(page, { config: two })
      await panel.getByRole('combobox').selectOption('qwen3.8-max')
      const advice = await new AxeBuilder({ page }).include('section[aria-labelledby="advice-title"]').analyze()
      expect(advice.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

      await page.locator('#shell-tab-setup').click()
      await page.locator('section[aria-labelledby="setup-ai-title"]').getByRole('switch').check()
      const setup = await new AxeBuilder({ page }).include('section[aria-labelledby="setup-ai-title"]').analyze()
      expect(setup.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    })
  }
}
