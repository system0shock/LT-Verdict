import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { AnalysisResult } from '../src/types'
import { BASELINE_LABELS } from '../src/shell/labels'
import { ADVICE_LABELS } from '../src/shell/labels.advice'
import { EN_ANALYTICS_LABELS } from '../src/shell/labels.export'
import { STAGE_DECIDED_PHRASE, STAGE_MARKER, STAGE_REFERENCE_NOTE, summarizeVerdict } from '../src/verdictSummary'

// ADR 0030, PR C: a run with declared stages says aloud that its verdict is by the steady window. The stage_binding is the real
// item of the engine (the Kotlin test compares the file with the engine output), the rest of the result is hand-built.
const binding = JSON.parse(readFileSync(fileURLToPath(new URL('../../fixtures/stages/stage-binding.sample.json', import.meta.url)), 'utf8'))
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 720, error_count: 0,
  error_rate_ratio: { numerator: 0, denominator: 720 }, throughput_rps: { numerator: 720000, denominator: 119800 },
  latency_ms: { p50: 149, p95: 821, p99: 836, max: 839 },
}
const rule = (status: string, extra: Record<string, unknown> = {}) => ({
  id: 'check-p95', type: 'policy_check', rule_id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 250, window_id: 'steady',
  scope: { kind: 'overall' }, status, observed: 194, ...extra,
})
function build(verdict: string, evidence: unknown[], over: Record<string, unknown> = {}): AnalysisResult {
  return {
    schema_version: 'analysis-result.v1', run_id: 'stage-run', analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: verdict,
    analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence, ...over,
  } as unknown as AnalysisResult
}
const staged = (verdict: string, checks: unknown[] = []) => build(verdict, [overall, binding, ...checks])
const plain = (verdict: string, checks: unknown[] = []) => build(verdict, [overall, ...checks])

test.describe('stage window in the verdict summary', () => {
  test('a decided PASS and FAIL carry the marker, the phrase, the window facts and the stage table', () => {
    for (const [verdict, check] of [['PASS', rule('PASS')], ['FAIL', rule('FAIL')]] as const) {
      const summary = summarizeVerdict(staged(verdict, [check]))
      const base = summarizeVerdict(plain(verdict, [check]))

      expect(summary.headline).toBe(`${base.headline} · ${STAGE_MARKER}`)
      expect(summary.lead.startsWith(base.lead)).toBe(true)
      expect(summary.lead).toContain(STAGE_DECIDED_PHRASE)
      expect(summary.lead).toContain('Окно вердикта: steady, 1 мин, 2026-01-01 00:00:40 UTC – 2026-01-01 00:01:40 UTC. Исключено: 59,8 с.')
      expect(summary.facts.slice(0, base.facts.length)).toEqual(base.facts.slice(0, base.facts.length))
      expect(summary.facts.find((fact) => fact.label === 'Окно вердикта')?.value).toBe('steady, 1 мин, 2026-01-01 00:00:40 UTC – 2026-01-01 00:01:40 UTC')
      expect(summary.facts.find((fact) => fact.label === 'Исключено')?.value).toBe('59,8 с')
      expect(summary.stages?.heads).toEqual(['Стадия', 'Роль', 'Смещения, мс', 'Границы (UTC)', 'Границы (epoch, мс)', 'Обрезана до конца прогона'])
      expect(summary.stages?.rows).toEqual([
        ['ramp-up', 'excluded', '0 – 40000', '2026-01-01 00:00:00 UTC – 2026-01-01 00:00:40 UTC', '1767225600000 – 1767225640000', '—'],
        ['steady', 'steady', '40000 – 100000', '2026-01-01 00:00:40 UTC – 2026-01-01 00:01:40 UTC', '1767225640000 – 1767225700000', '—'],
        ['ramp-down', 'excluded', '100000 – 120000', '2026-01-01 00:01:40 UTC – 2026-01-01 00:02:00 UTC', '1767225700000 – 1767225720000', '—'],
      ])
      expect(summary.stages?.note).toBe(STAGE_REFERENCE_NOTE)
    }
  })

  test('without a verdict the card keeps its headline and does not claim one', () => {
    const cases: Array<[AnalysisResult, AnalysisResult, string]> = [
      [staged('NO_POLICY'), plain('NO_POLICY'), 'NO_POLICY'],
      [staged('NO_VERDICT', [rule('NO_VERDICT', { reason_code: 'RULE_WINDOW_NOT_FOUND' })]), plain('NO_VERDICT', [rule('NO_VERDICT', { reason_code: 'RULE_WINDOW_NOT_FOUND' })]), 'NO_VERDICT'],
      [
        build('NO_VERDICT', [overall, binding], { run_validity: 'DEGRADED' }),
        build('NO_VERDICT', [overall], { run_validity: 'DEGRADED' }),
        'NO_VERDICT',
      ],
    ]
    for (const [withStages, without, verdict] of cases) {
      const summary = summarizeVerdict(withStages)
      const base = summarizeVerdict(without)

      expect(summary.headline).toBe(base.headline)
      expect(summary.lead).not.toContain('Вердикт посчитан')
      expect(summary.lead).toContain(`Окно steady задано (steady), разгон исключён из метрик окна; вердикт: ${verdict}`)
      expect(summary.stages?.rows).toHaveLength(3)
    }
  })

  test('a result without stages is unchanged: no marker, no phrase, no table, no stage facts', () => {
    for (const verdict of ['PASS', 'FAIL', 'NO_POLICY', 'NO_VERDICT']) {
      const summary = summarizeVerdict(plain(verdict, [rule(verdict === 'PASS' || verdict === 'FAIL' ? verdict : 'NO_VERDICT')]))

      expect(summary.stages).toBeNull()
      expect(`${summary.headline} ${summary.lead}`).not.toMatch(/окну steady|Окно steady|Вердикт посчитан/)
      expect(summary.facts.map((fact) => fact.label)).not.toContain('Окно вердикта')
      expect(summary.facts.map((fact) => fact.label)).not.toContain('Исключено')
    }
  })

  test('a clipped steady stage is marked and an early foreign sample shows in the epoch bounds', () => {
    const shifted = {
      ...binding,
      run_from_epoch_ms: 1767225570000,
      stages: binding.stages.map((stage: Record<string, unknown>) => (
        stage.id === 'steady'
          ? { ...stage, from_epoch_ms: 1767225610000, to_epoch_ms: 1767225670000, clipped_to_run_end: true }
          : stage
      )),
    }
    const summary = summarizeVerdict(build('PASS', [overall, shifted, rule('PASS')]))

    expect(summary.stages?.rows[1]).toEqual(['steady', 'steady', '40000 – 100000', '2026-01-01 00:00:10 UTC – 2026-01-01 00:01:10 UTC', '1767225610000 – 1767225670000', 'да'])
  })

  test('the labels exist: the comparison warning, the evidence type and the whole-run note', () => {
    expect(BASELINE_LABELS.warnings.WHOLE_RUN_METRICS_WITH_STAGES).toContain('по всему прогону, справочно')
    expect(ADVICE_LABELS.evidence.types.stage_binding).toBe('Стадии нагрузки (окно steady)')
    expect(EN_ANALYTICS_LABELS.wholeRunNote).toContain('whole-run metrics')
  })
})

const reference = { run_id: 'stage-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'stages.jtl' }

async function fixtureApi(page: Page, result: unknown) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analytics')) {
      body = {
        schema_version: 'saved-analytics.v1', history_scan_truncated: false, history_scan_limit: 1000, history_metadata_byte_limit: 16777216,
        history_integrity: 'SAVED_DOCUMENT_HASHES', dynamics: null, transactions: null, overlay: null,
        metric_packs: { schema_version: 'metric-packs.v1', packs: [] },
      }
    } else if (path.endsWith('/analyses')) {
      body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_id: 'stages', policy_verdict: 'PASS', run_validity: 'VALID' }], next_after: null }
    } else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

async function openSaved(page: Page) {
  await page.goto('/?shell=old')
  await page.getByRole('button', { name: 'stages.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
}

test.describe('stage window on the screen', () => {
  test('the verdict card names the steady window and shows the stage table for a staged analysis', async ({ page }) => {
    await fixtureApi(page, staged('PASS', [rule('PASS')]))
    await openSaved(page)

    await expect(page.locator('#verdict h2')).toContainText(STAGE_MARKER)
    await expect(page.locator('#verdict')).toContainText(STAGE_DECIDED_PHRASE)
    const stages = page.getByTestId('verdict-stages')
    await expect(stages.locator('tbody tr')).toHaveCount(3)
    await expect(stages.locator('tbody tr').nth(1)).toContainText('1767225640000 – 1767225700000')
    await expect(stages).toContainText(STAGE_REFERENCE_NOTE)
    await expect(page.locator('.verdict-facts div').filter({ hasText: 'Окно вердикта' })).toHaveCount(1)
  })

  test('the card of an analysis without stages has no stage block', async ({ page }) => {
    await fixtureApi(page, plain('PASS', [rule('PASS')]))
    await openSaved(page)

    await expect(page.getByTestId('verdict-stages')).toHaveCount(0)
    await expect(page.locator('#verdict')).not.toContainText('окну steady')
  })

  test('the saved analytics say the metrics are of the whole run only for a staged analysis', async ({ page }) => {
    await fixtureApi(page, staged('PASS', [rule('PASS')]))
    await openSaved(page)
    await page.getByRole('button', { name: EN_ANALYTICS_LABELS.refresh, exact: true }).click()
    await expect(page.getByTestId('analytics-whole-run')).toHaveText(EN_ANALYTICS_LABELS.wholeRunNote)

    const other = await page.context().newPage()
    await fixtureApi(other, plain('PASS', [rule('PASS')]))
    await openSaved(other)
    await other.getByRole('button', { name: EN_ANALYTICS_LABELS.refresh, exact: true }).click()
    await expect(other.getByRole('heading', { name: EN_ANALYTICS_LABELS.title })).toBeVisible()
    await expect(other.getByTestId('analytics-whole-run')).toHaveCount(0)
  })

  for (const theme of ['light', 'dark'] as const) {
    test(`the staged verdict card has no serious axe violations, ${theme}`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: theme })
      await fixtureApi(page, staged('PASS', [rule('PASS')]))
      await openSaved(page)
      const results = await new AxeBuilder({ page }).include('#verdict').analyze()

      expect(results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')).toEqual([])
    })
  }
})
