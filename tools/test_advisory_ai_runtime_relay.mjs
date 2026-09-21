import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

const relayPath = path.join(import.meta.dirname, "advisory_ai_runtime_relay.mjs");

async function withRelay(run) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ltv-ai-relay-test-"));
  const ready = path.join(root, "ready");
  const port = 18_181;
  const child = spawn(process.execPath, [relayPath], {
    env: {
      ADVISORY_RELAY_MODE: "preflight",
      ADVISORY_RELAY_OUTPUT_ROOT: root,
      ADVISORY_RELAY_READY_PATH: ready,
      ADVISORY_RELAY_PORT: String(port),
    },
    stdio: ["ignore", "ignore", "pipe"],
  });
  let stderr = "";
  child.stderr.on("data", chunk => { stderr += chunk; });
  try {
    const deadline = Date.now() + 5_000;
    while (!fs.existsSync(ready) && child.exitCode === null && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 25));
    }
    assert.equal(child.exitCode, null, stderr);
    assert.ok(fs.existsSync(ready), `relay did not become ready: ${stderr}`);
    await run(root, port);
  } finally {
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await new Promise(resolve => child.once("exit", resolve));
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function request(port, body) {
  return new Promise((resolve, reject) => {
    const encoded = Buffer.from(JSON.stringify(body));
    const call = http.request({
      host: "127.0.0.1",
      port,
      path: "/v1/chat/completions",
      method: "POST",
      headers: { "content-type": "application/json", "content-length": encoded.length },
    }, response => {
      const chunks = [];
      response.on("data", chunk => chunks.push(chunk));
      response.on("end", () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
    });
    call.on("error", reject);
    call.end(encoded);
  });
}

const validRequest = {
  model: "untrusted-client-model",
  n: 3,
  stream: false,
  max_tokens: 99_999,
  messages: [{ role: "user", content: "bounded evidence" }],
  tools: [{ type: "function", function: { name: "structured_output", parameters: {} } }],
};

await withRelay(async (root, port) => {
  const response = await request(port, validRequest);
  assert.equal(response.status, 200);
  assert.match(response.body, /structured_output/);
  assert.match(response.body, /\[DONE\]/);

  const wire = JSON.parse(fs.readFileSync(path.join(root, "wire-observation.json"), "utf8"));
  assert.equal(wire.model_after, "deepseek-v4-flash-0731");
  assert.equal(wire.n_after, 1);
  assert.equal(wire.stream, true);
  assert.equal(wire.max_tokens_after_present, false);
  assert.deepEqual(wire.tool_names, ["structured_output"]);

  const duplicate = await request(port, validRequest);
  assert.equal(duplicate.status, 409);
});

await withRelay(async (_root, port) => {
  const invalid = await request(port, { ...validRequest, tools: [] });
  assert.equal(invalid.status, 400);
});

console.log("advisory runtime relay checks passed");
