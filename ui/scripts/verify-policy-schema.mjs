import Ajv2020 from 'ajv/dist/2020.js'
import { readFile } from 'node:fs/promises'
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
const manifest = await readJson('fixtures/slice1/manifest.json')
const schema = await readJson('docs/contracts/policy/v1/policy.schema.json')
const validate = new Ajv2020({ strict: false }).compile(schema)

for (const example of manifest.policy_examples) {
  const valid = validate(await readJson(example.path))
  if (valid !== example.schema_valid) {
    throw new Error(`${example.path}: expected schema_valid=${example.schema_valid}, got ${valid}; ${JSON.stringify(validate.errors)}`)
  }
}

const resourceSchema = await readJson('docs/contracts/resources/v1/resource-snapshot.schema.json')
const validateResource = new Ajv2020({ strict: false }).compile(resourceSchema)
const resourceExample = await readJson('docs/contracts/resources/v1/examples/valid/basic.json')
for (const [name, value, expected] of [
  ['basic resources', resourceExample, true],
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
