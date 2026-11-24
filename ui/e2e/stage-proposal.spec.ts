import AxeBuilder from '@axe-core/playwright'
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { SETUP_LABELS } from '../src/shell/labels'
import type { StageProposalResponse } from '../src/types'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const anchorJtl = fileURLToPath(new URL('../../fixtures/stages/ramp-steady-rampdown.jtl', import.meta.url))
const anchorStages = fileURLToPath(new URL('../../docs/contracts/stages/v1/examples/valid/ramp-steady-down.json', import.meta.url))
const invalidNoSteady = fileURLToPath(new URL('../../docs/contracts/stages/v1/examples/invalid/no-steady.json', import.meta.url))

const anchorPolicy = {
  schema_version: 'policy.v1',
  policy_id: 'stage-proposal-e2e',
  defaults: { sample_floor: 1, min_samples: 1 },
  rules: [
    { id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 250, scope: { kind: 'overall' } },
    { id: 'rps', metric: 'throughput_rps', operator: 'gte', threshold: 9, scope: { kind: 'overall' } },
  ],
}

const mockRunA = { run_id: `jmeter_jtl_csv-${'a'.repeat(64)}`, source_type: 'jmeter_jtl_csv', sha256: 'a'.repeat(64), size_bytes: 100, original_filename: 'run-a.jtl' }
const mockRunB = { run_id: `jmeter_jtl_csv-${'b'.repeat(64)}`, source_type: 'jmeter_jtl_csv', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'run-b.jtl' }
const failedJob = { job_id: 'job-mock-1', state: 'FAILED', processed_bytes: 1, total_bytes: 1, run_id: mockRunA.run_id, analysis_id: null, diagnostic: { code: 'TEST_DONE', message: 'done' } }

const detectedProposal: StageProposalResponse = {
  status: 'DETECTED',
  reason: null,
  detail: null,
  method: 'rps-plateau-segmentation.v2',
  steady_steps: 'top',
  confidence: 'HIGH',
  calibrated: false,
  run_start_epoch_ms: 1767225600000,
  run_end_epoch_ms: 1767225719800,
  cell_ms: 5000,
  cells: 23,
  noise_rps: 0.2,
  segments: [
    { from_offset_ms: 0, to_offset_ms: 40000, kind: 'FLAT', level_rps: 2, drift: 0, residual_cv: 0 },
    { from_offset_ms: 40000, to_offset_ms: 100000, kind: 'FLAT', level_rps: 10, drift: 0, residual_cv: 0 },
    { from_offset_ms: 100000, to_offset_ms: 115000, kind: 'FLAT', level_rps: 2, drift: 0, residual_cv: 0 },
  ],
  boundaries: [
    { stage_id: 'ramp-up', basis: 'BEFORE_FIRST_STEADY', level_rps: null, from_contrast_sigma: null, to_contrast_sigma: null, trimmed_cells: 0 },
    { stage_id: 'steady', basis: 'PLATEAU', level_rps: 10, from_contrast_sigma: 40, to_contrast_sigma: 40, trimmed_cells: 0 },
    { stage_id: 'ramp-down', basis: 'AFTER_LAST_STEADY', level_rps: null, from_contrast_sigma: null, to_contrast_sigma: null, trimmed_cells: 0 },
  ],
  stretches: [],
  warnings: [],
  proposal: {
    schema_version: 'load-stages.v1',
    stages: [
      { id: 'ramp-up', role: 'excluded', from_offset_ms: 0, to_offset_ms: 40000 },
      { id: 'steady', role: 'steady', from_offset_ms: 40000, to_offset_ms: 100000 },
      { id: 'ramp-down', role: 'excluded', from_offset_ms: 100000, to_offset_ms: 120000 },
    ],
  },
}

interface MockCalls {
  inputs: number
  proposals: string[]
  jobs: string[]
}

async function mockStageApi(page: Page, proposalForRun: (runId: string) => StageProposalResponse = () => detectedProposal): Promise<MockCalls> {
  const calls: MockCalls = { inputs: 0, proposals: [], jobs: [] }
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1_000_000 } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) {
      return route.fulfill({ json: { profiles: [] } })
    }
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: [], next_after: null } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [] } })
    if (path === '/api/policies/validate') {
      return route.fulfill({ json: { valid: true, policy: anchorPolicy, errors: [] } })
    }
    if (path === '/api/inputs') {
      calls.inputs += 1
      const run = calls.inputs === 1 ? mockRunA : mockRunB
      return route.fulfill({ status: 201, json: run })
    }
    const proposalMatch = /^\/api\/runs\/([^/]+)\/stage-proposal$/.exec(path)
    if (method === 'POST' && proposalMatch) {
      const runId = decodeURIComponent(proposalMatch[1])
      calls.proposals.push(runId)
      return route.fulfill({ json: proposalForRun(runId) })
    }
    if (method === 'POST' && path === '/api/jobs') {
      const body = request.postDataBuffer()!.toString()
      calls.jobs.push(body)
      return route.fulfill({ status: 202, json: failedJob })
    }
    if (path.endsWith('/analyses')) return route.fulfill({ json: { analyses: [], next_after: null } })
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })
  return calls
}

function multipartPart(body: string, name: string): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = new RegExp(`name="${escaped}"(?:; filename="([^"]+)")?\\r\\n(?:Content-Type: [^\\r\\n]+\\r\\n)?\\r\\n([\\s\\S]*?)\\r\\n--`).exec(body)
  return match ? match[2] : null
}

test('proposes stages on real server, requires explicit confirmation, caches upload, and matches CLI analysis_id', async ({ page }) => {
  let inputsCount = 0
  let proposalsCount = 0
  let jobsCount = 0
  let jobHasStagesFilePart = false
  let submittedAnalysisId = ''
  await page.route('**/api/jobs', async (route) => {
    const req = route.request()
    if (req.method() === 'POST') {
      const raw = req.postDataBuffer()?.toString('utf8') ?? ''
      jobHasStagesFilePart = raw.includes('name="stages"; filename="load-stages.json"')
    }
    await route.continue()
  })
  page.on('request', (req) => {
    const pathname = new URL(req.url()).pathname
    if (req.method() === 'POST' && pathname === '/api/inputs') inputsCount += 1
    if (req.method() === 'POST' && /^\/api\/runs\/[^/]+\/stage-proposal$/.test(pathname)) proposalsCount += 1
    if (req.method() === 'POST' && pathname === '/api/jobs') jobsCount += 1
  })
  page.on('response', async (res) => {
    const pathname = new URL(res.url()).pathname
    if (res.request().method() === 'GET' && /^\/api\/jobs\/[^/]+$/.test(pathname)) {
      const status = await res.json().catch(() => null) as { state?: string; analysis_id?: string | null } | null
      if (status?.state === 'COMPLETE' && status.analysis_id) submittedAnalysisId = status.analysis_id
    }
  })

  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped
  await page.locator('#input-file').setInputFiles(anchorJtl)
  await page.locator('#policy-file').setInputFiles({
    name: 'policy.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(anchorPolicy)),
  })
  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toHaveAttribute('data-level', 'ok')

  await page.getByTestId('stages-propose').click()

  const stagesField = page.getByTestId('stages-json')
  await expect(stagesField).toHaveValue(/"id": "steady"/)
  await expect(stagesField).toHaveValue(/40000/)
  const explanation = page.getByTestId('stages-explanation')
  await expect(explanation).toContainText(SETUP_LABELS.stagesConfidence.HIGH)
  await expect(explanation).toContainText(SETUP_LABELS.stagesUncalibrated)
  await expect(page.getByTestId('readiness-will')).toContainText(SETUP_LABELS.willStages)

  // Explicit confirmation: proposing stages does not launch analysis automatically.
  expect(inputsCount).toBe(1)
  expect(proposalsCount).toBe(1)
  expect(jobsCount).toBe(0)

  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.getByTestId('verdict-chip')).toContainText('PASS')
  await expect(page.locator('#verdict')).toContainText('по окну steady')

  // Same File was cached after proposal, so start-analysis did not re-upload.
  expect(inputsCount).toBe(1)
  expect(jobsCount).toBe(1)
  expect(jobHasStagesFilePart).toBe(true)
  expect(submittedAnalysisId).toMatch(/^[0-9a-f]{64}$/)

  const ltvBin = join(repoRoot, 'build', 'install', 'ltv', 'bin', process.platform === 'win32' ? 'ltv.bat' : 'ltv')
  if (existsSync(ltvBin)) {
    const cliDir = await mkdtemp(join(tmpdir(), 'ltv-e2e-stages-cli-'))
    try {
      const cliPolicyPath = join(cliDir, 'policy.json')
      await writeFile(cliPolicyPath, JSON.stringify(anchorPolicy))
      const dataDir = join(cliDir, 'data')
      const cliArgs = ['analyze', anchorJtl, '--policy', cliPolicyPath, '--stages', anchorStages, '--data-dir', dataDir]
      const cli = process.platform === 'win32'
        ? spawnSync(process.env.ComSpec ?? 'cmd.exe', ['/c', ltvBin, ...cliArgs], { cwd: repoRoot, encoding: 'utf8' })
        : spawnSync(ltvBin, cliArgs, { cwd: repoRoot, encoding: 'utf8' })
      expect(cli.status, cli.stderr).toBe(0)
      const cliMatch = /analysis_id=([0-9a-f]{64})/.exec(cli.stderr)
      expect(cliMatch?.[1]).toBe(submittedAnalysisId)
    } finally {
      await rm(cliDir, { recursive: true, force: true })
    }
  }
})

test('user can edit proposed JSON before launching and POST /api/jobs receives the edited stages', async ({ page }) => {
  const calls = await mockStageApi(page)
  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped
  await page.locator('#input-file').setInputFiles({ name: 'run-a.jtl', mimeType: 'text/csv', buffer: Buffer.from('a') })

  await page.getByTestId('stages-propose').click()
  const textarea = page.getByTestId('stages-json')
  await expect(textarea).toHaveValue(/"id": "steady"/)

  const edited = (await textarea.inputValue()).replace('"id": "steady"', '"id": "steady-edited"')
  await textarea.fill(edited)
  await page.getByTestId('start-analysis').click()

  await expect.poll(() => calls.jobs.length).toBe(1)
  const sentStages = multipartPart(calls.jobs[0], 'stages')
  expect(sentStages).not.toBeNull()
  expect(JSON.parse(sentStages!).stages.map((s: { id: string }) => s.id)).toContain('steady-edited')
})

test('NOT_DETECTED refusal keeps textarea unchanged and displays Russian reason and detail', async ({ page }) => {
  await mockStageApi(page, () => ({
    ...detectedProposal,
    status: 'NOT_DETECTED',
    reason: 'NO_PLATEAU',
    detail: 'steady_share=0.000',
    confidence: null,
    boundaries: [],
    proposal: null,
  }))
  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped
  await page.locator('#input-file').setInputFiles({ name: 'ramp.jtl', mimeType: 'text/csv', buffer: Buffer.from('ramp') })

  const customJson = '{"schema_version":"load-stages.v1","stages":[{"id":"manual","role":"steady","from_offset_ms":1000,"to_offset_ms":2000}]}'
  await page.getByTestId('stages-json').fill(customJson)
  await page.getByTestId('stages-propose').click()

  const explanation = page.getByTestId('stages-explanation')
  await expect(explanation).toContainText(SETUP_LABELS.stagesRefusals.NO_PLATEAU)
  await expect(explanation).toContainText('steady_share=0.000')
  await expect(page.getByTestId('stages-json')).toHaveValue(customJson)
})

test('selecting another input file invalidates cached run_id and explanation', async ({ page }) => {
  const calls = await mockStageApi(page)
  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped

  await page.locator('#input-file').setInputFiles({ name: 'run-a.jtl', mimeType: 'text/csv', buffer: Buffer.from('a') })
  await page.getByTestId('stages-propose').click()
  await expect(page.getByTestId('stages-explanation')).toBeVisible()
  expect(calls.inputs).toBe(1)

  await page.locator('#input-file').setInputFiles({ name: 'run-b.jtl', mimeType: 'text/csv', buffer: Buffer.from('b') })
  await expect(page.getByTestId('stages-explanation')).toHaveCount(0)

  await page.getByTestId('start-analysis').click()
  await expect.poll(() => calls.jobs.length).toBe(1)
  expect(calls.inputs).toBe(2)
})

test('422 INVALID_STAGES renders field errors with English island and does not start job', async ({ page }) => {
  const badStages = await readFile(invalidNoSteady, 'utf8')
  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped
  await page.locator('#input-file').setInputFiles(anchorJtl)
  await page.getByTestId('stages-json').fill(badStages)

  await page.getByTestId('start-analysis').click()

  const errors = page.getByTestId('stages-errors')
  await expect(errors).toBeVisible()
  await expect(errors).toContainText('/stages: at least one stage must have the role steady')
  await expect(errors).not.toHaveAttribute('aria-live', /.*/)
  await expect(errors.locator('li')).toHaveAttribute('lang', 'en')
  await expect(page.locator('#job-status')).toHaveCount(0)
})

test('empty or whitespace stages textarea submits POST /api/jobs without stages part', async ({ page }) => {
  const calls = await mockStageApi(page)
  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped
  await page.locator('#input-file').setInputFiles({ name: 'run-a.jtl', mimeType: 'text/csv', buffer: Buffer.from('a') })
  await page.getByTestId('stages-json').fill('   \n  ')

  await page.getByTestId('start-analysis').click()

  await expect.poll(() => calls.jobs.length).toBe(1)
  expect(multipartPart(calls.jobs[0], 'stages')).toBeNull()
})

test('renders Russian warnings and D14 stretch facts in explanation', async ({ page }) => {
  await mockStageApi(page, () => ({
    ...detectedProposal,
    confidence: 'LOW',
    stretches: [
      {
        kind: 'TAIL',
        from_offset_ms: 100000,
        to_offset_ms: 120000,
        reference_id: 'steady',
        samples: 300,
        failures: 30,
        error_ratio: 0.1,
        mean_ms: 850,
        reference_samples: 600,
        reference_failures: 0,
        reference_error_ratio: 0,
        reference_mean_ms: 100,
        verdict: 'DEGRADED',
      },
    ],
    warnings: [
      { code: 'LEAD_IN_IDLE', stage_id: 'lead-in', at_offset_ms: 0, from_rps: null, to_rps: null, duration_ms: 30000, stretch: null },
      {
        code: 'DEGRADED_TAIL_KEPT',
        stage_id: 'steady',
        at_offset_ms: 100000,
        from_rps: 10,
        to_rps: 2,
        duration_ms: 20000,
        stretch: {
          kind: 'TAIL',
          from_offset_ms: 100000,
          to_offset_ms: 120000,
          reference_id: 'steady',
          samples: 300,
          failures: 30,
          error_ratio: 0.1,
          mean_ms: 850,
          reference_samples: 600,
          reference_failures: 0,
          reference_error_ratio: 0,
          reference_mean_ms: 100,
          verdict: 'DEGRADED',
        },
      },
    ],
  }))
  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped
  await page.locator('#input-file').setInputFiles({ name: 'run-a.jtl', mimeType: 'text/csv', buffer: Buffer.from('a') })
  await page.getByTestId('stages-propose').click()

  const explanation = page.getByTestId('stages-explanation')
  await expect(explanation).toContainText(SETUP_LABELS.stagesConfidence.LOW)
  await expect(explanation).toContainText(SETUP_LABELS.stagesWarnings.LEAD_IN_IDLE)
  await expect(explanation).toContainText(SETUP_LABELS.stagesWarnings.DEGRADED_TAIL_KEPT)
  await expect(explanation).toContainText(SETUP_LABELS.stagesStretchKinds.TAIL)
  await expect(explanation).toContainText(SETUP_LABELS.stagesStretchVerdicts.DEGRADED)
})

test('stage proposal UI passes axe-core in light and dark themes and avoids horizontal overflow on narrow viewport', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1_000_000 } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) {
      return route.fulfill({ json: { profiles: [] } })
    }
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: [], next_after: null } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [] } })
    if (path === '/api/inputs') return route.fulfill({ status: 201, json: mockRunA })
    if (method === 'POST' && /^\/api\/runs\/[^/]+\/stage-proposal$/.test(path)) return route.fulfill({ json: detectedProposal })
    if (method === 'POST' && path === '/api/jobs') {
      return route.fulfill({
        status: 422,
        json: {
          error: {
            code: 'INVALID_STAGES',
            message: 'Load stages are invalid',
            details: [{ code: 'STAGES_NO_STEADY', json_pointer: '/stages', message: 'at least one stage must have the role steady' }],
          },
        },
      })
    }
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })

  const bootstrapped = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/bootstrap' && res.ok())
  await page.goto('/?shell=new')
  await bootstrapped
  await page.locator('#input-file').setInputFiles({ name: 'run-a.jtl', mimeType: 'text/csv', buffer: Buffer.from('a') })
  await page.getByTestId('stages-propose').click()
  await expect(page.getByTestId('stages-explanation')).toBeVisible()
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('stages-errors')).toBeVisible()

  for (const width of [1280, 375]) {
    await page.setViewportSize({ width, height: 800 })
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
    expect(overflow, `horizontal overflow at ${width}px`).toBe(false)
  }

  await page.setViewportSize({ width: 1280, height: 800 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate((t) => {
      document.documentElement.dataset.theme = t
      document.documentElement.style.colorScheme = t
    }, theme)
    const audit = await new AxeBuilder({ page }).include('section[aria-labelledby="setup-input-title"]').analyze()
    expect(audit.violations, `axe violations in ${theme} theme`).toEqual([])
  }
})
