// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/LoadStages.kt.
// Do not edit. Regenerate with
// LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorStageItemsTest
// Describes the stage_binding evidence item of a run with declared load stages (ADR 0030); it is not a validator.

export type StageEvidence = StageBindingEvidence

export interface StageBindingEvidence {
  type: 'stage_binding'
  id: string
  mode: string
  declaration_sha256: string
  run_from_epoch_ms: number
  run_to_epoch_ms: number
  evaluated_window_ids: Array<string>
  evaluated_millis: number
  excluded_millis: number
  stages: Array<StageBindingStage>
  verdict_scope: string
}

export interface StageBindingStage {
  id: string
  role: string
  from_offset_ms: number
  to_offset_ms: number
  from_epoch_ms: number
  to_epoch_ms: number
  clipped_to_run_end?: boolean
}
