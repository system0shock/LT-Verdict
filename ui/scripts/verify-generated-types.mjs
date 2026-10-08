// Checks that ui/src/types.generated.ts (generated from the Kotlin models of analysis-result and analysis-identity)
// matches the JSON the engine really writes, and that the hand-written AnalysisResult in ui/src/types.ts keeps the
// same keys and enum values. Positive: every committed golden document is assigned to its generated type.
// Negative: a wrong enum value, an extra key and a missing key must be rejected, so the types are not too wide.
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

const lines = [
  "import type { AnalysisIdentityDocument, AnalysisResultDocument } from './types.generated'",
  "import type { AnalysisResult } from './types'",
  '',
]
let index = 0
for (const name of cases) {
  const result = readJson(resolve(goldenRoot, name, 'analysis-result.json'))
  lines.push(`export const result${index++}: AnalysisResultDocument = ${JSON.stringify(result)}`)
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
)

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
    const where = d.file ? `line ${d.file.getLineAndCharacterOfPosition(d.start ?? 0).line + 1}: ` : ''
    console.error(`  ${where}${ts.flattenDiagnosticMessageText(d.messageText, '\n')}`.slice(0, 600))
  }
  process.exit(1)
}
console.log(
  `verify-generated-types: ${cases.length} results, ${identityFiles.length} identities and ${negatives.length} negative cases agree with types.generated.ts`,
)
