import Ajv2020 from 'ajv/dist/2020.js'
import { readFile, readdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const readJson = async (path) => JSON.parse(await readFile(resolve(root, path), 'utf8'))

const runSchema = await readJson('docs/contracts/run/v1/run.schema.json')
const validateRun = new Ajv2020({ strict: false, validateFormats: false }).compile(runSchema)
const standardRun = runSchema.examples[0]
const capacityInput = { type: 'capacity_plan', path: 'capacity-plan.json', sha256: '0'.repeat(64) }
const resourceInput = { type: 'resource_snapshot', path: 'resource-snapshot.json', sha256: '0'.repeat(64) }
for (const [name, mode, additions, expected] of [
  ['standard unchanged', 'standard', [], true],
  ['standard forbids capacity plan', 'standard', [capacityInput, resourceInput], false],
  ['capacity requires resources', 'capacity_step', [capacityInput], false],
  ['capacity requires plan', 'capacity_step', [resourceInput], false],
  ['capacity bound inputs', 'capacity_step', [capacityInput, resourceInput], true],
]) {
  if (validateRun({ ...standardRun, analysis_mode: mode, inputs: [...standardRun.inputs, ...additions] }) !== expected) {
    throw new Error(`${name}: expected schema_valid=${expected}; ${JSON.stringify(validateRun.errors)}`)
  }
}
const sourceSchema = await readJson('docs/contracts/sources/v1/source-request.schema.json')
const validateSource = new Ajv2020({ strict: false }).compile(sourceSchema)
const sourceExample = await readJson('docs/contracts/sources/v1/request.example.json')
for (const [value, expected] of [[sourceExample, true], [{ ...sourceExample, url: 'http://unconfigured' }, false], [{ ...sourceExample, step_ms: 999 }, false]]) {
  if (validateSource(value) !== expected) throw new Error(`source request schema: ${JSON.stringify(validateSource.errors)}`)
}
const sourceV2Schema = await readJson('docs/contracts/sources/v2/source-request.schema.json')
const validateSourceV2 = new Ajv2020({ strict: false }).compile(sourceV2Schema)
for (const [name, path, expected] of [
  ['two profiles', 'docs/contracts/sources/v2/examples/valid/two-profiles.json', true],
  ['unknown field', 'docs/contracts/sources/v2/examples/invalid/unknown-field.json', false],
  ['duplicate profile', 'docs/contracts/sources/v2/examples/invalid/duplicate-profile.json', false],
]) {
  if (validateSourceV2(await readJson(path)) !== expected) {
    throw new Error(`source request v2 schema ${name}: expected schema_valid=${expected}; ${JSON.stringify(validateSourceV2.errors)}`)
  }
}
const sourceV3Schema = await readJson('docs/contracts/sources/v3/source-request.schema.json')
const validateSourceV3 = new Ajv2020({ strict: false }).compile(sourceV3Schema)
for (const [name, path, expected] of [
  ['auto window', 'docs/contracts/sources/v3/examples/valid/auto-window.json', true],
  ['explicit window', 'docs/contracts/sources/v3/examples/valid/explicit-window.json', true],
  ['unknown field', 'docs/contracts/sources/v3/examples/invalid/unknown-field.json', false],
  // Margin divisibility by step is a runtime check JSON Schema cannot express; SourceConfigTest rejects this document.
  ['margin not aligned', 'docs/contracts/sources/v3/examples/invalid/margin-not-aligned.json', true],
]) {
  if (validateSourceV3(await readJson(path)) !== expected) {
    throw new Error(`source request v3 schema ${name}: expected schema_valid=${expected}; ${JSON.stringify(validateSourceV3.errors)}`)
  }
}
const sourceV4Schema = await readJson('docs/contracts/sources/v4/source-request.schema.json')
const validateSourceV4 = new Ajv2020({ strict: false }).compile(sourceV4Schema)
for (const [name, path, expected] of [
  ['auto window auto step', 'docs/contracts/sources/v4/examples/valid/auto-window-auto-step.json', true],
  ['explicit window fixed', 'docs/contracts/sources/v4/examples/valid/explicit-window-fixed.json', true],
  ['step mode missing', 'docs/contracts/sources/v4/examples/invalid/step-mode-missing.json', false],
  ['unknown field', 'docs/contracts/sources/v4/examples/invalid/unknown-field.json', false],
]) {
  if (validateSourceV4(await readJson(path)) !== expected) {
    throw new Error(`source request v4 schema ${name}: expected schema_valid=${expected}; ${JSON.stringify(validateSourceV4.errors)}`)
  }
}
const runPeriodSchema = await readJson('docs/contracts/run-period/v1/run-period.schema.json')
const validateRunPeriod = new Ajv2020({ strict: false }).compile(runPeriodSchema)
for (const [name, path, expected] of [
  ['basic', 'docs/contracts/run-period/v1/examples/valid/basic.json', true],
  ['unknown field', 'docs/contracts/run-period/v1/examples/invalid/unknown-field.json', false],
]) {
  if (validateRunPeriod(await readJson(path)) !== expected) {
    throw new Error(`run period schema ${name}: expected schema_valid=${expected}; ${JSON.stringify(validateRunPeriod.errors)}`)
  }
}
const aiModelsSchema = await readJson('docs/contracts/advice/v1/ai-models.schema.json')
const validateAiModels = new Ajv2020({ strict: false }).compile(aiModelsSchema)
const aiModelsExamples = 'docs/contracts/advice/v1/examples/ai-models'
for (const [name, expected] of [
  ['valid', true],
  ['invalid', false],
  // JSON Schema cannot express duplicate property names (JSON.parse keeps the last one), unique ids or a
  // default_model outside the list; AiModelsConfigTest asserts that the Kotlin loader rejects these documents.
  ['runtime-only', true],
]) {
  for (const file of (await readdir(resolve(root, aiModelsExamples, name))).sort()) {
    const path = `${aiModelsExamples}/${name}/${file}`
    if (validateAiModels(await readJson(path)) !== expected) {
      throw new Error(`ai-models schema ${path}: expected schema_valid=${expected}; ${JSON.stringify(validateAiModels.errors)}`)
    }
  }
}
if (!validateAiModels(aiModelsSchema.examples[0])) throw new Error('ai-models schema example is invalid')
const aiAdviceSchema = await readJson('docs/contracts/advice/v1/ai-advice.schema.json')
const aiAdviceOutputSchema = await readJson('docs/contracts/advice/v1/ai-advice-output.schema.json')
const adviceAjv = new Ajv2020({ strict: false, validateFormats: false })
adviceAjv.addSchema(aiAdviceOutputSchema)
const validateAiAdvice = adviceAjv.compile(aiAdviceSchema)
const aiAdviceExamples = 'docs/contracts/advice/v1/examples/ai-advice'
for (const [name, expected] of [
  ['valid', true],
  ['invalid', false],
]) {
  for (const file of (await readdir(resolve(root, aiAdviceExamples, name))).sort()) {
    const path = `${aiAdviceExamples}/${name}/${file}`
    if (validateAiAdvice(await readJson(path)) !== expected) {
      throw new Error(`ai-advice schema ${path}: expected schema_valid=${expected}; ${JSON.stringify(validateAiAdvice.errors)}`)
    }
  }
}
const manifest = await readJson('fixtures/slice1/manifest.json')
const schema = await readJson('docs/contracts/policy/v1/policy.schema.json')
const validate = new Ajv2020({ strict: false }).compile(schema)

for (const example of manifest.policy_examples) {
  const valid = validate(await readJson(example.path))
  if (valid !== example.schema_valid) {
    throw new Error(`${example.path}: expected schema_valid=${example.schema_valid}, got ${valid}; ${JSON.stringify(validate.errors)}`)
  }
}

for (const name of ['api-basic', 'api-strict', 'api-throughput']) {
  const path = `ui/src/shell/policy-templates/${name}.json`
  if (!validate(await readJson(path))) throw new Error(`${path}: ${JSON.stringify(validate.errors)}`)
}

const resourceSchema = await readJson('docs/contracts/resources/v1/resource-snapshot.schema.json')
const validateResource = new Ajv2020({ strict: false }).compile(resourceSchema)
const resourceExample = await readJson('docs/contracts/resources/v1/examples/valid/basic.json')
const intervalExample = await readJson('docs/contracts/resources/v1/examples/valid/interval-max-min.json')
const unsupportedExample = await readJson('docs/contracts/resources/v1/examples/invalid/unsupported-aggregation.json')
for (const [name, value, expected] of [
  ['basic resources', resourceExample, true],
  ['interval max and min', intervalExample, true],
  ['unsupported aggregation', unsupportedExample, false],
  ['decimal integer grid', { ...resourceExample, ...JSON.parse('{"step_ms":1000.0}') }, true],
  ['exponent integer grid', { ...resourceExample, ...JSON.parse('{"step_ms":1e3}') }, true],
  ['unknown resource field', { ...resourceExample, token: 'not-allowed' }, false],
  ['subsecond resource grid', { ...resourceExample, step_ms: 100 }, false],
  ['empty resource grid', { ...resourceExample, point_count: 0 }, false],
  ['resource magnitude', { ...resourceExample, rules: [{ ...resourceExample.rules[0], threshold: 1e19 }] }, false],
]) {
  if (validateResource(value) !== expected) {
    throw new Error(`${name}: expected schema_valid=${expected}; ${JSON.stringify(validateResource.errors)}`)
  }
}

const podViewSchema = await readJson('docs/contracts/pod-view/v1/pod-view.schema.json')
const validatePodView = new Ajv2020({ strict: false }).compile(podViewSchema)
const podViewExamples = 'docs/contracts/pod-view/v1/examples'
for (const file of (await readdir(resolve(root, podViewExamples, 'valid'))).sort()) {
  if (!validatePodView(await readJson(`${podViewExamples}/valid/${file}`))) {
    throw new Error(`pod view schema valid/${file}: ${JSON.stringify(validatePodView.errors)}`)
  }
}
// JSON Schema cannot express these rules (value length versus column_count, uniqueness, references between arrays,
// coverage arithmetic, the 12-byte exponent-free value token); PodViewTest asserts that the Kotlin validator rejects them.
const podViewRuntimeOnly = new Set(['values-too-short', 'number-exponent', 'duplicate-pod', 'duplicate-row', 'unknown-pod', 'unknown-container', 'coverage-pods-mismatch'])
for (const file of (await readdir(resolve(root, podViewExamples, 'invalid'))).sort()) {
  const expected = podViewRuntimeOnly.has(file.replace(/\.json$/, ''))
  if (validatePodView(await readJson(`${podViewExamples}/invalid/${file}`)) !== expected) {
    throw new Error(`pod view schema invalid/${file}: expected schema_valid=${expected}; ${JSON.stringify(validatePodView.errors)}`)
  }
}

const seriesSchema = await readJson('docs/contracts/resources/v1/resource-series.schema.json')
const validateSeries = new Ajv2020({ strict: false }).compile(seriesSchema)
const seriesCatalog = await readJson('docs/contracts/resources/v1/examples/valid/resource-series-catalog.json')
const seriesValues = await readJson('docs/contracts/resources/v1/examples/valid/resource-series-values.json')
const seriesStepZero = await readJson('docs/contracts/resources/v1/examples/invalid/resource-series-values-step-zero.json')
const seriesUnknownKind = await readJson('docs/contracts/resources/v1/examples/invalid/resource-series-unknown-kind.json')
for (const [name, value, expected] of [
  ['series catalog', seriesCatalog, true],
  ['series values', seriesValues, true],
  ['series values step zero', seriesStepZero, false],
  ['series unknown kind', seriesUnknownKind, false],
]) {
  if (validateSeries(value) !== expected) {
    throw new Error(`${name}: expected schema_valid=${expected}; ${JSON.stringify(validateSeries.errors)}`)
  }
}

const diagnosticSchema = await readJson('docs/contracts/diagnostics/v1/correlation-plan.schema.json')
const validateDiagnostic = new Ajv2020({ strict: false }).compile(diagnosticSchema)
const diagnostic = await readJson('docs/contracts/diagnostics/v1/examples/valid/basic.json')
for (const [name, value, expected] of [
  ['basic diagnostics', diagnostic, true],
  ['anomalies only', { ...diagnostic, pairs: [] }, true],
  ['pairs only', { ...diagnostic, anomalies: [] }, true],
  ['empty diagnostics', { ...diagnostic, pairs: [], anomalies: [] }, false],
  ['unknown diagnostic field', { ...diagnostic, token: 'not-allowed' }, false],
  ['C1 identifier', { ...diagnostic, pairs: [{ ...diagnostic.pairs[0], id: 'pair\u0085' }] }, false],
  ['C1 plain text', { ...diagnostic, pairs: [{ ...diagnostic.pairs[0], topology_basis: 'node\u009f' }] }, false],
  ['unbounded lag', { ...diagnostic, pairs: [{ ...diagnostic.pairs[0], max_lag_ms: 60001 }] }, false],
  ['zero materiality', { ...diagnostic, pairs: [{ ...diagnostic.pairs[0], min_resource_delta: 0 }] }, false],
  ['ambiguous signal', { ...diagnostic, anomalies: [{ ...diagnostic.anomalies[0], signal: { series_id: 'cpu', load_metric: 'error_rate' } }] }, false],
]) {
  if (validateDiagnostic(value) !== expected) {
    throw new Error(`${name}: expected schema_valid=${expected}; ${JSON.stringify(validateDiagnostic.errors)}`)
  }
}

const trendSchema = await readJson('docs/contracts/trend/v1/trend-plan.schema.json')
const validateTrend = new Ajv2020({ strict: false }).compile(trendSchema)
const trend = await readJson('docs/contracts/trend/v1/examples/valid/basic.json')
const trendEither = await readJson('docs/contracts/trend/v1/examples/valid/either-direction.json')
const trendUnknown = await readJson('docs/contracts/trend/v1/examples/invalid/unknown-field.json')
const trendCheck = trend.checks[0]
for (const [name, value, expected] of [
  ['basic trend', trend, true],
  ['either direction with two checks', trendEither, true],
  ['unknown check field', trendUnknown, false],
  ['unknown root field', { ...trend, token: 'not-allowed' }, false],
  ['empty checks', { ...trend, checks: [] }, false],
  ['unsupported direction', { ...trend, checks: [{ ...trendCheck, direction: 'up' }] }, false],
  ['cell floor', { ...trend, checks: [{ ...trendCheck, min_cells: 29 }] }, false],
  ['fractional cells', { ...trend, checks: [{ ...trendCheck, min_cells: 30.5 }] }, false],
  ['zero slope gate', { ...trend, checks: [{ ...trendCheck, magnitude_gate: { ...trendCheck.magnitude_gate, min_slope_units_per_second: 0 } }] }, false],
  ['missing percentage gate', { ...trend, checks: [{ ...trendCheck, magnitude_gate: { min_slope_units_per_second: 0.001 } }] }, false],
  ['unknown gate field', { ...trend, checks: [{ ...trendCheck, magnitude_gate: { ...trendCheck.magnitude_gate, confidence: 'high' } }] }, false],
  ['uppercase snapshot hash', { ...trend, resource_snapshot_sha256: 'A'.repeat(64) }, false],
]) {
  if (validateTrend(value) !== expected) {
    throw new Error(`${name}: expected schema_valid=${expected}; ${JSON.stringify(validateTrend.errors)}`)
  }
}
