import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { RULES_LABELS } from '../src/shell/labels.rules'
import { SETUP_LABELS } from '../src/shell/labels'

const run = { run_id: 'trial-run', source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'trial.jtl' }
const otherRun = { ...run, run_id: 'trial-run-other', original_filename: 'trial-other.jtl' }
const trialAnalysisId = 'd'.repeat(64)
const metric = (id: string, scope: Record<string, unknown>) => ({
  id, type: 'metric_summary', scope, sample_count: 30, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 30 },
  throughput_rps: { numerator: 30, denominator: 1 }, latency_ms: { p50: 10, p95: 2340, p99: 30, max: 40 },
})
const failResult = {
  schema_version: 'analysis-result.v1', run_id: run.run_id, analysis_mode: 'standard', run_validity: 'VALID',
  policy_verdict: 'FAIL', analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [
    metric('m-overall', { kind: 'overall' }),
    { id: 'check-p95', type: 'policy_check', rule_id: 'overall-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 1000, status: 'FAIL', metric_evidence_id: 'm-overall', observed: 2340 },
  ],
}
const oldAnalysisId = 'e'.repeat(64)
const noPolicyResult = { ...failResult, policy_verdict: 'NO_POLICY', evidence: [metric('m-overall', { kind: 'overall' })] }
const job = { job_id: 'trial-job', state: 'QUEUED', processed_bytes: 0, total_bytes: 100, run_id: run.run_id, analysis_id: null, diagnostic: null }

interface Options { holdJob?: boolean; jobFinal?: 'COMPLETE' | 'FAILED'; jobError?: { status: number; code: string; message: string } }

async function fixtureApi(page: Page, options: Options = {}) {
  const calls = { jobs: [] as string[], inputUploads: 0, results: 0, validations: 0, holdValidate: false }
  let releaseJob: () => void = () => {}
  const jobGate = options.holdJob ? new Promise<void>((resolve) => { releaseJob = resolve }) : Promise.resolve()
  let releaseValidate: () => void = () => {}
  let validateGate: Promise<void> = Promise.resolve()
  let jobCompleted = false
  let validateReleased = true
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1000000 } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [] } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) return route.fulfill({ json: { profiles: [] } })
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: [run, otherRun], next_after: null } })
    if (path === '/api/inputs') { calls.inputUploads += 1; return route.fulfill({ status: 500, json: { error: { code: 'UNEXPECTED', message: 'no upload expected', details: [] } } }) }
    if (path === '/api/policies/validate') {
      calls.validations += 1
      const draft = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      if (calls.holdValidate) {
        calls.holdValidate = false
        validateReleased = false
        validateGate = new Promise<void>((resolve) => { releaseValidate = () => { validateReleased = true; resolve() } })
        await validateGate
      }
      return route.fulfill({ json: { valid: true, policy: draft, sha256: 'c'.repeat(64) } })
    }
    if (method === 'POST' && path === '/api/jobs') {
      calls.jobs.push(route.request().postDataBuffer()?.toString('latin1') ?? '')
      if (options.jobError) return route.fulfill({ status: options.jobError.status, json: { error: { code: options.jobError.code, message: options.jobError.message, details: [] } } })
      return route.fulfill({ json: job })
    }
    if (method === 'GET' && path === `/api/jobs/${job.job_id}`) {
      await jobGate
      if (options.jobFinal === 'FAILED') return route.fulfill({ json: { ...job, state: 'FAILED', diagnostic: { code: 'TRIAL_FAILED', message: 'core failed', source_offset: null } } })
      jobCompleted = true
      return route.fulfill({ json: { ...job, state: 'COMPLETE', processed_bytes: 100, analysis_id: trialAnalysisId } })
    }
    if (method === 'DELETE' && path === `/api/jobs/${job.job_id}`) return route.fulfill({ json: { ...job, state: 'CANCELLED' } })
    if (path.endsWith('/analyses')) {
      const older = { analysis_id: oldAnalysisId, policy_sha256: null, policy_id: null, policy_verdict: 'NO_POLICY', run_validity: 'VALID' }
      const trial = { analysis_id: trialAnalysisId, policy_sha256: 'c'.repeat(64), policy_id: 'template-api-basic', policy_verdict: 'FAIL', run_validity: 'VALID' }
      return route.fulfill({ json: { analyses: jobCompleted ? [trial, older] : [older], next_after: null } })
    }
    if (path.endsWith('/result')) {
      if (path.includes(trialAnalysisId)) calls.results += 1
      return route.fulfill({ json: path.includes(trialAnalysisId) ? failResult : noPolicyResult })
    }
    if (path.endsWith('/buckets')) return route.fulfill({ json: { buckets: [], next_from_ms: null } })
    if (path.endsWith('/advice')) return route.fulfill({ json: { advice: null, job: null } })
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })
  return {
    calls,
    releaseJob: () => releaseJob(),
    releaseValidate: () => releaseValidate(),
    validateReleased: () => validateReleased,
  }
}

const partNames = (body: string) => [...body.matchAll(/; name="([^"]+)"/g)].map((match) => match[1])
const trialButton = (page: Page) => page.getByRole('button', { name: RULES_LABELS.trialButton })

async function openRules(page: Page, selectRun: boolean, options: Options = {}) {
  const api = await fixtureApi(page, options)
  await page.goto('/?shell=new')
  if (selectRun) await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator('#shell-tab-rules').click()
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('template-api-basic')
  await expect.poll(() => api.calls.validations).toBeGreaterThan(0)
  return api
}

test('trial run posts the existing run id and the draft policy without re-uploading', async ({ page }) => {
  const { calls } = await openRules(page, true)
  await expect(trialButton(page)).toBeEnabled()
  // Имя файла у разных прогонов может совпадать: рядом с ним показан хэш прогона.
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.trialTarget(run.original_filename, run.sha256.slice(0, 8)))
  await trialButton(page).click()
  await expect.poll(() => calls.jobs.length).toBe(1)
  expect(calls.inputUploads).toBe(0)
  expect(partNames(calls.jobs[0])).toEqual(['run_id', 'policy'])
  expect(calls.jobs[0]).toContain(run.run_id)
  const summary = page.getByTestId('trial-summary')
  await expect(summary).toContainText('Прогон не проходит')
  await expect(summary).toContainText('нарушено проверок: 1 из 1')
  // Значок вердикта есть, а число нарушений не повторяется в скобках.
  await expect(summary.locator('.status-text')).toHaveText('FAIL')
  await expect(summary.locator('.status-text')).toHaveAttribute('data-status', 'FAIL')
  expect((await summary.innerText()).match(/нарушено/g)).toHaveLength(1)
  await expect(page.locator('#shell-tab-rules')).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('button', { name: RULES_LABELS.openOverview }).click()
  await expect(page.locator('#shell-tab-overview')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#verdict')).toContainText('FAIL')
})

test('editing the draft after a trial hides the stale trial summary', async ({ page }) => {
  await openRules(page, true)
  await trialButton(page).click()
  await expect(page.getByTestId('trial-summary')).toBeVisible()
  await page.getByLabel(RULES_LABELS.threshold).first().fill('900')
  await expect(page.getByTestId('trial-summary')).toHaveCount(0)
})

test('trial is blocked without a run and a double click sends one job', async ({ page }) => {
  const { calls } = await openRules(page, false)
  await expect(trialButton(page)).toBeDisabled()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.trialNoRun)
  await page.locator('#shell-tab-overview').click()
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator('#shell-tab-rules').click()
  await expect(trialButton(page)).toBeEnabled()
  await trialButton(page).dblclick()
  await expect.poll(() => calls.jobs.length).toBe(1)
  await expect(page.getByTestId('trial-summary')).toBeVisible()
  expect(calls.jobs.length).toBe(1)
})

test('trial is blocked while the draft has no policy', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator('#shell-tab-rules').click()
  await expect(trialButton(page)).toBeDisabled()
  await expect(page.locator('#rules-panel')).toContainText(RULES_LABELS.trialNoPolicy)
})

test('cancelling the job while the trial is polling does not publish the result and unlocks the button', async ({ page }) => {
  const api = await openRules(page, true, { holdJob: true })
  await trialButton(page).click()
  await expect.poll(() => api.calls.jobs.length).toBe(1)
  await expect(page.getByTestId('run-list').getByRole('button', { name: otherRun.original_filename })).toBeDisabled()
  await page.getByRole('button', { name: 'Cancel analysis' }).click()
  await expect(page.locator('#job-status')).toContainText('CANCELLED')
  api.releaseJob()
  await expect(trialButton(page)).toBeEnabled()
  await expect(page.getByTestId('trial-summary')).toHaveCount(0)
  await expect(page.locator('#shell-tab-rules')).toHaveAttribute('aria-selected', 'true')
  expect(api.calls.results).toBe(0)
})

test('editing the draft while the trial is polling drops the summary of the older draft', async ({ page }) => {
  const api = await openRules(page, true, { holdJob: true })
  await trialButton(page).click()
  await expect.poll(() => api.calls.jobs.length).toBe(1)
  await page.getByLabel(RULES_LABELS.threshold).first().fill('900')
  api.releaseJob()
  await expect(page.locator('#job-status')).toContainText('COMPLETE')
  await expect(trialButton(page)).toBeEnabled()
  await expect(page.getByTestId('trial-summary')).toHaveCount(0)
})

test('a failed trial job does not present the previously open analysis as the trial result', async ({ page }) => {
  const { calls } = await fixtureApi(page, { jobFinal: 'FAILED' })
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator(`button[title="${oldAnalysisId}"]`).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  await page.locator('#shell-tab-rules').click()
  await page.getByRole('button', { name: RULES_LABELS.templateName('api-basic') }).click()
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('template-api-basic')
  await trialButton(page).click()
  await expect(page.locator('#job-status')).toContainText('FAILED')
  await expect(trialButton(page)).toBeEnabled()
  await expect(page.getByTestId('trial-summary')).toHaveCount(0)
  expect(calls.jobs.length).toBe(1)
})

test('the run list and the setup form are locked from the click until the trial ends', async ({ page }) => {
  const api = await openRules(page, true)
  await page.locator('#shell-tab-setup').click()
  await page.getByTestId('input-file').setInputFiles({ name: 'x.jtl', mimeType: 'text/csv', buffer: Buffer.from('a b') })
  await expect(page.getByRole('button', { name: SETUP_LABELS.startButton })).toBeEnabled()
  await page.locator('#shell-tab-rules').click()
  api.calls.holdValidate = true
  await trialButton(page).click()
  await expect.poll(() => api.validateReleased()).toBe(false)
  await expect(page.getByTestId('run-list').getByRole('button', { name: otherRun.original_filename })).toBeDisabled()
  await page.locator('#shell-tab-setup').click()
  await expect(page.getByRole('button', { name: SETUP_LABELS.startButton })).toBeDisabled()
  api.releaseValidate()
  await expect.poll(() => api.calls.jobs.length).toBe(1)
  await expect(page.getByRole('button', { name: SETUP_LABELS.startButton })).toBeEnabled()
  await page.locator('#shell-tab-rules').click()
  await expect(page.getByTestId('trial-summary')).toBeVisible()
  await expect(page.getByTestId('run-list').getByRole('button', { name: otherRun.original_filename })).toBeEnabled()
})

test('BUSY from the queue is shown and does not leave the trial button locked', async ({ page }) => {
  await openRules(page, true, { jobError: { status: 409, code: 'BUSY', message: 'Analysis queue is full' } })
  await trialButton(page).click()
  await expect(page.getByTestId('busy-notice')).toBeVisible()
  await expect(trialButton(page)).toBeEnabled()
})

for (const theme of ['light', 'dark'] as const) {
  test(`trial section with a summary has no serious axe violations and no horizontal scroll in ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await page.setViewportSize({ width: 320, height: 800 })
    await openRules(page, true)
    await trialButton(page).click()
    await expect(page.getByTestId('trial-summary')).toBeVisible()
    const axe = await new AxeBuilder({ page }).analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  })
}
