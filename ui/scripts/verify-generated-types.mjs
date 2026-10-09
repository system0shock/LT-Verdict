// Checks that ui/src/types.generated.ts (generated from the Kotlin models of analysis-result and analysis-identity)
// matches the JSON the engine really writes, and that the hand-written AnalysisResult in ui/src/types.ts keeps the
// same keys and enum values. Positive: every committed golden document is assigned to its generated type.
// Negative: a wrong enum value, an extra key and a missing key must be rejected, so the types are not too wide.
// The same is done for the findings and evidence items of ui/src/types.items.generated.ts: the items of the golden
// results and one real item per type and key set (fixtures/typed-evidence/samples.ndjson) must be assignable, broken
// copies must not, and the hand-written evidence types of types.ts must keep the same keys (and may be looser about
// optional keys, because stored results of older engines lack them).
// The stage_binding item of ui/src/types.stage-items.generated.ts is checked against a real item (fixtures/stages/stage-binding.sample.json,
// compared with the engine output by a Kotlin test) and against the hand-written StageBindingEvidence of types.ts (ADR 0030).
// The same is done for the diagnostic, capacity and trend items of ui/src/types.derived-items.generated.ts, with the real
// items of fixtures/typed-evidence/samples-diagnostic-capacity-trend.ndjson. This script does not prove the bytes of the
// documents (JSON.parse loses the precision of wide numbers): the Kotlin tests do.
// The check source is virtual (never written to disk) and compiled with the options of ui/tsconfig.json.
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(uiRoot, '..')
const virtualFile = resolve(uiRoot, 'src/__verify_generated_types__.ts')

const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'))
const goldenRoot = resolve(repoRoot, 'fixtures/typed-boundary/golden')
const cases = readdirSync(goldenRoot).sort()
const identityFiles = [
  ...cases.map((name) => [`golden/${name}`, resolve(goldenRoot, name, 'identity.json')]),
  ['slice1/analysis-identity', resolve(repoRoot, 'fixtures/slice1/identity/analysis-identity.v1.json')],
  ['slice1/analysis-identity-resources', resolve(repoRoot, 'fixtures/slice1/identity/analysis-identity-resources.v1.json')],
  ['slice1/legacy-pre-adr-0016', resolve(repoRoot, 'fixtures/slice1/identity/legacy-pre-adr-0016.v1.json')],
]

const itemsText = readFileSync(resolve(uiRoot, 'src/types.items.generated.ts'), 'utf8').replace(/\r\n/g, '\n')
const typedTags = new Set([...itemsText.matchAll(/^ {2}type: '([a-z_]+)'$/gm)].map((match) => match[1]))
const samples = readFileSync(resolve(repoRoot, 'fixtures/typed-evidence/samples.ndjson'), 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line))
const derivedText = readFileSync(resolve(uiRoot, 'src/types.derived-items.generated.ts'), 'utf8').replace(/\r\n/g, '\n')
const derivedKinds = new Map(
  [...derivedText.matchAll(/^export interface (\w+) \{\n {2}type: '([a-z_]+)'$/gm)].map((match) => [match[2], match[1].endsWith('Finding') ? 'finding' : 'evidence']),
)
const derivedSamples = readFileSync(resolve(repoRoot, 'fixtures/typed-evidence/samples-diagnostic-capacity-trend.ndjson'), 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line))
const derivedEvidence = [
  'DiagnosticSummaryEvidence',
  'CorrelationPairEvidence',
  'CorrelationHeadlineSelectionEvidence',
  'AnomalyCheckEvidence',
  'WindowMetricSummaryEvidence',
  'TrendCheckEvidence',
  'TrendSummaryEvidence',
  'CapacityKneeDiagnosticEvidence',
]
const derivedFindings = ['ResourceTrendFinding']
// Keys the hand-written type keeps for results of older engines; the current engine does not write them.
const legacyKeys = { CorrelationHeadlineSelectionEvidence: ['stage_count'] }
const typedEvidence = [
  'MetricSummaryEvidence',
  'PolicyCheckEvidence',
  'DiagnosticEvidence',
  'RuleWindowCheckEvidence',
  'WindowPolicySummaryEvidence',
  'ResourceSummaryEvidence',
  'ResourcePolicyCheckEvidence',
]

const stageSample = readJson(resolve(repoRoot, 'fixtures/stages/stage-binding.sample.json'))

const lines = [
  "import type { AnalysisIdentityDocument, AnalysisResultDocument } from './types.generated'",
  "import type { AnalysisEvidence as GeneratedEvidence, AnalysisFinding as GeneratedFinding } from './types.items.generated'",
  `import type { ${typedEvidence.map((name) => `${name} as Generated${name}`).join(', ')} } from './types.items.generated'`,
  "import type { DerivedEvidence as GeneratedDerivedEvidence, DerivedFinding as GeneratedDerivedFinding } from './types.derived-items.generated'",
  `import type { ${[...derivedEvidence, ...derivedFindings, 'CapacitySummaryEvidence', 'CapacityStageDocument'].map((name) => `${name} as Generated${name}`).join(', ')} } from './types.derived-items.generated'`,
  "import type { StageEvidence as GeneratedStageEvidence, StageBindingEvidence as GeneratedStageBindingEvidence, StageBindingStage as GeneratedStageBindingStage } from './types.stage-items.generated'",
  "import type { StageBindingEvidence } from './types'",
  `import type { AnalysisResult, CapacityStage, CapacitySummary, ${[...typedEvidence, ...derivedEvidence, ...derivedFindings].join(', ')} } from './types'`,
  '',
]
let index = 0
let itemCount = 0
for (const name of cases) {
  const result = readJson(resolve(goldenRoot, name, 'analysis-result.json'))
  lines.push(`export const result${index++}: AnalysisResultDocument = ${JSON.stringify(result)}`)
  for (const [array, union] of [
    ['evidence', 'GeneratedEvidence'],
    ['findings', 'GeneratedFinding'],
  ]) {
    for (const item of result[array].filter((entry) => typedTags.has(entry.type))) {
      lines.push(`export const item${itemCount++}: ${union} = ${JSON.stringify(item)}`)
    }
    const derivedUnion = array === 'evidence' ? 'GeneratedDerivedEvidence' : 'GeneratedDerivedFinding'
    for (const item of result[array].filter((entry) => derivedKinds.has(entry.type))) {
      lines.push(`export const item${itemCount++}: ${derivedUnion} = ${JSON.stringify(item)}`)
    }
  }
}
for (const sample of samples) {
  lines.push(`export const sample${itemCount++}: GeneratedEvidence | GeneratedFinding = ${JSON.stringify(sample)}`)
}
for (const sample of derivedSamples) {
  const union = derivedKinds.get(sample.type) === 'finding' ? 'GeneratedDerivedFinding' : 'GeneratedDerivedEvidence'
  lines.push(`export const sample${itemCount++}: ${union} = ${JSON.stringify(sample)}`)
}
lines.push(`export const sample${itemCount++}: GeneratedStageEvidence = ${JSON.stringify(stageSample)}`)
const sampleTags = new Set([...samples, ...derivedSamples].map((sample) => sample.type))
const untested = [...typedTags, ...derivedKinds.keys()].filter((tag) => !sampleTags.has(tag))
if (untested.length) {
  console.error(`verify-generated-types: no sample in fixtures/typed-evidence/samples*.ndjson for type ${untested.join(', ')}`)
  process.exit(1)
}
for (const [, path] of identityFiles) {
  lines.push(`export const identity${index++}: AnalysisIdentityDocument = ${JSON.stringify(readJson(path))}`)
}

const sample = readJson(resolve(goldenRoot, cases[0], 'analysis-result.json'))
const identitySample = readJson(identityFiles[0][1])
const negatives = [
  ['unknown run_validity', 'AnalysisResultDocument', { ...sample, run_validity: 'BROKEN' }],
  ['unknown analysis_mode', 'AnalysisResultDocument', { ...sample, analysis_mode: 'other' }],
  ['unknown coverage status', 'AnalysisResultDocument', { ...sample, analysis_coverage: { status: 'MAYBE', reasons: [] } }],
  ['extra result key', 'AnalysisResultDocument', { ...sample, extra: 1 }],
  ['missing result key', 'AnalysisResultDocument', Object.fromEntries(Object.entries(sample).filter(([key]) => key !== 'findings'))],
  ['number in a string limit', 'AnalysisIdentityDocument', { ...identitySample, limits: { csv_columns_max: 64 } }],
  ['extra identity key', 'AnalysisIdentityDocument', { ...identitySample, extra: 1 }],
  ['missing identity key', 'AnalysisIdentityDocument', Object.fromEntries(Object.entries(identitySample).filter(([key]) => key !== 'engine'))],
]
const richest = (tag) => [...samples, ...derivedSamples].filter((sample) => sample.type === tag).sort((a, b) => Object.keys(b).length - Object.keys(a).length)[0]
const policyCheck = richest('policy_check')
const metricSummary = richest('metric_summary')
const itemNegatives = [
  ['extra key in a policy_check', 'GeneratedEvidence', { ...policyCheck, extra: 1 }],
  ['missing rule_id in a policy_check', 'GeneratedEvidence', Object.fromEntries(Object.entries(policyCheck).filter(([key]) => key !== 'rule_id'))],
  ['number for a string field', 'GeneratedEvidence', { ...policyCheck, rule_id: 5 }],
  ['unknown type tag', 'GeneratedEvidence', { ...policyCheck, type: 'policy_checks' }],
  ['unknown scope kind', 'GeneratedEvidence', { ...metricSummary, scope: { kind: 'other' } }],
  ['missing explicit null error_rate_ratio', 'GeneratedEvidence', Object.fromEntries(Object.entries(metricSummary).filter(([key]) => key !== 'error_rate_ratio'))],
]
const pair = richest('correlation_pair')
const capacity = richest('capacity_summary')
const withStage = (change) => ({ ...capacity, stages: [{ ...capacity.stages[0], ...change }] })
const withoutKey = (object, key) => Object.fromEntries(Object.entries(object).filter(([name]) => name !== key))
const derivedNegatives = [
  ['extra key in a correlation_pair', 'GeneratedDerivedEvidence', { ...pair, extra: 1 }],
  ['missing explicit null raw_rho', 'GeneratedDerivedEvidence', withoutKey(pair, 'raw_rho')],
  ['number for a string field of a correlation_pair', 'GeneratedDerivedEvidence', { ...pair, pair_id: 5 }],
  ['string for a number field of a correlation_pair', 'GeneratedDerivedEvidence', { ...pair, paired_cells: '5' }],
  ['string in a lag_profile lag', 'GeneratedDerivedEvidence', { ...pair, lag_profile: [{ lag_ms: 'x', rho: null }] }],
  ['unknown derived type tag', 'GeneratedDerivedEvidence', { ...pair, type: 'correlation_pairs' }],
  ['an evidence item as a finding', 'GeneratedDerivedFinding', pair],
  ['string for the achieved load of a stage', 'GeneratedDerivedEvidence', withStage({ achieved: '5' })],
  ['missing verdict of a stage', 'GeneratedDerivedEvidence', withStage({ verdict: undefined })],
  ['unknown policy_verdict of a capacity_summary', 'GeneratedDerivedEvidence', { ...capacity, policy_verdict: 'MAYBE' }],
  ['string for a knee point load', 'GeneratedDerivedEvidence', { ...richest('capacity_knee_diagnostic'), points: [{ stage_id: 'a', load: 'x', value: 1 }] }],
  ['missing required window_metric_summary latency', 'GeneratedDerivedEvidence', withoutKey(richest('window_metric_summary'), 'latency_ms')],
  ['missing explicit null in the magnitude gate of a trend_check', 'GeneratedDerivedEvidence', {
    ...richest('trend_check'),
    magnitude_gate: withoutKey(richest('trend_check').magnitude_gate, 'required_split_half_shift_units'),
  }],
]
const stageNegatives = [
  ['extra key in a stage_binding', 'GeneratedStageEvidence', { ...stageSample, extra: 1 }],
  ['missing evaluated_millis in a stage_binding', 'GeneratedStageEvidence', withoutKey(stageSample, 'evaluated_millis')],
  ['string for a number field of a stage_binding', 'GeneratedStageEvidence', { ...stageSample, excluded_millis: '59800' }],
  ['unknown type tag of a stage_binding', 'GeneratedStageEvidence', { ...stageSample, type: 'stage_bindings' }],
  ['string for the clip flag of a stage', 'GeneratedStageEvidence', { ...stageSample, stages: [{ ...stageSample.stages[1], clipped_to_run_end: 'no' }] }],
]
negatives.push(...itemNegatives, ...derivedNegatives, ...stageNegatives)
negatives.forEach(([label, type, value], position) => {
  lines.push(`// @ts-expect-error ${label}`)
  lines.push(`export const negative${position}: ${type} = ${JSON.stringify(value)}`)
})

lines.push(
  '',
  '// The hand-written AnalysisResult keeps the keys of the Kotlin model, and every enum value the engine can write is accepted by it.',
  'type SameKeys<A, B> = [keyof A] extends [keyof B] ? ([keyof B] extends [keyof A] ? true : never) : never',
  'export const sameKeys: SameKeys<AnalysisResult, AnalysisResultDocument> = true',
  "type Enums = 'analysis_mode' | 'run_validity' | 'policy_verdict' | 'analysis_coverage'",
  'export const enumsAccepted = (value: Pick<AnalysisResultDocument, Enums>): Pick<AnalysisResult, Enums> => value',
  '',
  '// The hand-written evidence types keep the keys of the Kotlin classes; an optional key of the engine is optional there too.',
  'type OptionalKeys<T> = { [K in keyof T]-?: object extends Pick<T, K> ? K : never }[keyof T]',
  'type NotStricter<Hand, Generated> = [Exclude<OptionalKeys<Generated>, OptionalKeys<Hand>>] extends [never] ? true : never',
)
typedEvidence.forEach((name, position) => {
  lines.push(
    `export const keys${position}: SameKeys<${name}, Generated${name}> = true`,
    `export const optional${position}: NotStricter<${name}, Generated${name}> = true`,
  )
})
derivedEvidence.forEach((name, position) => {
  const omitted = (legacyKeys[name] ?? []).map((key) => `'${key}'`).join(' | ') || 'never'
  lines.push(
    `export const derivedKeys${position}: SameKeys<Omit<${name}, ${omitted}>, Generated${name}> = true`,
    `export const derivedOptional${position}: NotStricter<${name}, Generated${name}> = true`,
  )
})
derivedFindings.forEach((name, position) => {
  lines.push(`export const findingKeys${position}: SameKeys<${name}, Generated${name}> = true`)
})
lines.push(
  '',
  '// The hand-written stage_binding keeps the keys of the Kotlin class and of its stages.',
  'export const stageKeys: SameKeys<StageBindingEvidence, GeneratedStageBindingEvidence> = true',
  'export const stageOptional: NotStricter<StageBindingEvidence, GeneratedStageBindingEvidence> = true',
  "export const stageRowKeys: SameKeys<StageBindingEvidence['stages'][number], GeneratedStageBindingStage> = true",
)
lines.push(
  '',
  "// The capacity_summary payload of analysis-result is the evidence without its id and type; the stages keep their keys too.",
  "export const capacityKeys: SameKeys<CapacitySummary, Omit<GeneratedCapacitySummaryEvidence, 'id' | 'type'>> = true",
  'export const capacityStageKeys: SameKeys<CapacityStage, GeneratedCapacityStageDocument> = true',
)
lines.push('')

const options = ts.parseJsonConfigFileContent(
  ts.readConfigFile(resolve(uiRoot, 'tsconfig.json'), ts.sys.readFile).config,
  ts.sys,
  uiRoot,
).options
const host = ts.createCompilerHost(options)
const source = lines.join('\n')
const baseGetSourceFile = host.getSourceFile.bind(host)
host.getSourceFile = (name, ...rest) =>
  resolve(name) === virtualFile ? ts.createSourceFile(name, source, options.target ?? ts.ScriptTarget.ES2023, true) : baseGetSourceFile(name, ...rest)
const baseFileExists = host.fileExists.bind(host)
host.fileExists = (name) => resolve(name) === virtualFile || baseFileExists(name)
const baseReadFile = host.readFile.bind(host)
host.readFile = (name) => (resolve(name) === virtualFile ? source : baseReadFile(name))

const program = ts.createProgram([virtualFile], options, host)
const diagnostics = ts.getPreEmitDiagnostics(program).filter((d) => !d.file || resolve(d.file.fileName) === virtualFile)
if (diagnostics.length) {
  console.error('verify-generated-types: generated types do not match the documents or the hand-written types')
  for (const d of diagnostics) {
    const line = d.file ? d.file.getLineAndCharacterOfPosition(d.start ?? 0).line : -1
    const where = d.file ? `line ${line + 1} (${source.split('\n')[line].slice(0, 90)}): ` : ''
    console.error(`  ${where}${ts.flattenDiagnosticMessageText(d.messageText, '\n')}`.slice(0, 600))
  }
  process.exit(1)
}
console.log(
  `verify-generated-types: ${cases.length} results, ${identityFiles.length} identities, ${itemCount} items of ${typedTags.size + derivedKinds.size} types and ${negatives.length} negative cases agree with the generated types`,
)
