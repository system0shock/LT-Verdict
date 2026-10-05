import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import { isDeepStrictEqual } from "node:util";

const mode = process.env.ADVISORY_RELAY_MODE;
if (mode !== "preflight" && mode !== "live") throw new Error("invalid relay mode");

// ADR 0023, D4: one model and one destination per process, both fixed by the operator's configuration at start.
// The launcher passes them in the environment; without them the built-in model and endpoint apply.
const BUILT_IN_MODEL = "deepseek-v4-flash-0731";
const BUILT_IN_UPSTREAM = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions";
const MODEL_SLUG = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/;
const HOST_NAME = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?){0,126}|\[(?=[0-9a-f:.]*:[0-9a-f:.]*:)(?=[0-9a-f:.]*[0-9a-f])[0-9a-f:.]{2,45}\])$/;
const UPSTREAM_FORM = /^(https?):\/\/(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?(\/[^?#]*)?$/;

function parseModel(text) {
  if (!MODEL_SLUG.test(text) || text.includes("..") || text.includes("//")) throw new Error("invalid relay model");
  return text;
}

function parseUpstream(text, httpAllowed) {
  // Printable ASCII only, none of the characters that could break quoting; query and fragment are not part of the address.
  if (text.length > 512 || !/^[\x21-\x7E]+$/.test(text) || /["\\`^|<>{}]/.test(text)) throw new Error("invalid relay upstream");
  const match = UPSTREAM_FORM.exec(text);
  if (!match || (match[1] === "http" && !httpAllowed)) throw new Error("invalid relay upstream");
  const hostname = match[2].toLowerCase();
  const port = match[3] === undefined ? (match[1] === "https" ? 443 : 80) : Number(match[3]);
  if (!HOST_NAME.test(hostname) || !Number.isInteger(port) || port < 1 || port > 65_535) throw new Error("invalid relay upstream");
  return { scheme: match[1], hostname, port, path: match[4] ?? "/" };
}

const allowHttpEnv = process.env.ADVISORY_RELAY_ALLOW_HTTP;
if (allowHttpEnv !== undefined && allowHttpEnv !== "1") throw new Error("invalid relay http permission");
const fixedModel = parseModel(process.env.ADVISORY_RELAY_MODEL ?? BUILT_IN_MODEL);
const upstream = parseUpstream(process.env.ADVISORY_RELAY_UPSTREAM ?? BUILT_IN_UPSTREAM, allowHttpEnv === "1");
const requestLimit = 524_288;
const responseLimit = 67_108_864;
const outputRoot = process.env.ADVISORY_RELAY_OUTPUT_ROOT ?? "/out";
const readyPath = process.env.ADVISORY_RELAY_READY_PATH ?? "/out/relay-ready";
const port = Number(process.env.ADVISORY_RELAY_PORT ?? 18_080);
const apiKey = mode === "live" ? process.env.OPENAI_API_KEY : null;
const TOP_LEVEL_KEYS = ["caveats", "hypotheses", "recommendations", "schema_version", "summary"];
const retryWindowEnv = process.env.ADVISORY_RELAY_RETRY_WINDOW_MS;
const retryWindowMs = mode === "preflight" && retryWindowEnv !== undefined
  ? Math.min(Number(retryWindowEnv), 300_000) : 300_000;
const preflightScenario = process.env.ADVISORY_RELAY_PREFLIGHT_SCENARIO;
const preflightResponsesEnv = process.env.ADVISORY_RELAY_PREFLIGHT_RESPONSES;
if (mode === "preflight" && retryWindowEnv !== undefined &&
    (!/^\d+$/.test(retryWindowEnv) || !Number.isSafeInteger(Number(retryWindowEnv)))) {
  throw new Error("invalid retry window");
}
if (mode === "live" && (preflightScenario !== undefined || preflightResponsesEnv !== undefined)) {
  throw new Error("preflight stubs are not allowed in live mode");
}
if (preflightScenario !== undefined && !["wrapped-then-valid", "wrapped-twice", "wrapped-then-error", "deep-violation"].includes(preflightScenario)) {
  throw new Error("invalid preflight scenario");
}
let preflightResponses = null;
if (preflightResponsesEnv !== undefined) {
  preflightResponses = JSON.parse(preflightResponsesEnv);
  if (!Array.isArray(preflightResponses) || preflightResponses.length > 2 ||
      preflightResponses.some(item => !item || !Number.isInteger(item.status) ||
        typeof item.content_type !== "string" || typeof item.body !== "string")) {
    throw new Error("invalid preflight responses");
  }
}
if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error("invalid relay port");
if (mode === "live" && !apiKey) throw new Error("OPENAI_API_KEY is required");

let received = 0;
let forwarded = 0;
let firstStartedAt = 0;
let firstBody = null;
let firstCall = null;
let firstOk = false;
let refusedReason = null;
let upstreamHost = null;
const outcomes = [];

function sendJson(response, status, type) {
  const body = Buffer.from(JSON.stringify({ error: { type, message: "request rejected" } }));
  response.writeHead(status, { "content-type": "application/json", "content-length": body.length });
  response.end(body);
}

function readBounded(stream, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let exceeded = false;
    stream.on("data", chunk => {
      if (exceeded) return;
      size += chunk.length;
      if (size > limit) {
        exceeded = true;
        chunks.length = 0;
        reject(new Error("bounded_size_exceeded"));
      } else {
        chunks.push(chunk);
      }
    });
    stream.on("end", () => {
      if (!exceeded) resolve(Buffer.concat(chunks));
    });
    stream.on("error", reject);
  });
}

function messagesAreTextOnly(messages, continuation = false) {
  if (continuation) {
    if (!Array.isArray(messages) || messages.length < 3) return false;
    const assistant = messages.at(-2);
    const tool = messages.at(-1);
    return messagesAreTextOnly(messages.slice(0, -2)) &&
      assistant?.role === "assistant" && (assistant.content === null || assistant.content === "") &&
      Array.isArray(assistant.tool_calls) && assistant.tool_calls.length === 1 &&
      tool?.role === "tool" && typeof tool.content === "string";
  }
  return Array.isArray(messages) && messages.length > 0 && messages.every(message => {
    if (!message || typeof message !== "object") return false;
    if (!["system", "user", "assistant"].includes(message.role) || Object.hasOwn(message, "tool_calls")) return false;
    if (typeof message.content === "string") return true;
    return Array.isArray(message.content) && message.content.every(part =>
      part && typeof part === "object" && part.type === "text" && typeof part.text === "string"
    );
  });
}

function toolNames(body) {
  return Array.isArray(body.tools)
    ? body.tools.map(tool => tool?.function?.name).filter(name => typeof name === "string")
    : [];
}

function forwardBody(body) {
  const forwarded = { ...body, model: fixedModel, n: 1, stream: true, parallel_tool_calls: false };
  for (const field of [
    "models", "plugins", "web_search_options", "modalities", "audio", "image_config", "cache_control",
    "max_tokens", "max_completion_tokens", "max_output_tokens", "best_of", "reasoning", "reasoning_effort",
    "route", "transforms", "stop_server_tools_when", "debug", "trace", "service_tier", "provider",
  ]) delete forwarded[field];
  return forwarded;
}

function exactForwardingPolicy(body, continuation = false) {
  return body.model === fixedModel && body.n === 1 && body.stream === true &&
    body.parallel_tool_calls === false && messagesAreTextOnly(body.messages, continuation) &&
    toolNames(body).length === 1 && toolNames(body)[0] === "structured_output" &&
    !["max_tokens", "max_completion_tokens", "max_output_tokens", "best_of", "provider"]
      .some(field => Object.hasOwn(body, field));
}

function writeObservation(before, after, ordinal) {
  fs.writeFileSync(`${outputRoot}/wire-observation-${ordinal}.json`, JSON.stringify({
    schema_version: "advisory-ai-runtime-wire.v1",
    model_before: typeof before.model === "string" ? before.model : null,
    model_after: after.model,
    n_before: Number.isInteger(before.n) ? before.n : null,
    n_after: after.n,
    stream: after.stream,
    tool_names: toolNames(after),
    max_tokens_before_present: Object.hasOwn(before, "max_tokens"),
    max_tokens_after_present: Object.hasOwn(after, "max_tokens"),
  }));
}

function writeResult(outcome, ordinal) {
  outcomes[ordinal - 1] = outcome;
  const latest = outcomes.at(-1);
  fs.writeFileSync(`${outputRoot}/relay-result.json`, JSON.stringify({
    schema_version: "advisory-ai-runtime-relay.v1",
    status: latest === "FORWARDED_RETRY" ? "FORWARDED_STRUCTURED_OUTPUT" : latest,
    received_request_count: received,
    forwarded_request_count: forwarded,
    outcomes: outcomes.filter(Boolean),
    retry_refused_reason: refusedReason,
    upstream_host: upstreamHost,
    model_id: fixedModel,
  }));
}

function topLevelInvalid(argsText) {
  let value;
  try { value = JSON.parse(argsText); } catch { return true; }
  return value === null || typeof value !== "object" || Array.isArray(value) ||
    !isDeepStrictEqual(Object.keys(value).sort(), TOP_LEVEL_KEYS) ||
    value.schema_version !== "ai-advice-output.v1";
}

function isContinuation(first, call, incoming) {
  if (!incoming || typeof incoming !== "object" || Array.isArray(incoming) ||
      !Array.isArray(first.messages) || !Array.isArray(incoming.messages) ||
      incoming.messages.length !== first.messages.length + 2 ||
      !messagesAreTextOnly(incoming.messages, true) ||
      !isDeepStrictEqual(incoming.messages.slice(0, first.messages.length), first.messages)) return false;
  const assistant = incoming.messages.at(-2);
  const tool = incoming.messages.at(-1);
  const assistantKeys = ["content", "role", "tool_calls", ...(Object.hasOwn(assistant, "reasoning_content") ? ["reasoning_content"] : [])];
  if (!isDeepStrictEqual(Object.keys(assistant).sort(), assistantKeys.sort()) ||
      (Object.hasOwn(assistant, "reasoning_content") && typeof assistant.reasoning_content !== "string")) return false;
  const calls = assistant.tool_calls;
  if (!calls[0] || typeof calls[0] !== "object" ||
      !isDeepStrictEqual(Object.keys(calls[0]).sort(), ["function", "id", "type"]) ||
      calls[0].id !== call.id || calls[0].type !== "function" ||
      !calls[0].function || typeof calls[0].function !== "object" ||
      !isDeepStrictEqual(Object.keys(calls[0].function).sort(), ["arguments", "name"]) ||
      calls[0].function.name !== "structured_output" || calls[0].function.arguments !== call.args) return false;
  if (!isDeepStrictEqual(Object.keys(tool).sort(), ["content", "role", "tool_call_id"]) ||
      tool.tool_call_id !== call.id || tool.content.length === 0 ||
      Buffer.byteLength(tool.content) > 8192) return false;
  const { messages: firstMessages, ...firstRest } = first;
  const { messages: incomingMessages, ...incomingRest } = incoming;
  return isDeepStrictEqual(incomingRest, firstRest);
}

const fakeAdvice = {
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

function fakeResponse(advice = fakeAdvice) {
  const frame = {
    id: "lt-verdict-preflight",
    model: fixedModel,
    choices: [{
      index: 0,
      delta: {
        role: "assistant",
        tool_calls: [{
          index: 0,
          id: "lt-verdict-preflight-call",
          type: "function",
          function: { name: "structured_output", arguments: JSON.stringify(advice) },
        }],
      },
      finish_reason: "tool_calls",
    }],
    usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
  };
  return Buffer.from(`data: ${JSON.stringify(frame)}\n\ndata: [DONE]\n\n`);
}

function fakeWrappedResponse() {
  const args = JSON.stringify({ arguments: JSON.stringify(fakeAdvice) });
  const halfway = Math.ceil(args.length / 2);
  const frames = [args.slice(0, halfway), args.slice(halfway)].map((part, index) => ({
    model: fixedModel,
    choices: [{ index: 0, delta: { tool_calls: [{
      index: 0,
      ...(index === 0 ? { id: "call_1" } : {}),
      function: { ...(index === 0 ? { name: "structured_output" } : {}), arguments: part },
    }] } }],
  }));
  frames.push({ model: fixedModel, choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] });
  return Buffer.from(frames.map(frame => `data: ${JSON.stringify(frame)}\n\n`).join("") + "data: [DONE]\n\n");
}

function inspectProviderResponse(status, contentType, body) {
  const invalid = { ok: false, call: null };
  if (status !== 200 || !String(contentType).toLowerCase().includes("text/event-stream")) return invalid;
  const calls = new Map();
  let done = false;
  try {
    for (const line of body.toString("utf8").split(/\r?\n/)) {
      if (!line.startsWith("data:")) continue;
      const data = line.slice(5).trim();
      if (!data) continue;
      if (data === "[DONE]") {
        if (done) return invalid;
        done = true;
        continue;
      }
      if (done) return invalid;
      const frame = JSON.parse(data);
      if (frame.model !== undefined && frame.model !== fixedModel) return invalid;
      for (const choice of Array.isArray(frame.choices) ? frame.choices : []) {
        if (choice?.index !== 0) return invalid;
        for (const fragments of [choice?.delta?.tool_calls, choice?.message?.tool_calls]) {
          if (fragments === undefined || fragments === null) continue;
          if (!Array.isArray(fragments)) return invalid;
          for (const call of fragments) {
            if (!call || call.index !== 0) return invalid;
            if (call.id !== undefined && call.id !== null && typeof call.id !== "string") return invalid;
            const name = call.function?.name;
            const args = call.function?.arguments;
            if (name !== undefined && name !== null && typeof name !== "string") return invalid;
            if (args !== undefined && args !== null && typeof args !== "string") return invalid;
            const meaningful = (typeof name === "string" && name.length > 0) ||
              (typeof args === "string" && args.length > 0) ||
              (typeof call.id === "string" && call.id.length > 0);
            if (!meaningful) continue;
            const current = calls.get(0) ?? { id: null, name: null, args: "" };
            if (typeof call.id === "string" && call.id.length > 0) {
              if (current.id !== null && current.id !== call.id) return invalid;
              current.id = call.id;
            }
            if (typeof name === "string" && name.length > 0) {
              if (current.name !== null && current.name !== name) return invalid;
              current.name = name;
            }
            if (typeof args === "string") current.args += args;
            calls.set(0, current);
          }
        }
      }
    }
  } catch {
    return invalid;
  }
  const call = calls.get(0) ?? null;
  return { ok: done && calls.size === 1 && call?.name === "structured_output", call };
}

function callProvider(body) {
  return new Promise((resolve, reject) => {
    const encoded = Buffer.from(JSON.stringify(body));
    // The host the request is sent to, lower case with the port; it ends up in provenance (ADR 0023, D4).
    upstreamHost = `${upstream.hostname}:${upstream.port}`;
    const transport = upstream.scheme === "https" ? https : http;
    const request = transport.request({
      hostname: upstream.hostname.replace(/^\[|\]$/g, ""),
      port: upstream.port,
      path: upstream.path,
      method: "POST",
      headers: {
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json",
        "content-length": encoded.length,
        "user-agent": "lt-verdict-advisory/1",
      },
      timeout: 600_000,
    }, providerResponse => {
      const chunks = [];
      let size = 0;
      providerResponse.on("data", chunk => {
        size += chunk.length;
        if (size > responseLimit) providerResponse.destroy(new Error("response_too_large"));
        else chunks.push(chunk);
      });
      providerResponse.on("end", () => resolve({
        status: providerResponse.statusCode ?? 502,
        contentType: providerResponse.headers["content-type"] ?? "application/octet-stream",
        body: Buffer.concat(chunks),
      }));
      providerResponse.on("error", reject);
    });
    request.on("timeout", () => request.destroy(new Error("provider_timeout")));
    request.on("error", reject);
    request.end(encoded);
  });
}

const server = http.createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
    response.writeHead(404).end();
    return;
  }
  received += 1;
  const ordinal = received;
  if (ordinal === 1) firstStartedAt = Date.now();
  if (ordinal >= 3) {
    request.resume();
    refusedReason = "RETRY_LIMIT_REACHED";
    writeResult("BLOCKED_ADDITIONAL_REQUEST", ordinal);
    sendJson(response, 409, "additional_request_blocked");
    return;
  }
  if (ordinal === 2) {
    if (forwarded !== 1 || !firstOk || !firstCall ||
        typeof firstCall.id !== "string" || firstCall.id.length === 0 ||
        firstCall.name !== "structured_output" ||
        typeof firstCall.args !== "string" || firstCall.args.length === 0) {
      refusedReason = "RETRY_FIRST_RESPONSE_NOT_ACCEPTED";
    } else if (!topLevelInvalid(firstCall.args)) {
      refusedReason = "RETRY_NOT_TOP_LEVEL_SCHEMA";
    } else if (Date.now() - firstStartedAt > retryWindowMs) {
      refusedReason = "RETRY_WINDOW_EXPIRED";
    }
    if (refusedReason !== null) {
      request.resume();
      writeResult("BLOCKED_ADDITIONAL_REQUEST", ordinal);
      sendJson(response, 409, "additional_request_blocked");
      return;
    }
  }
  let incoming;
  try {
    incoming = JSON.parse((await readBounded(request, requestLimit)).toString("utf8"));
  } catch {
    if (ordinal === 2) refusedReason = "RETRY_BODY_INVALID";
    writeResult("BLOCKED_INVALID_REQUEST", ordinal);
    sendJson(response, 400, "invalid_request");
    return;
  }
  if (ordinal === 2) {
    let matches = false;
    try { matches = isContinuation(firstBody, firstCall, incoming); } catch {}
    if (!matches) {
      refusedReason = "RETRY_CONTINUATION_MISMATCH";
      writeResult("BLOCKED_ADDITIONAL_REQUEST", ordinal);
      sendJson(response, 409, "additional_request_blocked");
      return;
    }
  }
  if (ordinal === 1) firstBody = incoming;
  if (!incoming || typeof incoming !== "object" || Array.isArray(incoming) ||
      !messagesAreTextOnly(incoming.messages, ordinal === 2) ||
      toolNames(incoming).length !== 1 || toolNames(incoming)[0] !== "structured_output") {
    writeResult("BLOCKED_REQUEST_CONTRACT", ordinal);
    sendJson(response, 400, "request_contract");
    return;
  }
  const outgoing = forwardBody(incoming);
  writeObservation(incoming, outgoing, ordinal);
  if (!exactForwardingPolicy(outgoing, ordinal === 2)) {
    writeResult("BLOCKED_FORWARDING_POLICY", ordinal);
    sendJson(response, 400, "forwarding_policy");
    return;
  }
  forwarded += 1;
  try {
    let provider;
    if (mode === "preflight") {
      const stub = preflightResponses?.[forwarded - 1] ??
        (preflightScenario === "wrapped-then-error" && forwarded === 2
          ? { status: 503, content_type: "text/plain", body: "server error" } : null);
      provider = stub
        ? { status: stub.status, contentType: stub.content_type, body: Buffer.from(stub.body) }
        : { status: 200, contentType: "text/event-stream",
          body: preflightScenario === "wrapped-twice" ||
            (["wrapped-then-valid", "wrapped-then-error"].includes(preflightScenario) && forwarded === 1)
            ? fakeWrappedResponse() : fakeResponse(preflightScenario === "deep-violation" && forwarded === 1
              ? { ...fakeAdvice, hypotheses: [{ rank: 1, possible_explanation: "x", recommended_check: "x", evidence_refs: ["analysis-result.json#/run_validity"] }] } : fakeAdvice) };
    } else {
      provider = await callProvider(outgoing);
    }
    const inspected = inspectProviderResponse(provider.status, provider.contentType, provider.body);
    if (ordinal === 1) {
      firstOk = inspected.ok;
      firstCall = inspected.call;
    }
    if (!inspected.ok) {
      writeResult("BLOCKED_PROVIDER_RESPONSE", ordinal);
      sendJson(response, 502, "provider_response");
      return;
    }
    writeResult(ordinal === 2 ? "FORWARDED_RETRY" : "FORWARDED_STRUCTURED_OUTPUT", ordinal);
    response.writeHead(200, { "content-type": provider.contentType, "content-length": provider.body.length });
    response.end(provider.body);
  } catch {
    writeResult("BLOCKED_PROVIDER_TRANSPORT", ordinal);
    sendJson(response, 502, "provider_transport");
  }
});

server.listen(port, "0.0.0.0", () => fs.writeFileSync(readyPath, "ready"));
