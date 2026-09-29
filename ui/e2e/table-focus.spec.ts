import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const reference = { run_id: 'table-focus-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'table-focus.jtl' }
const scope = { kind: 'transaction', group_path: [], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' }
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'FAIL',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  capacity_summary: {
    schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'BOUNDED', lower_inclusive: 296, upper_exclusive: 344,
    policy_verdict: 'NO_VERDICT', reasons: [], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED',
    stages: [{
      id: 'ramp-300', target: 300, achieved: 296, achieved_statistic: 'p05_10s', observed_min: 296, observed_max: 296, complete_bins: 30, expected_bins: 30,
      target_tolerance_ratio: 0.02, verified_bound_load: 296, verdict: 'PASS', reasons: [], evidence_refs: [],
    }],
  },
  evidence: [
    {
      id: 'm-checkout', type: 'metric_summary', scope, sample_count: 900, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 900 },
      throughput_rps: { numerator: 900000, denominator: 30000 }, latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
    },
    { id: 'c1', type: 'policy_check', rule_id: 'checkout-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000, status: 'FAIL', metric_evidence_id: 'm-checkout', observed: 2340 },
    { id: 'w1', type: 'window_policy_summary', window_id: 'w', from_epoch_ms: 1000, to_epoch_ms: 4000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' },
    {
      id: 'rs1', type: 'resource_summary', series_id: 'cpu', metric: 'cpu', unit: 'ratio', entity: 'app', role: 'system', aggregation: 'interval_mean', window_id: 'w',
      from_epoch_ms: 1000, to_epoch_ms: 4000, expected_cells: 3, observed_cells: 3, missing_cells: 0, longest_gap_cells: 0, statistics: null,
    },
    { id: 'rc1', type: 'resource_policy_check', window_id: 'w', rule_id: 'cpu-limit', series_id: 'cpu', unit: 'ratio', operator: 'gt', threshold: '0.8', effect: 'sla', status: 'PASS', reason: null },
    {
      id: 'source-summary', type: 'source_summary', status: 'PARTIAL', profile_id: 'prod-prometheus', source_kind: 'prometheus', transport: 'direct',
      queries: [{ id: 'cpu', status: 'COMPLETE' }], request_count: 3, retries: 1, throttle_wait_ms: 250, cap_exceeded: false,
      window_origin: 'auto', recognized_start_epoch_ms: 1000, recognized_end_epoch_ms: 4000, requested_margin_ms: 2000, applied_margin_ms: 2000,
      max_idle_gap_ms: 60000, detected_idle_gaps: 0, longest_idle_gap_ms: null, auto_window_status: 'DERIVED',
    },
    {
      id: 'os1', type: 'opensearch_errors', profile_id: 'prod-logs', total_errors: 1, error_rate_per_minute: 1, coverage: { status: 'COMPLETE', reasons: [] },
      groups: [{ service: 'checkout', error_type: 'Timeout', count: 1, first_epoch_ms: 1000, last_epoch_ms: 1000, samples: [] }],
    },
  ],
}

async function fixtureApi(page: Page) {
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
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

test('scrollable table wrappers are keyboard focusable regions with unique names', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await fixtureApi(page)
  await page.setViewportSize({ width: 600, height: 900 })
  await page.goto('/')
  await page.getByRole('button', { name: 'table-focus.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()

  const wraps = await page.locator('.table-wrap').evaluateAll((elements) =>
    elements.map((element) => ({
      id: element.closest('section')?.id ?? element.closest('[data-testid]')?.getAttribute('data-testid') ?? '',
      scrolls: element.scrollWidth > element.clientWidth,
      tabindex: element.getAttribute('tabindex'),
      role: element.getAttribute('role'),
      label: element.getAttribute('aria-label') ?? '',
    })),
  )
  expect(wraps.length).toBeGreaterThanOrEqual(10)
  expect(wraps.filter((wrap) => wrap.scrolls && (wrap.tabindex !== '0' || wrap.role !== 'region' || wrap.label.trim() === ''))).toEqual([])
  expect(new Set(wraps.map((wrap) => wrap.label)).size).toBe(wraps.length)

  const axe = await new AxeBuilder({ page }).withRules(['scrollable-region-focusable']).analyze()
  expect(axe.violations.map((violation) => ({ id: violation.id, nodes: violation.nodes.length }))).toEqual([])
  expect(errors).toEqual([])
})
