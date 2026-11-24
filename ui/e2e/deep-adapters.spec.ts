import { expect, test } from '@playwright/test'
import type { Bucket, ResourceSeriesEntry, ResourceSeriesValues } from '../src/types'
import { getBuckets, getResourceSeriesCatalog, getResourceSeriesValues, resourceSeriesValuesPath } from '../src/api'
import {
  allLoadTracks, batchSeriesIds, cellAt, chooseLoadRollup, chooseResourceStep, cursorReadouts,
  cursorStops, deepStages, defaultSelection, gridEndMs, groupMetricCatalog, loadBucketRange,
  loadTracks, mergePages, nearestTime, planResourcePeriod, resourceTrack, runStartMs, snapPeriod,
  stageButtons, thresholdsFor, trackPath, trackScale, valueY, windowMetricStats,
} from '../src/shell/deep'

const grid = { startMs: 1_767_225_600_000, stepMs: 17_000, pointCount: 1_700 }
const entry = (id: string) => ({ id, metric: 'cpu', unit: '%', entity: 'node', role: 'system', aggregation: 'interval_mean', reducer: 'mean', labels: {}, observed_cells: 2 }) as ResourceSeriesEntry
const valueGrid = (first: number, count: number, ratio = 4, last = ratio, step = 60_000) => ({
  start_epoch_ms: 0, source_step_ms: step / ratio, step_ms: step, first_cell_start_ms: first,
  cell_count: count, source_cells_per_cell: ratio, last_cell_source_cells: last,
}) as ResourceSeriesValues['grid']
const values = (id: string, data: Array<number | null>, observed?: number[]) => ({ id, aggregation: 'interval_mean', reducer: 'mean', values: data, ...(observed ? { observed } : {}) }) as ResourceSeriesValues['series'][number]

test('resource step uses the smallest source-step multiple for the span', () => {
  expect(chooseResourceStep(grid, grid.startMs, gridEndMs(grid))).toBe(34_000)
  expect(chooseResourceStep(grid, grid.startMs, grid.startMs + 100 * 17_000)).toBe(17_000)
  const longGrid = { ...grid, pointCount: 100_000 }
  expect(chooseResourceStep(longGrid, grid.startMs, gridEndMs(longGrid))).toBe(17_000 * 67)
})

test('period snaps outward and ends at the exact grid end', () => {
  expect(snapPeriod(grid, 34_000, grid.startMs + 40_000, grid.startMs + 70_000)).toEqual({ fromMs: grid.startMs + 34_000, toMs: grid.startMs + 102_000 })
  expect(snapPeriod(grid, 51_000, grid.startMs, gridEndMs(grid) + 5_000).toMs).toBe(gridEndMs(grid))
  expect(snapPeriod(grid, 34_000, grid.startMs + 5, grid.startMs + 6)).toEqual({ fromMs: grid.startMs, toMs: grid.startMs + 34_000 })
})

test('period planning accounts for cells added by outward snapping', () => {
  for (const [g, from, to] of [
    [grid, grid.startMs + 40_000, grid.startMs + 70_000],
    [grid, grid.startMs + 1, gridEndMs(grid)],
    [{ startMs: 0, stepMs: 1000, pointCount: 3001 }, 1, 1_500_001],
  ] as const) {
    const plan = planResourcePeriod(g, from, to)
    expect(plan.cells).toBeLessThanOrEqual(1500)
    expect(plan.stepMs % g.stepMs).toBe(0)
  }
  const g = { startMs: 0, stepMs: 1000, pointCount: 3001 }
  const plainStep = chooseResourceStep(g, 1, 1_500_001)
  const plainAxis = snapPeriod(g, plainStep, 1, 1_500_001)
  expect(Math.ceil((plainAxis.toMs - plainAxis.fromMs) / plainStep)).toBe(1501)
  expect(planResourcePeriod(g, 1, 1_500_001).cells).toBeLessThanOrEqual(1500)
})

test('load rollup picks the finest available budget fit', () => {
  expect(chooseLoadRollup(1_000_000)).toBe(1)
  expect(chooseLoadRollup(8 * 3600_000)).toBe(30)
  expect(chooseLoadRollup(24 * 3600_000)).toBe(60)
  expect(chooseLoadRollup(72 * 3600_000)).toBe(60)
})

test('load bucket range is relative, aligned and absent before run start', () => {
  expect(loadBucketRange(5000, { fromMs: 35_000, toMs: 85_000 }, 10)).toEqual({ fromMs: 30_000, toMs: 80_000 })
  expect(loadBucketRange(5000, { fromMs: -5000, toMs: 25_000 }, 10)).toEqual({ fromMs: 0, toMs: 20_000 })
  expect(loadBucketRange(5000, { fromMs: -5000, toMs: 5000 }, 10)).toBeNull()
})

test('series ids batch by actual URLSearchParams length and count', () => {
  const ids = [
    ...Array.from({ length: 70 }, (_, i) => `prom/a%b/${'x'.repeat(100)}-${i}`),
    ...Array.from({ length: 12 }, (_, i) => `${'~'.repeat(120)}-${i}`),
    '\u0440\u0435\u0441\u0443\u0440\u0441 \u0431',
  ]
  const base = '/api/x/resource-series/values?step_ms=15000'
  const batches = batchSeriesIds(ids, base.length)
  expect(batches.flat()).toEqual(ids)
  for (const batch of batches) {
    const query = new URLSearchParams({ step_ms: '15000' })
    for (const id of batch) query.append('series_id', id)
    expect(batch.length).toBeLessThanOrEqual(32)
    expect(`/api/x/resource-series/values?${query}`.length).toBeLessThanOrEqual(3500)
  }
  expect(batchSeriesIds(['a', 'b'], 1, 1)).toEqual([['a'], ['b']])
})

test('resource cells preserve gaps, observed counts and a short final cell', () => {
  const track = resourceTrack(entry('cpu'), values('cpu', [0.5, null], [4, 0]), valueGrid(0, 2), [])
  expect(cellAt(track, 59_999)?.value).toBe(0.5)
  expect(cellAt(track, 60_000)?.value).toBeNull()
  expect(cellAt(track, 120_000)).toBeNull()
  expect(track.segments).toEqual([[0]])
  const partial = resourceTrack(entry('cpu'), values('cpu', [1], [2]), valueGrid(720_000, 1, 3, 2, 45_000), [])
  expect(cellAt(partial, 749_999)?.value).toBe(1)
  expect(cellAt(partial, 760_000)).toBeNull()
  expect(partial.endMs).toBe(750_000)
  const direct = resourceTrack(entry('cpu'), values('cpu', [null, 2]), valueGrid(0, 2, 1, 1, 15_000), [])
  expect(direct.points.map((point) => point.observed)).toEqual([0, 1])
})

test('merged pages join adjacent cells but break across time gaps', () => {
  const first = resourceTrack(entry('cpu'), values('cpu', [1], [4]), valueGrid(0, 1), [])
  const adjacent = resourceTrack(entry('cpu'), values('cpu', [2], [4]), valueGrid(60_000, 1), [])
  const gap = resourceTrack(entry('cpu'), values('cpu', [3], [4]), valueGrid(180_000, 1), [])
  expect(mergePages(first, adjacent).segments).toEqual([[0, 1]])
  const merged = mergePages(mergePages(first, adjacent), gap)
  expect(merged.points.map((point) => point.value)).toEqual([1, 2, 3])
  expect(merged.segments).toEqual([[0, 1], [2]])
  expect(merged.endMs).toBe(240_000)
  expect(merged.max).toBe(3)
})

test('load tracks reuse bucket calculations and shift by the run origin', () => {
  const bucket = { bucket_start_ms: 0, sample_count: 60, error_count: 1, p95_latency_ms: 90, max_latency_ms: 100, hdr_v2_base64: '' } as Bucket
  const tracks = loadTracks([bucket], 60, 5000)
  expect(tracks.map((track) => track.key)).toEqual(['load-rps', 'load-p95'])
  expect(tracks[0].points[0]).toMatchObject({ startMs: 5000, value: 1, observed: null })
  expect(tracks[1].points[0].value).toBe(90)
  expect(loadTracks([], 60, 5000).map((track) => track.points)).toEqual([[], []])
})

test('cursor readouts identify partial values, nulls and missing cells', () => {
  const track = resourceTrack(entry('cpu'), values('cpu', [0.5, null], [2, 0]), valueGrid(0, 2), [])
  expect(cursorReadouts([track], 10_000)[0]).toMatchObject({ key: 'cpu', text: '0,5', raw: '0.5', gap: false, partial: true, observed: 2, total: 4 })
  expect(cursorReadouts([track], 60_000)[0]).toMatchObject({ text: '', raw: '', gap: true, partial: false })
  expect(cursorReadouts([track], 120_000)[0]).toMatchObject({ text: '', raw: '', gap: true })
})

test('cursor stops use the finest track and nearest time clamps', () => {
  const coarse = resourceTrack(entry('coarse'), values('coarse', [1], [1]), valueGrid(0, 1), [])
  const fine = resourceTrack(entry('fine'), values('fine', [2, 3]), valueGrid(0, 2, 1, 1, 15_000), [])
  expect(cursorStops([coarse, fine])).toEqual([0, 15_000])
  const early = resourceTrack(entry('early'), values('early', [1, 2, 3]), valueGrid(0, 3, 1, 1, 15_000), [])
  const late = loadTracks([{ bucket_start_ms: 0, sample_count: 15, error_count: 0, p95_latency_ms: 1, max_latency_ms: 1, hdr_v2_base64: '' }], 15, 30_000)[0]
  expect(cursorStops([late, early])).toEqual([0, 15_000, 30_000])
  expect(cursorStops([])).toEqual([])
  expect(nearestTime({ fromMs: 10, toMs: 20 }, -1)).toBe(10)
  expect(nearestTime({ fromMs: 10, toMs: 20 }, 1)).toBe(19)
  expect(nearestTime({ fromMs: 10, toMs: 10 }, 0.5)).toBe(10)
})

test('evidence produces finite unique thresholds and failure-first selection', () => {
  const evidence = [
    { type: 'resource_binding', run_from_epoch_ms: 1000 },
    { type: 'resource_policy_check', series_id: 'b', rule_id: 'r1', operator: 'gt', threshold: '0.5', status: 'PASS' },
    { type: 'resource_policy_check', series_id: 'b', rule_id: 'r1', operator: 'gt', threshold: '0.6', status: 'FAIL' },
    { type: 'resource_policy_check', series_id: 'a', rule_id: 'r2', operator: 'lt', threshold: 'abc', status: 'NO_VERDICT' },
    { type: 'resource_policy_check', series_id: 'a', rule_id: 'r4', operator: 'lt', threshold: '2', status: 'NO_VERDICT' },
    { type: 'resource_policy_check', series_id: 'c', rule_id: 'r3', operator: 'lt', threshold: '10', status: 'PASS' },
  ]
  const result = { evidence } as never
  expect(runStartMs(result)).toBe(1000)
  expect(runStartMs({ evidence: [] } as never)).toBeNull()
  expect(runStartMs({ evidence: [{ type: 'resource_binding', run_from_epoch_ms: '1000' }] } as never)).toBeNull()
  expect(thresholdsFor(result, 'b')).toEqual([{ ruleId: 'r1', operator: 'gt', value: 0.5, violated: true }])
  expect(thresholdsFor(result, 'a')).toEqual([{ ruleId: 'r4', operator: 'lt', value: 2, violated: false }])
  expect(thresholdsFor(result, 'c')).toEqual([{ ruleId: 'r3', operator: 'lt', value: 10, violated: false }])
  expect(defaultSelection('abcdefg'.split('').map(entry), result)).toEqual(['b', 'a', 'c', 'd', 'e', 'f'])
})

test('default selection takes failed series first, then system series before generator series', () => {
  const generators = new Set(['generator-queue', 'generator-threads'])
  const catalog = ['admission-queue', 'cpu-queue', 'db-busy', 'db-queue', 'downstream-wait', 'generator-queue', 'generator-threads', 'service-cpu-busy']
    .map((id) => ({ ...entry(id), role: generators.has(id) ? 'generator' : 'system' }) as ResourceSeriesEntry)
  const check = (seriesId: string, status: string) => ({ type: 'resource_policy_check', series_id: seriesId, rule_id: `${seriesId}-rule`, operator: 'gt', threshold: '1', status })
  const pass = ['service-cpu-busy', 'downstream-wait', 'generator-queue'].map((id) => check(id, 'PASS'))
  const saturation = { evidence: [...pass, check('db-busy', 'FAIL')] } as never
  expect(defaultSelection(catalog, saturation)).toEqual(['db-busy', 'admission-queue', 'cpu-queue', 'db-queue', 'downstream-wait', 'service-cpu-busy'])
  const slow = { evidence: [check('downstream-wait', 'FAIL'), ...pass] } as never
  expect(defaultSelection(catalog, slow)).toEqual(['downstream-wait', 'admission-queue', 'cpu-queue', 'db-busy', 'db-queue', 'service-cpu-busy'])
  expect(defaultSelection(catalog, { evidence: [] } as never)).toEqual(['admission-queue', 'cpu-queue', 'db-busy', 'db-queue', 'downstream-wait', 'service-cpu-busy'])
})

test('scale includes zero and thresholds while paths break at nulls', () => {
  const track = resourceTrack(entry('cpu'), values('cpu', [2, null, 4], [4, 0, 4]), valueGrid(0, 3), [{ ruleId: 'high', operator: 'gt', value: 8, violated: false }])
  expect(trackScale(track)).toEqual({ lo: 0, hi: 8 })
  expect(valueY({ lo: 0, hi: 8 }, 4, 80)).toBe(40)
  expect(trackPath(track, { fromMs: 0, toMs: 180_000 }, 180, 80)).toEqual(['30,58', '150,40'])
})

test('a point before a time gap is drawn at the middle of its own cell', () => {
  const bucket = (start: number) => ({ bucket_start_ms: start, sample_count: 60, error_count: 0, p95_latency_ms: 10, max_latency_ms: 20, hdr_v2_base64: '' })
  const [rps] = loadTracks([bucket(0), bucket(120_000)], 60, 0)
  expect(trackPath(rps, { fromMs: 0, toMs: 180_000 }, 180, 80)).toEqual(['30,4', '150,4'])
})

test('API path encodes ids once and requests carry abort signals', async () => {
  const path = resourceSeriesValuesPath('run /', 'analysis', ['a/b', 'x~y'], { step_ms: '15000', from_ms: '0' })
  expect(path).toBe('/api/runs/run%20%2F/analyses/analysis/resource-series/values?step_ms=15000&from_ms=0&series_id=a%2Fb&series_id=x%7Ey')
  const calls: Array<{ url: string; signal?: AbortSignal }> = []
  const original = globalThis.fetch
  globalThis.fetch = async (input, init) => {
    calls.push({ url: String(input), signal: init?.signal as AbortSignal | undefined })
    return { ok: true, text: async () => '{}' } as Response
  }
  try {
    const signal = new AbortController().signal
    await getResourceSeriesCatalog('r', 'a', 'cursor', signal)
    await getResourceSeriesValues(path, signal)
    await getBuckets('r', 'a', 10, 0, 1000, signal)
    expect(calls.map((call) => call.url)).toEqual([
      '/api/runs/r/analyses/a/resource-series?limit=256&after=cursor', path,
      '/api/runs/r/analyses/a/buckets?rollup=10&limit=500&from_ms=0&to_ms=1000',
    ])
    expect(calls.every((call) => call.signal === signal)).toBe(true)
  } finally {
    globalThis.fetch = original
  }
})

test('allLoadTracks exposes percentiles, max, rps, errors, error-rate and overall policy thresholds only', () => {
  const buckets: Bucket[] = [
    { bucket_start_ms: 0, sample_count: 100, error_count: 5, p50_latency_ms: 20, p95_latency_ms: 120, p99_latency_ms: 240, max_latency_ms: 300, hdr_v2_base64: '' },
    { bucket_start_ms: 10_000, sample_count: 0, error_count: 0, p95_latency_ms: 0, max_latency_ms: 0, hdr_v2_base64: '' },
  ]
  const result = {
    evidence: [
      { id: 'c1', type: 'policy_check', rule_id: 'p95-sla', metric: 'response_time_p95_ms', operator: 'lte', threshold: 100, status: 'FAIL', scope: { kind: 'overall' } },
      { id: 'c2', type: 'policy_check', rule_id: 'tx-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 50, status: 'FAIL', scope: { kind: 'transaction', label: 'pay', group_path: ['pay'], sample_kind: 'jmeter' } },
      { id: 'c3', type: 'policy_check', rule_id: 'rps-min', metric: 'throughput_rps', operator: 'gte', threshold: 20, status: 'FAIL', scope: { kind: 'overall' } },
      { id: 'c4', type: 'policy_check', rule_id: 'err-max', metric: 'error_rate_ratio', operator: 'lte', threshold: 0.02, status: 'FAIL', scope: { kind: 'overall' } },
    ],
  } as never
  const tracks = allLoadTracks(buckets, 10, 1000, result)
  expect(tracks.map((track) => track.key)).toEqual([
    'load-p50', 'load-p95', 'load-p99', 'load-max', 'load-rps', 'load-errors', 'load-error-rate',
  ])
  expect(tracks.find((t) => t.key === 'load-p50')?.points.map((p) => p.value)).toEqual([20, null])
  expect(tracks.find((t) => t.key === 'load-p99')?.points.map((p) => p.value)).toEqual([240, null])
  expect(tracks.find((t) => t.key === 'load-max')?.points.map((p) => p.value)).toEqual([300, 0])
  expect(tracks.find((t) => t.key === 'load-rps')?.points.map((p) => p.value)).toEqual([10, 0])
  expect(tracks.find((t) => t.key === 'load-errors')?.points.map((p) => p.value)).toEqual([5, 0])
  expect(tracks.find((t) => t.key === 'load-error-rate')?.points.map((p) => p.value)).toEqual([5, null])
  expect(tracks.find((t) => t.key === 'load-p95')?.thresholds).toEqual([{ ruleId: 'p95-sla', operator: 'gt', value: 100, violated: true }])
  expect(tracks.find((t) => t.key === 'load-rps')?.thresholds).toEqual([{ ruleId: 'rps-min', operator: 'lt', value: 20, violated: true }])
  expect(tracks.find((t) => t.key === 'load-error-rate')?.thresholds).toEqual([{ ruleId: 'err-max', operator: 'gt', value: 2, violated: true }])
})

test('stageButtons and deepStages read stage_binding first and fall back to catalog windows or window_policy_summary', () => {
  const withBinding = {
    evidence: [{
      id: 'sb',
      type: 'stage_binding',
      stages: [
        { id: 'ramp', role: 'excluded', from_offset_ms: 0, to_offset_ms: 60_000, from_epoch_ms: 1000, to_epoch_ms: 61_000 },
        { id: 'steady', role: 'steady', from_offset_ms: 60_000, to_offset_ms: 180_000, from_epoch_ms: 61_000, to_epoch_ms: 181_000 },
      ],
    }],
  } as never
  const catalogWindows = [{ id: 'plateau-1', from_epoch_ms: 61_000, to_epoch_ms: 181_000 }]
  expect(stageButtons(withBinding, catalogWindows, 1000)).toEqual([
    { id: 'ramp', role: 'excluded', fromMs: 1000, toMs: 61_000, fromMin: 0, toMin: 1, steady: false },
    { id: 'steady', role: 'steady', fromMs: 61_000, toMs: 181_000, fromMin: 1, toMin: 3, steady: true },
  ])
  expect(stageButtons({ evidence: [] } as never, catalogWindows, 1000)).toEqual([
    { id: 'plateau-1', role: 'steady', fromMs: 61_000, toMs: 181_000, fromMin: 1, toMin: 3, steady: true },
  ])
  const withPolicyWindow = {
    evidence: [{ id: 'wps', type: 'window_policy_summary', window_id: 'soak', from_epoch_ms: 121_000, to_epoch_ms: 241_000, business_verdict: 'PASS', resource_verdict: 'PASS', verdict: 'PASS' }],
  } as never
  expect(stageButtons(withPolicyWindow, [], 1000)).toEqual([
    { id: 'soak', role: 'steady', fromMs: 121_000, toMs: 241_000, fromMin: 2, toMin: 4, steady: true },
  ])
  expect(deepStages({ evidence: [] } as never, catalogWindows, 1000)).toEqual([
    { id: 'plateau-1', fromMs: 61_000, toMs: 181_000, steady: true },
  ])
})

test('windowMetricStats computes min, avg, p50, p95, max and threshold duration excluding nulls and clipping boundaries', () => {
  const track = resourceTrack(
    entry('cpu'),
    values('cpu', [10, null, 20, 30, 40], [4, 0, 4, 4, 2]),
    valueGrid(0, 5, 4, 2, 60_000),
    [{ ruleId: 'cpu-hi', operator: 'gt', value: 25, violated: true }],
  )
  // Cells: [0,60k)=10, [60k,120k)=null, [120k,180k)=20, [180k,240k)=30 (>25), [240k,270k)=40 (>25, short tail 30s)
  const full = windowMetricStats(track, { fromMs: 0, toMs: 270_000 })
  expect(full).toEqual({
    count: 4,
    min: 10,
    avg: 25,
    p50: 20,
    p95: 40,
    max: 40,
    timeAboveThresholdMs: 90_000,
    hasThresholds: true,
  })
  // Window clipped inside [150_000, 255_000): cells [120k,180k)=20, [180k,240k)=30 (>25, 60s), [240k,270k)=40 (>25, clipped 15s)
  const clipped = windowMetricStats(track, { fromMs: 150_000, toMs: 255_000 })
  expect(clipped.timeAboveThresholdMs).toBe(75_000)

  const ltTrack = resourceTrack(entry('free'), values('free', [1, 5, 2], [4, 4, 4]), valueGrid(0, 3), [{ ruleId: 'low', operator: 'lt', value: 3, violated: true }])
  expect(windowMetricStats(ltTrack, { fromMs: 0, toMs: 180_000 }).timeAboveThresholdMs).toBe(120_000)

  const noThreshold = resourceTrack(entry('mem'), values('mem', [null, null], [0, 0]), valueGrid(0, 2), [])
  expect(windowMetricStats(noThreshold, { fromMs: 0, toMs: 120_000 })).toEqual({
    count: 0, min: null, avg: null, p50: null, p95: null, max: null, timeAboveThresholdMs: null, hasThresholds: false,
  })
})

test('groupMetricCatalog categorizes load metrics and filters platform resources by service and role', () => {
  const catalog: ResourceSeriesEntry[] = [
    { ...entry('api.cpu'), entity: 'checkout-api', metric: 'cpu', role: 'system' },
    { ...entry('db.conn'), entity: 'checkout-db', metric: 'connections', role: 'system' },
    { ...entry('gen.queue'), entity: 'loadgen', metric: 'queue', role: 'generator' },
  ]
  const grouped = groupMetricCatalog(catalog, '', 'checkout-api')
  expect(grouped.services).toEqual(['checkout-api', 'checkout-db', 'loadgen'])
  expect(grouped.groups.map((g) => g.key)).toEqual(['delays', 'load', 'errors', 'platform'])
  expect(grouped.platformByRole.system.map((e) => e.id)).toEqual(['api.cpu'])
  expect(grouped.platformByRole.generator).toEqual([])
})
