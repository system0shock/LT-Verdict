import { expect, test } from '@playwright/test'
import {
  capacityKneeChartModel,
  capacityKneeOption,
  capacityKneeReadout,
  type CapacityChartColors,
} from '../src/shell/capacityChart'
import type { AnalysisResult } from '../src/types'

const colors: CapacityChartColors = {
  brand: '#1d4ed8',
  pass: '#15803d',
  fail: '#b91c1c',
  warn: '#b45309',
  text: '#111827',
  muted: '#4b5563',
  border: '#d1d5db',
  fontFamily: 'system-ui',
}

function baseResult(over: Partial<AnalysisResult> = {}): AnalysisResult {
  return {
    schema_version: 'analysis-result.v1',
    run_id: 'cap-chart-run',
    analysis_mode: 'capacity_step',
    run_validity: 'VALID',
    policy_verdict: 'PASS',
    analysis_coverage: { status: 'COMPLETE', reasons: [] },
    findings: [],
    evidence: [],
    ...over,
  }
}

test('capacityKneeChartModel returns null when capacity_summary is absent', () => {
  expect(capacityKneeChartModel(baseResult({ analysis_mode: 'standard' }))).toBeNull()
})

test('capacityKneeChartModel joins latency, calibrated knee, bounds and generator guards per stage', () => {
  const result = baseResult({
    capacity_summary: {
      schema_version: 'capacity.v1',
      load_axis: 'rps',
      unit: 'requests/s',
      bound_type: 'LOWER_BOUND',
      lower_inclusive: '80',
      upper_exclusive: null,
      policy_verdict: 'PASS',
      reasons: ['CAPACITY_GUARD_FAILED'],
      capacity_knee: '80',
      knee_reason: 'KNEE_DETECTED',
      stages: [
        {
          id: 's1', target: 40, achieved: '40', achieved_statistic: 'p05_10s',
          observed_min: 39, observed_max: 41, complete_bins: 30, expected_bins: 30,
          target_tolerance_ratio: 0.05, verified_bound_load: '40', verdict: 'PASS',
          reasons: [], evidence_refs: ['ws-1', 'gc-1'],
        },
        {
          id: 's2', target: 80, achieved: '80', achieved_statistic: 'p05_10s',
          observed_min: 79, observed_max: 81, complete_bins: 30, expected_bins: 30,
          target_tolerance_ratio: 0.05, verified_bound_load: '80', verdict: 'PASS',
          reasons: [], evidence_refs: ['ws-2', 'gc-2'],
        },
        {
          id: 's3', target: 100, achieved: '80', achieved_statistic: 'p05_10s',
          observed_min: 78, observed_max: 82, complete_bins: 30, expected_bins: 30,
          target_tolerance_ratio: 0.05, verified_bound_load: null, verdict: 'INDETERMINATE',
          reasons: ['CAPACITY_GUARD_FAILED', 'CAPACITY_TARGET_MISSED'], evidence_refs: ['ws-3', 'gc-3', 'sys-3'],
        },
      ],
    },
    evidence: [
      { id: 'ws-1', type: 'window_policy_summary', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' },
      { id: 'ws-2', type: 'window_policy_summary', window_id: 's2', from_epoch_ms: 300_000, to_epoch_ms: 600_000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' },
      { id: 'ws-3', type: 'window_policy_summary', window_id: 's3', from_epoch_ms: 600_000, to_epoch_ms: 900_000, business_verdict: 'PASS', resource_verdict: 'NO_POLICY', verdict: 'PASS' },
      { id: 'wm-1', type: 'window_metric_summary', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, sample_count: 1000, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 40, denominator: 1 }, latency_ms: { p50: 20, p95: 50, p99: 80, max: 120 }, resource_bindings: [] },
      { id: 'wm-2', type: 'window_metric_summary', window_id: 's2', from_epoch_ms: 300_000, to_epoch_ms: 600_000, sample_count: 1000, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 80, denominator: 1 }, latency_ms: { p50: 22, p95: 55, p99: 95, max: 150 }, resource_bindings: [] },
      { id: 'wm-3', type: 'window_metric_summary', window_id: 's3', from_epoch_ms: 600_000, to_epoch_ms: 900_000, sample_count: 1000, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 80, denominator: 1 }, latency_ms: { p50: 40, p95: 900, p99: 1500, max: 2500 }, resource_bindings: [] },
      { id: 'rs-gen-3', type: 'resource_summary', series_id: 'gen-cpu', metric: 'cpu', unit: 'ratio', entity: 'gen', role: 'generator', aggregation: 'interval_mean', window_id: 's3', from_epoch_ms: 600_000, to_epoch_ms: 900_000, expected_cells: 30, observed_cells: 30, missing_cells: 0, longest_gap_cells: 0, statistics: { max: '0.97' }, reasons: [] },
      { id: 'rs-sys-3', type: 'resource_summary', series_id: 'app-cpu', metric: 'cpu', unit: 'ratio', entity: 'app', role: 'system', aggregation: 'interval_mean', window_id: 's3', from_epoch_ms: 600_000, to_epoch_ms: 900_000, expected_cells: 30, observed_cells: 30, missing_cells: 0, longest_gap_cells: 0, statistics: { max: '0.70' }, reasons: [] },
      { id: 'gc-1', type: 'resource_policy_check', window_id: 's1', rule_id: 'gen-guard', series_id: 'gen-cpu', unit: 'ratio', operator: 'gt', threshold: '0.9', effect: 'diagnostic', status: 'PASS', reason: null },
      { id: 'gc-2', type: 'resource_policy_check', window_id: 's2', rule_id: 'gen-guard', series_id: 'gen-cpu', unit: 'ratio', operator: 'gt', threshold: '0.9', effect: 'diagnostic', status: 'PASS', reason: null },
      { id: 'gc-3', type: 'resource_policy_check', window_id: 's3', rule_id: 'gen-guard', series_id: 'gen-cpu', unit: 'ratio', operator: 'gt', threshold: '0.9', effect: 'diagnostic', status: 'FAIL', reason: null },
      { id: 'sys-3', type: 'resource_policy_check', window_id: 's3', rule_id: 'app-diag', series_id: 'app-cpu', unit: 'ratio', operator: 'gt', threshold: '0.6', effect: 'diagnostic', status: 'FAIL', reason: null },
      {
        id: 'capacity-knee-diagnostic', type: 'capacity_knee_diagnostic', method: 'piecewise-hinge-ln-p95.v2',
        status: 'DETECTED', confidence: 'HIGH', calibrated: true, diagnostic_only: true,
        last_stable_stage_id: 's2', last_stable_load: '80', first_degraded_stage_id: 's3', first_degraded_load: '80',
        reasons: [], points: [{ stage_id: 's1', load: '40', value: 50 }, { stage_id: 's2', load: '80', value: 55 }, { stage_id: 's3', load: '80', value: 900 }],
      },
    ],
  })

  const model = capacityKneeChartModel(result)!
  expect(model).not.toBeNull()
  expect(model.plottableCount).toBe(3)
  expect(model.hasP99).toBe(true)
  expect(model.hasGuards).toBe(true)
  expect(model.guardAlert).toBe(true)
  expect(model.lowerBound).toBe(80)
  expect(model.upperBound).toBeNull()
  expect(model.calibratedKnee).toBe(80)
  expect(model.points.map((p) => p.guardStatus)).toEqual(['PASS', 'PASS', 'FAIL'])
  // System diagnostic rule is excluded when generator series is known.
  expect(model.points[2].guardChecks.map((c) => c.ruleId)).toEqual(['gen-guard'])
  expect(model.points[2].guardChecks[0].observedPeak).toBe('0.97')

  const option = capacityKneeOption(model, colors) as {
    animation: boolean
    tooltip?: unknown
    series: Array<{ data: Array<{ symbol?: string }>; markLine?: { data: unknown[] }; markArea?: { data: unknown[] } }>
  }
  expect(option.animation).toBe(false)
  expect(option).not.toHaveProperty('tooltip')
  expect(option.series).toHaveLength(2)
  expect(option.series[0].data.map((d) => d.symbol)).toEqual(['circle', 'circle', 'diamond'])
  expect(option.series[0].markLine?.data).toHaveLength(2)
  expect(option.series[0].markArea?.data).toHaveLength(1)

  const readout = capacityKneeReadout(model.points[2], model.unit).replace(/\s/g, ' ')
  expect(readout).toContain('Ступень s3')
  expect(readout).toContain('900 мс')
  expect(readout).toContain('p99 1 500 мс')
  expect(readout).toContain('насыщение / нарушение')
})

test('capacityKneeChartModel does not misclassify system diagnostic checks when generator series are absent', () => {
  const probe4a = baseResult({
    capacity_summary: {
      schema_version: 'capacity.v1',
      load_axis: 'rps',
      unit: 'requests/s',
      bound_type: 'INDETERMINATE',
      lower_inclusive: null,
      upper_exclusive: null,
      policy_verdict: 'NO_VERDICT',
      reasons: ['CAPACITY_GUARD_MISSING'],
      capacity_knee: null,
      knee_reason: 'KNEE_TOO_FEW_STAGES',
      stages: [
        {
          id: 's1', target: 40, achieved: '40', achieved_statistic: 'p05_10s',
          observed_min: 39, observed_max: 41, complete_bins: 30, expected_bins: 30,
          target_tolerance_ratio: 0.05, verified_bound_load: null, verdict: 'INDETERMINATE',
          reasons: ['CAPACITY_GUARD_MISSING'], evidence_refs: ['ws-1', 'sys-1'],
        },
        {
          id: 's2', target: 80, achieved: '80', achieved_statistic: 'p05_10s',
          observed_min: 79, observed_max: 81, complete_bins: 30, expected_bins: 30,
          target_tolerance_ratio: 0.05, verified_bound_load: '80', verdict: 'PASS',
          reasons: ['CAPACITY_GUARD_MISSING'], evidence_refs: ['ws-2'],
        },
      ],
    },
    evidence: [
      { id: 'ws-1', type: 'window_policy_summary', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' },
      { id: 'ws-2', type: 'window_policy_summary', window_id: 's2', from_epoch_ms: 300_000, to_epoch_ms: 600_000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' },
      { id: 'wm-1', type: 'window_metric_summary', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, sample_count: 1000, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 40, denominator: 1 }, latency_ms: { p50: 20, p95: 50, p99: 80, max: 120 }, resource_bindings: [] },
      { id: 'wm-2', type: 'window_metric_summary', window_id: 's2', from_epoch_ms: 300_000, to_epoch_ms: 600_000, sample_count: 1000, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 80, denominator: 1 }, latency_ms: { p50: 22, p95: 55, p99: 95, max: 150 }, resource_bindings: [] },
      { id: 'rs-sys-1', type: 'resource_summary', series_id: 'app-cpu', metric: 'cpu', unit: 'ratio', entity: 'app', role: 'system', aggregation: 'interval_mean', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, expected_cells: 30, observed_cells: 30, missing_cells: 0, longest_gap_cells: 0, statistics: { max: '0.85' }, reasons: [] },
      { id: 'sys-1', type: 'resource_policy_check', window_id: 's1', rule_id: 'app-cpu-warn', series_id: 'app-cpu', unit: 'ratio', operator: 'gt', threshold: '0.7', effect: 'diagnostic', status: 'FAIL', reason: null },
    ],
  })

  const model4a = capacityKneeChartModel(probe4a)!
  expect(model4a.points[0].guardStatus).toBe('MISSING')
  expect(model4a.points[0].guardChecks).toEqual([])
  expect(model4a.hasGuards).toBe(true)
  expect(model4a.guardAlert).toBe(true)

  const option4a = capacityKneeOption(model4a, colors) as {
    series: Array<{ data: Array<{ symbol?: string; itemStyle?: { color?: string } }> }>
  }
  expect(option4a.series[0].data[0].symbol).toBe('diamond')
  expect(option4a.series[0].data[0].itemStyle?.color).toBe(colors.warn)

  const probe4b = baseResult({
    capacity_summary: {
      schema_version: 'capacity.v1',
      load_axis: 'rps',
      unit: 'requests/s',
      bound_type: 'LOWER_BOUND',
      lower_inclusive: '80',
      upper_exclusive: null,
      policy_verdict: 'PASS',
      reasons: [],
      capacity_knee: null,
      knee_reason: 'KNEE_TOO_FEW_STAGES',
      stages: [
        {
          id: 's1', target: 40, achieved: '40', achieved_statistic: 'p05_10s',
          observed_min: 39, observed_max: 41, complete_bins: 30, expected_bins: 30,
          target_tolerance_ratio: 0.05, verified_bound_load: '40', verdict: 'PASS',
          reasons: [], evidence_refs: ['ws-1', 'sys-1', 'sys-gen-name'],
        },
        {
          id: 's2', target: 80, achieved: '80', achieved_statistic: 'p05_10s',
          observed_min: 79, observed_max: 81, complete_bins: 30, expected_bins: 30,
          target_tolerance_ratio: 0.05, verified_bound_load: '80', verdict: 'PASS',
          reasons: [], evidence_refs: ['ws-2'],
        },
      ],
    },
    evidence: [
      { id: 'ws-1', type: 'window_policy_summary', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' },
      { id: 'ws-2', type: 'window_policy_summary', window_id: 's2', from_epoch_ms: 300_000, to_epoch_ms: 600_000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' },
      { id: 'wm-1', type: 'window_metric_summary', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, sample_count: 1000, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 40, denominator: 1 }, latency_ms: { p50: 20, p95: 50, p99: 80, max: 120 }, resource_bindings: [] },
      { id: 'wm-2', type: 'window_metric_summary', window_id: 's2', from_epoch_ms: 300_000, to_epoch_ms: 600_000, sample_count: 1000, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 80, denominator: 1 }, latency_ms: { p50: 22, p95: 55, p99: 95, max: 150 }, resource_bindings: [] },
      { id: 'rs-sys-1', type: 'resource_summary', series_id: 'app-cpu', metric: 'cpu', unit: 'ratio', entity: 'app', role: 'system', aggregation: 'interval_mean', window_id: 's1', from_epoch_ms: 0, to_epoch_ms: 300_000, expected_cells: 30, observed_cells: 30, missing_cells: 0, longest_gap_cells: 0, statistics: { max: '0.85' }, reasons: [] },
      { id: 'sys-1', type: 'resource_policy_check', window_id: 's1', rule_id: 'app-cpu-warn', series_id: 'app-cpu', unit: 'ratio', operator: 'gt', threshold: '0.7', effect: 'diagnostic', status: 'FAIL', reason: null },
      { id: 'sys-gen-name', type: 'resource_policy_check', window_id: 's1', rule_id: 'codegen-latency-warn', series_id: 'app-cpu', unit: 'ratio', operator: 'gt', threshold: '0.7', effect: 'diagnostic', status: 'FAIL', reason: null },
    ],
  })

  const model4b = capacityKneeChartModel(probe4b)!
  expect(model4b.points.map((p) => p.guardStatus)).toEqual(['NONE', 'NONE'])
  expect(model4b.points[0].guardChecks).toEqual([])
  expect(model4b.hasGuards).toBe(false)
  expect(model4b.guardAlert).toBe(false)

  const option4b = capacityKneeOption(model4b, colors) as {
    series: Array<{ data: Array<{ symbol?: string; itemStyle?: { color?: string } }> }>
  }
  expect(option4b.series[0].data.map((d) => d.symbol)).toEqual(['circle', 'circle'])
  expect(option4b.series[0].data.map((d) => d.itemStyle?.color)).toEqual([colors.pass, colors.pass])
})

test('capacityKneeOption renders middle-plateau markArea and omits markers on flat NOT_DETECTED curves', () => {
  const plateauResult = baseResult({
    capacity_summary: {
      schema_version: 'capacity.v1',
      load_axis: 'rps',
      unit: 'requests/s',
      bound_type: 'LOWER_BOUND',
      lower_inclusive: '80',
      upper_exclusive: null,
      policy_verdict: 'PASS',
      reasons: [],
      capacity_knee: '80',
      knee_reason: 'KNEE_DETECTED',
      stages: [
        { id: 's0', target: 40, achieved: '40', achieved_statistic: 'p05_10s', observed_min: 40, observed_max: 40, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '40', verdict: 'PASS', reasons: [], evidence_refs: [] },
        { id: 's1', target: 60, achieved: '60', achieved_statistic: 'p05_10s', observed_min: 60, observed_max: 60, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '60', verdict: 'PASS', reasons: [], evidence_refs: [] },
        { id: 's2', target: 80, achieved: '80', achieved_statistic: 'p05_10s', observed_min: 80, observed_max: 80, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '80', verdict: 'PASS', reasons: [], evidence_refs: [] },
        { id: 's3', target: 90, achieved: '80', achieved_statistic: 'p05_10s', observed_min: 80, observed_max: 80, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: null, verdict: 'INDETERMINATE', reasons: [], evidence_refs: [] },
        { id: 's4', target: 100, achieved: '100', achieved_statistic: 'p05_10s', observed_min: 100, observed_max: 100, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: null, verdict: 'FAIL', reasons: [], evidence_refs: [] },
      ],
    },
    evidence: [
      {
        id: 'capacity-knee-diagnostic', type: 'capacity_knee_diagnostic', method: 'piecewise-hinge-ln-p95.v2',
        status: 'DETECTED', confidence: 'HIGH', calibrated: true, diagnostic_only: true,
        last_stable_stage_id: 's2', last_stable_load: '80', first_degraded_stage_id: 's3', first_degraded_load: '80',
        reasons: [], points: [{ stage_id: 's0', load: '40', value: 50 }, { stage_id: 's1', load: '60', value: 50 }, { stage_id: 's2', load: '80', value: 50 }, { stage_id: 's3', load: '80', value: 5000 }, { stage_id: 's4', load: '100', value: 10000 }],
      },
    ],
  })

  const plateauModel = capacityKneeChartModel(plateauResult)!
  const plateauOption = capacityKneeOption(plateauModel, colors) as {
    series: Array<{ markArea?: { data: Array<[{ xAxis: number }, { xAxis: number }]> } }>
  }
  const area = plateauOption.series[0].markArea?.data[0]
  expect(area?.[0].xAxis).toBe(80)
  expect(area?.[1].xAxis).toBeGreaterThan(80)

  const flatResult = baseResult({
    capacity_summary: {
      schema_version: 'capacity.v1',
      load_axis: 'rps',
      unit: 'requests/s',
      bound_type: 'LOWER_BOUND',
      lower_inclusive: '100',
      upper_exclusive: null,
      policy_verdict: 'PASS',
      reasons: [],
      capacity_knee: null,
      knee_reason: 'KNEE_NO_BREAK',
      stages: [
        { id: 's0', target: 20, achieved: '20', achieved_statistic: 'p05_10s', observed_min: 20, observed_max: 20, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '20', verdict: 'PASS', reasons: [], evidence_refs: [] },
        { id: 's1', target: 40, achieved: '40', achieved_statistic: 'p05_10s', observed_min: 40, observed_max: 40, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '40', verdict: 'PASS', reasons: [], evidence_refs: [] },
        { id: 's2', target: 60, achieved: '60', achieved_statistic: 'p05_10s', observed_min: 60, observed_max: 60, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '60', verdict: 'PASS', reasons: [], evidence_refs: [] },
        { id: 's3', target: 80, achieved: '80', achieved_statistic: 'p05_10s', observed_min: 80, observed_max: 80, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '80', verdict: 'PASS', reasons: [], evidence_refs: [] },
        { id: 's4', target: 100, achieved: '100', achieved_statistic: 'p05_10s', observed_min: 100, observed_max: 100, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.05, verified_bound_load: '100', verdict: 'PASS', reasons: [], evidence_refs: [] },
      ],
    },
    evidence: [
      {
        id: 'capacity-knee-diagnostic', type: 'capacity_knee_diagnostic', method: 'piecewise-hinge-ln-p95.v2',
        status: 'NOT_DETECTED', confidence: 'NONE', calibrated: true, diagnostic_only: true,
        last_stable_stage_id: null, last_stable_load: null, first_degraded_stage_id: null, first_degraded_load: null,
        reasons: ['KNEE_NO_BREAK'], points: [{ stage_id: 's0', load: '20', value: 50 }, { stage_id: 's1', load: '40', value: 50 }, { stage_id: 's2', load: '60', value: 50 }, { stage_id: 's3', load: '80', value: 50 }, { stage_id: 's4', load: '100', value: 50 }],
      },
    ],
  })

  const flatModel = capacityKneeChartModel(flatResult)!
  expect(flatModel.plottableCount).toBe(5)
  const flatOption = capacityKneeOption(flatModel, colors) as {
    series: Array<{ markLine?: { data: Array<{ name?: string }> }; markArea?: unknown }>
  }
  expect(flatOption.series[0].markLine?.data.some((line) => line.name === 'calibrated_knee')).toBe(false)
  expect(flatOption.series[0].markArea).toBeUndefined()
})
