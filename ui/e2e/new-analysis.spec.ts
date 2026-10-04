import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { SETUP_LABELS, SETUP_MESSAGES } from '../src/shell/labels'
import { RULES_LABELS } from '../src/shell/labels.rules'

const run = { run_id: `jmeter_jtl_csv-${'b'.repeat(64)}`, source_type: 'jmeter_jtl_csv', sha256: 'b'.repeat(64), size_bytes: 1, original_filename: 'setup.jtl' }
const failedJob = { job_id: 'job-1', state: 'FAILED', processed_bytes: 1, total_bytes: 1, run_id: run.run_id, analysis_id: null, diagnostic: { code: 'TEST_DONE', message: 'finished by the test' } }
const rule = { id: 'overall-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: '100', scope: { kind: 'overall' } }
const profiles = [
  { id: 'prod-prometheus', source_kind: 'prometheus', transport: 'direct' },
  { id: 'app-logs', source_kind: 'opensearch', transport: 'direct' },
  { id: 'pg-main', source_kind: 'postgresql', transport: 'jdbc' },
]

interface Calls {
  jobs: string[]
  inputs: number
  captures: string[]
}

// API целиком подменён; имена частей POST /api/jobs те же, что у настоящего сервера.
async function fixtureApi(page: Page): Promise<Calls> {
  const calls: Calls = { jobs: [], inputs: 0, captures: [] }
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1000000 } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana')) return route.fulfill({ json: { profiles: [] } })
    if (method === 'GET' && path === '/api/sources') return route.fulfill({ json: { profiles } })
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: [], next_after: null } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [] } })
    if (path === '/api/policies/validate') {
      const draft = JSON.parse(request.postData() ?? '{}') as { policy_id: string }
      if (draft.policy_id === 'malformed') return route.fulfill({ status: 400, json: { error: { code: 'MALFORMED_JSON', message: 'malformed' } } })
      if (draft.policy_id === '' || draft.policy_id === 'reject-me') {
        return route.fulfill({ status: 422, json: { valid: false, errors: [{ code: 'POLICY_ID_INVALID', json_pointer: '/policy_id', message: 'policy id is not accepted' }] } })
      }
      return route.fulfill({ json: { valid: true, policy: { schema_version: 'policy.v1', policy_id: draft.policy_id, rules: [rule] }, errors: [] } })
    }
    if (path === '/api/inputs') {
      calls.inputs += 1
      return route.fulfill({ status: 201, json: run })
    }
    if (method === 'POST' && path === '/api/jobs') {
      calls.jobs.push(request.postDataBuffer()!.toString())
      return route.fulfill({ status: 202, json: failedJob })
    }
    if (method === 'POST' && path.startsWith('/api/sources/postgresql/')) {
      calls.captures.push(path.split('/').pop()!)
      return route.fulfill({ json: { phase_json: '{}', pg_profile_html_base64: null } })
    }
    if (path.endsWith('/analyses')) return route.fulfill({ json: { analyses: [], next_after: null } })
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })
  return calls
}

const json = (name: string, body: unknown = {}) => ({ name, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(body)) })
const load = { name: 'setup.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') }
const policyFile = (id = 'mock-policy') => json('policy.json', { schema_version: 'policy.v1', policy_id: id, rules: [rule] })
const start = (page: Page) => page.getByTestId('start-analysis')
const readiness = (page: Page, key: string) => page.locator(`[data-testid="readiness-item"][data-key="${key}"]`)
const partNames = (body: string) => [...body.matchAll(/name="([^"]+)"(?:; filename="([^"]+)")?/g)].map((match) => (match[2] ? `${match[1]}:${match[2]}` : match[1]))

async function openSetup(page: Page, path = '/?shell=new') {
  const calls = await fixtureApi(page)
  await page.goto(path)
  await expect(page.locator('#source-profile option')).toHaveCount(3)
  return calls
}

test('shows the Russian setup screen: five sections, readiness and a disabled start button', async ({ page }) => {
  await openSetup(page)
  const setup = page.locator('#run-setup')

  await expect(setup.getByRole('heading', { level: 2, name: SETUP_LABELS.title })).toBeVisible()
  for (const title of [SETUP_LABELS.inputTitle, SETUP_LABELS.rulesTitle, SETUP_LABELS.systemTitle, SETUP_LABELS.plansTitle, SETUP_LABELS.aiTitle]) {
    await expect(setup.getByRole('heading', { level: 3, name: title })).toBeVisible()
  }
  await expect(page.getByRole('region', { name: SETUP_LABELS.readinessTitle })).toBeVisible()
  await expect(start(page)).toHaveText(SETUP_LABELS.startButton)
  await expect(start(page)).toBeDisabled()
  await expect(readiness(page, 'input')).toHaveAttribute('data-level', 'block')
  await expect(readiness(page, 'input')).toContainText(SETUP_LABELS.inputMissing)
  await expect(page.locator('#readiness-status')).toContainText(SETUP_LABELS.startBlocked)
  for (const text of ['Run setup', 'Load test log', 'Analyze run', '(optional)']) await expect(setup).not.toContainText(text)
})

test('every current field is on the screen with its Russian label and the existing id', async ({ page }) => {
  await openSetup(page)
  const fields: Array<[string, string]> = [
    ['input-file', SETUP_LABELS.inputLabel], ['policy-file', SETUP_LABELS.policyLabel], ['resource-snapshot-file', SETUP_LABELS.resourcesLabel],
    ['correlation-plan-file', SETUP_LABELS.correlationLabel], ['capacity-plan-file', SETUP_LABELS.capacityLabel], ['trend-plan-file', SETUP_LABELS.trendLabel],
    ['source-context-file', SETUP_LABELS.contextLabel], ['source-profile', SETUP_LABELS.sourceProfileLabel], ['postgres-profile', SETUP_LABELS.postgresProfileLabel],
    ['postgres-pre-file', SETUP_LABELS.postgresPreLabel], ['postgres-post-file', SETUP_LABELS.postgresPostLabel], ['pg-profile-html-file', SETUP_LABELS.pgHtmlLabel],
  ]
  for (const [id, label] of fields) {
    await expect(page.locator(`#${id}`), id).toBeVisible()
    await expect(page.locator(`#${id}`), id).toHaveAccessibleName(new RegExp('^' + label.replace(/[()]/g, '\\$&')))
  }
  await expect(page.locator('#source-window-origin')).toHaveCount(0)
})

test('readiness follows the form and the start button follows readiness', async ({ page }) => {
  await openSetup(page)

  await page.locator('#input-file').setInputFiles(load)
  await expect(start(page)).toBeEnabled()
  await expect(readiness(page, 'input')).toHaveAttribute('data-level', 'ok')
  await expect(readiness(page, 'input')).toContainText('setup.jtl')
  await expect(page.locator('#readiness-status')).toContainText(SETUP_LABELS.startReady)
  await expect(readiness(page, 'policy')).toHaveAttribute('data-level', 'info')
  await expect(page.getByTestId('readiness-will')).toContainText(SETUP_LABELS.willNoVerdict)

  await page.locator('#policy-file').setInputFiles(policyFile())
  await expect(readiness(page, 'policy')).toHaveAttribute('data-level', 'ok')
  await expect(readiness(page, 'policy')).toContainText('mock-policy')
  await expect(page.getByTestId('readiness-will')).toContainText(SETUP_LABELS.willVerdict('mock-policy'))

  await page.locator('#input-file').setInputFiles([])
  await expect(start(page)).toBeDisabled()
  await expect(readiness(page, 'input')).toHaveAttribute('data-level', 'block')
})

test('correlation, capacity and trend plans without a snapshot block the start before any request', async ({ page }) => {
  const calls = await openSetup(page)
  await page.locator('#input-file').setInputFiles(load)

  await page.locator('#capacity-plan-file').setInputFiles(json('capacity.json'))
  await page.locator('#trend-plan-file').setInputFiles(json('trend.json'))
  await expect(start(page)).toBeDisabled()
  await expect(readiness(page, 'resources')).toHaveAttribute('data-level', 'block')
  await expect(readiness(page, 'resources')).toContainText(SETUP_LABELS.planNames.capacity)
  await expect(readiness(page, 'resources')).toContainText(SETUP_LABELS.planNames.trend)
  await expect(page.locator('#readiness-status')).toContainText(SETUP_LABELS.startBlocked)
  expect(calls.inputs).toBe(0)

  await page.locator('#resource-snapshot-file').setInputFiles(json('snapshot.json'))
  await expect(start(page)).toBeEnabled()
  await expect(readiness(page, 'resources')).toHaveAttribute('data-level', 'ok')

  await page.locator('#resource-snapshot-file').setInputFiles([])
  await page.locator('#capacity-plan-file').setInputFiles([])
  await page.locator('#trend-plan-file').setInputFiles([])
  await page.locator('#correlation-plan-file').setInputFiles(json('correlation.json'))
  await expect(start(page)).toBeDisabled()
  await expect(readiness(page, 'resources')).toHaveAttribute('data-level', 'block')
  await expect(readiness(page, 'resources')).toContainText(SETUP_LABELS.planNames.diagnostic)
  await expect(page.locator('#readiness-status')).toContainText(SETUP_LABELS.startBlocked)
  expect(calls.inputs).toBe(0)

  await page.locator('#resource-snapshot-file').setInputFiles(json('snapshot.json'))
  await expect(start(page)).toBeEnabled()
})

test('the old form refuses a correlation plan without a snapshot before any upload, in English', async ({ page }) => {
  const calls = await openSetup(page, '/?shell=old')
  await page.getByTestId('input-file').setInputFiles(load)
  await page.getByTestId('correlation-plan-file').setInputFiles(json('correlation.json'))
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()

  await expect(page.getByRole('alert').filter({ hasText: 'Correlation plan requires a matching resource snapshot.' })).toBeVisible()
  expect(calls.inputs).toBe(0)
})

test('an invalid policy draft blocks the start and a rejected policy file does not', async ({ page }) => {
  await openSetup(page)
  await page.locator('#input-file').setInputFiles(load)

  await page.locator('#policy-file').setInputFiles(policyFile('reject-me'))
  await expect(page.locator('#run-setup .field__errors')).toContainText('/policy_id: policy id is not accepted')
  await expect(readiness(page, 'policy')).toHaveAttribute('data-level', 'warn')
  await expect(start(page)).toBeEnabled()

  await page.locator('#policy-file').setInputFiles(policyFile())
  await page.locator('#shell-tab-rules').click()
  await expect(page.getByLabel(RULES_LABELS.policyId)).toHaveValue('mock-policy')
  await page.getByLabel(RULES_LABELS.policyId).fill('')
  await page.locator('#shell-tab-setup').click()
  await expect(readiness(page, 'policy')).toHaveAttribute('data-level', 'block')
  await expect(start(page)).toBeDisabled()
  await page.locator('#shell-tab-rules').click()
  await page.getByLabel(RULES_LABELS.policyId).fill('fixed')
  await page.locator('#shell-tab-setup').click()
  await expect(readiness(page, 'policy')).toHaveAttribute('data-level', 'ok')
  await expect(start(page)).toBeEnabled()
})

test('policy error lists are not live regions, server messages are English islands and the local one is Russian', async ({ page }) => {
  await openSetup(page)
  const errors = page.locator('#run-setup .field__errors')

  await page.locator('#policy-file').setInputFiles(policyFile('reject-me'))
  await expect(errors).toContainText('/policy_id: policy id is not accepted')
  await expect(errors).not.toHaveAttribute('aria-live', /.*/)
  await expect(errors.locator('li')).toHaveAttribute('lang', 'en')

  await page.locator('#policy-file').setInputFiles(policyFile('malformed'))
  await expect(errors).toContainText(SETUP_MESSAGES.policyMalformed)
  await expect(errors.locator('li')).not.toHaveAttribute('lang', /.*/)
  await expect(page.locator('#readiness-status')).toHaveAttribute('role', 'status')
  await expect(start(page)).toHaveAttribute('aria-describedby', 'readiness-status')
})

async function fillEverythingFromFiles(page: Page) {
  await page.locator('#input-file').setInputFiles(load)
  await page.locator('#policy-file').setInputFiles(policyFile())
  await expect(page.locator('#policy-file')).toBeVisible()
  await page.locator('#resource-snapshot-file').setInputFiles(json('snapshot.json'))
  await page.locator('#correlation-plan-file').setInputFiles(json('correlation.json'))
  await page.locator('#capacity-plan-file').setInputFiles(json('capacity.json'))
  await page.locator('#trend-plan-file').setInputFiles(json('trend.json'))
  await page.locator('#source-context-file').setInputFiles([json('context-a.json'), json('context-b.json')])
  await page.locator('#postgres-pre-file').setInputFiles(json('pre.json'))
  await page.locator('#postgres-post-file').setInputFiles(json('post.json'))
  await page.locator('#pg-profile-html-file').setInputFiles({ name: 'pg-profile.html', mimeType: 'text/html', buffer: Buffer.from('<p>x</p>') })
}

test('sends the same job parts as the old form for a full file selection', async ({ page }) => {
  const sent: Record<string, string[]> = {}
  for (const [name, path, label] of [['old', '/', 'Analyze run'], ['new', '/?shell=new', SETUP_LABELS.startButton]] as const) {
    const calls = await openSetup(page, path)
    await fillEverythingFromFiles(page)
    await expect(page.getByRole('button', { name: label, exact: true })).toBeEnabled()
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect.poll(() => calls.jobs.length).toBe(1)
    sent[name] = partNames(calls.jobs[0])
    await page.unroute('**/api/**')
  }

  expect(sent.new).toEqual(sent.old)
  expect(sent.new).toEqual(expect.arrayContaining([
    'run_id', 'policy:policy.json', 'resource_snapshot:snapshot.json', 'correlation_plan:correlation.json', 'capacity_plan:capacity.json',
    'trend_plan:trend.json', 'source_context:context-a.json', 'source_context:context-b.json', 'postgres_pre:pre.json', 'postgres_post:post.json', 'pg_profile_html:pg-profile.html',
  ]))
})

test('online profiles lock the file inputs and send the same source request as the old form', async ({ page }) => {
  const sent: Record<string, string> = {}
  for (const [name, path, label] of [['old', '/', 'Analyze run'], ['new', '/?shell=new', SETUP_LABELS.startButton]] as const) {
    const calls = await openSetup(page, path)
    await page.locator('#input-file').setInputFiles(load)
    await page.locator('#source-profile').selectOption(['prod-prometheus', 'app-logs'])
    for (const id of ['resource-snapshot-file', 'correlation-plan-file', 'capacity-plan-file', 'trend-plan-file', 'source-context-file']) {
      await expect(page.locator(`#${id}`), `${name} ${id}`).toBeDisabled()
    }
    await expect(page.locator('#source-window-origin')).toHaveValue('auto')
    // Новый экран принимает секунды, прежняя форма миллисекунды: на сервер уходит одно и то же.
    const [step, margin, idle] = name === 'new' ? ['1', '2', '60'] : ['1000', '2000', '60000']
    if (name === 'new') await expect(page.locator('#source-max-idle-gap')).toHaveValue('60')
    await page.locator('#source-step').fill(step)
    await page.locator('#source-margin').fill(margin)
    await page.locator('#source-max-idle-gap').fill(idle)
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect.poll(() => calls.jobs.length).toBe(1)
    expect(partNames(calls.jobs[0])).toEqual(['run_id', 'source_request:source-request.json'])
    sent[name] = calls.jobs[0].split('\r\n').find((line) => line.startsWith('{"schema_version"')) ?? ''
    await page.unroute('**/api/**')
  }

  expect(sent.new).toBe('{"schema_version":"source-request.v3","profile_ids":["app-logs","prod-prometheus"],"window":{"origin":"auto","step_ms":1000,"margin_ms":2000,"max_idle_gap_ms":60000}}')
  expect(sent.new).toBe(sent.old)
})

test('an explicit source window asks for a period and its errors are Russian here and English in the old form', async ({ page }) => {
  await openSetup(page)
  await page.locator('#input-file').setInputFiles(load)
  await page.locator('#source-profile').selectOption('prod-prometheus')

  await expect(page.getByTestId('source-request-error')).toHaveText(SETUP_MESSAGES.autoRequired)
  await expect(readiness(page, 'sources')).toHaveAttribute('data-level', 'block')
  await expect(readiness(page, 'sources')).toContainText(SETUP_MESSAGES.autoRequired)
  await expect(start(page)).toBeDisabled()
  await page.locator('#source-step').fill('61')
  await expect(page.getByTestId('source-request-error')).toHaveText(SETUP_MESSAGES.stepWholeSeconds)
  await page.locator('#source-window-origin').selectOption('explicit')
  await expect(page.locator('#source-start')).toBeVisible()
  await expect(page.locator('#source-end')).toBeVisible()
  await expect(page.locator('#source-margin')).toHaveCount(0)
  await page.locator('#source-start').fill('1')
  await page.locator('#source-end').fill('4')
  await page.locator('#source-step').fill('1')
  await expect(page.getByTestId('source-request-error')).toHaveCount(0)
  await expect(start(page)).toBeEnabled()

  await page.unroute('**/api/**')
  await openSetup(page, '/?shell=old')
  await page.locator('#source-profile').selectOption('prod-prometheus')
  await expect(page.getByTestId('source-request-error')).toHaveText('Online source requires step, margin, and max idle gap in milliseconds.')
})

test('the window fields are in seconds and an explicit window may hold at most 100000 cells, like the server check', async ({ page }) => {
  const calls = await openSetup(page)
  await page.locator('#input-file').setInputFiles(load)
  await page.locator('#source-profile').selectOption('prod-prometheus')
  await page.locator('#source-window-origin').selectOption('explicit')
  for (const [id, label] of [['source-start', SETUP_LABELS.startLabel], ['source-end', SETUP_LABELS.endLabel], ['source-step', SETUP_LABELS.stepLabel]]) {
    await expect(page.locator('#' + id)).toHaveAccessibleName(label)
  }

  await page.locator('#source-start').fill('0')
  await page.locator('#source-end').fill('100000')
  await page.locator('#source-step').fill('1')
  await expect(page.getByTestId('source-request-error')).toHaveCount(0)
  await expect(start(page)).toBeEnabled()
  await page.locator('#source-end').fill('100001')
  await expect(page.getByTestId('source-request-error')).toHaveText(SETUP_MESSAGES.explicitTooManyCells)
  await expect(readiness(page, 'sources')).toContainText(SETUP_MESSAGES.explicitTooManyCells)
  await expect(start(page)).toBeDisabled()
  await page.locator('#source-step').fill('2')
  await expect(page.getByTestId('source-request-error')).toHaveText(SETUP_MESSAGES.explicitDivisible)
  await page.locator('#source-end').fill('100000')
  await expect(page.getByTestId('source-request-error')).toHaveCount(0)
  await page.locator('#source-end').fill('4')
  await page.locator('#source-step').fill('1')
  await start(page).click()
  await expect.poll(() => calls.jobs.length).toBe(1)
  expect(calls.jobs[0]).toContain('{"schema_version":"source-request.v3","profile_ids":["prod-prometheus"],"window":{"origin":"explicit","start_epoch_ms":0,"end_epoch_ms":4000,"step_ms":1000}}')
})

test('the old form checks the number of cells too, in milliseconds and English', async ({ page }) => {
  await openSetup(page, '/?shell=old')
  await page.locator('#source-profile').selectOption('prod-prometheus')
  await page.locator('#source-window-origin').selectOption('explicit')
  await page.locator('#source-start').fill('0')
  await page.locator('#source-step').fill('1000')
  await page.locator('#source-end').fill('100000000')
  await expect(page.getByTestId('source-request-error')).toHaveCount(0)
  await page.locator('#source-end').fill('100001000')
  await expect(page.getByTestId('source-request-error')).toHaveText('Source range may hold at most 100000 cells (range divided by step): increase the step or shorten the period.')
})

test('PostgreSQL capture buttons need a profile and call the existing endpoints', async ({ page }) => {
  const calls = await openSetup(page)

  await expect(page.getByRole('button', { name: SETUP_LABELS.captureBefore })).toBeDisabled()
  await page.locator('#postgres-profile').selectOption('pg-main')
  await page.getByRole('button', { name: SETUP_LABELS.captureBefore }).click()
  await expect.poll(() => calls.captures).toEqual(['pre'])
  await page.getByRole('button', { name: SETUP_LABELS.captureAfter }).click()
  await expect.poll(() => calls.captures).toEqual(['pre', 'post'])
})

test('the ИИ-разбор section offers a toggle with no consent wording and sends nothing by itself', async ({ page }) => {
  const calls = await openSetup(page)
  const ai = page.locator('#run-setup section', { has: page.getByRole('heading', { name: SETUP_LABELS.aiTitle }) })
  const requested = page.locator('#ai-requested')

  await expect(ai).toContainText(SETUP_LABELS.aiText)
  await expect(requested).toHaveAccessibleName(SETUP_LABELS.aiRequestedLabel)
  await expect(requested).not.toBeChecked()
  for (const word of ['Разрешаю', 'ModelStudio', 'Singapore']) await expect(ai).not.toContainText(word)
  await expect(page.getByTestId('readiness-will')).not.toContainText(SETUP_LABELS.willAdvice)
  await requested.check()
  await expect(page.getByTestId('readiness-will')).toContainText(SETUP_LABELS.willAdvice)
  await page.locator('#input-file').setInputFiles(load)
  await expect(start(page)).toBeEnabled()
  expect(calls.inputs).toBe(0)
  await expect(ai.locator('button, select')).toHaveCount(0)
})

test('the old interface keeps its own form and never shows the new screen', async ({ page }) => {
  await openSetup(page, '/?shell=old')

  await expect(page.getByRole('heading', { name: 'Run setup' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Analyze run', exact: true })).toBeDisabled()
  await expect(page.getByTestId('start-analysis')).toHaveCount(0)
  await expect(page.getByText(SETUP_LABELS.readinessTitle)).toHaveCount(0)
})

for (const theme of ['light', 'dark'] as const) {
  test(`the setup screen has no serious axe violations with every section open: ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openSetup(page)
    await page.locator('#input-file').setInputFiles(load)
    await page.locator('#policy-file').setInputFiles(policyFile())
    await expect(page.getByRole('button', { name: RULES_LABELS.openRules })).toBeVisible()
    await page.locator('#source-profile').selectOption('prod-prometheus')
    await page.locator('#source-window-origin').selectOption('explicit')

    const axe = await new AxeBuilder({ page }).analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const size of [{ width: 1280, height: 800 }, { width: 375, height: 800 }]) {
  test(`the setup screen has no horizontal scroll and a 44px start button at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    await openSetup(page)
    await page.locator('#input-file').setInputFiles(load)
    await page.locator('#policy-file').setInputFiles(policyFile())
    await expect(page.getByRole('button', { name: RULES_LABELS.openRules })).toBeVisible()
    await page.locator('#source-profile').selectOption('prod-prometheus')
    await page.locator('#source-window-origin').selectOption('explicit')

    const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(width.scroll).toBeLessThanOrEqual(width.client)
    const box = await start(page).boundingBox()
    expect(box!.height).toBeGreaterThanOrEqual(44)
  })
}
