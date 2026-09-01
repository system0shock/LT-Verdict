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
