import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { BASELINE_ERROR_LABELS } from '../src/shell/labels'
import { HISTORY_LABELS } from '../src/shell/labels.history'
import { DEFAULT_VISIBLE_RELEASES } from '../src/shell/history'
import type { Release, ReleaseAnalysis, ReleaseProfile } from '../src/types'

// The tab against a mocked server: table, expansion, numbers and their absence, the form payload, error phrases, accessibility.
const L = HISTORY_LABELS
const current = { run_id: 'hist-run-0', analysis_id: 'a'.repeat(64) }
const run = { ...current, source_type: 'jmeter', sha256: 'd'.repeat(64), size_bytes: 100, original_filename: 'hist.jtl' }
const SERIES = 'Checkout'
const profile = (pacing: string | null, over: Partial<ReleaseProfile> = {}): ReleaseProfile => ({
  scenario_mix: null, environment_dataset: null, load_model: null, targets_stages: null, pacing, generator_limits: null, ...over,
})

const analysis = (n: number, over: Partial<ReleaseAnalysis> = {}): ReleaseAnalysis => ({
  analysis_id: String(n).repeat(64).slice(0, 64), arm: null, coverage_reasons: [], coverage_status: 'COMPLETE', policy_sha256: 'e'.repeat(64),
  policy_verdict: 'PASS', run_validity: 'VALID', analysis_state: 'OK', baseline_eligible: true, ineligible_reasons: [], ...over,
})
// Release n (1 = oldest) of the series; the registry lists newest first.
const release = (n: number, over: Partial<Release> = {}, analysisOver: Partial<ReleaseAnalysis> = {}): Release => ({
  schema_version: 'local-release.v1', release_id: `2026010100000${n}-${String(n).repeat(8)}`, series: SERIES, label: `v1.${n}`, run_id: `hist-run-${n}`,
  started_at: `2026-01-0${n}T10:00:00Z`, analyses: [analysis(n, analysisOver)], profile: null, notes: null,
  created_at: `2026-01-0${n}T11:00:00Z`, updated_at: `2026-01-0${n}T11:00:00Z`,
  baseline_eligible: (analysisOver.baseline_eligible ?? true), ineligible_reasons: analysisOver.ineligible_reasons ?? [], ...over,
})

type Options = { p95?: Record<number, string>; releases?: Release[]; corrupt?: number; truncated?: boolean; baselineRun?: string; failures?: Record<string, { status: number; code: string; message: string; limit?: number }>; noSelection?: boolean }

async function openHistory(page: Page, opts: Options = {}) {
  const requests: Array<{ method: string; path: string; search: string; body?: unknown }> = []
  const releases = opts.releases ?? [release(1), release(2), release(3)]
  const store = [...releases].reverse()
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    requests.push({ method, path, search: url.search, body: method === 'POST' || method === 'PUT' ? route.request().postDataJSON() as unknown : undefined })
    const failure = opts.failures?.[`${method} ${path}`]
    if (failure) {
      await route.fulfill({ status: failure.status, json: { error: { code: failure.code, message: failure.message, limit: failure.limit } } })
      return
    }
    let body: unknown
    let status = 200
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources') body = { profiles: [] }
    else if (path.endsWith('/advice')) body = { advice: null, job: null }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline' && method === 'GET') {
      const reference = opts.baselineRun ? store.find((item) => item.run_id === opts.baselineRun) : undefined
      body = {
        baseline: null,
        baselines: reference ? [{ series: SERIES, arm: null, source: 'SLOT', baseline: {
          schema_version: 'local-baseline.v1', series: SERIES, mode: 'manual', reference: { run_id: reference.run_id, analysis_id: reference.analyses[0]!.analysis_id },
          algorithm: null, candidates: [], scores: [],
        } }] : [],
      }
    } else if (path === '/api/baseline' && method === 'POST') {
      const posted = route.request().postDataJSON() as { series: string; reference: { run_id: string; analysis_id: string } }
      body = { baseline: { schema_version: 'local-baseline.v1', series: posted.series, mode: 'manual', reference: posted.reference, algorithm: null, candidates: [], scores: [] } }
    } else if (path === '/api/releases' && method === 'GET') {
      const wanted = url.searchParams.get('series')
      const limit = Number(url.searchParams.get('limit') ?? '100')
      const list = store.filter((item) => wanted === null || item.series === wanted)
      body = {
        releases: list.slice(0, limit), next_after: list.length > limit ? list[limit - 1]!.release_id : null,
        series_summary: store.length ? [{ series: SERIES, count: store.length }] : [], corrupt_count: opts.corrupt ?? 0,
        corrupt_names: Array.from({ length: opts.corrupt ?? 0 }, (_, index) => ({ name: `broken-${index}.json`, reason: 'CORRUPT_RELEASE' })),
      }
    } else if (path === '/api/releases' && method === 'POST') {
      const posted = route.request().postDataJSON() as { series: string; label: string; run_id: string }
      status = 201
      body = release(9, { series: posted.series, label: posted.label, run_id: posted.run_id })
    } else if (path.endsWith('/analytics')) {
      body = {
        schema_version: 'saved-analytics.v1', history_scan_truncated: opts.truncated ?? false, history_scan_limit: 1000, history_metadata_byte_limit: 16777216,
        history_integrity: 'SAVED_DOCUMENT_HASHES', transactions: null, overlay: null, metric_packs: { schema_version: 'metric-packs.v1', packs: [] },
        dynamics: opts.truncated ? null : {
          schema_version: 'run-dynamics.v1', limit: 100, comparable_count: 2, excluded_incompatible_count: 0, baseline: null,
          rows: store.filter((item) => item.analyses[0]!.analysis_state === 'OK').map((item) => ({
            reference: { run_id: item.run_id, analysis_id: item.analyses[0]!.analysis_id }, run_date: item.started_at, jenkins_build: null, commit: null,
            application_version: item.label, load_profile: null, verdict: item.analyses[0]!.policy_verdict,
            metrics: [
              { metric: 'response_time_p95_ms', unit: 'ms', value: opts.p95?.[Number(item.run_id.split('-').pop())] ?? '451.5', delta_previous: null, delta_previous_percent: null, previous_reason: null, delta_baseline: null, delta_baseline_percent: null, baseline_reason: null },
              { metric: 'error_rate_ratio', unit: 'ratio', value: '0.0123', delta_previous: null, delta_previous_percent: null, previous_reason: null, delta_baseline: null, delta_baseline_percent: null, baseline_reason: null },
              { metric: 'throughput_rps', unit: 'rps', value: '60', delta_previous: null, delta_previous_percent: null, previous_reason: null, delta_baseline: null, delta_baseline_percent: null, baseline_reason: null },
            ],
          })),
        },
      }
    } else if (path.endsWith('/analyses')) {
      const own = store.find((item) => path.includes(`/${item.run_id}/`))
      body = {
        analyses: own
          ? [own.analyses[0]!, { ...own.analyses[0]!, analysis_id: 'f'.repeat(64), policy_verdict: 'FAIL' }].map((item) => ({
            analysis_id: item.analysis_id, policy_sha256: item.policy_sha256, policy_verdict: item.policy_verdict, run_validity: 'VALID',
          }))
          : [{ analysis_id: current.analysis_id, policy_sha256: 'e'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }],
        next_after: null,
      }
    } else if (path.endsWith('/result')) {
      body = {
        schema_version: 'analysis-result.v1', run_id: path.split('/')[3], analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'PASS',
        analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [],
      }
    } else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else if (path.startsWith('/api/releases/') && method === 'PUT') {
      const put = route.request().postDataJSON() as { label: string; analyses: Array<{ analysis_id: string }> }
      const target = store.find((item) => path.endsWith(item.release_id))!
      body = { ...target, label: put.label, analyses: [{ ...target.analyses[0]!, analysis_id: put.analyses[0]!.analysis_id }] }
    } else if (path.endsWith('/baseline-conditions')) body = { conditions: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ status, json: body })
  })
  await page.goto('/?shell=new')
  if (!opts.noSelection) {
    await page.getByRole('button', { name: run.original_filename }).click()
    await page.locator(`button[title="${current.analysis_id}"]`).click()
    await expect(page.locator('#verdict')).toBeVisible()
  }
  await page.locator('#shell-tab-history').click()
  await expect(page.getByTestId('history-panel')).toBeVisible()
  return requests
}

const rowsOf = (page: Page) => page.getByTestId('release-row')

test('the newest four releases are listed newest first and the rest opens on demand', async ({ page }) => {
  await openHistory(page, { releases: [1, 2, 3, 4, 5, 6].map((n) => release(n)) })
  await expect(rowsOf(page)).toHaveCount(DEFAULT_VISIBLE_RELEASES)
  await expect(rowsOf(page).first()).toContainText('v1.6')
  await expect(rowsOf(page).nth(3)).toContainText('v1.3')
  await expect(page.getByTestId('history-table').locator('caption')).toHaveText(L.tableCaption(SERIES))
  await page.getByRole('button', { name: L.showAll, exact: true }).click()
  await expect(rowsOf(page)).toHaveCount(6)
  await expect(rowsOf(page).last()).toContainText('v1.1')
  await page.getByRole('button', { name: L.showLatest(DEFAULT_VISIBLE_RELEASES), exact: true }).click()
  await expect(rowsOf(page)).toHaveCount(DEFAULT_VISIBLE_RELEASES)
})

test('a series without releases shows the empty state', async ({ page }) => {
  await openHistory(page, { releases: [] })
  await expect(page.getByTestId('history-panel')).toContainText(L.noSeries)
  await expect(page.getByTestId('history-table')).toHaveCount(0)
})

test('numbers come from the saved analytics and their absence is explained, never shown as zero', async ({ page }) => {
  await openHistory(page, { releases: [release(1), release(2), release(3, {}, { analysis_state: 'MISSING', baseline_eligible: false, ineligible_reasons: ['ANALYSIS_MISSING'] })] })
  // The newest release is the anchor of the dynamics request and is not a row of its own dynamics in this mock.
  const cells = (index: number) => rowsOf(page).nth(index).locator('td')
  await expect(rowsOf(page).nth(0)).toContainText(L.noNumbersMissing)
  await expect(cells(1).nth(3)).toHaveText('451,5')
  await expect(cells(1).nth(4)).toHaveText('0,0123')
  await expect(cells(1).nth(5)).toHaveText('60')
  await expect(rowsOf(page).nth(0)).not.toContainText('0,0')
})

test('the scan limit is named when the saved history is truncated', async ({ page }) => {
  await openHistory(page, { truncated: true })
  await expect(rowsOf(page).first()).toContainText(L.noNumbersTruncated)
})

test('damaged records are reported without hiding the readable releases', async ({ page }) => {
  await openHistory(page, { corrupt: 2 })
  await expect(page.getByTestId('history-corrupt')).toHaveText(L.corrupt(2))
  await expect(rowsOf(page)).toHaveCount(3)
})

test('a release that cannot be a baseline names the reason and keeps the action disabled', async ({ page }) => {
  await openHistory(page, { releases: [release(1), release(2, {}, { policy_verdict: 'FAIL', baseline_eligible: false, ineligible_reasons: ['BASELINE_CANDIDATE_NOT_PASS'] })] })
  const failing = rowsOf(page).first()
  const button = failing.getByRole('button', { name: L.actionAria(L.makeBaseline, 'v1.2', null) })
  await expect(button).toBeDisabled()
  const describedBy = await button.getAttribute('aria-describedby')
  await expect(page.locator(`#${describedBy}`)).toHaveText(L.reasons.BASELINE_CANDIDATE_NOT_PASS!)
  await expect(rowsOf(page).nth(1).getByRole('button', { name: L.actionAria(L.makeBaseline, 'v1.1', null) })).toBeEnabled()
})

test('make baseline sends a manual request for the release series and the analysis of the row', async ({ page }) => {
  const requests = await openHistory(page)
  await rowsOf(page).nth(1).getByRole('button', { name: L.actionAria(L.makeBaseline, 'v1.2', null) }).click()
  await expect.poll(() => requests.filter((entry) => entry.method === 'POST' && entry.path === '/api/baseline').map((entry) => entry.body)).toEqual([
    { mode: 'manual', series: SERIES, reference: { run_id: 'hist-run-2', analysis_id: '2'.repeat(64) } },
  ])
})

test('the row marked as baseline carries the badge', async ({ page }) => {
  await openHistory(page, { baselineRun: 'hist-run-2' })
  await expect(page.getByTestId('baseline-badge')).toHaveCount(1)
  await expect(rowsOf(page).nth(1).getByTestId('baseline-badge')).toHaveText(L.baselineBadge)
  await expect(rowsOf(page).first().getByTestId('baseline-badge')).toHaveCount(0)
})

test('open and compare act on the release analysis and the compare tab shows its series', async ({ page }) => {
  await openHistory(page)
  await rowsOf(page).nth(1).getByRole('button', { name: L.actionAria(L.open, 'v1.2', null) }).click()
  await expect(page.locator('#shell-tab-overview')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#verdict')).toContainText('PASS')
  await page.locator('#shell-tab-history').click()
  await rowsOf(page).first().getByRole('button', { name: L.actionAria(L.compare, 'v1.3', null) }).click()
  await expect(page.locator('#shell-tab-compare')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByLabel('Серия сравнения', { exact: true })).toHaveValue(SERIES)
})

test('the form saves the open analysis with a null profile when no field is filled', async ({ page }) => {
  const requests = await openHistory(page)
  await page.getByLabel(L.formSeries, { exact: true }).fill(' New series ')
  await expect(page.getByRole('button', { name: L.save, exact: true })).toBeDisabled()
  await page.getByLabel(L.formLabel, { exact: true }).fill('v2.0')
  await page.getByRole('button', { name: L.save, exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: L.saved('v2.0') })).toBeVisible()
  expect(requests.filter((entry) => entry.method === 'POST' && entry.path === '/api/releases').map((entry) => entry.body)).toEqual([
    { series: 'New series', label: 'v2.0', run_id: current.run_id, analyses: [{ analysis_id: current.analysis_id }], profile: null, notes: null },
  ])
})

test('a filled profile and a note go to the server in the fixed field order', async ({ page }) => {
  const requests = await openHistory(page)
  await page.getByLabel(L.formLabel, { exact: true }).fill('v2.1')
  await page.getByLabel(L.profileFields.pacing, { exact: true }).fill(' 10 s ')
  await page.getByLabel(L.formNotes, { exact: true }).fill('note')
  await expect(page.getByRole('button', { name: L.save, exact: true })).toBeDisabled()
  await page.getByTestId('profile-confirm').check()
  await page.getByRole('button', { name: L.save, exact: true }).click()
  await expect.poll(() => requests.filter((entry) => entry.method === 'POST' && entry.path === '/api/releases').length).toBe(1)
  const posted = requests.find((entry) => entry.method === 'POST' && entry.path === '/api/releases')!.body as { profile: unknown; notes: unknown }
  expect(Object.entries(posted.profile as Record<string, unknown>)).toEqual([
    ['scenario_mix', null], ['environment_dataset', null], ['load_model', null], ['targets_stages', null], ['pacing', '10 s'], ['generator_limits', null],
  ])
  expect(posted.notes).toBe('note')
})

test('the form is unavailable without an open analysis', async ({ page }) => {
  await openHistory(page, { noSelection: true })
  await expect(page.getByTestId('history-form')).toContainText(L.saveHint)
  await expect(page.getByRole('button', { name: L.save, exact: true })).toBeDisabled()
})

test('too long text is refused before sending', async ({ page }) => {
  await openHistory(page)
  await page.getByLabel(L.formLabel, { exact: true }).fill('я'.repeat(65))
  await expect(page.getByTestId('history-form')).toContainText(L.tooLong)
  await expect(page.getByRole('button', { name: L.save, exact: true })).toBeDisabled()
})

test('a hostile label is shown as text', async ({ page }) => {
  await openHistory(page, { releases: [release(1, { label: '<img src=x onerror=window.__pwn=1>' })] })
  await expect(rowsOf(page).first()).toContainText('<img src=x onerror=window.__pwn=1>')
  expect(await page.evaluate(() => (window as unknown as { __pwn?: number }).__pwn)).toBeUndefined()
})

test('release API codes are Russian phrases and the limit comes from the response', async ({ page }) => {
  await openHistory(page, { failures: { 'POST /api/releases': { status: 422, code: 'RELEASE_LIMIT_REACHED', message: 'Release limit is reached', limit: 1000 } } })
  await page.getByLabel(L.formLabel, { exact: true }).fill('v3')
  await page.getByRole('button', { name: L.save, exact: true }).click()
  const alert = page.getByRole('alert')
  await expect(alert).toHaveText(L.errors.RELEASE_LIMIT_REACHED!(1000))
  await expect(alert).not.toHaveAttribute('lang', 'en')
})

test('a refusal of the baseline shows the Russian phrase of the dictionary', async ({ page }) => {
  await openHistory(page, { failures: { 'POST /api/baseline': { status: 422, code: 'BASELINE_SLOTS_LIMIT_REACHED', message: 'Baseline slot limit is reached', limit: 64 } } })
  await rowsOf(page).first().getByRole('button', { name: L.actionAria(L.makeBaseline, 'v1.3', null) }).click()
  await expect(page.getByRole('alert')).toHaveText(BASELINE_ERROR_LABELS.BASELINE_SLOTS_LIMIT_REACHED!(64))
})

test('the table region and the actions are reachable with the keyboard', async ({ page }) => {
  await openHistory(page)
  const region = page.getByRole('region', { name: L.regionLabel })
  await region.focus()
  await expect(region).toBeFocused()
  await page.getByTestId('release-row').first().getByRole('button', { name: L.actionAria(L.open, 'v1.3', null) }).focus()
  await page.keyboard.press('Enter')
  await expect(page.locator('#shell-tab-overview')).toHaveAttribute('aria-selected', 'true')
})

for (const theme of ['light', 'dark'] as const) {
  test(`the history tab has no serious axe violations, ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openHistory(page, { releases: [release(1), release(2, {}, { policy_verdict: 'FAIL', baseline_eligible: false, ineligible_reasons: ['BASELINE_CANDIDATE_NOT_PASS'] })], corrupt: 1, baselineRun: 'hist-run-1' })
    const audit = await new AxeBuilder({ page }).include('#history-panel').analyze()
    expect(audit.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const width of [1280, 375, 320]) {
  test(`the history tab does not overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await openHistory(page, { releases: [release(1, { label: 'L'.repeat(120), series: SERIES }), release(2)] })
    await page.evaluate(() => document.documentElement.style.setProperty('letter-spacing', '0.15em'))
    const size = await page.evaluate(() => {
      const spilling = [...document.querySelectorAll('body *')]
        .filter((element) => element.scrollWidth > element.clientWidth + 1 && element.clientWidth > 0 && element.tagName !== 'INPUT' && !element.closest('.table-wrap'))
      return { scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth, wide: spilling.slice(0, 4).map((element) => `${element.tagName}#${element.id}.${element.className}`) }
    })
    expect(size.scrollWidth, JSON.stringify(size)).toBeLessThanOrEqual(size.innerWidth)
  })
}

test('the profile of the newest release is offered for confirmation, never carried silently', async ({ page }) => {
  const requests = await openHistory(page, { releases: [release(1, { profile: profile('10 s') }), release(2, { profile: profile('10 s', { load_model: 'open' }) })] })
  await expect(page.getByLabel(L.profileFields.pacing, { exact: true })).toHaveValue('10 s')
  await expect(page.getByLabel(L.profileFields.load_model, { exact: true })).toHaveValue('open')
  await expect(page.getByTestId('profile-prefilled')).toHaveText(L.profilePrefilled('v1.2'))
  await page.getByLabel(L.formLabel, { exact: true }).fill('v3')
  const save = page.getByRole('button', { name: L.save, exact: true })
  await expect(save).toBeDisabled()
  await page.getByLabel(L.profileFields.pacing, { exact: true }).fill('20 s')
  await expect(page.getByTestId('profile-prefilled')).toHaveCount(0)
  await expect(save).toBeDisabled()
  await page.getByTestId('profile-confirm').check()
  await expect(save).toBeEnabled()
  await save.click()
  await expect.poll(() => requests.filter((entry) => entry.method === 'POST' && entry.path === '/api/releases').length).toBe(1)
  const posted = requests.find((entry) => entry.method === 'POST' && entry.path === '/api/releases')!.body as { profile: Record<string, string | null> }
  expect(posted.profile.pacing).toBe('20 s')
  expect(posted.profile.load_model).toBe('open')
})

test('an emptied profile is saved as null without the confirmation', async ({ page }) => {
  const requests = await openHistory(page, { releases: [release(1, { profile: profile('10 s') })] })
  await expect(page.getByLabel(L.profileFields.pacing, { exact: true })).toHaveValue('10 s')
  await page.getByLabel(L.profileFields.pacing, { exact: true }).fill('')
  await expect(page.getByTestId('profile-confirm')).toHaveCount(0)
  await page.getByLabel(L.formLabel, { exact: true }).fill('v2')
  await page.getByRole('button', { name: L.save, exact: true }).click()
  await expect.poll(() => requests.filter((entry) => entry.method === 'POST' && entry.path === '/api/releases').map((entry) => (entry.body as { profile: unknown }).profile)).toEqual([null])
})

test('a different profile is flagged against the newest release, an undeclared one is not', async ({ page }) => {
  await openHistory(page, { releases: [release(1, { profile: profile('20 s') }), release(2, { profile: null }), release(3, { profile: profile('10 s') })] })
  await expect(rowsOf(page).first().getByTestId('profile-differs')).toHaveCount(0)
  await expect(rowsOf(page).nth(1)).toContainText(L.noProfile)
  await expect(rowsOf(page).nth(1).getByTestId('profile-differs')).toHaveCount(0)
  await expect(rowsOf(page).nth(2).getByTestId('profile-differs')).toHaveText(L.profileDiffers('v1.3'))
})

test('statistical selection explains why it is unavailable', async ({ page }) => {
  await openHistory(page, { releases: [2, 3].map((n) => release(n, { profile: profile('10 s') })) })
  await expect(page.getByTestId('history-statistical-hint')).toHaveText(L.statisticalNeeds(2))
  await expect(page.getByTestId('history-statistical')).toHaveCount(0)
})

test('statistical selection becomes available with three releases of one profile and opens the compare tab', async ({ page }) => {
  await openHistory(page, { releases: [1, 2, 3].map((n) => release(n, { profile: profile('10 s') })) })
  await expect(page.getByTestId('history-statistical-hint')).toHaveCount(0)
  await page.getByTestId('history-statistical').click()
  await expect(page.locator('#shell-tab-compare')).toHaveAttribute('aria-selected', 'true')
})

test('suggestions need a declared profile and are never applied automatically', async ({ page }) => {
  const requests = await openHistory(page, { releases: [release(1), release(2), release(3)] })
  await expect(page.getByTestId('history-needs-profile')).toHaveText(L.suggestionNeedsProfile)
  await expect(page.getByTestId('baseline-suggestion')).toHaveCount(0)
  await expect(page.getByTestId('history-statistical')).toHaveCount(0)
  expect(requests.filter((entry) => entry.method === 'POST' && entry.path === '/api/baseline')).toHaveLength(0)
})

test('the previous release with the same profile is suggested as the baseline', async ({ page }) => {
  const requests = await openHistory(page, { releases: [release(1, { profile: profile('10 s') }), release(2, { profile: profile('20 s') }), release(3, { profile: profile('10 s') })] })
  await expect(page.getByTestId('baseline-suggestion')).toHaveCount(1)
  await expect(rowsOf(page).nth(2).getByTestId('baseline-suggestion')).toHaveText(L.suggestion)
  await expect(page.getByTestId('history-needs-profile')).toHaveCount(0)
  expect(requests.filter((entry) => entry.method === 'POST' && entry.path === '/api/baseline')).toHaveLength(0)
})

test('dynamics points carry the verdict as shape and text, the table keeps the same numbers', async ({ page }) => {
  const notPass = { baseline_eligible: false, ineligible_reasons: ['BASELINE_CANDIDATE_NOT_PASS'] }
  await openHistory(page, {
    releases: [release(1, {}, { policy_verdict: 'PASS' }), release(2, {}, { policy_verdict: 'FAIL', ...notPass }), release(3, {}, { policy_verdict: 'NO_POLICY', ...notPass })],
    p95: { 1: '100', 2: '200', 3: '300' },
  })
  const chart = page.getByTestId('history-dynamics')
  await expect(chart.getByRole('img')).toHaveAttribute('aria-label', L.dynamicsAria(3, [L.dynamicsPoint('v1.1', 'PASS', '100'), L.dynamicsPoint('v1.2', 'FAIL', '200'), L.dynamicsPoint('v1.3', 'NO_POLICY', '300')].join('; ')))
  await expect(chart.locator('circle')).toHaveCount(1)
  await expect(chart.locator('rect')).toHaveCount(1)
  await expect(chart.locator('polygon')).toHaveCount(1)
  await expect(chart).toContainText(L.dynamicsLegend)
  await expect(chart.locator('title').first()).toHaveText(L.dynamicsPoint('v1.1', 'PASS', '100'))
  await expect(rowsOf(page).nth(0).locator('td').nth(3)).toHaveText('300')
})

test('without numbers the chart says so instead of drawing zeros', async ({ page }) => {
  await openHistory(page, { truncated: true })
  await expect(page.getByTestId('history-dynamics')).toContainText(L.dynamicsEmpty)
  await expect(page.getByTestId('history-dynamics').locator('circle, rect, polygon')).toHaveCount(0)
})

test('re-pin replaces the analysis of the release with another analysis of the same run', async ({ page }) => {
  const requests = await openHistory(page)
  await rowsOf(page).nth(1).getByRole('button', { name: L.rebindAria('v1.2') }).click()
  const panel = page.getByTestId('rebind-panel')
  await expect(panel.getByRole('radio')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: L.rebindTitle('v1.2') })).toBeFocused()
  await panel.getByRole('radio').check()
  await page.getByTestId('rebind-apply').click()
  await expect.poll(() => requests.filter((entry) => entry.method === 'PUT').map((entry) => entry.body)).toEqual([
    { label: 'v1.2', analyses: [{ analysis_id: 'f'.repeat(64) }], profile: null, notes: null },
  ])
  await expect(page.getByRole('status').filter({ hasText: L.rebound('v1.2') })).toBeVisible()
  await expect(page.getByTestId('rebind-panel')).toHaveCount(0)
})

test('re-pin cancel returns the focus to its button and a refusal is a Russian phrase', async ({ page }) => {
  await openHistory(page, {
    failures: { 'PUT /api/releases/20260101000002-22222222': { status: 409, code: 'RELEASE_ANALYSIS_ALREADY_REGISTERED', message: 'An analysis already belongs to a release' } },
  })
  const button = rowsOf(page).nth(1).getByRole('button', { name: L.rebindAria('v1.2') })
  await button.click()
  await page.getByRole('button', { name: L.rebindCancel, exact: true }).click()
  await expect(button).toBeFocused()
  await button.click()
  await page.getByTestId('rebind-panel').getByRole('radio').check()
  await page.getByTestId('rebind-apply').click()
  await expect(page.getByRole('alert')).toHaveText(L.errors.RELEASE_ANALYSIS_ALREADY_REGISTERED!(null))
})

test('a release with several analyses cannot be re-pinned and says why', async ({ page }) => {
  await openHistory(page, { releases: [release(1, { analyses: [analysis(1, { arm: 'blue' }), analysis(2, { arm: 'green' })] })] })
  const button = rowsOf(page).first().getByTestId('rebind')
  await expect(button).toBeDisabled()
  await expect(page.locator(`#${await button.getAttribute('aria-describedby')}`)).toHaveText(L.rebindMulti)
})

for (const theme of ['light', 'dark'] as const) {
  test(`the chart, hints and the re-pin panel have no serious axe violations, ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openHistory(page, { releases: [1, 2, 3].map((n) => release(n, { profile: profile(n === 2 ? '20 s' : '10 s') })), p95: { 1: '100', 2: '200', 3: '300' } })
    await rowsOf(page).first().getByRole('button', { name: L.rebindAria('v1.3') }).click()
    await expect(page.getByTestId('rebind-panel').getByRole('radio')).toHaveCount(1)
    const audit = await new AxeBuilder({ page }).include('#history-panel').analyze()
    expect(audit.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}
test('the compare action keeps the series of the release even when it has no baseline', async ({ page }) => {
  await openHistory(page, { releases: [release(1), release(2, { series: 'Other' })], baselineRun: 'hist-run-1' })
  await page.getByTestId('release-row').first().getByRole('button', { name: L.actionAria(L.compare, 'v1.2', null) }).click()
  await expect(page.locator('#shell-tab-compare')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByLabel('Серия сравнения', { exact: true })).toHaveValue('Other')
  await expect(page.locator('#baseline-panel')).toContainText('Other')
  await expect(page.getByTestId('baseline-selection')).toHaveCount(0)
})

test('a failed analytics request and a damaged analysis give their own reasons', async ({ page }) => {
  await openHistory(page, {
    releases: [release(1), release(2, {}, { analysis_state: 'CORRUPT', baseline_eligible: false, ineligible_reasons: ['ANALYSIS_CORRUPT'] })],
    failures: { 'GET /api/runs/hist-run-1/analyses/1111111111111111111111111111111111111111111111111111111111111111/analytics': { status: 500, code: 'X', message: 'boom' } },
  })
  await expect(rowsOf(page).first()).toContainText(L.noNumbersCorrupt)
  await expect(rowsOf(page).nth(1)).toContainText(L.noNumbersFailed)
})

test('releases with the same label have distinguishable action names', async ({ page }) => {
  await openHistory(page, { releases: [release(1, { label: 'same' }), release(2, { label: 'same' })] })
  await expect(rowsOf(page)).toHaveCount(2)
  const names = await page.getByTestId('release-row').getByRole('button', { name: L.open }).evaluateAll((buttons) => buttons.map((button) => button.getAttribute('aria-label')))
  expect(new Set(names).size).toBe(2)
})

test('the statistical count and the suggestion look beyond the four shown releases', async ({ page }) => {
  const notPass = { baseline_eligible: false, ineligible_reasons: ['BASELINE_CANDIDATE_NOT_PASS'], policy_verdict: 'FAIL' as const }
  await openHistory(page, {
    releases: [
      release(1, { profile: profile('10 s') }), release(2, { profile: profile('10 s') }), release(3, { profile: profile('10 s') }, notPass),
      release(4, { profile: profile('10 s') }, notPass), release(5, { profile: profile('10 s') }, notPass), release(6, { profile: profile('10 s') }),
    ],
  })
  await expect(rowsOf(page)).toHaveCount(DEFAULT_VISIBLE_RELEASES)
  await expect(page.getByTestId('history-statistical-hint')).toHaveCount(0)
  await expect(page.getByTestId('history-statistical')).toBeVisible()
})
