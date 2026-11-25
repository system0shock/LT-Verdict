import type { EChartsCoreOption } from 'echarts/core'
import type { AnalysisResult, StageBindingEvidence } from '../types'
import { formatNumber, formatOffset } from './overview'

// Модель одной дорожки графика: чистые функции без ECharts и DOM (в ECharts отсюда только типы).
export type ChartToken =
  | 'brand'
  | 'warn'
  | 'fail'
  | 'text'
  | 'muted'
  | 'c0'
  | 'c1'
  | 'c2'
  | 'c3'
  | 'c4'
  | 'c5'
  | 'c6'
  | 'c7'
  | 'rest'

export interface ChartPoint { x: number; y: number }
export interface ChartThreshold { id: string; value: number; text: string; violated: boolean }
export interface ChartStage { id: string; fromMs: number; toMs: number; steady: boolean }
export interface ChartFrame { fromMs: number; toMs: number; originMs: number }
export interface ChartSeries {
  key: string
  label?: string
  color: ChartToken
  segments: ChartPoint[][]
  area?: boolean
  bars?: boolean
  dashed?: boolean
  stack?: string
  unit?: string
}
export interface ChartLane {
  key: string
  color: ChartToken
  segments: ChartPoint[][]
  series?: ChartSeries[]
  baseline?: ChartPoint[][]
  area?: boolean
  bars?: boolean
  breachFromMs?: number | null
  trend?: [ChartPoint, ChartPoint] | null
  lo: number
  hi: number
  tightMin?: boolean
  integer?: boolean
  thresholds?: ChartThreshold[]
}
export interface ChartColors {
  brand: string
  warn: string
  fail: string
  text: string
  muted: string
  border: string
  fontFamily: string
  surface?: string
  c0?: string
  c1?: string
  c2?: string
  c3?: string
  c4?: string
  c5?: string
  c6?: string
  c7?: string
  rest?: string
}
export interface LaneFlags { timeLabels: boolean; stageLabels: boolean; plotHeight?: number; ySplits?: number; sparkline?: boolean }

export const PLOT_INSET = { left: 56, right: 16 } as const
const PLOT_HEIGHT = 78
const STAGE_LABEL_MIN_SHARE = 0.1
const TIME_STEPS_MS = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10_800, 21_600, 43_200].map((seconds) => seconds * 1000)

export const SERIES_PALETTE: readonly ChartToken[] = ['c0', 'c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7']

export function resolveTokenColor(token: ChartToken, colors: ChartColors): string {
  const fallbacks: Record<ChartToken, string> = {
    brand: colors.brand,
    warn: colors.warn,
    fail: colors.fail,
    text: colors.text,
    muted: colors.muted,
    c0: colors.c0 || colors.brand,
    c1: colors.c1 || colors.warn,
    c2: colors.c2 || '#0E8F86',
    c3: colors.c3 || '#7B4FB3',
    c4: colors.c4 || '#2563EB',
    c5: colors.c5 || '#DB2777',
    c6: colors.c6 || '#059669',
    c7: colors.c7 || '#4F46E5',
    rest: colors.rest || colors.muted,
  }
  return fallbacks[token] || colors.brand
}

export function laneGrid(flags: LaneFlags) {
  if (flags.sparkline) {
    return { left: 2, right: 2, top: 4, bottom: 4, height: flags.plotHeight ?? 44 }
  }
  const top = flags.stageLabels ? 18 : 6
  const bottom = flags.timeLabels ? 50 : 6
  const plotHeight = flags.plotHeight ?? PLOT_HEIGHT
  return { left: PLOT_INSET.left, right: PLOT_INSET.right, top, bottom, height: plotHeight + top + bottom }
}

export function niceCeil(value: number): number {
  if (!(value > 0)) return 1
  const exponent = Math.floor(Math.log10(value))
  const scale = 10 ** exponent
  const mantissa = value / scale
  const step = [1, 2, 2.5, 5, 10].find((candidate) => mantissa <= candidate + 1e-9) ?? 10
  return Number((step * scale).toPrecision(12))
}

export function valueAxis(
  lo: number,
  hi: number,
  integer = false,
  splits = 2,
  tightMin = false,
): { min: number; max: number; interval: number } {
  const safeSplits = Math.max(1, Math.round(splits))
  if (tightMin && lo > 0 && hi > lo) {
    const span = hi - lo
    const pad = Math.max(span * 0.1, 1)
    const min = Math.max(0, Math.floor((lo - pad) / 5) * 5)
    const ceiling = Math.max(min + safeSplits, niceCeil(hi + pad * 0.5))
    const remainder = integer ? (ceiling - min) % safeSplits : 0
    const max = remainder !== 0 ? ceiling + (safeSplits - remainder) : ceiling
    return { min, max, interval: (max - min) / safeSplits }
  }
  const min = lo < 0 ? -niceCeil(-lo) : 0
  const ceiling = niceCeil(Math.max(hi, 0))
  // Counts: a multiple of splits keeps every tick a whole number.
  const remainder = integer ? (ceiling - min) % safeSplits : 0
  const max = remainder !== 0 ? ceiling + (safeSplits - remainder) : ceiling
  return { min, max, interval: (max - min) / safeSplits }
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

export function valueAtTime(segments: readonly ChartPoint[][], targetMs: number, toleranceMs = Infinity): number | null {
  let bestVal: number | null = null
  let bestDist = Infinity
  for (const segment of segments) {
    for (const point of segment) {
      const dist = Math.abs(point.x - targetMs)
      if (dist <= toleranceMs && dist < bestDist) {
        bestDist = dist
        bestVal = point.y
      }
    }
  }
  return bestVal
}

export interface CursorDot {
  key: string
  yPercent: number
  color: ChartToken
  dashed?: boolean
}

export function cursorDots(
  lane: ChartLane,
  flags: LaneFlags,
  cursorMs: number | null,
  toleranceMs = Infinity,
): CursorDot[] {
  if (cursorMs === null || flags.sparkline) return []
  const axis = valueAxis(lane.lo, lane.hi, lane.integer, flags.ySplits ?? 2, lane.tightMin)
  const span = Math.max(1e-9, axis.max - axis.min)
  const toY = (val: number) => Math.max(0, Math.min(100, (1 - (val - axis.min) / span) * 100))
  const dots: CursorDot[] = []

  if (lane.series?.length) {
    let stackSum = 0
    const isStacked = lane.series.some((s) => Boolean(s.stack))
    for (const s of lane.series) {
      const val = valueAtTime(s.segments, cursorMs, toleranceMs)
      if (val === null) continue
      const yVal = isStacked && s.stack ? (stackSum += val) : val
      dots.push({ key: s.key, yPercent: toY(yVal), color: s.color, dashed: s.dashed })
    }
  } else {
    const val = valueAtTime(lane.segments, cursorMs, toleranceMs)
    if (val !== null) dots.push({ key: lane.key, yPercent: toY(val), color: lane.color })
  }
  if (lane.baseline?.length) {
    const bVal = valueAtTime(lane.baseline, cursorMs, toleranceMs)
    if (bVal !== null) dots.push({ key: `${lane.key}:baseline`, yPercent: toY(bVal), color: 'muted', dashed: true })
  }
  return dots
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
  const ticks = flags.sparkline ? [] : timeTicks(frame)
  const axis = valueAxis(lane.lo, lane.hi, lane.integer, flags.ySplits ?? 2, lane.tightMin)
  const text = { color: colors.muted, fontSize: 12, fontFamily: colors.fontFamily }
  const areas: unknown[] = flags.sparkline ? [] : [...stageAreas(stages, frame, flags, colors)]
  if (!flags.sparkline && lane.breachFromMs !== undefined && lane.breachFromMs !== null && lane.breachFromMs < frame.toMs) {
    areas.push([
      { xAxis: Math.max(frame.fromMs, lane.breachFromMs), itemStyle: { color: colors.fail, opacity: 0.08 } },
      { xAxis: frame.toMs },
    ])
  }

  const markLineObj = lane.thresholds?.length && !flags.sparkline
    ? { markLine: { silent: true, symbol: 'none', data: lane.thresholds.map((threshold) => thresholdLine(threshold, axis.max, colors)) } }
    : {}
  const markAreaObj = areas.length ? { markArea: { silent: true, data: areas } } : {}

  const builtSeries: Record<string, unknown>[] = []
  if (lane.series?.length) {
    lane.series.forEach((item, index) => {
      const hex = resolveTokenColor(item.color, colors)
      if (item.bars) {
        builtSeries.push({
          type: 'bar',
          silent: true,
          barMaxWidth: 10,
          itemStyle: { color: hex, opacity: 0.72 },
          data: item.segments.flatMap((segment) => segment.map((point) => [point.x, point.y])),
          ...(index === 0 ? { ...markLineObj, ...markAreaObj } : {}),
        })
      } else {
        builtSeries.push({
          type: 'line',
          silent: true,
          symbol: 'none',
          showSymbol: true,
          showAllSymbol: true,
          symbolSize: flags.sparkline ? 4 : 6,
          connectNulls: false,
          ...(item.stack ? { stack: item.stack } : {}),
          lineStyle: { width: item.dashed ? 1.5 : (flags.sparkline ? 1.75 : 2), ...(item.dashed ? { type: [6, 4] } : {}), color: hex },
          itemStyle: { color: hex },
          ...(item.area || item.stack ? { areaStyle: { color: hex, opacity: item.color === 'rest' ? 0.18 : item.stack ? 0.28 : 0.12 } } : {}),
          data: lineData(item.segments),
          ...(index === 0 ? { ...markLineObj, ...markAreaObj } : {}),
        })
      }
    })
  } else {
    const hex = resolveTokenColor(lane.color, colors)
    if (lane.bars) {
      builtSeries.push({
        type: 'bar',
        silent: true,
        barMaxWidth: 10,
        itemStyle: { color: hex, opacity: 0.76 },
        data: lane.segments.flatMap((segment) => segment.map((point) => [point.x, point.y])),
        ...markLineObj,
        ...markAreaObj,
      })
    } else {
      builtSeries.push({
        type: 'line',
        silent: true,
        symbol: 'none',
        showSymbol: true,
        showAllSymbol: true,
        symbolSize: flags.sparkline ? 4 : 6,
        connectNulls: false,
        lineStyle: { width: flags.sparkline ? 1.75 : 2, color: hex },
        itemStyle: { color: hex },
        ...(lane.area !== false ? { areaStyle: { color: hex, opacity: 0.11 } } : {}),
        data: lineData(lane.segments),
        ...markLineObj,
        ...markAreaObj,
      })
    }
  }

  if (lane.baseline?.length) {
    builtSeries.push({
      type: 'line',
      silent: true,
      symbol: 'none',
      showSymbol: true,
      showAllSymbol: true,
      symbolSize: 5,
      connectNulls: false,
      lineStyle: { width: 1.5, type: [6, 4], color: colors.muted },
      itemStyle: { color: colors.muted },
      data: lineData(lane.baseline),
    })
  }

  if (lane.trend) {
    builtSeries.push({
      type: 'line',
      silent: true,
      symbol: 'none',
      lineStyle: { width: 1.5, type: [4, 4], color: colors.brand },
      itemStyle: { color: colors.brand },
      data: [[lane.trend[0].x, lane.trend[0].y], [lane.trend[1].x, lane.trend[1].y]],
    })
  }

  return {
    animation: false,
    grid: { left: grid.left, right: grid.right, top: grid.top, bottom: grid.bottom },
    xAxis: {
      type: 'value',
      min: frame.fromMs,
      max: frame.toMs,
      show: !flags.sparkline,
      axisLabel: { show: !flags.sparkline && flags.timeLabels, customValues: ticks, alignMaxLabel: 'right', formatter: (value: number) => signedOffset(value - frame.originMs), ...text },
      axisTick: { show: !flags.sparkline && flags.timeLabels, customValues: ticks },
      axisLine: { show: !flags.sparkline, lineStyle: { color: colors.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      min: axis.min,
      max: axis.max,
      interval: axis.interval,
      show: !flags.sparkline,
      axisLabel: { show: !flags.sparkline, formatter: (value: number) => formatNumber(value, Math.max(2, stepDigits(axis.interval))), ...text },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { show: !flags.sparkline, lineStyle: { color: colors.border } },
    },
    series: builtSeries,
  }
}
