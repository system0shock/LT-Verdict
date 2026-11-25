import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { AnalysisResult, Bucket, ResourceSeriesEntry, ResourceSeriesValues } from '../src/types'
import { compareFrame, compareLanes, compareReadout, compareStops, compareThresholds, nearestStopIndex } from '../src/shell/compareChart'
import { COMPARE_LABELS } from '../src/shell/labels.compare'
import { deepLane, resourceTrack } from '../src/shell/deep'
import { DEEP_LABELS } from '../src/shell/labels'
import { formatNumber, loadFrame, loadLane, loadSeries } from '../src/shell/overview'
import {
  PLOT_INSET,
  cursorX,
  laneGrid,
  laneOption,
  lineData,
  niceCeil,
  plotFraction,
  signedOffset,
  stageBands,
  timeTicks,
  valueAxis,
  type ChartColors,
  type ChartFrame,
  type ChartLane,
} from '../src/shell/timeChart'

// Чистая модель дорожки графика: без страницы и без ECharts, как deep-adapters.spec.ts.
const binding = JSON.parse(readFileSync(fileURLToPath(new URL('../../fixtures/stages/stage-binding.sample.json', import.meta.url)), 'utf8'))
const bucket = (start: number, samples: number, errors: number, p95: number): Bucket => ({
  bucket_start_ms: start, sample_count: samples, error_count: errors, p95_latency_ms: p95, max_latency_ms: p95 + 10, hdr_v2_base64: 'AAAA',
})
const lane: ChartLane = { key: 'rps', color: 'brand', segments: [[{ x: 0, y: 10 }, { x: 6000, y: 120 }]], lo: 0, hi: 120 }
const colors: ChartColors = { brand: '#1', warn: '#2', fail: '#3', text: '#4', muted: '#5', border: '#6', fontFamily: 'sans-serif' }

test('niceCeil rounds up to 1, 2, 2.5, 5 times a power of ten', () => {
  expect([0, 7, 120, 900, 1180, 2.3, 0.07].map(niceCeil)).toEqual([1, 10, 200, 1000, 2000, 2.5, 0.1])
})
test('valueAxis never collapses', () => {
  expect(valueAxis(0, 120)).toEqual({ min: 0, max: 200, interval: 100 })
  expect(valueAxis(0, 0)).toEqual({ min: 0, max: 1, interval: 0.5 })
  expect(valueAxis(-3, 10)).toEqual({ min: -5, max: 10, interval: 7.5 })
})
test('valueAxis keeps whole-number ticks for counts', () => {
  expect(valueAxis(0, 0, true)).toEqual({ min: 0, max: 2, interval: 1 })
  expect(valueAxis(0, 1, true)).toEqual({ min: 0, max: 2, interval: 1 })
  expect(valueAxis(0, 3, true)).toEqual({ min: 0, max: 6, interval: 3 })
  expect(valueAxis(0, 25, true)).toEqual({ min: 0, max: 26, interval: 13 })
  expect(valueAxis(0, 7, true)).toEqual({ min: 0, max: 10, interval: 5 })
})
test('value tick labels keep every digit of the step instead of rounding it', () => {
  const formatter = (hi: number) => ((laneOption({ ...lane, hi }, { fromMs: 0, toMs: 6000, originMs: 0 }, [], { timeLabels: false, stageLabels: false }, colors).yAxis as { axisLabel: { formatter: (value: number) => string } }).axisLabel.formatter)
  expect([0, 0.025, 0.05].map(formatter(0.03))).toEqual(['0', '0,025', '0,05'])
  expect([0, 0.5, 1].map(formatter(1))).toEqual(['0', '0,5', '1'])
  expect([0, 2500, 5000].map(formatter(3241))).toEqual([0, 2500, 5000].map((value) => formatNumber(value)))
})
test('only the error lane is a count lane', () => {
  const series = loadSeries([bucket(0, 100, 0, 200), bucket(1000, 120, 3, 220)], 1)
  expect([loadLane(series, 'rps').integer, loadLane(series, 'errors').integer, loadLane(series, 'p95').integer]).toEqual([undefined, true, undefined])
  const option = laneOption(loadLane(series, 'errors'), loadFrame(series), [], { timeLabels: false, stageLabels: false }, colors)
  expect(option.yAxis).toMatchObject({ min: 0, max: 6, interval: 3 })
})
test('timeTicks follow the step ladder and the run origin', () => {
  expect(timeTicks({ fromMs: 0, toMs: 6000, originMs: 0 })).toEqual([0, 1000, 2000, 3000, 4000, 5000, 6000])
  expect(timeTicks({ fromMs: 0, toMs: 14_390_000, originMs: 0 })).toEqual([0, 3_600_000, 7_200_000, 10_800_000, 14_390_000])
  const origin = 1_767_225_600_000
  expect(timeTicks({ fromMs: origin - 61_000, toMs: origin + 119_000, originMs: origin }).map((t) => signedOffset(t - origin)))
    .toEqual(['-1:00', '-0:30', '0:00', '0:30', '1:00', '1:30', '1:59'])
  // A step ladder tick closer than three quarters of a step to the frame end gives way to the end itself.
  expect(timeTicks({ fromMs: 0, toMs: 6500, originMs: 0 })).toEqual([0, 2000, 4000, 6500])
})
test('lineData breaks between segments and marks an isolated point with a symbol', () => {
  const data = lineData([[{ x: 0, y: 1 }, { x: 1000, y: 2 }], [{ x: 4000, y: 3 }]])
  expect(data.map((item) => item.value)).toEqual([[0, 1], [1000, 2], [2500, null], [4000, 3]])
  expect(data.map((item) => item.symbol ?? '')).toEqual(['', '', '', 'circle'])
})
test('plotFraction ignores the axis gutters and clamps', () => {
  const rect = { left: 100, width: 1072 }
  expect(plotFraction(100, rect)).toBe(0)
  expect(plotFraction(100 + PLOT_INSET.left, rect)).toBe(0)
  expect(plotFraction(100 + 1072 - PLOT_INSET.right, rect)).toBe(1)
  expect(plotFraction(100 + PLOT_INSET.left + 500, rect)).toBe(0.5)
})
test('loadFrame centres a single bucket', () => {
  expect(loadFrame(loadSeries([bucket(500, 1, 0, 1)], 1))).toEqual({ fromMs: 0, toMs: 1000, originMs: 0 })
  expect(cursorX({ fromMs: 0, toMs: 6000, originMs: 0 }, 4000)).toBe(666.67)
})
test('loadLane maps a series to a coloured lane on the shared scale', () => {
  const series = loadSeries([bucket(0, 100, 0, 200), bucket(1000, 120, 1, 220), bucket(3000, 90, 7, 900)], 1)
  expect(loadLane(series, 'p95')).toMatchObject({ key: 'p95', color: 'warn', lo: 0, hi: 900, segments: [[{ x: 0, y: 200 }, { x: 1000, y: 220 }], [{ x: 3000, y: 900 }]] })
  expect(loadLane(series, 'errors').color).toBe('fail')
  expect(loadLane(series, 'rps').color).toBe('brand')
})
test('stageBands reads stage_binding offsets on the given origin', () => {
  const result = { evidence: [binding] } as never
  expect(stageBands(result, 0)).toEqual([
    { id: 'ramp-up', fromMs: 0, toMs: 40_000, steady: false },
    { id: 'steady', fromMs: 40_000, toMs: 100_000, steady: true },
    { id: 'ramp-down', fromMs: 100_000, toMs: 120_000, steady: false },
  ])
  expect(stageBands({ evidence: [] } as never, 0)).toEqual([])
})
test('laneOption is static, svg-safe and fixes the gutters', () => {
  const option = laneOption(lane, { fromMs: 0, toMs: 6000, originMs: 0 }, [], { timeLabels: true, stageLabels: false }, colors)
  expect(option.animation).toBe(false)
  expect(option).not.toHaveProperty('tooltip')
  expect(option.grid).toMatchObject({ left: 56, right: 16, bottom: 50 })
  expect(option.xAxis).toMatchObject({ type: 'value', min: 0, max: 6000, axisLabel: { show: true, customValues: [0, 1000, 2000, 3000, 4000, 5000, 6000] } })
  expect(option.yAxis).toMatchObject({ min: 0, max: 200, interval: 100 })
})
test('stage areas are clipped to the frame and labelled only when wide enough', () => {
  const stages = stageBands({ evidence: [binding] } as never, 0)
  type Area = Array<{ name: string; xAxis: number; label: { show: boolean } }>
  const areas = (frame: ChartFrame, stageLabels: boolean) =>
    (laneOption(lane, frame, stages, { timeLabels: false, stageLabels }, colors).series as Array<{ markArea: { data: Area[] } }>)[0].markArea.data
  expect(areas({ fromMs: 0, toMs: 119_000, originMs: 0 }, true).map(([a, b]) => [a.name, a.xAxis, b.xAxis, a.label.show]))
    .toEqual([['ramp-up', 0, 40_000, true], ['steady', 40_000, 100_000, true], ['ramp-down', 100_000, 119_000, true]])
  expect(areas({ fromMs: 50_000, toMs: 104_000, originMs: 0 }, true).map(([a]) => [a.name, a.label.show])).toEqual([['steady', true], ['ramp-down', false]])
  expect(areas({ fromMs: 0, toMs: 119_000, originMs: 0 }, false).every(([a]) => !a.label.show)).toBe(true)
})

test('thresholds become dashed mark lines coloured by violation', () => {
  const thresholds = [{ id: 'cpu-high', value: 5, text: 'порог 5 %', violated: true }, { id: 'queue-ok', value: 50, text: 'порог 50 %', violated: false }]
  type Mark = { yAxis: number; lineStyle: { color: string }; label: { formatter: string; color: string; fontFamily: string } }
  const line = (laneOption({ ...lane, thresholds }, { fromMs: 0, toMs: 6000, originMs: 0 }, [], { timeLabels: false, stageLabels: false }, colors).series as Array<{ markLine: { silent: boolean; data: Mark[] } }>)[0].markLine
  expect(line.silent).toBe(true)
  expect(line.data.map((item) => [item.yAxis, item.label.formatter, item.lineStyle.color, item.label.color, item.label.fontFamily]))
    .toEqual([[5, 'порог 5 %', colors.fail, colors.fail, 'sans-serif'], [50, 'порог 50 %', colors.muted, colors.muted, 'sans-serif']])
})
test('deepLane keeps cell middles, breaks at nulls and carries thresholds', () => {
  const entry = { id: 'cpu', metric: 'cpu', unit: '%', entity: 'node', role: 'system', aggregation: 'interval_mean', reducer: 'mean', labels: {}, observed_cells: 2 } as ResourceSeriesEntry
  const valueGrid = { start_epoch_ms: 0, source_step_ms: 15_000, step_ms: 60_000, first_cell_start_ms: 0, cell_count: 3, source_cells_per_cell: 4, last_cell_source_cells: 4 } as ResourceSeriesValues['grid']
  const series = { id: 'cpu', aggregation: 'interval_mean', reducer: 'mean', values: [2, null, 4], observed: [4, 0, 4] } as ResourceSeriesValues['series'][number]
  const track = resourceTrack(entry, series, valueGrid, [{ ruleId: 'high', operator: 'gt', value: 8, violated: false }])
  const result = deepLane(track, { fromMs: 0, toMs: 180_000 })
  expect(result).toMatchObject({ key: 'cpu', color: 'text' })
  expect(result.segments).toEqual([[{ x: 30_000, y: 2 }], [{ x: 150_000, y: 4 }]])
  expect([result.lo, result.hi]).toEqual([0, 8])
  expect(result.thresholds).toEqual([{ id: 'high', value: 8, text: DEEP_LABELS.thresholdMark(formatNumber(8), '%'), violated: false }])
})
test('a threshold on the top edge of the axis puts its label below the line', () => {
  const thresholds = [{ id: 'top', value: 200, text: 'порог 200', violated: false }, { id: 'inside', value: 50, text: 'порог 50', violated: false }]
  const line = (laneOption({ ...lane, thresholds }, { fromMs: 0, toMs: 6000, originMs: 0 }, [], { timeLabels: false, stageLabels: false }, colors).series as Array<{ markLine: { data: Array<{ label: { position: string } }> } }>)[0].markLine
  expect(line.data.map((item) => item.label.position)).toEqual(['insideStartBottom', 'insideStartTop'])
})

test('laneGrid and valueAxis support optional plotHeight and ySplits while preserving defaults', () => {
  expect(laneGrid({ timeLabels: false, stageLabels: false })).toEqual({ left: 56, right: 16, top: 6, bottom: 6, height: 90 })
  expect(laneGrid({ timeLabels: false, stageLabels: true, plotHeight: 236, ySplits: 4 })).toEqual({ left: 56, right: 16, top: 18, bottom: 6, height: 260 })
  expect(valueAxis(0, 100, false, 4)).toEqual({ min: 0, max: 100, interval: 25 })
  expect(valueAxis(0, 5, true, 4)).toEqual({ min: 0, max: 8, interval: 2 })
  expect(valueAxis(0, 7, true, 4)).toEqual({ min: 0, max: 12, interval: 3 })
  const option = laneOption({ ...lane, hi: 100 }, { fromMs: 0, toMs: 6000, originMs: 0 }, [], { timeLabels: false, stageLabels: true, plotHeight: 236, ySplits: 4 }, colors)
  expect(option.yAxis).toMatchObject({ min: 0, max: 100, interval: 25 })
})

test('ChartLane.baseline adds a dashed muted second series without changing primary series', () => {
  const withBaseline = laneOption(
    { ...lane, baseline: [[{ x: 0, y: 12 }, { x: 6000, y: 90 }]] },
    { fromMs: 0, toMs: 6000, originMs: 0 },
    [],
    { timeLabels: false, stageLabels: false },
    colors,
  )
  const series = withBaseline.series as Array<{ type: string; lineStyle: { color: string; type?: number[] }; data: Array<{ value: [number, number | null] }> }>
  expect(series).toHaveLength(2)
  expect(series[0].lineStyle.color).toBe(colors.brand)
  expect(series[1].lineStyle).toMatchObject({ color: colors.muted, type: [6, 4] })
  expect(series[1].data.map((point) => point.value)).toEqual([[0, 12], [6000, 90]])
})

test('compareThresholds extracts overall rps/p95 thresholds and ignores transaction or ratio rules', () => {
  const result = {
    evidence: [
      { id: 'c1', type: 'policy_check', rule_id: 'overall-rps', metric: 'throughput_rps', operator: 'gte', threshold: 18, status: 'FAIL', scope: { kind: 'overall' } },
      { id: 'c2', type: 'policy_check', rule_id: 'overall-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000, status: 'PASS' },
      { id: 'c3', type: 'policy_check', rule_id: 'checkout-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 500, status: 'FAIL', scope: { kind: 'transaction', label: 'POST /checkout' } },
      { id: 'c4', type: 'policy_check', rule_id: 'overall-errors', metric: 'error_rate_ratio', operator: 'lte', threshold: 0.02, status: 'FAIL', scope: { kind: 'overall' } },
    ],
  } as unknown as AnalysisResult
  expect(compareThresholds(result)).toEqual({
    rps: [{ id: 'overall-rps', value: 18, text: COMPARE_LABELS.chartsThresholdMark('18', 'RPS'), violated: true }],
    p95: [{ id: 'overall-p95', value: 2000, text: COMPARE_LABELS.chartsThresholdMark('2\u00a0000', 'мс'), violated: false }],
  })
})

test('compareLanes, compareFrame, compareStops and compareReadout align current and baseline', () => {
  const cur = loadSeries([bucket(0, 100, 1, 200), bucket(1000, 150, 2, 250)], 1)
  const base = loadSeries([bucket(0, 80, 0, 180), bucket(2000, 200, 4, 300)], 1)
  const result = {
    evidence: [
      { id: 'c1', type: 'policy_check', rule_id: 'p95-sla', metric: 'response_time_p95_ms', operator: 'lte', threshold: 500, status: 'PASS', scope: { kind: 'overall' } },
    ],
  } as unknown as AnalysisResult
  const lanes = compareLanes(cur, base, result)
  expect(lanes.rps.hi).toBe(200)
  expect(lanes.errors.integer).toBe(true)
  expect(lanes.p95.hi).toBe(500)
  expect(lanes.p95.thresholds).toHaveLength(1)
  expect(lanes.p95.baseline).toHaveLength(2)
  const frame = compareFrame(cur, base)
  expect(frame).toEqual({ fromMs: 0, toMs: 2000, originMs: 0 })
  const stops = compareStops(cur, base)
  expect(stops).toEqual([0, 1000, 2000])
  expect(nearestStopIndex(stops, frame, 0.5)).toBe(1)
  expect(compareReadout(cur, base, 1000)).toMatchObject({
    time: '0:01',
    current: { rps: '150', errors: '2', p95: '250' },
    baseline: { rps: '—', errors: '—', p95: '—' },
  })
  expect(compareReadout(cur, base, -5000).time).toBe('-0:05')
})

test('compareThresholds resolves transaction scope via metric_evidence_id and isolates rule thresholds', () => {
  const result = {
    evidence: [
      { id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' } },
      { id: 'm-tx', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /pay', sample_kind: 'JMETER_SAMPLER' } },
      { id: 'c-tx', type: 'policy_check', rule_id: 'tx-pay-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 250, status: 'FAIL', metric_evidence_id: 'm-tx' },
      { id: 'c-w1', type: 'policy_check', rule_id: 'p95-sla', metric: 'response_time_p95_ms', operator: 'lte', threshold: 100, status: 'PASS', window_id: 'w1', metric_evidence_id: 'm-overall' },
      { id: 'c-w2', type: 'policy_check', rule_id: 'p95-sla', metric: 'response_time_p95_ms', operator: 'lte', threshold: 200, status: 'FAIL', window_id: 'w2', metric_evidence_id: 'm-overall' },
    ],
  } as unknown as AnalysisResult
  expect(compareThresholds(result)).toEqual({
    rps: [],
    p95: [
      { id: 'p95-sla', value: 100, text: COMPARE_LABELS.chartsThresholdMark('100', 'мс'), violated: false },
      { id: 'p95-sla', value: 200, text: COMPARE_LABELS.chartsThresholdMark('200', 'мс'), violated: true },
    ],
  })
})

test('loadSeries and compareLanes guard NaN bucket metrics and zero rollup', () => {
  const zeroRollup = loadSeries([bucket(0, 10, 1, 50), bucket(1000, 20, 0, 60)], 0)
  expect(zeroRollup.points.map((p) => p.rps)).toEqual([0, 0])
  expect(Number.isFinite(zeroRollup.missingIntervals)).toBe(true)

  const nanSeries = loadSeries([bucket(0, Number.NaN, Number.NaN, Number.NaN)], 1)
  const validSeries = loadSeries([bucket(0, 10, 1, 50)], 1)
  const tainted = { ...validSeries, max: { rps: Number.NaN, errors: Number.NaN, p95: Number.NaN } }
  const lanes = compareLanes(nanSeries, tainted, null)
  expect([lanes.rps.hi, lanes.errors.hi, lanes.p95.hi]).toEqual([0, 0, 0])
})

test('regression: compareThresholds skips negative/non-finite thresholds and compareFrame handles large arrays', () => {
  const result = {
    evidence: [
      { id: 'c-neg', type: 'policy_check', rule_id: 'r-neg', metric: 'response_time_p95_ms', operator: 'lte', threshold: -5, status: 'FAIL', scope: { kind: 'overall' } },
      { id: 'c-nan', type: 'policy_check', rule_id: 'r-nan', metric: 'throughput_rps', operator: 'gte', threshold: Number.NaN, status: 'NO_VERDICT', scope: { kind: 'overall' } },
      { id: 'c-ok', type: 'policy_check', rule_id: 'r-ok', metric: 'response_time_p95_ms', operator: 'lte', threshold: 250, status: 'PASS', scope: { kind: 'overall' } },
    ],
  } as unknown as AnalysisResult
  expect(compareThresholds(result)).toEqual({
    rps: [],
    p95: [{ id: 'r-ok', value: 250, text: COMPARE_LABELS.chartsThresholdMark('250', 'мс'), violated: false }],
  })

  const largePoints = Array.from({ length: 200_000 }, (_, index) => ({
    startMs: index * 1000,
    rps: 10,
    errors: 0,
    p95: 50,
  }))
  const emptySeries = loadSeries([], 1)
  expect(compareFrame({ ...emptySeries, points: largePoints }, emptySeries)).toEqual({
    fromMs: 0,
    toMs: 199_999_000,
    originMs: 0,
  })
  expect(compareFrame({ ...emptySeries, points: [{ startMs: Number.NaN, rps: 0, errors: 0, p95: 0 }] }, emptySeries)).toEqual({
    fromMs: 0,
    toMs: 1000,
    originMs: 0,
  })
})
