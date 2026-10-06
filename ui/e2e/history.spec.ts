import { expect, test, type Page } from '@playwright/test'
import { HISTORY_LABELS } from '../src/shell/labels.history'

// The history tab against the real server: analyses are created in the new analysis tab, saved as releases and acted on.
const L = HISTORY_LABELS
const policyBody = (id: string, threshold: number) => JSON.stringify({
  schema_version: 'policy.v1',
  policy_id: id,
  defaults: { sample_floor: 1, min_samples: 1 },
  rules: [{ id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold, scope: { kind: 'overall' } }],
})
const POLICIES = {
  permissive: { verdict: 'PASS', body: policyBody('permissive', 100000) },
  failing: { verdict: 'FAIL', body: policyBody('failing', 1) },
}

async function analyze(page: Page, name: string, elapsed: number, timestamp: number, policy: keyof typeof POLICIES = 'permissive') {
  await page.locator('#shell-tab-setup').click()
  await page.locator('#input-file').setInputFiles({
    name, mimeType: 'text/csv', buffer: Buffer.from(`timeStamp,elapsed,label,success\n${timestamp},${elapsed},history-test,true\n`),
  })
  await page.locator('#policy-file').setInputFiles({ name: 'policy.json', mimeType: 'application/json', buffer: Buffer.from(POLICIES[policy].body) })
  await expect(page.locator('[data-testid="readiness-item"][data-key="policy"]')).toHaveAttribute('data-level', 'ok')
  await page.getByTestId('start-analysis').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.getByTestId('verdict-chip')).toContainText(POLICIES[policy].verdict)
  return (await page.locator('button[aria-pressed="true"][title]').getAttribute('title'))!
}

async function saveRelease(page: Page, series: string, label: string) {
  await page.locator('#shell-tab-history').click()
  await page.getByLabel(L.formSeries, { exact: true }).fill(series)
  await page.getByLabel(L.formLabel, { exact: true }).fill(label)
  await page.getByRole('button', { name: L.save, exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: L.saved(label) })).toBeVisible()
}

test.beforeEach(async ({ page }) => {
  // One data directory serves every spec file: remove all releases and baselines, so the order and the limits are the test's own.
  await page.goto('/?shell=new')
  const bootstrap = await (await page.request.get('/api/bootstrap')).json() as { csrf_token: string }
  const headers = { Origin: new URL(page.url()).origin, 'X-LTV-CSRF': bootstrap.csrf_token }
  for (;;) {
    const listed = await (await page.request.get('/api/releases?limit=100')).json() as { releases: Array<{ release_id: string }> }
    if (!listed.releases.length) break
    for (const item of listed.releases) await page.request.delete(`/api/releases/${item.release_id}`, { headers })
  }
  const slots = await (await page.request.get('/api/baseline')).json() as { baselines: Array<{ series: string; arm: string | null }> }
  for (const slot of slots.baselines) {
    const query = new URLSearchParams({ series: slot.series, ...(slot.arm === null ? {} : { arm: slot.arm }) })
    await page.request.delete(`/api/baseline?${query}`, { headers })
  }
  await page.request.delete('/api/baseline', { headers })
  await page.reload()
})

test('an empty registry shows the empty state', async ({ page }) => {
  await page.locator('#shell-tab-history').click()
  await expect(page.getByTestId('history-panel')).toContainText(L.noSeries)
})

test('a finished analysis is saved as a release and listed newest first', async ({ page }) => {
  const series = `history-order-${Date.now()}`
  await analyze(page, 'history-order-a.jtl', 100, 1767300000000)
  await saveRelease(page, series, 'v1')
  await analyze(page, 'history-order-b.jtl', 120, 1767300100000)
  await saveRelease(page, series, 'v2')
  const rows = page.getByTestId('release-row')
  await expect(rows).toHaveCount(2)
  await expect(rows.first()).toContainText('v2')
  await expect(rows.first()).toContainText('PASS')
  await expect(rows.nth(1)).toContainText('v1')
  await expect(page.getByTestId('history-toggle')).toHaveCount(0)
})

test('a hostile label is text and two releases may share a label', async ({ page }) => {
  const series = `history-hostile-${Date.now()}`
  const hostile = '<img src=x onerror=window.__pwn=1>'
  await analyze(page, 'history-hostile-a.jtl', 100, 1767300200000)
  await saveRelease(page, series, hostile)
  await analyze(page, 'history-hostile-b.jtl', 110, 1767300300000)
  await saveRelease(page, series, hostile)
  await expect(page.getByTestId('release-row')).toHaveCount(2)
  await expect(page.getByTestId('release-row').first()).toContainText(hostile)
  expect(await page.evaluate(() => (window as unknown as { __pwn?: number }).__pwn)).toBeUndefined()
})

test('a baseline can be assigned only from an eligible release', async ({ page }) => {
  const series = `history-baseline-${Date.now()}`
  const passing = await analyze(page, 'history-baseline-a.jtl', 100, 1767300400000)
  await saveRelease(page, series, 'good')
  await analyze(page, 'history-baseline-b.jtl', 100, 1767300500000, 'failing')
  await saveRelease(page, series, 'bad')
  const bad = page.getByTestId('release-row').first().getByRole('button', { name: L.actionAria(L.makeBaseline, 'bad', null) })
  await expect(bad).toBeDisabled()
  await expect(page.locator(`#${await bad.getAttribute('aria-describedby')}`)).toHaveText(L.reasons.BASELINE_CANDIDATE_NOT_PASS!)
  await page.getByTestId('release-row').nth(1).getByRole('button', { name: L.actionAria(L.makeBaseline, 'good', null) }).click()
  await expect(page.getByTestId('release-row').nth(1).getByTestId('baseline-badge')).toBeVisible()
  await page.locator('#shell-tab-compare').click()
  await expect(page.getByTestId('baseline-selection')).toContainText(passing)
  await expect(page.getByLabel('Серия сравнения', { exact: true })).toHaveValue(series)
})

test('open and compare act on the analysis of the release', async ({ page }) => {
  const series = `history-open-${Date.now()}`
  const first = await analyze(page, 'history-open-a.jtl', 100, 1767300600000)
  await saveRelease(page, series, 'first')
  await analyze(page, 'history-open-b.jtl', 100, 1767300700000)
  await page.getByRole('button', { name: 'history-open-a.jtl' }).click()
  await page.locator('#shell-tab-history').click()
  await page.getByTestId('release-row').first().getByRole('button', { name: L.actionAria(L.open, 'first', null) }).click()
  await expect(page.locator('#shell-tab-overview')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator(`button[aria-pressed="true"][title="${first}"]`)).toBeVisible()
  await page.locator('#shell-tab-history').click()
  await page.getByTestId('release-row').first().getByRole('button', { name: L.actionAria(L.compare, 'first', null) }).click()
  await expect(page.locator('#shell-tab-compare')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByLabel('Серия сравнения', { exact: true })).toHaveValue(series)
})

test('the same analysis cannot be saved twice', async ({ page }) => {
  const series = `history-twice-${Date.now()}`
  await analyze(page, 'history-twice.jtl', 100, 1767300800000)
  await saveRelease(page, series, 'once')
  await page.getByLabel(L.formLabel, { exact: true }).fill('twice')
  await page.getByRole('button', { name: L.save, exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText(L.errors.RELEASE_ANALYSIS_ALREADY_REGISTERED!(null))
})

test('a declared profile is offered again for the next release of the series and must be confirmed', async ({ page }) => {
  const series = `history-profile-${Date.now()}`
  await analyze(page, 'history-profile-a.jtl', 100, 1767301000000)
  await page.locator('#shell-tab-history').click()
  await page.getByLabel(L.formSeries, { exact: true }).fill(series)
  await page.getByLabel(L.formLabel, { exact: true }).fill('p1')
  await page.getByLabel(L.profileFields.pacing, { exact: true }).fill('10 s')
  await page.getByTestId('profile-confirm').check()
  await page.getByRole('button', { name: L.save, exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: L.saved('p1') })).toBeVisible()
  await analyze(page, 'history-profile-b.jtl', 110, 1767301100000)
  await page.locator('#shell-tab-history').click()
  await page.getByLabel(L.formLabel, { exact: true }).fill('p2')
  await expect(page.getByLabel(L.profileFields.pacing, { exact: true })).toHaveValue('10 s')
  await expect(page.getByTestId('profile-prefilled')).toHaveText(L.profilePrefilled('p1'))
  await expect(page.getByRole('button', { name: L.save, exact: true })).toBeDisabled()
  await page.getByTestId('profile-confirm').check()
  await page.getByRole('button', { name: L.save, exact: true }).click()
  await expect(page.getByTestId('release-row')).toHaveCount(2)
  await expect(page.getByTestId('release-row').first().getByTestId('profile-differs')).toHaveCount(0)
  await expect(page.getByTestId('baseline-suggestion')).toHaveCount(1)
})

test('re-pin points the release at an analysis made later from the same run', async ({ page }) => {
  const series = `history-repin-${Date.now()}`
  await analyze(page, 'history-repin.jtl', 100, 1767301200000)
  await saveRelease(page, series, 'old rules')
  await expect(page.getByTestId('release-row').first()).toContainText('PASS')
  await analyze(page, 'history-repin.jtl', 100, 1767301200000, 'failing')
  await page.locator('#shell-tab-history').click()
  await page.getByTestId('release-row').first().getByTestId('rebind').click()
  await page.getByTestId('rebind-panel').getByRole('radio').check()
  await page.getByTestId('rebind-apply').click()
  await expect(page.getByTestId('release-row').first()).toContainText('FAIL')
  await expect(page.getByTestId('rebind-panel')).toHaveCount(0)
})
