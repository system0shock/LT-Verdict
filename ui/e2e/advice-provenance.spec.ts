import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { ADVICE_LABELS } from '../src/shell/labels.advice'

// Показ модели, версии prompt и ошибок ИИ-разбора (срез U6a). API целиком подменён: ИИ не запускается.
const runId = `jmeter_jtl_csv-${'e'.repeat(64)}`
const run = { run_id: runId, source_type: 'jmeter_jtl_csv', sha256: 'e'.repeat(64), size_bytes: 100, original_filename: 'u6a.jtl' }
const analysisA = 'a'.repeat(64)
const analysisB = 'b'.repeat(64)
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

function advice(analysisId: string, model: string, prompt: string, summary = 'Сводка совета', extra: Record<string, unknown> = {}) {
  return {
    advisory: true, run_id: runId, analysis_id: analysisId,
    provenance: { invocation_id: 'f'.repeat(8), runner_id: 'gigacode-qwen-code', runner_version: '0.21.1', model_id: model, prompt_version: prompt, duration_ms: 12400, ...extra },
    output: { summary, hypotheses: [], recommendations: [], caveats: ['Требуется проверка'] },
  }
}

interface Setup {
  advice?: Record<string, unknown> | null
  job?: Record<string, unknown> | null
  postStatus?: number
  postError?: { code: string; message: string }
  perAnalysis?: Record<string, { advice: unknown; job?: unknown }>
  holdFirst?: Promise<void>
}

async function mockApi(page: Page, setup: Setup) {
  const calls = { posts: 0, postBodies: [] as unknown[], gets: 0, heldStarted: false, heldDone: false }
  let firstHeld = false
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    let body: unknown
    let status = 200
    let held = false
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana')) body = { profiles: [] }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (method === 'GET' && path === '/api/runs') body = { runs: [run], next_after: null }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses$/.test(path)) {
      body = { analyses: [analysisA, analysisB].map((id) => ({ analysis_id: id, policy_sha256: 'c'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' })), next_after: null }
    } else if (method === 'GET' && /\/analyses\/[^/]+\/result$/.test(path)) body = result
    else if (method === 'GET' && /\/analyses\/[^/]+\/buckets$/.test(path)) body = { buckets: [], next_from_ms: null }
    else if (/\/analyses\/[^/]+\/advice$/.test(path)) {
      const analysisId = path.split('/')[5]!
      if (method === 'POST') {
        calls.posts += 1
        calls.postBodies.push(route.request().postDataJSON())
        if (setup.postStatus && setup.postStatus >= 400) {
          status = setup.postStatus
          body = { error: { code: setup.postError?.code ?? 'AI_BUSY', message: setup.postError?.message ?? 'x', details: [] } }
        } else {
          status = 202
          body = { job_id: 'advice-job', run_id: runId, analysis_id: analysisId, state: 'PROCESSING', reused: false, failure: null, unavailable_reason: null }
        }
      } else {
        calls.gets += 1
        const own = setup.perAnalysis?.[analysisId]
        if (setup.holdFirst && !firstHeld && analysisId === analysisA) {
          firstHeld = true
          held = true
          calls.heldStarted = true
          await setup.holdFirst
        }
        body = own ? { advice: own.advice, job: own.job ?? null } : { advice: setup.advice ?? null, job: setup.job ?? null }
      }
    } else {
      status = 404
      body = { error: { code: 'NOT_FOUND', message: 'not mocked', details: [] } }
    }
    await route.fulfill({ status, json: body })
    if (held) calls.heldDone = true
  })
  return calls
}

const failedJob = (failure: string | null, reason: string | null = null, state = 'FAILED') => ({
  job_id: 'advice-job', run_id: runId, analysis_id: analysisA, state, reused: false, failure, unavailable_reason: reason,
})

async function openAdvice(page: Page, setup: Setup, shell: 'new' | 'old' = 'new') {
  const calls = await mockApi(page, setup)
  await page.goto(shell === 'new' ? '/?shell=new' : '/?shell=old')
  await page.getByRole('button', { name: 'u6a.jtl' }).click()
  await page.locator(`button[title="${analysisA}"]`).click()
  if (shell === 'new') {
    await page.locator('#shell-tab-advice').click()
  }
  const panel = page.locator('section[aria-labelledby="advice-title"]')
  await expect(panel).toBeVisible()
  return { calls, panel }
}

test('the advice shows its model and prompt version from provenance and not a hard-coded name', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: advice(analysisA, 'deepseek-v4-flash-0731', 'advisory-system.v1') })
  const origin = page.getByTestId('advice-provenance')
  await expect(origin).toContainText('deepseek-v4-flash-0731')
  await expect(origin).toContainText('advisory-system.v1')
  await expect(origin).toContainText(ADVICE_LABELS.promptLegacy('advisory-system.v1'))
  await expect(origin).toContainText('12,4 с')
  await expect(panel).not.toContainText('DeepSeek V4 Flash')
  await expect(panel).toContainText('Сводка совета')
  await expect(page.getByTestId('advice-provenance').locator('dt')).toHaveText([
    ADVICE_LABELS.model, ADVICE_LABELS.promptVersion, ADVICE_LABELS.duration, ADVICE_LABELS.runner, ADVICE_LABELS.invocation,
  ])
})

test('the old interface shows the same provenance because the panel is shared', async ({ page }) => {
  await openAdvice(page, { advice: advice(analysisA, 'm-old-shell', 'advisory-system.v2', 'Сводка совета', { provider_requests: 2 }) }, 'old')
  const origin = page.getByTestId('advice-provenance')
  await expect(origin).toContainText('m-old-shell')
  await expect(origin).toContainText('advisory-system.v2')
  await expect(origin.locator('dd').filter({ hasText: ADVICE_LABELS.requestsRetry })).toBeVisible()
})

test('before the request the panel names no model and asks no consent', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: null })
  await expect(panel).toContainText(ADVICE_LABELS.intro)
  await expect(panel).not.toContainText('DeepSeek')
  await expect(panel.getByTestId('advice-provenance')).toHaveCount(0)
  await expect(panel.getByRole('checkbox')).toHaveCount(0)
  for (const word of ['Разрешаю', 'ModelStudio', 'Singapore']) await expect(panel).not.toContainText(word)
  await expect(panel.getByRole('button', { name: 'Получить рекомендации' })).toBeEnabled()
  await expect(panel.getByRole('heading', { name: 'Рекомендации AI' })).toBeVisible()
})

test('a failed job shows the Russian reason, the raw code and a hint, and the request stays available', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: null, job: failedJob('TIMEOUT') })
  const status = panel.getByRole('status')
  await expect(status).toContainText(ADVICE_LABELS.states.FAILED)
  await expect(status).toContainText(ADVICE_LABELS.failure.TIMEOUT!)
  await expect(status).toContainText('TIMEOUT')
  await expect(status).toContainText(ADVICE_LABELS.failureHint.TIMEOUT!)
  await expect(panel.getByRole('button', { name: 'Получить рекомендации' })).toBeEnabled()
})

test('UNAVAILABLE names the missing prerequisite', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: null, job: failedJob(null, 'DOCKER_UNAVAILABLE', 'UNAVAILABLE') })
  const status = panel.getByRole('status')
  await expect(status).toContainText(ADVICE_LABELS.states.UNAVAILABLE)
  await expect(status).toContainText(ADVICE_LABELS.unavailable.DOCKER_UNAVAILABLE!)
  await expect(status).toContainText('DOCKER_UNAVAILABLE')
  await expect(status).toContainText(ADVICE_LABELS.unavailableHint.DOCKER_UNAVAILABLE!)
})

test('an unknown failure code is shown with its code and does not break the panel', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: null, job: failedJob('BRAND_NEW_CODE') })
  await expect(panel.getByRole('status')).toContainText(ADVICE_LABELS.unknownFailure)
  await expect(panel.getByRole('status')).toContainText('BRAND_NEW_CODE')
})

test('no request is sent until the button is clicked, then exactly one without a consent field', async ({ page }) => {
  const { calls, panel } = await openAdvice(page, { advice: null })
  const start = panel.getByRole('button', { name: 'Получить рекомендации' })
  await expect(start).toBeEnabled()
  expect(calls.posts).toBe(0)
  await start.click()
  await expect.poll(() => calls.posts).toBe(1)
  expect(calls.postBodies[0]).toEqual({})
})

test('AI_BUSY responses are readable, keep the server message and keep the page usable', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: null, postStatus: 409, postError: { code: 'AI_BUSY', message: 'An AI task is already running' } })
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  const alert = panel.getByRole('alert')
  await expect(alert).toContainText(ADVICE_LABELS.apiBusy)
  await expect(alert).toContainText('AI_BUSY')
  await expect(alert.locator('[lang="en"]')).toHaveText('An AI task is already running')
  await expect(panel.getByRole('button', { name: 'Получить рекомендации' })).toBeEnabled()
})

test('AI_UNAVAILABLE reads as words and other API errors keep the raw message', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: null, postStatus: 503, postError: { code: 'AI_UNAVAILABLE', message: 'AI runner is not configured' } })
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect(panel.getByRole('alert')).toContainText(ADVICE_LABELS.apiUnavailable)
  await expect(panel.getByRole('alert')).toContainText('AI_UNAVAILABLE')
})

test('other API errors keep the raw server message', async ({ page }) => {
  const { panel } = await openAdvice(page, { advice: null, postStatus: 500, postError: { code: 'SOMETHING_ELSE', message: 'Something else failed' } })
  await panel.getByRole('button', { name: 'Получить рекомендации' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Something else failed')
  await expect(panel.getByRole('alert').locator('[lang="en"]')).toHaveText('Something else failed')
})

test('model text is rendered as text, never as HTML', async ({ page }) => {
  const evil = '<img src=x onerror=alert(1)>'
  const { panel } = await openAdvice(page, { advice: advice(analysisA, evil, evil, evil) })
  await expect(page.locator('img[src="x"]')).toHaveCount(0)
  await expect(panel.getByText(evil, { exact: true }).first()).toBeVisible()
  await expect(page.getByTestId('advice-provenance').locator('dd').first()).toHaveText(evil)
})

test('a stale response after switching the analysis does not overwrite the new panel', async ({ page }) => {
  let release: () => void = () => {}
  const holdFirst = new Promise<void>((resolve) => { release = resolve })
  const { calls, panel } = await openAdvice(page, {
    holdFirst,
    perAnalysis: {
      [analysisA]: { advice: advice(analysisA, 'old-model', 'advisory-system.v1') },
      [analysisB]: { advice: advice(analysisB, 'new-model', 'advisory-system.v1') },
    },
  })
  await expect.poll(() => calls.heldStarted).toBe(true)
  await page.locator('#shell-tab-setup').click()
  await page.locator(`button[title="${analysisB}"]`).click()
  await page.locator('#shell-tab-advice').click()
  await expect(page.getByTestId('advice-provenance')).toContainText('new-model')
  release()
  await expect.poll(() => calls.heldDone).toBe(true)
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  await expect(page.getByTestId('advice-provenance')).toContainText('new-model')
  await expect(page.getByTestId('advice-provenance')).not.toContainText('old-model')
  await expect(panel).not.toContainText('old-model')
})

for (const theme of ['light', 'dark'] as const) {
  test(`the advice panel with provenance and an error has no serious axe violations in ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    const { panel } = await openAdvice(page, { advice: advice(analysisA, 'deepseek-v4-flash-0731', 'advisory-system.v1'), job: failedJob('TIMEOUT') })
    await expect(panel.getByTestId('advice-provenance')).toBeVisible()
    const axe = await new AxeBuilder({ page }).include('section[aria-labelledby="advice-title"]').analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const size of [{ width: 1280, height: 800 }, { width: 375, height: 800 }, { width: 320, height: 800 }]) {
  test(`the advice panel has no horizontal page scroll at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    const { panel } = await openAdvice(page, { advice: advice(analysisA, 'deepseek-v4-flash-0731-with-a-very-long-model-identifier', 'advisory-system.v1'), job: failedJob('UNKNOWN_EVIDENCE_REFERENCE') })
    await expect(panel.getByTestId('advice-provenance')).toBeVisible()
    const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(width.scroll).toBeLessThanOrEqual(width.client)
  })
}
