import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { SETUP_LABELS } from '../src/shell/labels'
import { pairLaunchBlockers, pairSourceRequests } from '../src/shell/setup'
import type { SourceProfile, SourceRequest } from '../src/types'

// Запуск пары плеч (платформа, P1d): один вход, по заданию на плечо, общие окно и явный шаг. API целиком подменён.
const reference = { run_id: 'pair-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter_jtl_csv', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'pair.jtl' }
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [],
}
const profiles: SourceProfile[] = [
  { id: 'prom-a1', source_kind: 'prometheus', transport: 'direct', arm: 'A' },
  { id: 'prom-a2', source_kind: 'prometheus', transport: 'direct', arm: 'A' },
  { id: 'prom-b', source_kind: 'prometheus', transport: 'direct', arm: 'B' },
  { id: 'prom-plain', source_kind: 'prometheus', transport: 'direct' },
  { id: 'app-logs', source_kind: 'opensearch', transport: 'direct' },
]

interface Calls {
  inputs: number
  jobs: string[]
  jobPolls: number
}

async function fixtureApi(page: Page): Promise<Calls> {
  const calls: Calls = { inputs: 0, jobs: [], jobPolls: 0 }
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    let body: unknown
    let status = 200
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana')) body = { profiles: [] }
    else if (method === 'GET' && path === '/api/sources') body = { profiles }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path === '/api/inputs') {
      calls.inputs += 1
      status = 201
      body = run
    } else if (method === 'POST' && path === '/api/jobs') {
      calls.jobs.push(request.postDataBuffer()!.toString())
      status = 202
      body = { job_id: `job-${calls.jobs.length}`, state: 'COMPLETE', processed_bytes: 100, total_bytes: 100, ...reference, diagnostic: null }
    } else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (path.startsWith('/api/jobs/')) {
      calls.jobPolls += 1
      body = { job_id: path.split('/').pop(), state: 'COMPLETE', processed_bytes: 100, total_bytes: 100, ...reference, diagnostic: null }
    } else if (path.endsWith('/analyses')) body = { analyses: [], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else if (/\/advice$/.test(path)) body = { advice: null, job: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ status, json: body })
  })
  return calls
}

const load = { name: 'pair.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') }
const start = (page: Page) => page.getByTestId('start-analysis')
const readiness = (page: Page, key: string) => page.locator(`[data-testid="readiness-item"][data-key="${key}"]`)
const armSelect = (page: Page, number: number) => page.getByLabel(SETUP_LABELS.pairArmLabel(number))
const requestOf = (body: string) => body.match(/\{"schema_version":"source-request\.v3".*?\}\}/)![0]

async function openPair(page: Page) {
  const calls = await fixtureApi(page)
  await page.goto('/?shell=new')
  await expect(page.locator('#source-profile option')).toHaveCount(profiles.length + 1)
  await page.getByTestId('input-file').setInputFiles(load)
  await page.getByLabel(SETUP_LABELS.pairToggleLabel).check()
  return calls
}

async function fillStep(page: Page) {
  await page.getByLabel(SETUP_LABELS.stepLabel).fill('15')
}

test('launches one job per arm over one uploaded input with the same window and step', async ({ page }) => {
  const calls = await openPair(page)
  await armSelect(page, 1).selectOption(['prom-a1', 'prom-a2'])
  await armSelect(page, 2).selectOption(['prom-b', 'app-logs'])
  await fillStep(page)
  await expect(readiness(page, 'arms')).toHaveAttribute('data-level', 'ok')
  await expect(start(page)).toBeEnabled()
  await start(page).click()

  await expect.poll(() => calls.jobs.length).toBe(2)
  expect(calls.inputs).toBe(1)
  const window = '"window":{"origin":"auto","step_ms":15000,"margin_ms":0,"max_idle_gap_ms":60000}}'
  expect(requestOf(calls.jobs[0])).toBe(`{"schema_version":"source-request.v3","profile_ids":["prom-a1","prom-a2"],${window}`)
  expect(requestOf(calls.jobs[1])).toBe(`{"schema_version":"source-request.v3","profile_ids":["app-logs","prom-b"],${window}`)
  for (const body of calls.jobs) {
    expect(body).toContain('name="run_id"')
    expect(body).toContain(`${reference.run_id}`)
    expect(body).not.toContain('resource_snapshot')
  }
})

test('says in the readiness list that there is no combined arm verdict', async ({ page }) => {
  await openPair(page)
  await armSelect(page, 1).selectOption(['prom-a1'])
  await armSelect(page, 2).selectOption(['prom-b'])
  await fillStep(page)
  await expect(page.getByTestId('readiness-will')).toContainText(SETUP_LABELS.willPair(2))
  await expect(readiness(page, 'arms')).toContainText(SETUP_LABELS.pairOk(['A', 'B']))
})

test('blocks the start while the arms declare the same label', async ({ page }) => {
  await openPair(page)
  await armSelect(page, 1).selectOption(['prom-a1'])
  await armSelect(page, 2).selectOption(['prom-a2'])
  await fillStep(page)
  await expect(readiness(page, 'arms')).toHaveAttribute('data-level', 'block')
  await expect(readiness(page, 'arms')).toContainText(SETUP_LABELS.pairSame('A'))
  await expect(start(page)).toBeDisabled()
})

test('blocks the start while an arm has no profiles', async ({ page }) => {
  await openPair(page)
  await armSelect(page, 1).selectOption(['prom-a1'])
  await fillStep(page)
  await expect(readiness(page, 'arms')).toContainText(SETUP_LABELS.pairArmEmpty(2))
  await expect(start(page)).toBeDisabled()
})

test('blocks the start while a profile does not declare its arm', async ({ page }) => {
  await openPair(page)
  await armSelect(page, 1).selectOption(['prom-a1'])
  await armSelect(page, 2).selectOption(['prom-plain'])
  await fillStep(page)
  await expect(readiness(page, 'arms')).toContainText(SETUP_LABELS.pairNoArm(2, 'prom-plain'))
  await expect(start(page)).toBeDisabled()
})

test('blocks the start until the shared step is set explicitly', async ({ page }) => {
  await openPair(page)
  await armSelect(page, 1).selectOption(['prom-a1'])
  await armSelect(page, 2).selectOption(['prom-b'])
  await expect(readiness(page, 'sources')).toHaveAttribute('data-level', 'block')
  await expect(start(page)).toBeDisabled()
  await fillStep(page)
  await expect(start(page)).toBeEnabled()
})

test('leaves the single launch alone when the pair switch is off', async ({ page }) => {
  const calls = await fixtureApi(page)
  await page.goto('/?shell=new')
  await expect(page.locator('#source-profile option')).toHaveCount(profiles.length + 1)
  await page.getByTestId('input-file').setInputFiles(load)
  await expect(readiness(page, 'arms')).toHaveCount(0)
  await page.getByTestId('source-profile').selectOption(['prom-a1', 'prom-a2'])
  await fillStep(page)
  await start(page).click()
  await expect.poll(() => calls.jobs.length).toBe(1)
  expect(requestOf(calls.jobs[0])).toContain('"profile_ids":["prom-a1","prom-a2"]')
})

test('the pair section has no accessibility violations', async ({ page }) => {
  await openPair(page)
  await armSelect(page, 1).selectOption(['prom-a1'])
  const scan = await new AxeBuilder({ page }).include('#run-setup').analyze()
  expect(scan.violations).toEqual([])
})

const catalog: SourceProfile[] = profiles

test('pairLaunchBlockers names each reason once and passes a clean pair', () => {
  expect(pairLaunchBlockers([['prom-a1', 'app-logs'], ['prom-b']], catalog)).toEqual([])
  expect(pairLaunchBlockers([['prom-a1'], ['prom-a2']], catalog)).toEqual([SETUP_LABELS.pairSame('A')])
  expect(pairLaunchBlockers([[], ['app-logs']], catalog)).toEqual([SETUP_LABELS.pairArmEmpty(1), SETUP_LABELS.pairArmEmpty(2)])
  expect(pairLaunchBlockers([['prom-a1', 'prom-b'], ['prom-plain']], catalog)).toEqual([SETUP_LABELS.pairMixed(1), SETUP_LABELS.pairNoArm(2, 'prom-plain')])
})

test('pairSourceRequests copies the shared window into one request per arm', () => {
  const base: SourceRequest = {
    schema_version: 'source-request.v3',
    profile_ids: ['prom-a2', 'prom-b'],
    window: { origin: 'auto', step_ms: 15000, margin_ms: 0, max_idle_gap_ms: 60000 },
  }
  expect(pairSourceRequests(base, [['prom-b'], ['prom-a2', 'prom-a1']])).toEqual([
    { ...base, profile_ids: ['prom-b'] },
    { ...base, profile_ids: ['prom-a1', 'prom-a2'] },
  ])
})
