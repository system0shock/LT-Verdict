import { expect, test } from '@playwright/test'
import type { Bucket, ResourceSeriesEntry, ResourceSeriesValues } from '../src/types'
import { getBuckets, getResourceSeriesCatalog, getResourceSeriesValues, resourceSeriesValuesPath } from '../src/api'
import {
  batchSeriesIds, cellAt, chooseLoadRollup, chooseResourceStep, cursorReadouts, cursorStops,
  defaultSelection, gridEndMs, loadBucketRange, loadTracks, mergePages, nearestTime,
  planResourcePeriod, resourceTrack, runStartMs, snapPeriod, thresholdsFor, trackPath,
  trackScale, valueY,
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
