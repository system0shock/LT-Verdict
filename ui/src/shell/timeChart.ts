import type { EChartsCoreOption } from 'echarts/core'
import type { AnalysisResult, StageBindingEvidence } from '../types'
import { formatNumber, formatOffset } from './overview'

// Модель одной дорожки графика: чистые функции без ECharts и DOM (в ECharts отсюда только типы).
export type ChartToken = 'brand' | 'warn' | 'fail' | 'text'
export interface ChartPoint { x: number; y: number }
export interface ChartThreshold { id: string; value: number; text: string; violated: boolean }
export interface ChartStage { id: string; fromMs: number; toMs: number; steady: boolean }
export interface ChartFrame { fromMs: number; toMs: number; originMs: number }
export interface ChartLane {
  key: string
  color: ChartToken
  segments: ChartPoint[][]
  lo: number
  hi: number
  integer?: boolean
  thresholds?: ChartThreshold[]
}
export interface ChartColors { brand: string; warn: string; fail: string; text: string; muted: string; border: string; fontFamily: string }
export interface LaneFlags { timeLabels: boolean; stageLabels: boolean }

export const PLOT_INSET = { left: 56, right: 16 } as const
const PLOT_HEIGHT = 78
const STAGE_LABEL_MIN_SHARE = 0.1
const TIME_STEPS_MS = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10_800, 21_600, 43_200].map((seconds) => seconds * 1000)

export function laneGrid(flags: LaneFlags) {
  const top = flags.stageLabels ? 18 : 6
  const bottom = flags.timeLabels ? 50 : 6
  return { left: PLOT_INSET.left, right: PLOT_INSET.right, top, bottom, height: PLOT_HEIGHT + top + bottom }
}

export function niceCeil(value: number): number {
  if (!(value > 0)) return 1
  const exponent = Math.floor(Math.log10(value))
  const scale = 10 ** exponent
  const mantissa = value / scale
  const step = [1, 2, 2.5, 5, 10].find((candidate) => mantissa <= candidate + 1e-9) ?? 10
  return Number((step * scale).toPrecision(12))
}

export function valueAxis(lo: number, hi: number, integer = false): { min: number; max: number; interval: number } {
  const min = lo < 0 ? -niceCeil(-lo) : 0
  const ceiling = niceCeil(Math.max(hi, 0))
  // Counts: an even maximum keeps the middle tick a whole number.
  const max = integer && ceiling % 2 !== 0 ? ceiling + 1 : ceiling
  return { min, max, interval: (max - min) / 2 }
}

// Fraction digits that show the tick step exactly (0,025 must not read as 0,03).
function stepDigits(interval: number): number {
  for (let digits = 0; digits < 6; digits += 1) if (Math.abs(interval * 10 ** digits - Math.round(interval * 10 ** digits)) < 1e-9) return digits
  return 6
}

export function timeTicks(frame: ChartFrame, maxIntervals = 6): number[] {
  const span = frame.toMs - frame.fromMs
  if (span <= 0) return [frame.fromMs]
  const step = TIME_STEPS_MS.find((candidate) => span / candidate <= maxIntervals) ?? TIME_STEPS_MS[TIME_STEPS_MS.length - 1]
  const ticks: number[] = []
  for (let k = Math.ceil((frame.fromMs - frame.originMs) / step); frame.originMs + k * step <= frame.toMs; k += 1) ticks.push(frame.originMs + k * step)
  // The last label shows the end of the frame; a step tick closer than three quarters of a step gives way to it.
  const last = ticks[ticks.length - 1]
  if (last !== undefined && last < frame.toMs) {
    if (frame.toMs - last < step * 0.75) ticks.pop()
    ticks.push(frame.toMs)
  }
  return ticks
}

export function signedOffset(ms: number): string {
  return ms < 0 ? `-${formatOffset(-ms)}` : formatOffset(ms)
}

export function lineData(segments: ChartPoint[][]): Array<{ value: [number, number | null]; symbol?: 'circle' }> {
  const data: Array<{ value: [number, number | null]; symbol?: 'circle' }> = []
  segments.forEach((segment, index) => {
    const previous = segments[index - 1]
    if (previous) data.push({ value: [(previous[previous.length - 1].x + segment[0].x) / 2, null] })
    segment.forEach((point) => data.push(segment.length === 1 ? { value: [point.x, point.y], symbol: 'circle' } : { value: [point.x, point.y] }))
  })
  return data
}

export function plotFraction(clientX: number, rect: { left: number; width: number }): number {
  const width = rect.width - PLOT_INSET.left - PLOT_INSET.right
  if (width <= 0) return 0
  return Math.max(0, Math.min(1, (clientX - rect.left - PLOT_INSET.left) / width))
}

export function cursorX(frame: ChartFrame, cursorMs: number): number {
  const span = frame.toMs - frame.fromMs
  if (span <= 0) return 500
  return Math.round(((cursorMs - frame.fromMs) / span) * 1000 * 100) / 100
}

export function stageBands(result: AnalysisResult, originMs: number): ChartStage[] {
  const binding = result.evidence.find((item): item is StageBindingEvidence => item.type === 'stage_binding')
  return (binding?.stages ?? []).map((stage) => ({
    id: stage.id,
    fromMs: originMs + stage.from_offset_ms,
    toMs: originMs + stage.to_offset_ms,
    steady: stage.role === 'steady',
  }))
}

function stageAreas(stages: ChartStage[], frame: ChartFrame, flags: LaneFlags, colors: ChartColors) {
  const span = frame.toMs - frame.fromMs
  return stages.flatMap((stage) => {
    const from = Math.max(stage.fromMs, frame.fromMs)
    const to = Math.min(stage.toMs, frame.toMs)
    if (!(to > from)) return []
    return [[
      {
        name: stage.id,
        xAxis: from,
        itemStyle: { color: stage.steady ? 'transparent' : colors.border, opacity: stage.steady ? 1 : 0.25 },
        label: { show: flags.stageLabels && span > 0 && (to - from) / span >= STAGE_LABEL_MIN_SHARE, position: 'insideTop', color: colors.muted, fontSize: 12, fontFamily: colors.fontFamily },
      },
      { xAxis: to },
    ]]
  })
}

// На верхней границе оси подпись над линией выходит за поле графика и обрезается, поэтому она ставится под линию.
function thresholdLine(threshold: ChartThreshold, axisMax: number, colors: ChartColors) {
  const color = threshold.violated ? colors.fail : colors.muted
  return {
    name: threshold.id,
    yAxis: threshold.value,
    lineStyle: { type: [5, 4], width: 1.5, color },
    label: { formatter: threshold.text, position: threshold.value >= axisMax ? 'insideStartBottom' : 'insideStartTop', color, fontSize: 12, fontFamily: colors.fontFamily },
  }
}

export function laneOption(lane: ChartLane, frame: ChartFrame, stages: ChartStage[], flags: LaneFlags, colors: ChartColors): EChartsCoreOption {
  const grid = laneGrid(flags)
  const ticks = timeTicks(frame)
  const axis = valueAxis(lane.lo, lane.hi, lane.integer)
  const text = { color: colors.muted, fontSize: 12, fontFamily: colors.fontFamily }
  const areas = stageAreas(stages, frame, flags, colors)
  return {
    animation: false,
    grid: { left: grid.left, right: grid.right, top: grid.top, bottom: grid.bottom },
    xAxis: {
      type: 'value',
      min: frame.fromMs,
      max: frame.toMs,
      axisLabel: { show: flags.timeLabels, customValues: ticks, alignMaxLabel: 'right', formatter: (value: number) => signedOffset(value - frame.originMs), ...text },
      axisTick: { show: flags.timeLabels, customValues: ticks },
      axisLine: { lineStyle: { color: colors.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      min: axis.min,
      max: axis.max,
      interval: axis.interval,
      axisLabel: { formatter: (value: number) => formatNumber(value, Math.max(2, stepDigits(axis.interval))), ...text },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: colors.border } },
    },
    series: [{
      type: 'line',
      silent: true,
      symbol: 'none',
      showSymbol: true,
      showAllSymbol: true,
      symbolSize: 6,
      connectNulls: false,
      lineStyle: { width: 2, color: colors[lane.color] },
      itemStyle: { color: colors[lane.color] },
      data: lineData(lane.segments),
      ...(lane.thresholds?.length ? { markLine: { silent: true, symbol: 'none', data: lane.thresholds.map((threshold) => thresholdLine(threshold, axis.max, colors)) } } : {}),
      ...(areas.length ? { markArea: { silent: true, data: areas } } : {}),
    }],
  }
}
