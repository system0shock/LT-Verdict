import fs from "node:fs";
import http from "node:http";
import https from "node:https";

const mode = process.env.ADVISORY_RELAY_MODE;
if (mode !== "preflight" && mode !== "live") throw new Error("invalid relay mode");

const fixedModel = "deepseek-v4-flash-0731";
const requestLimit = 524_288;
const responseLimit = 67_108_864;
const outputRoot = process.env.ADVISORY_RELAY_OUTPUT_ROOT ?? "/out";
const readyPath = process.env.ADVISORY_RELAY_READY_PATH ?? "/out/relay-ready";
const port = Number(process.env.ADVISORY_RELAY_PORT ?? 18_080);
const apiKey = mode === "live" ? process.env.OPENAI_API_KEY : null;
if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error("invalid relay port");
if (mode === "live" && !apiKey) throw new Error("OPENAI_API_KEY is required");

let requestCount = 0;

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

function messagesAreTextOnly(messages) {
  return Array.isArray(messages) && messages.length > 0 && messages.every(message => {
    if (!message || typeof message !== "object") return false;
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

function exactForwardingPolicy(body) {
  return body.model === fixedModel && body.n === 1 && body.stream === true &&
    body.parallel_tool_calls === false && messagesAreTextOnly(body.messages) &&
    toolNames(body).length === 1 && toolNames(body)[0] === "structured_output" &&
    !["max_tokens", "max_completion_tokens", "max_output_tokens", "best_of", "provider"]
      .some(field => Object.hasOwn(body, field));
}

function writeObservation(before, after) {
  fs.writeFileSync(`${outputRoot}/wire-observation.json`, JSON.stringify({
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

function writeResult(status) {
  fs.writeFileSync(`${outputRoot}/relay-result.json`, JSON.stringify({
    schema_version: "advisory-ai-runtime-relay.v1",
    status,
    request_count: requestCount,
  }));
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

function fakeResponse() {
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
          function: { name: "structured_output", arguments: JSON.stringify(fakeAdvice) },
        }],
      },
      finish_reason: "tool_calls",
    }],
    usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
  };
  return Buffer.from(`data: ${JSON.stringify(frame)}\n\ndata: [DONE]\n\n`);
}

function inspectProviderResponse(status, contentType, body) {
  if (status !== 200 || !String(contentType).toLowerCase().includes("text/event-stream")) return false;
  const calls = new Map();
  let done = false;
  try {
    for (const line of body.toString("utf8").split(/\r?\n/)) {
      if (!line.startsWith("data:")) continue;
      const data = line.slice(5).trim();
      if (!data) continue;
      if (data === "[DONE]") {
        if (done) return false;
        done = true;
        continue;
      }
      if (done) return false;
      const frame = JSON.parse(data);
      if (frame.model !== undefined && frame.model !== fixedModel) return false;
      for (const choice of Array.isArray(frame.choices) ? frame.choices : []) {
        if (choice?.index !== 0) return false;
        for (const fragments of [choice?.delta?.tool_calls, choice?.message?.tool_calls]) {
          if (fragments === undefined || fragments === null) continue;
          if (!Array.isArray(fragments)) return false;
          for (const call of fragments) {
            if (!call || call.index !== 0) return false;
            const name = call.function?.name;
            const args = call.function?.arguments;
            if (name !== undefined && name !== null && typeof name !== "string") return false;
            if (args !== undefined && args !== null && typeof args !== "string") return false;
            const meaningful = (typeof name === "string" && name.length > 0) ||
              (typeof args === "string" && args.length > 0) ||
              (typeof call.id === "string" && call.id.length > 0);
            if (!meaningful) continue;
            const current = calls.get(0) ?? { name: null };
            if (typeof name === "string" && name.length > 0) {
              if (current.name !== null && current.name !== name) return false;
              current.name = name;
            }
            calls.set(0, current);
          }
        }
      }
    }
  } catch {
    return false;
  }
  return done && calls.size === 1 && calls.get(0)?.name === "structured_output";
}

function callProvider(body) {
  return new Promise((resolve, reject) => {
    const encoded = Buffer.from(JSON.stringify(body));
    const request = https.request({
      hostname: "token-plan.ap-southeast-1.maas.aliyuncs.com",
      port: 443,
      path: "/compatible-mode/v1/chat/completions",
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
  requestCount += 1;
  if (requestCount > 1) {
    request.resume();
    writeResult("BLOCKED_ADDITIONAL_REQUEST");
    sendJson(response, 409, "additional_request_blocked");
    return;
  }
  let incoming;
  try {
    incoming = JSON.parse((await readBounded(request, requestLimit)).toString("utf8"));
  } catch {
    writeResult("BLOCKED_INVALID_REQUEST");
    sendJson(response, 400, "invalid_request");
    return;
  }
  if (!messagesAreTextOnly(incoming.messages) || toolNames(incoming).length !== 1 || toolNames(incoming)[0] !== "structured_output") {
    writeResult("BLOCKED_REQUEST_CONTRACT");
    sendJson(response, 400, "request_contract");
    return;
  }
  const forwarded = forwardBody(incoming);
  writeObservation(incoming, forwarded);
  if (!exactForwardingPolicy(forwarded)) {
    writeResult("BLOCKED_FORWARDING_POLICY");
    sendJson(response, 400, "forwarding_policy");
    return;
  }
  try {
    const provider = mode === "preflight"
      ? { status: 200, contentType: "text/event-stream", body: fakeResponse() }
      : await callProvider(forwarded);
    if (!inspectProviderResponse(provider.status, provider.contentType, provider.body)) {
      writeResult("BLOCKED_PROVIDER_RESPONSE");
      sendJson(response, 502, "provider_response");
      return;
    }
    writeResult("FORWARDED_STRUCTURED_OUTPUT");
    response.writeHead(200, { "content-type": provider.contentType, "content-length": provider.body.length });
    response.end(provider.body);
  } catch {
    writeResult("BLOCKED_PROVIDER_TRANSPORT");
    sendJson(response, 502, "provider_transport");
  }
});

server.listen(port, "0.0.0.0", () => fs.writeFileSync(readyPath, "ready"));
