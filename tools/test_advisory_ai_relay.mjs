import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const relayPath = new URL("../build/ai-probe/fixed-openrouter-relay.mjs", import.meta.url);
const tempRoot = path.resolve(os.tmpdir());
const temp = path.resolve(fs.mkdtempSync(path.join(tempRoot, "ltv-sse-parser-")));
if (path.dirname(temp) !== tempRoot || !path.basename(temp).startsWith("ltv-sse-parser-")) {
  throw new Error(`Unsafe synthetic test directory: ${temp}`);
}
process.on("exit", () => fs.rmSync(temp, { recursive: true, force: true }));
const authorizationPath = path.join(temp, "authorization.json");
fs.writeFileSync(authorizationPath, JSON.stringify({
  schema_version: "advisory-ai-budget-authorization.v1",
  attempt_id: "synthetic-parser-check",
  allowed: true,
  budget_mode: "disabled_by_user",
  status: "DISABLED_BY_USER",
  model: "deepseek-v4-flash-0731",
  max_output_tokens: null,
  output_token_limit_mode: "provider_default"
}));
process.env.ADVISORY_RELAY_MODE = "preflight";
process.env.ADVISORY_BUDGET_AUTHORIZATION_PATH = authorizationPath;
process.env.ADVISORY_RELAY_OUTPUT_ROOT = temp;

const source = fs.readFileSync(relayPath, "utf8");
const parserOnly = source.slice(0, source.indexOf("function writeSettlement")) +
  "\nexport { inspectCompleteResponse, providerPolicyIsExact };\n";
const { inspectCompleteResponse, providerPolicyIsExact } = await import(`data:text/javascript;base64,${Buffer.from(parserOnly).toString("base64")}`);

const common = { id: "chatcmpl-synthetic", model: "deepseek-v4-flash-0731" };
const frames = [
  { ...common, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: "call-0", type: "function", function: { name: "structured_output", arguments: "" } }] }, finish_reason: null }] },
  { ...common, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { name: "", arguments: "{\"summary\":" } }] }, finish_reason: null }] },
  { ...common, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { name: null, arguments: "\"ok\"}" } }] }, finish_reason: null }] },
  { ...common, choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] },
  { ...common, choices: [], usage: { prompt_tokens: 11, completion_tokens: 5000, total_tokens: 5011 } }
];
const body = Buffer.from(frames.map(frame => `data: ${JSON.stringify(frame)}\n\n`).join("") + "data: [DONE]\n\n");
const result = inspectCompleteResponse(200, "text/event-stream", body);
assert.equal(result.protocolValid, true);
assert.deepEqual(result.toolNames, ["structured_output"]);
assert.deepEqual(result.finishReasons, ["tool_calls"]);
assert.equal(result.settlement.status, "KNOWN");
assert.equal(result.settlement.prompt_tokens, 11);
assert.equal(result.settlement.completion_tokens, 5000);
assert.equal(result.settlement.total_tokens, 5011);
assert.equal(result.settlement.reported_cost_micro_usd, null);

const providerBody = {
  model: "deepseek-v4-flash-0731",
  messages: [{ role: "user", content: "bounded input" }],
  n: 1,
  stream: true,
  parallel_tool_calls: false
};
assert.equal(providerPolicyIsExact(providerBody), true);
for (const field of ["max_tokens", "max_completion_tokens", "max_output_tokens"]) {
  assert.equal(providerPolicyIsExact({ ...providerBody, [field]: 4096 }), false);
}
assert.doesNotMatch(source, /max_tokens:\s*effectiveMaxTokens/);
assert.match(source, /output_token_limit_mode:\s*authorization\.output_token_limit_mode/);

const inspect = calls => inspectCompleteResponse(200, "text/event-stream", Buffer.from([
  `data: ${JSON.stringify({ ...common, choices: [{ index: 0, delta: { tool_calls: calls }, finish_reason: "tool_calls" }] })}\n\n`,
  `data: ${JSON.stringify({ ...common, choices: [], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })}\n\n`,
  "data: [DONE]\n\n"
].join("")));

assert.equal(inspect([{ function: { name: "structured_output" } }]).protocolValid, false);
const secondCall = inspect([
  { index: 0, function: { name: "structured_output" } },
  { index: 1, function: { name: "structured_output" } }
]);
assert.equal(secondCall.protocolValid, false);
assert.equal(inspect([
  { index: 0, function: { name: "structured_output" } },
  { index: 0, function: { name: "run_shell_command" } }
]).protocolValid, false);
assert.equal(inspect([
  { index: 0, id: "call-without-name", function: { name: null, arguments: "{}" } }
]).protocolValid, false);
const emptyNoOp = inspect([{ index: 0, function: { name: null, arguments: "" } }]);
assert.equal(emptyNoOp.protocolValid, true);
assert.deepEqual(emptyNoOp.toolNames, []);
assert.match(source, /safeNames\.length !== 1/);

console.log("synthetic SSE parser PASS");
