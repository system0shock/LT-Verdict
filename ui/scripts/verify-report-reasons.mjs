// Checks that the reason codes of the interface dictionary (ui/src/verdictReasons.ts)
// and of the HTML report dictionary (ReportReasons.kt) are the same set.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (relative) => readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8')

const ts = read('../src/verdictReasons.ts')
const start = ts.indexOf('export const REASONS')
const end = ts.indexOf('\n}', start)
if (start < 0 || end < 0) {
  console.error('verify-report-reasons: REASONS block not found in ui/src/verdictReasons.ts')
  process.exit(1)
}
const uiCodes = new Set([...ts.slice(start, end).matchAll(/^\s{2}([A-Z][A-Z0-9_]*):/gm)].map((match) => match[1]))

const kt = read('../../src/main/kotlin/io/ltverdict/report/ReportReasons.kt')
const reportCodes = new Set([...kt.matchAll(/^\s+"([A-Z][A-Z0-9_]*)"\s+to\s/gm)].map((match) => match[1]))

const onlyUi = [...uiCodes].filter((code) => !reportCodes.has(code))
const onlyReport = [...reportCodes].filter((code) => !uiCodes.has(code))
if (uiCodes.size === 0 || reportCodes.size === 0 || onlyUi.length || onlyReport.length) {
  console.error(`verify-report-reasons: dictionaries differ (interface ${uiCodes.size} codes, report ${reportCodes.size} codes)`)
  if (onlyUi.length) console.error(`  only in verdictReasons.ts: ${onlyUi.join(', ')}`)
  if (onlyReport.length) console.error(`  only in ReportReasons.kt: ${onlyReport.join(', ')}`)
  process.exit(1)
}
console.log(`verify-report-reasons: ${uiCodes.size} reason codes are in sync`)
