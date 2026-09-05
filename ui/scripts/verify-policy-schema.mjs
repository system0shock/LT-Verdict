import Ajv2020 from 'ajv/dist/2020.js'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const readJson = async (path) => JSON.parse(await readFile(resolve(root, path), 'utf8'))
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
