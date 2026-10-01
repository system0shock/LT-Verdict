import { expect, test, type Page } from '@playwright/test'

const run = { run_id: `jmeter_jtl_csv-${'a'.repeat(64)}`, source_type: 'jmeter_jtl_csv', sha256: 'a'.repeat(64), size_bytes: 100, original_filename: 'poll.jtl' }
const processing = { job_id: 'job-1', state: 'PROCESSING', processed_bytes: 10, total_bytes: 100, run_id: run.run_id, analysis_id: null, diagnostic: null }
const failed = { ...processing, state: 'FAILED', diagnostic: { code: 'TEST_DONE', message: 'finished by the test' } }

type Outcome = 'fail-503' | 'fail-network' | 'processing' | 'failed' | 'hold'

interface Fixture {
  polls: number[]
  deletes: number
  setPolicy: (policy: (call: number) => Outcome) => void
  release: (response: object) => void
}

async function fixtureApi(page: Page): Promise<Fixture> {
  const state: Fixture = { polls: [], deletes: 0, setPolicy: () => {}, release: () => {} }
  let policy: (call: number) => Outcome = () => 'processing'
  let held: ((response: object) => void) | null = null
  state.setPolicy = (next) => { policy = next }
  state.release = (response) => { held?.(response); held = null }
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1000000 } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) return route.fulfill({ json: { profiles: [] } })
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: [run], next_after: null } })
    if (path.endsWith('/analyses')) return route.fulfill({ json: { analyses: [], next_after: null } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [processing] } })
    if (method === 'GET' && path === '/api/jobs/job-1') {
      state.polls.push(Date.now())
      const outcome = policy(state.polls.length)
      if (outcome === 'fail-503') return route.fulfill({ status: 503, json: { error: { code: 'UNAVAILABLE', message: 'Temporarily unavailable', details: [] } } })
      if (outcome === 'fail-network') return route.abort('failed')
      if (outcome === 'failed') return route.fulfill({ json: failed })
      if (outcome === 'hold') {
        const response = await new Promise<object>((resolve) => { held = resolve })
        return route.fulfill({ json: response })
      }
      return route.fulfill({ json: processing })
    }
    if (method === 'DELETE' && path === '/api/jobs/job-1') {
      state.deletes += 1
      return route.fulfill({ json: { ...processing, state: 'CANCELLED' } })
    }
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })
  return state
}

test('recovers from one failed job poll without flooding the server', async ({ page }) => {
  const api = await fixtureApi(page)
  api.setPolicy((call) => (call === 1 ? 'fail-503' : call === 2 ? 'hold' : 'failed'))
  await page.goto('/')

  await expect(page.locator('#job-status')).toContainText('PROCESSING')
  await expect(page.getByTestId('poll-retrying')).toContainText('Connection problem')
  await expect(page.getByTestId('poll-lost')).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect.poll(() => api.polls.length).toBe(2)

  api.release(processing)
  await expect(page.getByTestId('poll-retrying')).toHaveCount(0)
  await expect(page.locator('#job-status')).toContainText('FAILED')
  await expect(page.locator('#job-status')).toContainText('TEST_DONE')

  expect(api.polls).toHaveLength(3)
  for (let index = 1; index < api.polls.length; index += 1) {
    expect(api.polls[index] - api.polls[index - 1]).toBeGreaterThanOrEqual(400)
  }
})

test('does not let a late poll response override a cancelled job', async ({ page }) => {
  const api = await fixtureApi(page)
  api.setPolicy((call) => (call === 1 ? 'fail-503' : 'hold'))
  await page.goto('/')

  await expect(page.getByTestId('poll-retrying')).toBeVisible()
  await expect.poll(() => api.polls.length).toBe(2)
  await page.getByRole('button', { name: 'Cancel analysis' }).click()
  await expect(page.locator('#job-status')).toContainText('CANCELLED')

  api.release(processing)
  await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 1500)))
  await expect(page.locator('#job-status')).toContainText('CANCELLED')
  await expect(page.getByTestId('poll-retrying')).toHaveCount(0)
  expect(api.polls).toHaveLength(2)
  expect(api.deletes).toBe(1)
})

test('treats a hung job poll as a failed attempt and polls again', async ({ page }) => {
  test.setTimeout(45_000)
  const api = await fixtureApi(page)
  api.setPolicy((call) => (call === 1 ? 'hold' : 'failed'))
  await page.goto('/')

  await expect(page.getByTestId('poll-retrying')).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('#job-status')).toContainText('FAILED', { timeout: 10_000 })
  expect(api.polls).toHaveLength(2)
})

for (const scenario of [
  { name: 'legacy', path: '/', lost: 'Connection lost', retry: 'Retry', cancel: 'Cancel analysis' },
  { name: 'new shell', path: '/?shell=new', lost: 'Связь потеряна', retry: 'Повторить', cancel: 'Cancel analysis' },
]) {
  test(`stops after a long outage and offers Retry (${scenario.name})`, async ({ page }) => {
    await page.clock.install()
    const api = await fixtureApi(page)
    api.setPolicy((call) => (call % 2 === 0 ? 'fail-503' : 'fail-network'))
    await page.goto(scenario.path)

    const lost = page.getByTestId('poll-lost')
    await expect.poll(async () => {
      await page.clock.runFor(10_000)
      return lost.count()
    }, { timeout: 30_000 }).toBe(1)
    await expect(lost).toContainText(scenario.lost)
    await expect(page.locator('#job-status')).toContainText('PROCESSING')
    await expect(page.getByRole('button', { name: scenario.cancel })).toBeVisible()
    const callsWhenLost = api.polls.length
    expect(callsWhenLost).toBe(10)

    await page.clock.runFor(300_000)
    expect(api.polls).toHaveLength(callsWhenLost)

    api.setPolicy(() => 'failed')
    await page.getByRole('button', { name: scenario.retry }).click()
    await expect.poll(async () => {
      await page.clock.runFor(1_000)
      return page.locator('#job-status').innerText()
    }, { timeout: 30_000 }).toContain('FAILED')
    await expect(lost).toHaveCount(0)
    await expect(page.getByRole('button', { name: scenario.retry })).toHaveCount(0)
    expect(api.polls).toHaveLength(callsWhenLost + 1)
  })
}
