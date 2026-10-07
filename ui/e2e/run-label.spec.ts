import { expect, test, type Page } from '@playwright/test'
import { SHELL_LABELS } from '../src/shell/labels'

// Подпись прогона в новой оболочке: одинаковое имя файла не различает прогоны, поэтому вместо него показано время приёма.
const prefixes = ['3fa9c1d2', '9b01e77a', 'c4d2a8f0']
const acceptedAt = ['2026-10-07T12:00:10.000Z', '2026-10-07T12:00:05.000Z', '2026-10-07T12:00:00.000Z']
const runs = prefixes.map((prefix, index) => {
  const sha256 = `${prefix}${'0'.repeat(56)}`
  return { run_id: `jmeter_jtl_csv-${sha256}`, source_type: 'jmeter_jtl_csv', sha256, size_bytes: 1000, original_filename: 'load.jtl', accepted_at: acceptedAt[index] }
})
const analysisId = 'a'.repeat(64)
const result = {
  schema_version: 'analysis-result.v1', analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'PASS',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [],
}

async function fixtureApi(page: Page, listed: typeof runs, analysis: Record<string, unknown> = {}) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (method === 'GET' && path === '/api/releases') body = { releases: [], next_after: null, series_summary: [], corrupt_count: 0, corrupt_names: [] }
    else if (path === '/api/runs') body = { runs: listed, next_after: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: analysisId, policy_sha256: 'c'.repeat(64), policy_verdict: 'PASS', run_validity: 'VALID', ...analysis }], next_after: null }
    else if (path.endsWith('/result')) body = { ...result, run_id: path.split('/')[3] }
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else if (method === 'GET' && /\/advice$/.test(path)) body = { advice: null, job: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
}

const buttons = (page: Page) => page.getByTestId('run-list').getByRole('button')

test('runs sharing one file name are listed by acceptance time, not by the repeated name', async ({ page }) => {
  await fixtureApi(page, runs)
  await page.goto('/?shell=new')

  await expect(buttons(page)).toHaveCount(3)
  const titles: string[] = []
  for (const [index, prefix] of prefixes.entries()) {
    const button = buttons(page).nth(index)
    await expect(button).not.toContainText('load.jtl')
    await expect(button).toContainText(SHELL_LABELS.runAccepted)
    await expect(button).toContainText(`jmeter_jtl_csv · ${prefix}`)
    await expect(button.locator('time')).toHaveCount(0)
    titles.push((await button.locator('span').first().innerText()).trim())
  }
  expect(new Set(titles).size).toBe(3)
})

test('a file name that tells runs apart stays in the list', async ({ page }) => {
  await fixtureApi(page, [{ ...runs[0], original_filename: 'first.jtl' }, runs[1]])
  await page.goto('/?shell=new')

  await expect(buttons(page).nth(0)).toContainText('first.jtl')
  await expect(buttons(page).nth(1)).toContainText('load.jtl')
  await expect(buttons(page).nth(0)).toContainText(`jmeter_jtl_csv · ${prefixes[0]}`)
  await expect(buttons(page).nth(0).locator('time')).toHaveAttribute('datetime', acceptedAt[0])
})

test('a single run and a run without acceptance time keep the file name', async ({ page }) => {
  await fixtureApi(page, [runs[0]])
  await page.goto('/?shell=new')
  await expect(buttons(page).first()).toContainText('load.jtl')
  await expect(buttons(page).first()).not.toContainText(SHELL_LABELS.runAccepted)

  const { accepted_at: _omitted, ...legacy } = runs[1]
  void _omitted
  await page.unroute('**/api/**')
  await fixtureApi(page, [runs[0], legacy as typeof runs[number]])
  await page.reload()
  await expect(buttons(page).nth(0)).toContainText(SHELL_LABELS.runAccepted)
  await expect(buttons(page).nth(1)).toContainText('load.jtl')
  await expect(buttons(page).nth(1).locator('time')).toHaveCount(0)
})

test('the header names the open run by time, its policy and arm', async ({ page }) => {
  await fixtureApi(page, runs, { policy_id: 'basic-api-sla', resource_arm: 'blue' })
  await page.goto('/?shell=new')
  const identity = page.locator('.run-identity')

  await buttons(page).nth(1).click()
  await expect(identity).not.toContainText('load.jtl')
  await expect(identity).toContainText(SHELL_LABELS.runAccepted)
  await expect(identity).toContainText(`jmeter_jtl_csv-${prefixes[1]}`)
  await expect(identity).not.toContainText(SHELL_LABELS.runPolicy)

  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(identity).toContainText(`${SHELL_LABELS.runPolicy} basic-api-sla`)
  await expect(identity).toContainText(`${SHELL_LABELS.runArm} blue`)
})

test('the header falls back to the policy hash and omits the policy of a NO_POLICY analysis', async ({ page }) => {
  await fixtureApi(page, runs, {})
  await page.goto('/?shell=new')
  await buttons(page).first().click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(page.locator('.run-identity')).toContainText(`${SHELL_LABELS.runPolicy} ${'c'.repeat(8)}`)
  await expect(page.locator('.run-identity')).not.toContainText(SHELL_LABELS.runArm)

  await page.unroute('**/api/**')
  await fixtureApi(page, runs, { policy_verdict: 'NO_POLICY' })
  await page.reload()
  await buttons(page).first().click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(page.locator('.run-identity')).not.toContainText(SHELL_LABELS.runPolicy)
})
