// ADR 0027: JSON and hash helper of advisory_ai_runtime_local.sh (the host may have no jq).
//   hash <file>                    prints the lower case SHA-256 of the file
//   result <stdout-file> <output>  parses the stdout of the CLI (--output-format=json), checks the init event and writes the advice
//                                  prints "OK <version> <model>" or "BAD"
// The advice is taken like Save-QwenAdvice of advisory_ai_runtime.ps1 does: an element that is the advice itself, or the
// `result` member (object or JSON text) of an element, with schema_version ai-advice-output.v1; at most 131072 bytes.
import crypto from "node:crypto";
import fs from "node:fs";

const OUTPUT_LIMIT = 131_072;
const VERSION = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$/;
const MODEL_SLUG = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/;
const [command, first, second] = process.argv.slice(2);

function advice(value) {
  if (value && typeof value === "object" && !Array.isArray(value) && value.schema_version === "ai-advice-output.v1") return value;
  return null;
}

if (command === "hash") {
  process.stdout.write(`${crypto.createHash("sha256").update(fs.readFileSync(first)).digest("hex")}\n`);
} else if (command === "result") {
  let line = "BAD";
  try {
    const parsed = JSON.parse(fs.readFileSync(first, "utf8").trim());
    const events = Array.isArray(parsed) ? parsed : [parsed];
    // The CLI must report the tool set the launcher asked for: the one structured_output tool and no MCP servers.
    const init = events.find((event) => event?.type === "system" && event?.subtype === "init");
    const version = typeof init?.qwen_code_version === "string" && VERSION.test(init.qwen_code_version) ? init.qwen_code_version : null;
    const tools = Array.isArray(init?.tools) && init.tools.length === 1 && init.tools[0] === "structured_output";
    const mcp = Array.isArray(init?.mcp_servers) && init.mcp_servers.length === 0;
    let found = null;
    for (const event of events) {
      found = advice(event);
      if (!found && event && typeof event === "object" && event.result != null) {
        let candidate = event.result;
        if (typeof candidate === "string") {
          try { candidate = JSON.parse(candidate); } catch { continue; }
        }
        found = advice(candidate);
      }
      if (found) break;
    }
    if (version && tools && mcp && found) {
      const text = JSON.stringify(found);
      if (Buffer.byteLength(text) <= OUTPUT_LIMIT) {
        fs.writeFileSync(second, text);
        const model = typeof init.model === "string" && MODEL_SLUG.test(init.model) && !init.model.includes("..") && !init.model.includes("//")
          ? init.model : "-";
        line = `OK ${version} ${model}`;
      }
    }
  } catch { /* BAD */ }
  process.stdout.write(`${line}\n`);
} else {
  process.exit(2);
}
