import type { AnalysisSummary, ResourcePolicyCheckEvidence } from '../types'
import type { ArmAnalysis, ArmResult, MapCellState, ServiceArmCell, ServiceArmMap } from '../platformTypes'

const RANK: Record<MapCellState, number> = { FAIL: 0, NO_VERDICT: 1, PASS: 2, NOT_CHECKED: 3 }

function compare(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0
}

function platformChecks(result: ArmResult['result'], service: string): ResourcePolicyCheckEvidence[] {
  return (result?.evidence ?? []).filter((item): item is ResourcePolicyCheckEvidence =>
    item.type === 'resource_policy_check' && item.service === service)
}

function cellOf(service: string, arm: string, checks: ResourcePolicyCheckEvidence[]): ServiceArmCell {
  // Состояние строится только по SLA-проверкам: диагностические на вердикт не влияют и карту не красят.
  const sla = checks.filter((check) => check.effect === 'sla')
  const failed = sla.filter((check) => check.status === 'FAIL')
  const undecided = sla.filter((check) => check.status === 'NO_VERDICT')
  const state: MapCellState = failed.length ? 'FAIL' : undecided.length ? 'NO_VERDICT' : sla.length ? 'PASS' : 'NOT_CHECKED'
  const unique = (values: string[]) => [...new Set(values)]
  return {
    service,
    arm,
    state,
    failedRuleIds: unique(failed.map((check) => check.platform_rule_id ?? check.rule_id)),
    reasons: unique(undecided.flatMap((check) => check.reason ? [check.reason] : [])),
  }
}

// Строки - сервисы с платформенной проверкой (поле service) хотя бы на одном плече; порядок: нарушение, затем причина без вердикта.
export function buildServiceArmMap(results: ArmResult[]): ServiceArmMap {
  const arms = [...new Set(results.map((item) => item.arm))].sort(compare)
  const withResult = results.filter((item) => item.result !== null)
  const names = new Set<string>()
  for (const item of withResult) {
    for (const evidence of item.result!.evidence) {
      if (evidence.type === 'resource_policy_check' && evidence.service !== undefined) names.add(evidence.service)
    }
  }
  const cells = [...names].flatMap((service) =>
    withResult.map((item) => cellOf(service, item.arm, platformChecks(item.result, service))))
  const worst = (service: string) => Math.min(...cells.filter((cell) => cell.service === service).map((cell) => RANK[cell.state]))
  return {
    arms,
    missingArms: arms.filter((arm) => !withResult.some((item) => item.arm === arm)),
    services: [...names].sort((left, right) => worst(left) - worst(right) || compare(left, right)),
    cells,
  }
}

export function problemServices(map: ServiceArmMap): string[] {
  return map.services.filter((service) =>
    map.cells.some((cell) => cell.service === service && (cell.state === 'FAIL' || cell.state === 'NO_VERDICT')))
}

// Один анализ на плечо: выбранный, если он этого плеча, иначе первый в списке. Анализы без плеча в карту не входят.
export function armAnalyses(analyses: AnalysisSummary[], selectedAnalysisId: string): ArmAnalysis[] {
  const byArm = new Map<string, AnalysisSummary[]>()
  for (const analysis of analyses) {
    if (!analysis.resource_arm) continue
    byArm.set(analysis.resource_arm, [...(byArm.get(analysis.resource_arm) ?? []), analysis])
  }
  return [...byArm.entries()].sort(([left], [right]) => compare(left, right)).map(([arm, items]) => ({
    arm,
    analysisId: (items.find((item) => item.analysis_id === selectedAnalysisId) ?? items[0]).analysis_id,
    analysesOfArm: items.length,
  }))
}
