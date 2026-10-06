import type { AnalysisResult } from './types'

// Карта «сервис x плечо» (платформа, срез P4b): состояние сервиса на плече по полям результата анализа плеча.
export type MapCellState = 'FAIL' | 'NO_VERDICT' | 'PASS' | 'NOT_CHECKED'

export interface ServiceArmCell {
  service: string
  arm: string
  state: MapCellState
  failedRuleIds: string[]
  reasons: string[]
}

// result: null - у плеча нет результата анализа (столбец «нет анализа», а не «в норме»).
export interface ArmResult {
  arm: string
  result: AnalysisResult | null
}

export interface ServiceArmMap {
  arms: string[]
  missingArms: string[]
  services: string[]
  cells: ServiceArmCell[]
}

export interface ArmAnalysis {
  arm: string
  analysisId: string
  analysesOfArm: number
}
