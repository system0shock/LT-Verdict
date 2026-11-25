import type { EChartsCoreOption } from 'echarts/core'
import type {
  AnalysisResult,
  CapacityKneeDiagnosticEvidence,
  ResourcePolicyCheckEvidence,
  ResourceSummaryEvidence,
  WindowMetricSummaryEvidence,
  WindowPolicySummaryEvidence,
} from '../types'
import { CAPACITY_CHART_LABELS, CAPACITY_LABELS } from './labels.tables'
import { formatNumber } from './overview'
import { capacityNumber } from './tables'
import { PLOT_INSET, valueAxis } from './timeChart'

export type CapacityGuardStatus = 'PASS' | 'FAIL' | 'MISSING' | 'NONE'

export interface CapacityGuardCheck {
  ruleId: string
  seriesId: string
  status: 'PASS' | 'FAIL' | 'NO_VERDICT'
  operator: 'gt' | 'lt'
  threshold: string
  unit: string
  observedPeak: string | null
}

export interface CapacityKneeStagePoint {
  stageId: string
  target: number
  achieved: number | null
  load: number
  p95Ms: number | null
  p99Ms: number | null
  verdict: string
  guardStatus: CapacityGuardStatus
  guardChecks: CapacityGuardCheck[]
  smallSample: boolean
}

export interface CapacityKneeDiagnosticRange {
  status: string
  calibrated: boolean
  confidence: string
  lastStableLoad: number | null
  firstDegradedLoad: number | null
  lastStableStageId: string | null
  firstDegradedStageId: string | null
}

export interface CapacityKneeChartModel {
  axis: string
  unit: string
  points: CapacityKneeStagePoint[]
  plottableCount: number
  hasP99: boolean
  hasGuards: boolean
  guardAlert: boolean
  lowerBound: number | null
  upperBound: number | null
  calibratedKnee: number | null
  diagnosticKnee: CapacityKneeDiagnosticRange | null
}

export interface CapacityChartColors {
  brand: string
  pass: string
  fail: string
  warn: string
  text: string
  muted: string
  border: string
  fontFamily: string
}

function finiteNumber(value: number | string | null | undefined): number | null {
  if (value == null) return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

export function capacityKneeChartModel(result: AnalysisResult): CapacityKneeChartModel | null {
  const summary = result.capacity_summary
  if (!summary) return null

  const windowSummariesById = new Map(
    result.evidence
      .filter((item): item is WindowPolicySummaryEvidence => item.type === 'window_policy_summary')
      .map((item) => [item.id, item]),
  )
  const windowMetricsByWindow = new Map(
    result.evidence
      .filter((item): item is WindowMetricSummaryEvidence => item.type === 'window_metric_summary')
      .map((item) => [item.window_id, item]),
  )
  const checksById = new Map(
    result.evidence
      .filter((item): item is ResourcePolicyCheckEvidence => item.type === 'resource_policy_check')
      .map((item) => [item.id, item]),
  )
  const resourceSummaries = result.evidence.filter(
    (item): item is ResourceSummaryEvidence => item.type === 'resource_summary',
  )
  const generatorSeriesIds = new Set(
    resourceSummaries.filter((item) => item.role === 'generator').map((item) => item.series_id),
  )
  const summaryByWindowSeries = new Map(
    resourceSummaries.map((item) => [`${item.window_id}\u0000${item.series_id}`, item]),
  )

  const knee = result.evidence.find(
    (item): item is CapacityKneeDiagnosticEvidence => item.type === 'capacity_knee_diagnostic',
  )
  const kneePointsByStage = new Map(
    (knee?.points ?? []).map((point) => [point.stage_id, point]),
  )

  const points: CapacityKneeStagePoint[] = (summary.stages ?? []).map((stage) => {
    const refs = stage.evidence_refs ?? []
    const policySummary = refs.map((id) => windowSummariesById.get(id)).find((item) => item != null)
    const windowId = policySummary?.window_id ?? stage.id
    const metricSummary = windowMetricsByWindow.get(windowId)
    const kneePoint = kneePointsByStage.get(stage.id)

    const achieved = finiteNumber(stage.achieved)
    const target = finiteNumber(stage.target) ?? 0
    const load = achieved ?? finiteNumber(kneePoint?.load) ?? target
    const p95Ms = finiteNumber(metricSummary?.latency_ms?.p95) ?? finiteNumber(kneePoint?.value)
    const p99Ms = finiteNumber(metricSummary?.latency_ms?.p99)

    const guardChecks: CapacityGuardCheck[] = refs
      .map((id) => checksById.get(id))
      .filter((check): check is ResourcePolicyCheckEvidence =>
        check != null
        && check.effect === 'diagnostic'
        && generatorSeriesIds.has(check.series_id),
      )
      .map((check) => {
        const rs = summaryByWindowSeries.get(`${check.window_id}\u0000${check.series_id}`)
        const peak = rs?.statistics?.max ?? rs?.statistics?.q95 ?? null
        return {
          ruleId: check.rule_id,
          seriesId: check.series_id,
          status: check.status,
          operator: check.operator,
          threshold: check.threshold,
          unit: check.unit,
          observedPeak: peak,
        }
      })

    const reasons = stage.reasons ?? []
    let guardStatus: CapacityGuardStatus = 'NONE'
    if (reasons.includes('CAPACITY_GUARD_FAILED') || guardChecks.some((c) => c.status === 'FAIL')) {
      guardStatus = 'FAIL'
    } else if (reasons.includes('CAPACITY_GUARD_MISSING') || guardChecks.some((c) => c.status === 'NO_VERDICT')) {
      guardStatus = 'MISSING'
    } else if (guardChecks.length > 0) {
      guardStatus = 'PASS'
    }

    return {
      stageId: stage.id,
      target,
      achieved,
      load,
      p95Ms,
      p99Ms,
      verdict: stage.verdict,
      guardStatus,
      guardChecks,
      smallSample: reasons.includes('CAPACITY_INSUFFICIENT_SAMPLES'),
    }
  })

  const plottableCount = points.filter((point) => point.p95Ms !== null).length
  const hasP99 = points.some((point) => point.p99Ms !== null)
  const hasGuards = points.some((point) => point.guardStatus !== 'NONE')
  const guardAlert = points.some((point) => point.guardStatus === 'FAIL' || point.guardStatus === 'MISSING')

  return {
    axis: summary.load_axis,
    unit: summary.unit,
    points,
    plottableCount,
    hasP99,
    hasGuards,
    guardAlert,
    lowerBound: finiteNumber(summary.lower_inclusive),
    upperBound: finiteNumber(summary.upper_exclusive),
    calibratedKnee: finiteNumber(summary.capacity_knee),
    diagnosticKnee: knee
      ? {
          status: knee.status,
          calibrated: Boolean(knee.calibrated),
          confidence: knee.confidence,
          lastStableLoad: finiteNumber(knee.last_stable_load),
          firstDegradedLoad: finiteNumber(knee.first_degraded_load),
          lastStableStageId: knee.last_stable_stage_id,
          firstDegradedStageId: knee.first_degraded_stage_id,
        }
      : null,
  }
}

function pointVisual(point: CapacityKneeStagePoint, colors: CapacityChartColors) {
  if (point.guardStatus === 'FAIL') {
    return { symbol: 'diamond', symbolSize: 13, itemStyle: { color: colors.fail } }
  }
  if (point.guardStatus === 'MISSING') {
    return { symbol: 'diamond', symbolSize: 13, itemStyle: { color: colors.warn } }
  }
  if (point.verdict === 'PASS') {
    return { symbol: 'circle', symbolSize: 10, itemStyle: { color: colors.pass } }
  }
  if (point.verdict === 'FAIL') {
    return { symbol: 'rect', symbolSize: 11, itemStyle: { color: colors.fail } }
  }
  return { symbol: 'triangle', symbolSize: 11, itemStyle: { color: colors.warn } }
}

export function capacityKneeOption(model: CapacityKneeChartModel, colors: CapacityChartColors): EChartsCoreOption {
  const plottable = model.points.filter((point): point is CapacityKneeStagePoint & { p95Ms: number } => point.p95Ms !== null)
  const maxLoad = Math.max(
    1,
    model.lowerBound ?? 0,
    model.upperBound ?? 0,
    model.calibratedKnee ?? 0,
    ...plottable.map((point) => point.load),
  )
  const maxLatency = Math.max(
    1,
    ...plottable.flatMap((point) => [point.p95Ms, point.p99Ms ?? 0]),
  )
  const xAxis = valueAxis(0, maxLoad, false, 4)
  const yAxis = valueAxis(0, maxLatency, false, 4)
  const textStyle = { color: colors.muted, fontSize: 12, fontFamily: colors.fontFamily }

  const markLines: Array<Record<string, unknown>> = []
  if (model.lowerBound !== null) {
    markLines.push({
      name: 'lower_bound',
      xAxis: model.lowerBound,
      lineStyle: { type: [6, 3], width: 1.5, color: colors.pass },
      label: {
        formatter: CAPACITY_CHART_LABELS.lowerLine(capacityNumber(model.lowerBound), model.unit),
        position: 'insideEndTop',
        color: colors.pass,
        fontSize: 11,
        fontFamily: colors.fontFamily,
      },
    })
  }
  if (model.upperBound !== null) {
    markLines.push({
      name: 'upper_bound',
      xAxis: model.upperBound,
      lineStyle: { type: [2, 3], width: 1.5, color: colors.fail },
      label: {
        formatter: CAPACITY_CHART_LABELS.upperLine(capacityNumber(model.upperBound), model.unit),
        position: 'insideStartTop',
        color: colors.fail,
        fontSize: 11,
        fontFamily: colors.fontFamily,
      },
    })
  }
  if (model.calibratedKnee !== null) {
    markLines.push({
      name: 'calibrated_knee',
      xAxis: model.calibratedKnee,
      lineStyle: { type: 'solid', width: 2, color: colors.warn },
      label: {
        formatter: CAPACITY_CHART_LABELS.kneeLine(capacityNumber(model.calibratedKnee), model.unit),
        position: 'insideMiddleTop',
        color: colors.warn,
        fontSize: 11,
        fontFamily: colors.fontFamily,
      },
    })
  }

  const markAreas: Array<[Record<string, unknown>, Record<string, unknown>]> = []
  if (
    model.diagnosticKnee?.status === 'DETECTED'
    && model.diagnosticKnee.lastStableLoad !== null
    && model.diagnosticKnee.firstDegradedLoad !== null
  ) {
    const lo = model.diagnosticKnee.lastStableLoad
    const hi = model.diagnosticKnee.firstDegradedLoad
    const spanHi = hi > lo ? hi : Math.min(xAxis.max, lo + Math.max(xAxis.interval * 0.15, 0.5))
    markAreas.push([
      {
        name: CAPACITY_CHART_LABELS.kneeInterval(capacityNumber(lo), capacityNumber(hi), model.unit),
        xAxis: lo,
        itemStyle: { color: colors.warn, opacity: 0.14 },
        label: { show: false },
      },
      { xAxis: spanHi },
    ])
  }

  const p95Series: Record<string, unknown> = {
    name: CAPACITY_CHART_LABELS.p95Series,
    type: 'line',
    silent: true,
    showSymbol: true,
    showAllSymbol: true,
    connectNulls: false,
    lineStyle: { width: 2, color: colors.brand },
    itemStyle: { color: colors.brand },
    data: plottable.map((point) => ({
      value: [point.load, point.p95Ms],
      ...pointVisual(point, colors),
    })),
  }
  if (markLines.length) {
    p95Series.markLine = { silent: true, symbol: 'none', data: markLines }
  }
  if (markAreas.length) {
    p95Series.markArea = { silent: true, data: markAreas }
  }

  const series: Array<Record<string, unknown>> = [p95Series]
  if (model.hasP99) {
    series.push({
      name: CAPACITY_CHART_LABELS.p99Series,
      type: 'line',
      silent: true,
      showSymbol: false,
      connectNulls: false,
      lineStyle: { width: 1.5, type: [6, 4], color: colors.muted },
      itemStyle: { color: colors.muted },
      data: plottable.map((point) => ({ value: [point.load, point.p99Ms] })),
    })
  }

  return {
    animation: false,
    grid: { left: PLOT_INSET.left, right: PLOT_INSET.right, top: 24, bottom: 42 },
    xAxis: {
      type: 'value',
      min: xAxis.min,
      max: xAxis.max,
      interval: xAxis.interval,
      name: CAPACITY_CHART_LABELS.xAxisTitle(model.unit),
      nameLocation: 'middle',
      nameGap: 26,
      nameTextStyle: textStyle,
      axisLabel: { hideOverlap: true, formatter: (value: number) => formatNumber(value), ...textStyle },
      axisLine: { lineStyle: { color: colors.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      min: yAxis.min,
      max: yAxis.max,
      interval: yAxis.interval,
      axisLabel: { formatter: (value: number) => formatNumber(value), ...textStyle },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: colors.border } },
    },
    series,
  }
}

export function capacityKneeReadout(point: CapacityKneeStagePoint, unit: string): string {
  const verdictWord = (CAPACITY_LABELS.stageVerdict as Record<string, string>)[point.verdict] ?? point.verdict
  const guardWord = CAPACITY_CHART_LABELS.guardStatus[point.guardStatus] ?? point.guardStatus
  return CAPACITY_CHART_LABELS.readout(
    point.stageId,
    capacityNumber(point.load),
    unit,
    capacityNumber(point.p95Ms),
    point.p99Ms === null ? null : capacityNumber(point.p99Ms),
    verdictWord,
    guardWord,
  )
}
