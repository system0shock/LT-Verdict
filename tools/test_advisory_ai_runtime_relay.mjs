import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { test } from "node:test";

const relayPath = path.join(import.meta.dirname, "advisory_ai_runtime_relay.mjs");
const vectorsPath = path.join(import.meta.dirname, "advisory_ai_relay_retry_vectors.json");
const schemaPath = path.join(import.meta.dirname, "../docs/contracts/advice/v1/ai-advice-output.schema.json");
const model = "deepseek-v4-flash-0731";
const advice = {
  schema_version: "ai-advice-output.v1",
  summary: "Preflight completed with bounded evidence.",
  hypotheses: [{
    rank: 1,
    observation: "The run validity fact is present.",
    possible_explanation: "No explanation is established by this preflight.",
    recommended_check: "Inspect the deterministic analysis.",
    evidence_refs: ["analysis-result.json#/run_validity"],
  }],
  recommendations: [],
  caveats: ["This is a transport preflight, not model advice."],
};
const wrappedArgs = JSON.stringify({ arguments: JSON.stringify(advice) });
const firstRequest = {
  model,
  messages: [{ role: "system", content: "Return structured advice." }, { role: "user", content: "bounded evidence" }],
  temperature: 0,
  max_tokens: 64000,
  stream: true,
  stream_options: { include_usage: true },
  tools: [{ type: "function", function: { name: "structured_output", parameters: { type: "object" } } }],
};

function sse(args, { id = "call_1", name = "structured_output", modelId = model, done = true, message = false } = {}) {
  const halves = [args.slice(0, Math.ceil(args.length / 2)), args.slice(Math.ceil(args.length / 2))];
  const frames = halves.map((part, index) => ({
    model: modelId,
    choices: [{ index: 0, [message ? "message" : "delta"]: { tool_calls: [{ index: 0, ...(index === 0 ? { id } : {}), function: { ...(index === 0 ? { name } : {}), arguments: part } }] } }],
  }));
  frames.push({ model: modelId, choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] });
  return frames.map(frame => `data: ${JSON.stringify(frame)}\n\n`).join("") + (done ? "data: [DONE]\n\n" : "");
}

function provider(body, status = 200, content_type = "text/event-stream") {
  return { status, content_type, body };
}

function retryRequest(first, args, { reasoning = "", id = "call_1" } = {}) {
  return {
    ...structuredClone(first),
    messages: [
      ...structuredClone(first.messages),
      { role: "assistant", content: null, reasoning_content: reasoning, tool_calls: [{ id, type: "function", function: { name: "structured_output", arguments: args } }] },
      { role: "tool", tool_call_id: id, content: "params must have required property 'schema_version'" },
    ],
  };
}

async function freePort() {
  const server = http.createServer();
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

function launch(root, port, env = {}, nodeArgs = []) {
  const ready = path.join(root, "ready");
  const cleanEnv = { ...process.env };
  for (const key of ["ADVISORY_RELAY_PREFLIGHT_SCENARIO", "ADVISORY_RELAY_PREFLIGHT_RESPONSES", "ADVISORY_RELAY_RETRY_WINDOW_MS"]) delete cleanEnv[key];
  const child = spawn(process.execPath, [...nodeArgs, relayPath], {
    env: { ...cleanEnv, ADVISORY_RELAY_MODE: "preflight", ADVISORY_RELAY_OUTPUT_ROOT: root, ADVISORY_RELAY_READY_PATH: ready, ADVISORY_RELAY_PORT: String(port), ...env },
    stdio: ["ignore", "ignore", "pipe"],
  });
  const exited = new Promise(resolve => child.once("exit", resolve));
  let stderr = "";
  child.stderr.on("data", chunk => { stderr += chunk; });
  return { child, ready, exited, get stderr() { return stderr; } };
}

async function waitReady(relay) {
  const deadline = Date.now() + 5000;
  while (!fs.existsSync(relay.ready) && relay.child.exitCode === null && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  assert.equal(relay.child.exitCode, null, relay.stderr);
  assert.ok(fs.existsSync(relay.ready), `relay did not become ready: ${relay.stderr}`);
}

async function stop(relay) {
  if (relay.child.exitCode === null && relay.child.signalCode === null) {
    relay.child.kill("SIGTERM");
    await relay.exited;
  }
}

async function withRelay(run, env = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ltv-ai-relay-test-"));
  const port = await freePort();
  const relay = launch(root, port, env);
  try {
    await waitReady(relay);
    await run(root, port);
  } finally {
    await stop(relay);
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function request(port, body) {
  return new Promise((resolve, reject) => {
    const encoded = Buffer.from(typeof body === "string" ? body : JSON.stringify(body));
    const call = http.request({ host: "127.0.0.1", port, path: "/v1/chat/completions", method: "POST", headers: { "content-type": "application/json", "content-length": encoded.length } }, response => {
      const chunks = [];
      response.on("data", chunk => chunks.push(chunk));
      response.on("end", () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
    });
    call.on("error", reject);
    call.end(encoded);
  });
}

function result(root) {
  return JSON.parse(fs.readFileSync(path.join(root, "relay-result.json"), "utf8"));
}

function assertResult(root, { received, forwarded, status, outcomes, reason }) {
  const actual = result(root);
  assert.equal(actual.schema_version, "advisory-ai-runtime-relay.v1");
  assert.equal(actual.received_request_count, received);
  assert.equal(actual.forwarded_request_count, forwarded);
  assert.equal(actual.status, status);
  assert.deepEqual(actual.outcomes, outcomes);
  assert.equal(actual.retry_refused_reason, reason);
  assert.equal(Object.hasOwn(actual, "request_count"), false);
}

test("retry is forwarded once after wrapped arguments assembled from several stream fragments", async () => {
  await withRelay(async (root, port) => {
    assert.equal((await request(port, firstRequest)).status, 200);
    const second = await request(port, retryRequest(firstRequest, wrappedArgs));
    assert.equal(second.status, 200);
    const args = second.body.split(/\r?\n/).filter(line => line.startsWith("data: {"))
      .flatMap(line => JSON.parse(line.slice(6)).choices ?? [])
      .flatMap(choice => choice.delta?.tool_calls ?? [])
      .map(call => call.function?.arguments ?? "").join("");
    assert.deepEqual(JSON.parse(args), advice);
    assertResult(root, { received: 2, forwarded: 2, status: "FORWARDED_STRUCTURED_OUTPUT", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "FORWARDED_RETRY"], reason: null });
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
});

test("provider error on forwarded retry is blocked", async () => {
  await withRelay(async (root, port) => {
    assert.equal((await request(port, firstRequest)).status, 200);
    assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 502);
    assertResult(root, { received: 2, forwarded: 2, status: "BLOCKED_PROVIDER_RESPONSE", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_PROVIDER_RESPONSE"], reason: null });
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-error" });
});

test("third request is blocked with 409", async () => {
  await withRelay(async (root, port) => {
    await request(port, firstRequest);
    await request(port, retryRequest(firstRequest, wrappedArgs));
    const third = await request(port, retryRequest(firstRequest, wrappedArgs));
    assert.equal(third.status, 409);
    assert.equal(JSON.parse(third.body).error.type, "additional_request_blocked");
    assertResult(root, { received: 3, forwarded: 2, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "FORWARDED_RETRY", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_LIMIT_REACHED" });
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
});

test("outcomes remain in request order when the first body is delayed", async () => {
  await withRelay(async (root, port) => {
    const encoded = Buffer.from(JSON.stringify(firstRequest));
    let firstCall;
    const firstResponse = new Promise((resolve, reject) => {
      firstCall = http.request({ host: "127.0.0.1", port, path: "/v1/chat/completions", method: "POST", headers: { "content-type": "application/json", "content-length": encoded.length } }, response => {
        response.resume();
        response.on("end", () => resolve(response.statusCode));
      });
      firstCall.on("error", reject);
    });
    const connected = new Promise(resolve => firstCall.once("socket", socket => socket.connecting ? socket.once("connect", resolve) : resolve()));
    firstCall.flushHeaders();
    await connected;
    firstCall.write(encoded.subarray(0, 1));
    await new Promise(resolve => setTimeout(resolve, 50));
    assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 409);
    firstCall.end(encoded.subarray(1));
    assert.equal(await firstResponse, 200);
    assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_FIRST_RESPONSE_NOT_ACCEPTED" });
  });
});

test("retry is refused after provider error, text answer, or truncated stream", async t => {
  const cases = [
    ["provider error", provider("server error", 503, "text/plain")],
    ["text answer", provider('data: {"model":"deepseek-v4-flash-0731","choices":[{"index":0,"delta":{"content":"text"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')],
    ["truncated stream", provider(sse(wrappedArgs, { done: false }))],
  ];
  for (const [name, firstResponse] of cases) await t.test(name, async () => {
    await withRelay(async (root, port) => {
      assert.equal((await request(port, firstRequest)).status, 502);
      assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 409);
      assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["BLOCKED_PROVIDER_RESPONSE", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_FIRST_RESPONSE_NOT_ACCEPTED" });
    }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([firstResponse]) });
  });
});

test("retry accepts message.tool_calls fragments and rejects conflicting call ids", async t => {
  await t.test("message.tool_calls fragments", async () => {
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 200);
      assert.equal(result(root).forwarded_request_count, 2);
    }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(sse(wrappedArgs, { message: true }))]) });
  });
  await t.test("conflicting ids", async () => {
    const lines = sse(wrappedArgs).split("\n");
    const secondFrame = JSON.parse(lines[2].slice(6));
    secondFrame.choices[0].delta.tool_calls[0].id = "call_2";
    lines[2] = `data: ${JSON.stringify(secondFrame)}`;
    await withRelay(async (root, port) => {
      assert.equal((await request(port, firstRequest)).status, 502);
      assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 409);
      assert.equal(result(root).retry_refused_reason, "RETRY_FIRST_RESPONSE_NOT_ACCEPTED");
    }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(lines.join("\n"))]) });
  });
});

test("retry is eligible for malformed top-level JSON values", async t => {
  for (const args of ["not JSON", "null", "[]", "42"]) await t.test(args, async () => {
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      assert.equal((await request(port, retryRequest(firstRequest, args))).status, 200);
      assert.equal(result(root).forwarded_request_count, 2);
    }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(sse(args))]) });
  });
});

test("failed retry response remains a blocked provider response", async () => {
  await withRelay(async (root, port) => {
    await request(port, firstRequest);
    assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 502);
    assertResult(root, { received: 2, forwarded: 2, status: "BLOCKED_PROVIDER_RESPONSE", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_PROVIDER_RESPONSE"], reason: null });
  }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(sse(wrappedArgs)), provider("server error", 503, "text/plain")]) });
});

test("retry is refused for a valid top level with a deeper violation", async () => {
  const deep = structuredClone(advice);
  deep.hypotheses[0].rank = "bad";
  const args = JSON.stringify(deep);
  await withRelay(async (root, port) => {
    assert.equal((await request(port, firstRequest)).status, 200);
    assert.equal((await request(port, retryRequest(firstRequest, args))).status, 409);
    assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_NOT_TOP_LEVEL_SCHEMA" });
  }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(sse(args))]) });
});

test("deep-violation preflight scenario refuses continuation", async () => {
  await withRelay(async (root, port) => {
    const first = await request(port, firstRequest);
    assert.equal(first.status, 200);
    const args = JSON.parse(first.body.split("\n")[0].slice(6)).choices[0].delta.tool_calls[0].function.arguments;
    assert.equal(Object.hasOwn(JSON.parse(args).hypotheses[0], "observation"), false);
    assert.equal((await request(port, retryRequest(firstRequest, args, { id: "lt-verdict-preflight-call" }))).status, 409);
    assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_NOT_TOP_LEVEL_SCHEMA" });
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "deep-violation" });
});

test("retry is refused for mismatched continuation messages", async t => {
  const mutations = {
    "earlier message differs": body => { body.messages[0].content = "changed"; },
    "second tool call": body => { body.messages.at(-2).tool_calls.push(structuredClone(body.messages.at(-2).tool_calls[0])); },
    "null tool call": body => { body.messages.at(-2).tool_calls[0] = null; },
    "tool_call_id differs": body => { body.messages.at(-1).tool_call_id = "wrong"; },
    "assistant id differs": body => { body.messages.at(-2).tool_calls[0].id = "wrong"; },
    "assistant name differs": body => { body.messages.at(-2).tool_calls[0].function.name = "wrong"; },
    "assistant arguments differ": body => { body.messages.at(-2).tool_calls[0].function.arguments = "{}"; },
    "tool text empty": body => { body.messages.at(-1).content = ""; },
    "tool text over 8 KiB": body => { body.messages.at(-1).content = "x".repeat(8193); },
    "assistant extra key": body => { body.messages.at(-2).extra = true; },
    "tool extra key": body => { body.messages.at(-1).extra = true; },
    "assistant non-empty content": body => { body.messages.at(-2).content = "text"; },
    "reasoning_content is not a string": body => { body.messages.at(-2).reasoning_content = null; },
  };
  for (const [name, mutate] of Object.entries(mutations)) await t.test(name, async () => {
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      const second = retryRequest(firstRequest, wrappedArgs);
      mutate(second);
      assert.equal((await request(port, second)).status, 409);
      assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_CONTINUATION_MISMATCH" });
    }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
  });
});

test("retry is forwarded with a non-empty reasoning_content of about 20 KiB", async () => {
  await withRelay(async (root, port) => {
    await request(port, firstRequest);
    assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs, { reasoning: "r".repeat(20 * 1024) }))).status, 200);
    assert.equal(result(root).forwarded_request_count, 2);
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
});

test("deeply nested continuation is refused without crashing the relay", async () => {
  let deep = {};
  for (let index = 0; index < 2000; index += 1) deep = { x: deep };
  const first = { ...firstRequest, extra: deep };
  const second = { ...retryRequest(firstRequest, wrappedArgs), extra: deep };
  await withRelay(async (root, port) => {
    assert.equal((await request(port, first)).status, 200);
    assert.equal((await request(port, second)).status, 409);
    assert.equal(result(root).retry_refused_reason, "RETRY_CONTINUATION_MISMATCH");
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
});

test("first request rejects continuation-only messages", async t => {
  for (const message of [{ role: "assistant", content: null }, { role: "tool", content: "error", tool_call_id: "call_1" }]) await t.test(message.role, async () => {
    await withRelay(async (root, port) => {
      const invalid = { ...firstRequest, messages: [message] };
      assert.equal((await request(port, invalid)).status, 400);
      assertResult(root, { received: 1, forwarded: 0, status: "BLOCKED_REQUEST_CONTRACT", outcomes: ["BLOCKED_REQUEST_CONTRACT"], reason: null });
      assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 409);
      assert.equal(result(root).retry_refused_reason, "RETRY_FIRST_RESPONSE_NOT_ACCEPTED");
    });
  });
});

test("retry is refused after the retry window", async () => {
  await withRelay(async (root, port) => {
    await request(port, firstRequest);
    await new Promise(resolve => setTimeout(resolve, 90));
    assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 409);
    assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_WINDOW_EXPIRED" });
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid", ADVISORY_RELAY_RETRY_WINDOW_MS: "50" });
});

test("retry body over requestLimit or invalid JSON is blocked as invalid_request", async t => {
  for (const [name, second] of [
    ["oversized body", retryRequest(firstRequest, wrappedArgs, { reasoning: "r".repeat(524288) })],
    ["invalid JSON", "{"],
  ]) await t.test(name, async () => {
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      const reply = await request(port, second);
      assert.equal(reply.status, 400);
      assert.equal(JSON.parse(reply.body).error.type, "invalid_request");
      assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_INVALID_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_INVALID_REQUEST"], reason: "RETRY_BODY_INVALID" });
    }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
  });
});

test("retry window override is bounded and live mode ignores it", async t => {
  await t.test("preflight cannot raise the window", async () => {
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 200);
      assert.equal(result(root).forwarded_request_count, 2);
    }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid", ADVISORY_RELAY_RETRY_WINDOW_MS: "999999999" });
  });
  await t.test("large preflight override is capped at 300000", async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "ltv-ai-relay-clock-"));
    const clockPath = path.join(root, "clock.cjs");
    fs.writeFileSync(clockPath, "const http = require('node:http'); const create = http.createServer; http.createServer = function (handler) { let ordinal = 0; return create.call(this, (request, response) => { ordinal += 1; const now = Date.now; Date.now = () => ordinal === 1 ? 0 : 300001; try { return handler(request, response); } finally { Date.now = now; } }); };");
    const port = await freePort();
    const relay = launch(root, port, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid", ADVISORY_RELAY_RETRY_WINDOW_MS: "999999999" }, ["--require", clockPath]);
    try {
      await waitReady(relay);
      assert.equal((await request(port, firstRequest)).status, 200);
      assert.equal((await request(port, retryRequest(firstRequest, wrappedArgs))).status, 409);
      assert.equal(result(root).retry_refused_reason, "RETRY_WINDOW_EXPIRED");
    } finally {
      await stop(relay);
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
  await t.test("live ignores invalid window and starts", async () => {
    await withRelay(async () => {}, { ADVISORY_RELAY_MODE: "live", OPENAI_API_KEY: "fake-test-key", ADVISORY_RELAY_RETRY_WINDOW_MS: "invalid" });
  });
  for (const key of ["ADVISORY_RELAY_PREFLIGHT_SCENARIO", "ADVISORY_RELAY_PREFLIGHT_RESPONSES"]) await t.test(`${key} is rejected in live mode`, async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "ltv-ai-relay-startup-"));
    const relay = launch(root, await freePort(), { ADVISORY_RELAY_MODE: "live", OPENAI_API_KEY: "fake-test-key", [key]: key.endsWith("SCENARIO") ? "wrapped-then-valid" : "[]" });
    try {
      const exit = await Promise.race([relay.exited, new Promise(resolve => setTimeout(() => resolve(null), 400))]);
      assert.notEqual(exit, null, "relay unexpectedly started");
      assert.notEqual(exit, 0);
    } finally {
      await stop(relay);
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
  for (const value of ["-1", "1.5", "bad"]) await t.test(`invalid preflight window ${value}`, async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "ltv-ai-relay-startup-"));
    const relay = launch(root, await freePort(), { ADVISORY_RELAY_RETRY_WINDOW_MS: value });
    try {
      const exit = await Promise.race([relay.exited, new Promise(resolve => setTimeout(() => resolve(null), 400))]);
      assert.notEqual(exit, null, "relay unexpectedly started");
      assert.notEqual(exit, 0);
    } finally {
      await stop(relay);
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
});

test("top-level constants equal ai-advice-output.schema.json", async t => {
  const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
  const valid = { ...advice, schema_version: schema.properties.schema_version.const };
  for (const key of schema.required) await t.test(`missing ${key}`, async () => {
    const invalid = structuredClone(valid);
    delete invalid[key];
    const args = JSON.stringify(invalid);
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      assert.equal((await request(port, retryRequest(firstRequest, args))).status, 200);
      assert.equal(result(root).forwarded_request_count, 2);
    }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(sse(args))]) });
  });
  for (const invalid of [{ ...valid, extra: true }, { ...valid, schema_version: "wrong" }]) await t.test("invalid top-level object", async () => {
    const args = JSON.stringify(invalid);
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      assert.equal((await request(port, retryRequest(firstRequest, args))).status, 200);
      assert.equal(result(root).forwarded_request_count, 2);
    }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(sse(args))]) });
  });
  const deep = structuredClone(valid);
  deep.hypotheses[0].rank = "bad";
  const args = JSON.stringify(deep);
  await withRelay(async (root, port) => {
    await request(port, firstRequest);
    assert.equal((await request(port, retryRequest(firstRequest, args))).status, 409);
    assert.equal(result(root).retry_refused_reason, "RETRY_NOT_TOP_LEVEL_SCHEMA");
  }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify([provider(sse(args))]) });
});

test("wire observation is written per request ordinal", async () => {
  await withRelay(async (root, port) => {
    await request(port, firstRequest);
    await request(port, retryRequest(firstRequest, wrappedArgs));
    for (const ordinal of [1, 2]) {
      const wire = JSON.parse(fs.readFileSync(path.join(root, `wire-observation-${ordinal}.json`), "utf8"));
      assert.equal(wire.model_after, model);
      assert.equal(wire.n_after, 1);
      assert.equal(wire.stream, true);
      assert.equal(wire.max_tokens_after_present, false);
      assert.deepEqual(wire.tool_names, ["structured_output"]);
    }
    assert.equal(fs.existsSync(path.join(root, "wire-observation.json")), false);
  }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
});

test("retry is refused when any non-message top-level field differs from the first request", async t => {
  const mutations = {
    temperature: body => { body.temperature = 1; },
    stream_options: body => { body.stream_options.include_usage = false; },
    tools: body => { body.tools[0].function.parameters.type = "string"; },
    max_tokens: body => { body.max_tokens = 1; },
    model: body => { body.model = "changed"; },
    "added unknown field": body => { body.unknown = true; },
    "removed field": body => { delete body.temperature; },
  };
  for (const [name, mutate] of Object.entries(mutations)) await t.test(name, async () => {
    await withRelay(async (root, port) => {
      await request(port, firstRequest);
      const second = retryRequest(firstRequest, wrappedArgs);
      mutate(second);
      assert.equal((await request(port, second)).status, 409);
      assertResult(root, { received: 2, forwarded: 1, status: "BLOCKED_ADDITIONAL_REQUEST", outcomes: ["FORWARDED_STRUCTURED_OUTPUT", "BLOCKED_ADDITIONAL_REQUEST"], reason: "RETRY_CONTINUATION_MISMATCH" });
    }, { ADVISORY_RELAY_PREFLIGHT_SCENARIO: "wrapped-then-valid" });
  });
});

test("existing forwarding and request contract behavior", async t => {
  await t.test("untrusted model, n, stream, and max_tokens are rewritten", async () => {
    await withRelay(async (root, port) => {
      const untrusted = { ...firstRequest, model: "untrusted-client-model", n: 3, stream: false, max_tokens: 99999 };
      const reply = await request(port, untrusted);
      assert.equal(reply.status, 200);
      assert.match(reply.body, /structured_output/);
      assert.match(reply.body, /\[DONE\]/);
      const wire = JSON.parse(fs.readFileSync(path.join(root, "wire-observation-1.json"), "utf8"));
      assert.equal(wire.model_before, "untrusted-client-model");
      assert.equal(wire.model_after, model);
      assert.equal(wire.n_before, 3);
      assert.equal(wire.n_after, 1);
      assert.equal(wire.stream, true);
      assert.equal(wire.max_tokens_before_present, true);
      assert.equal(wire.max_tokens_after_present, false);
      assert.deepEqual(wire.tool_names, ["structured_output"]);
      assert.equal((await request(port, untrusted)).status, 409);
      assert.equal(result(root).retry_refused_reason, "RETRY_NOT_TOP_LEVEL_SCHEMA");
    });
  });
  await t.test("tools [] is rejected", async () => {
    await withRelay(async (root, port) => {
      assert.equal((await request(port, { ...firstRequest, tools: [] })).status, 400);
      assert.equal(result(root).status, "BLOCKED_REQUEST_CONTRACT");
    });
  });
});

test("shared retry vectors", async t => {
  const fixture = JSON.parse(fs.readFileSync(vectorsPath, "utf8"));
  assert.equal(fixture.schema_version, "advisory-ai-relay-retry-vectors.v1");
  assert.ok(fixture.vectors.length >= 8 && fixture.vectors.length <= 10);
  for (const vector of fixture.vectors) await t.test(vector.name, async () => {
    await withRelay(async (root, port) => {
      await request(port, vector.first_request);
      const second = vector.second_request === null ? null : await request(port, vector.second_request);
      assert.equal(second?.status ?? null, vector.expected.second_http_status);
      assertResult(root, {
        received: vector.expected.received_request_count,
        forwarded: vector.expected.forwarded_request_count,
        status: vector.expected.status,
        outcomes: vector.expected.outcomes,
        reason: vector.expected.retry_refused_reason,
      });
    }, { ADVISORY_RELAY_PREFLIGHT_RESPONSES: JSON.stringify(vector.provider_responses) });
  });
});
