import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { SETUP_LABELS } from '../src/shell/labels'
import type { SourceCheckResponse, SourceProbeResponse, SourceProfile, SourceReloadResponse } from '../src/types'

const initialProfiles: SourceProfile[] = [
  { id: 'prom-old', source_kind: 'prometheus', transport: 'direct' },
]

const reloadedProfiles: SourceProfile[] = [
  { id: 'prom-new', source_kind: 'prometheus', transport: 'direct' },
  { id: 'pg-new', source_kind: 'postgresql', transport: 'jdbc' },
]

interface MockReply {
  status: number
  json: unknown
}

interface MockState {
  profiles: SourceProfile[]
  reloadCalls: Array<{ csrf: string | undefined; body: string }>
  validateBodies: Array<{ profile_id?: string }>
  probeBodies: Array<Record<string, unknown>>
  jobBodies: string[]
  reloadHandler: () => MockReply | Promise<MockReply>
  validateHandler: (body: { profile_id?: string }) => MockReply | Promise<MockReply>
  probeHandler: (body: Record<string, unknown>) => MockReply | Promise<MockReply>
}

async function setupMockApi(page: Page): Promise<MockState> {
  const reference = { run_id: 'reload-pair-run', analysis_id: 'd'.repeat(64) }
  const run = { ...reference, source_type: 'jmeter_jtl_csv', sha256: 'e'.repeat(64), size_bytes: 100, original_filename: 'pair.jtl' }
  const state: MockState = {
    profiles: [...initialProfiles],
    reloadCalls: [],
    validateBodies: [],
    probeBodies: [],
    jobBodies: [],
    reloadHandler: () => {
      state.profiles = [...reloadedProfiles]
      const payload: SourceReloadResponse = {
        schema_version: 'source-reload.v1',
        status: 'RELOADED',
        profile_count: 2,
        profiles: ['pg-new', 'prom-new'],
        added: ['pg-new', 'prom-new'],
        removed: ['prom-old'],
        changed: [],
        revision: 'a'.repeat(64),
      }
      return { status: 200, json: payload }
    },
    validateHandler: (body) => {
      const targetId = body.profile_id ?? state.profiles[0]?.id ?? 'prom-old'
      const payload: SourceCheckResponse = {
        schema_version: 'source-check.v1',
        profiles: [
          {
            profile_id: targetId,
            source_kind: 'prometheus',
            transport: 'direct',
            arm: null,
            query_count: 1,
            rule_count: 0,
            checks: [
              { id: 'config', status: 'OK', code: null },
              { id: 'url', status: 'OK', code: null },
              { id: 'credentials', status: 'FAIL', code: 'SOURCE_AUTH_UNAVAILABLE', auth_kind: 'bearer' },
              { id: 'tls', status: 'SKIPPED', code: null },
            ],
          },
        ],
      }
      return { status: 200, json: payload }
    },
    probeHandler: (body) => {
      const payload: SourceProbeResponse = {
        schema_version: 'source-probe.v1',
        profile_id: String(body.profile_id),
        source_kind: 'prometheus',
        transport: 'direct',
        mode: 'profile_query',
        query_id: String(body.query_id),
        expression_sha256: 'b'.repeat(64),
        window: {
          start_epoch_ms: 1767225000000,
          end_epoch_ms: 1767225300000,
          step_ms: 15000,
          cells: 20,
        },
        status: 'OK',
        code: null,
        http_status: 200,
        series_count: 2,
        series: [
          { labels: { instance: 'app-1', token: '***' }, observed_cells: 20, expected_cells: 20 },
          { labels: { instance: 'app-2' }, observed_cells: 19, expected_cells: 20 },
        ],
        series_truncated: false,
        label_keys: [
          { key: 'instance', distinct_values: 2, sample_values: ['app-1', 'app-2'], truncated: false },
          { key: 'token', distinct_values: 1, sample_values: ['***'], truncated: false },
        ],
        label_keys_truncated: false,
        redacted_count: 1,
        warnings: [],
        decoder_check: { accepted: false, code: 'AMBIGUOUS_SERIES' },
        request_count: 1,
        elapsed_ms: 42,
      }
      return { status: 200, json: payload }
    },
  }

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1000000 } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana')) return route.fulfill({ json: { profiles: [] } })
    if (method === 'GET' && path === '/api/sources') return route.fulfill({ json: { profiles: state.profiles } })
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: [], next_after: null } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [] } })
    if (method === 'POST' && path === '/api/inputs') return route.fulfill({ status: 201, json: run })
    if (method === 'POST' && path === '/api/jobs') {
      state.jobBodies.push(request.postDataBuffer()!.toString())
      return route.fulfill({
        status: 202,
        json: { job_id: `job-${state.jobBodies.length}`, state: 'COMPLETE', processed_bytes: 100, total_bytes: 100, ...reference, diagnostic: null },
      })
    }
    if (method === 'GET' && path.startsWith('/api/jobs/')) {
      return route.fulfill({
        json: { job_id: path.split('/').pop(), state: 'COMPLETE', processed_bytes: 100, total_bytes: 100, ...reference, diagnostic: null },
      })
    }
    if (method === 'GET' && path.endsWith('/analyses')) return route.fulfill({ json: { analyses: [], next_after: null } })
    if (method === 'GET' && path.endsWith('/result')) {
      return route.fulfill({
        json: {
          schema_version: 'analysis-result.v1',
          run_id: reference.run_id,
          analysis_mode: 'standard',
          run_validity: 'VALID',
          policy_verdict: 'NO_POLICY',
          analysis_coverage: { status: 'COMPLETE', reasons: [] },
          findings: [],
          evidence: [],
        },
      })
    }
    if (method === 'GET' && path.endsWith('/buckets')) return route.fulfill({ json: { buckets: [], next_from_ms: null } })
    if (method === 'GET' && /\/advice$/.test(path)) return route.fulfill({ json: { advice: null, job: null } })
    if (method === 'POST' && path === '/api/sources/reload') {
      state.reloadCalls.push({ csrf: request.headers()['x-ltv-csrf'], body: request.postData() ?? '' })
      const reply = await state.reloadHandler()
      return route.fulfill({ status: reply.status, json: reply.json })
    }
    if (method === 'POST' && path === '/api/sources/validate') {
      const parsed = JSON.parse(request.postData() ?? '{}') as { profile_id?: string }
      state.validateBodies.push(parsed)
      const reply = await state.validateHandler(parsed)
      return route.fulfill({ status: reply.status, json: reply.json })
    }
    if (method === 'POST' && path === '/api/sources/probe') {
      const parsed = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>
      state.probeBodies.push(parsed)
      const reply = await state.probeHandler(parsed)
      return route.fulfill({ status: reply.status, json: reply.json })
    }
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })

  return state
}

test('reloads profiles via POST /api/sources/reload, refreshes list, and clears removed selections', async ({ page }) => {
  const state = await setupMockApi(page)
  await page.goto('/?shell=new')

  await expect(page.locator('#source-profile option')).toHaveCount(2)
  await page.locator('#source-profile').selectOption('prom-old')
  await page.getByTestId('reload-sources').click()

  await expect.poll(() => state.reloadCalls.length).toBe(1)
  expect(state.reloadCalls[0]).toEqual({ csrf: 'ui-test', body: '' })

  await expect(page.getByTestId('source-reload-status')).toContainText(SETUP_LABELS.reloadProfilesSummary(2, 2, 0, 1))
  await expect(page.locator('#source-profile option')).toHaveCount(2)
  await expect(page.locator('#source-profile option[value="prom-new"]')).toBeAttached()
  await expect(page.locator('#source-profile option[value="prom-old"]')).toHaveCount(0)
  await expect(page.locator('#postgres-profile option[value="pg-new"]')).toBeAttached()
  expect(await page.locator('#source-profile').evaluate((el: HTMLSelectElement) => Array.from(el.selectedOptions).map((o) => o.value))).toEqual([''])
  await expect(page.locator('#source-probe-query-id')).toHaveCount(0)
})

test('shows closed error codes on reload failure without leaking file text or secrets and preserves old profiles', async ({ page }) => {
  const state = await setupMockApi(page)
  const cases: Array<[number, string]> = [
    [422, 'SOURCE_CONFIG_INVALID'],
    [404, 'SOURCE_RELOAD_NOT_CONFIGURED'],
    [409, 'BUSY'],
    [429, 'RELOAD_TOO_FREQUENT'],
  ]

  await page.goto('/?shell=new')
  await expect(page.locator('#source-profile option[value="prom-old"]')).toBeAttached()

  for (const [status, code] of cases) {
    state.reloadHandler = () => ({
      status,
      json: { error: { code, message: 'SECRET_FILE_TEXT http://secret.internal/api token=SECRET_TOKEN_123' } },
    })
    await page.getByTestId('reload-sources').click()

    const errorBox = page.getByTestId('source-reload-error')
    await expect(errorBox).toBeVisible()
    await expect(errorBox).toContainText(code)
    await expect(errorBox).not.toContainText('SECRET_FILE_TEXT')
    await expect(errorBox).not.toContainText('SECRET_TOKEN_123')
    await expect(errorBox).not.toContainText('http://secret.internal')
    await expect(page.locator('#source-profile option[value="prom-old"]')).toBeAttached()
  }
})

test('validates selected profile offline and executes profile_query-only probe without expression mode', async ({ page }) => {
  const state = await setupMockApi(page)
  await page.goto('/?shell=new')

  await page.locator('#source-profile').selectOption('prom-old')
  await page.getByTestId('validate-sources').click()
  await expect.poll(() => state.validateBodies.length).toBe(1)
  expect(state.validateBodies[0]).toEqual({ profile_id: 'prom-old' })
  await expect(page.getByTestId('source-validation-result')).toContainText('prom-old: config OK, url OK, credentials FAIL (SOURCE_AUTH_UNAVAILABLE), tls SKIPPED')

  // Strictly no ad_hoc / expression field in DOM (ADR 0033 Q1)
  await expect(page.locator('#run-setup')).not.toContainText('ad_hoc')
  await expect(page.locator('#run-setup textarea')).toHaveCount(0)

  await page.getByTestId('probe-source').click()
  await expect(page.getByTestId('source-probe-error')).toHaveText(SETUP_LABELS.probeQueryRequired)
  expect(state.probeBodies).toHaveLength(0)

  await page.getByTestId('source-probe-query-id').fill('cpu')
  await page.getByTestId('probe-source').click()
  await expect.poll(() => state.probeBodies.length).toBe(1)
  expect(state.probeBodies[0]).toEqual({ profile_id: 'prom-old', query_id: 'cpu' })
  expect(state.probeBodies[0]).not.toHaveProperty('expression')

  await expect(page.getByTestId('source-probe-result')).toContainText('Серий: 2')
  await expect(page.getByTestId('source-probe-result')).toContainText('AMBIGUOUS_SERIES')
  await expect(page.getByTestId('source-probe-result')).toContainText('Скрыто значений: 1')
  await expect(page.getByTestId('source-probe-series')).toContainText('instance=app-1, token=***')

  state.probeHandler = () => ({
    status: 200,
    json: {
      schema_version: 'source-probe.v1',
      profile_id: 'prom-old',
      source_kind: 'prometheus',
      transport: 'direct',
      mode: 'profile_query',
      query_id: 'cpu',
      expression_sha256: 'c'.repeat(64),
      window: { start_epoch_ms: 1767225000000, end_epoch_ms: 1767225300000, step_ms: 15000, cells: 20 },
      status: 'FAILED',
      code: 'SOURCE_HTTP_AUTH',
      http_status: 401,
      series_count: null,
      series: [],
      series_truncated: false,
      label_keys: [],
      label_keys_truncated: false,
      redacted_count: 0,
      warnings: [],
      decoder_check: null,
      request_count: 1,
      elapsed_ms: 15,
    },
  })
  await page.getByTestId('probe-source').click()
  await expect(page.getByTestId('source-probe-result')).toContainText('SOURCE_HTTP_AUTH (HTTP 401)')
})

for (const theme of ['light', 'dark'] as const) {
  test(`source reload, validate and probe controls pass axe-core in ${theme} theme`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await setupMockApi(page)
    await page.goto('/?shell=new')

    await page.locator('#source-profile').selectOption('prom-old')
    await page.getByTestId('validate-sources').click()
    await expect(page.getByTestId('source-validation-result')).toBeVisible()
    await page.getByTestId('source-probe-query-id').fill('cpu')
    await page.getByTestId('probe-source').click()
    await expect(page.getByTestId('source-probe-result')).toBeVisible()

    const axe = await new AxeBuilder({ page }).analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

test('source controls reflow cleanly at 320x568 and legacy ?shell=old stays untouched', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await setupMockApi(page)
  await page.goto('/?shell=new')

  await page.locator('#source-profile').selectOption('prom-old')
  await page.getByTestId('validate-sources').click()
  await expect(page.getByTestId('source-validation-result')).toBeVisible()
  await page.getByTestId('source-probe-query-id').fill('cpu')
  await page.getByTestId('probe-source').click()
  await expect(page.getByTestId('source-probe-result')).toBeVisible()

  const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
  expect(width.scroll).toBeLessThanOrEqual(width.client)

  await page.goto('/?shell=old')
  await expect(page.getByRole('heading', { name: 'Run setup' })).toBeVisible()
  await expect(page.getByTestId('reload-sources')).toHaveCount(0)
  await expect(page.getByTestId('validate-sources')).toHaveCount(0)
  await expect(page.getByTestId('probe-source')).toHaveCount(0)
})

test('prunes removed profile IDs from pairSlots on reload so they are neither submitted nor resurrected', async ({ page }) => {
  const state = await setupMockApi(page)
  const armA1: SourceProfile = { id: 'arm-a1', source_kind: 'prometheus', transport: 'direct', arm: 'A' }
  const armA2: SourceProfile = { id: 'arm-a2', source_kind: 'prometheus', transport: 'direct', arm: 'A' }
  const armB: SourceProfile = { id: 'arm-b', source_kind: 'prometheus', transport: 'direct', arm: 'B' }
  state.profiles = [armA1, armA2, armB]

  await page.goto('/?shell=new')
  await expect(page.locator('#source-profile option')).toHaveCount(4)

  await page.getByTestId('input-file').setInputFiles({ name: 'pair.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByTestId('pair-requested').check()
  await page.getByTestId('pair-arm-1').selectOption(['arm-a1'])
  await page.getByTestId('pair-arm-2').selectOption(['arm-b'])
  await page.getByLabel(SETUP_LABELS.stepLabel).fill('15')
  await expect(page.locator('[data-testid="readiness-item"][data-key="arms"]')).toHaveAttribute('data-level', 'ok')
  await expect(page.getByTestId('start-analysis')).toBeEnabled()

  // Reload 1 removes arm-a1: slot 1 becomes empty -> arms blocked and start disabled.
  state.reloadHandler = () => {
    state.profiles = [armA2, armB]
    return {
      status: 200,
      json: {
        schema_version: 'source-reload.v1',
        status: 'RELOADED',
        profile_count: 2,
        profiles: ['arm-a2', 'arm-b'],
        added: [],
        removed: ['arm-a1'],
        changed: [],
        revision: '1'.repeat(64),
      } satisfies SourceReloadResponse,
    }
  }
  await page.getByTestId('reload-sources').click()
  await expect(page.getByTestId('source-reload-status')).toContainText(SETUP_LABELS.reloadProfilesSummary(2, 0, 0, 1))
  await expect(page.locator('[data-testid="readiness-item"][data-key="arms"]')).toHaveAttribute('data-level', 'block')
  await expect(page.locator('[data-testid="readiness-item"][data-key="arms"]')).toContainText(SETUP_LABELS.pairArmEmpty(1))
  await expect(page.getByTestId('start-analysis')).toBeDisabled()

  // Reload 2 restores arm-a1: pruned slot must NOT resurrect arm-a1.
  state.reloadHandler = () => {
    state.profiles = [armA1, armA2, armB]
    return {
      status: 200,
      json: {
        schema_version: 'source-reload.v1',
        status: 'RELOADED',
        profile_count: 3,
        profiles: ['arm-a1', 'arm-a2', 'arm-b'],
        added: ['arm-a1'],
        removed: [],
        changed: [],
        revision: '2'.repeat(64),
      } satisfies SourceReloadResponse,
    }
  }
  await page.getByTestId('reload-sources').click()
  await expect(page.locator('[data-testid="pair-arm-1"] option[value="arm-a1"]')).toBeAttached()
  expect(await page.getByTestId('pair-arm-1').evaluate((el: HTMLSelectElement) => Array.from(el.selectedOptions).map((o) => o.value))).toEqual([])
  await expect(page.locator('[data-testid="readiness-item"][data-key="arms"]')).toHaveAttribute('data-level', 'block')
  await expect(page.getByTestId('start-analysis')).toBeDisabled()

  // Select both arm-a1 and arm-a2, then remove arm-a1 via reload: only arm-a2 is submitted.
  await page.getByTestId('pair-arm-1').selectOption(['arm-a1', 'arm-a2'])
  await expect(page.getByTestId('start-analysis')).toBeEnabled()
  state.reloadHandler = () => {
    state.profiles = [armA2, armB]
    return {
      status: 200,
      json: {
        schema_version: 'source-reload.v1',
        status: 'RELOADED',
        profile_count: 2,
        profiles: ['arm-a2', 'arm-b'],
        added: [],
        removed: ['arm-a1'],
        changed: [],
        revision: '3'.repeat(64),
      } satisfies SourceReloadResponse,
    }
  }
  await page.getByTestId('reload-sources').click()
  await expect(page.locator('[data-testid="pair-arm-1"] option[value="arm-a1"]')).toHaveCount(0)
  expect(await page.getByTestId('pair-arm-1').evaluate((el: HTMLSelectElement) => Array.from(el.selectedOptions).map((o) => o.value))).toEqual(['arm-a2'])
  await expect(page.getByTestId('start-analysis')).toBeEnabled()

  await page.getByTestId('start-analysis').click()
  await expect.poll(() => state.jobBodies.length).toBe(2)
  expect(state.jobBodies[0]).toContain('"profile_ids":["arm-a2"]')
  expect(state.jobBodies[0]).not.toContain('arm-a1')
  expect(state.jobBodies[1]).toContain('"profile_ids":["arm-b"]')
})

test('discards stale in-flight validate and probe responses after switching selected profile', async ({ page }) => {
  const state = await setupMockApi(page)
  state.profiles = [
    { id: 'prom-old', source_kind: 'prometheus', transport: 'direct' },
    { id: 'prom-other', source_kind: 'prometheus', transport: 'direct' },
  ]

  let releaseOldValidate!: (reply: MockReply) => void
  let releaseOldProbe!: (reply: MockReply) => void
  const defaultValidate = state.validateHandler
  const defaultProbe = state.probeHandler

  state.validateHandler = (body) => {
    if (body.profile_id === 'prom-old') {
      return new Promise((resolve) => {
        releaseOldValidate = resolve
      })
    }
    return defaultValidate(body)
  }
  state.probeHandler = (body) => {
    if (body.profile_id === 'prom-old') {
      return new Promise((resolve) => {
        releaseOldProbe = resolve
      })
    }
    return defaultProbe(body)
  }

  await page.goto('/?shell=new')
  await page.locator('#source-profile').selectOption('prom-old')
  await page.getByTestId('source-probe-query-id').fill('cpu')
  await page.getByTestId('validate-sources').click()
  await page.getByTestId('probe-source').click()
  await expect.poll(() => state.validateBodies.length).toBe(1)
  await expect.poll(() => state.probeBodies.length).toBe(1)

  // Switch profile while both requests for prom-old are still in-flight.
  await page.locator('#source-profile').selectOption('prom-other')
  await expect(page.getByTestId('validate-sources')).toBeEnabled()
  await expect(page.getByTestId('probe-source')).toBeEnabled()

  // Resolve stale prom-old responses: neither result nor error must appear.
  releaseOldValidate(defaultValidate({ profile_id: 'prom-old' }))
  releaseOldProbe(defaultProbe({ profile_id: 'prom-old', query_id: 'cpu' }))
  await page.waitForTimeout(50)
  await expect(page.getByTestId('source-validation-result')).toHaveCount(0)
  await expect(page.getByTestId('source-validation-error')).toHaveCount(0)
  await expect(page.getByTestId('source-probe-result')).toHaveCount(0)
  await expect(page.getByTestId('source-probe-error')).toHaveCount(0)

  // Subsequent validate & probe for prom-other display prom-other results.
  await page.getByTestId('validate-sources').click()
  await page.getByTestId('probe-source').click()
  await expect(page.getByTestId('source-validation-result')).toContainText('prom-other:')
  await expect(page.getByTestId('source-probe-result')).toContainText('Серий: 2')
})

test('discards stale in-flight validate and probe responses after reloading profiles', async ({ page }) => {
  const state = await setupMockApi(page)
  let releaseOldValidate!: (reply: MockReply) => void
  let releaseOldProbe!: (reply: MockReply) => void
  const defaultValidate = state.validateHandler
  const defaultProbe = state.probeHandler

  state.validateHandler = (body) => {
    if (body.profile_id === 'prom-old') {
      return new Promise((resolve) => {
        releaseOldValidate = resolve
      })
    }
    return defaultValidate(body)
  }
  state.probeHandler = (body) => {
    if (body.profile_id === 'prom-old') {
      return new Promise((resolve) => {
        releaseOldProbe = resolve
      })
    }
    return defaultProbe(body)
  }

  await page.goto('/?shell=new')
  await page.locator('#source-profile').selectOption('prom-old')
  await page.getByTestId('source-probe-query-id').fill('cpu')
  await page.getByTestId('validate-sources').click()
  await page.getByTestId('probe-source').click()
  await expect.poll(() => state.validateBodies.length).toBe(1)
  await expect.poll(() => state.probeBodies.length).toBe(1)

  // Reload profiles while validate and probe for prom-old are still in-flight.
  await page.getByTestId('reload-sources').click()
  await expect(page.getByTestId('source-reload-status')).toContainText(SETUP_LABELS.reloadProfilesSummary(2, 2, 0, 1))

  // Stale validate resolves with data, stale probe fails with 500: both must be ignored.
  releaseOldValidate(defaultValidate({ profile_id: 'prom-old' }))
  releaseOldProbe({ status: 500, json: { error: { code: 'SOURCE_HTTP_5XX', message: 'stale failure' } } })
  await page.waitForTimeout(50)
  await expect(page.getByTestId('source-validation-result')).toHaveCount(0)
  await expect(page.getByTestId('source-validation-error')).toHaveCount(0)
  await expect(page.getByTestId('source-probe-result')).toHaveCount(0)
  await expect(page.getByTestId('source-probe-error')).toHaveCount(0)
})
