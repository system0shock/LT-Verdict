import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { Bucket } from '../src/types'
import { loadFrame, loadLane, loadSeries } from '../src/shell/overview'
import {
  PLOT_INSET,
  cursorX,
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
test('timeTicks follow the step ladder and the run origin', () => {
  expect(timeTicks({ fromMs: 0, toMs: 6000, originMs: 0 })).toEqual([0, 1000, 2000, 3000, 4000, 5000, 6000])
  expect(timeTicks({ fromMs: 0, toMs: 14_390_000, originMs: 0 })).toEqual([0, 3_600_000, 7_200_000, 10_800_000])
  const origin = 1_767_225_600_000
  expect(timeTicks({ fromMs: origin - 61_000, toMs: origin + 119_000, originMs: origin }).map((t) => signedOffset(t - origin)))
    .toEqual(['-1:00', '-0:30', '0:00', '0:30', '1:00', '1:30'])
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
  expect(option.grid).toMatchObject({ left: 56, right: 16, bottom: 30 })
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
