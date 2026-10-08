"""Prompt and request builders for stages S0, S1, S2 (S3 is deferred, S4 is offline).

S0: approximation of the product request (system-prompt.md + evidence JSON as user message,
    single tool structured_output with the ai-advice-output schema). Not a byte copy of the
    Qwen Code 0.21.1 request: tool description, tool_choice and stdin wrapping are unknown.
S1: S0 + domain invariants appended to the system prompt.
S2: S1 + derived-facts addendum in the system prompt + a deterministic derived-facts block
    appended to the user message after the evidence JSON.
"""
import json
import os
import re

from .common import MODEL, PROMPT_DIR, SCHEMA_PATH, SYSTEM_PROMPT_PATH, read_text, sha256_bytes

STAGES = ["S0", "S1", "S2"]
MAX_SYSTEM_PROMPT_BYTES = 16384
TOOL_DESCRIPTION = "Return the final structured output matching the required JSON schema."
_SAFE = re.compile(r"^[A-Za-z0-9_.:\-]{1,80}$")


def _safe(v):
    return v if isinstance(v, str) and _SAFE.match(v) else "<elided>"


def base_system_prompt():
    # the product passes "$(cat system-prompt.md)": trailing newlines are stripped
    return read_text(SYSTEM_PROMPT_PATH).rstrip("\n")


def system_prompt(stage):
    parts = [base_system_prompt()]
    if stage in ("S1", "S2"):
        parts.append(read_text(os.path.join(PROMPT_DIR, "s1_invariants.txt")).strip("\n"))
    if stage == "S2":
        parts.append(read_text(os.path.join(PROMPT_DIR, "s2_addendum.txt")).strip("\n"))
    text = "\n\n".join(parts)
    assert len(text.encode("utf-8")) <= MAX_SYSTEM_PROMPT_BYTES
    return text


# ---------------------------------------------------------------- derived facts (S2)

def _j(v):
    return "null" if v is None else v


def _refs(refs):
    short = [r.replace("analysis-result.json#/", "") for r in refs]
    if len(short) > 10:
        return ", ".join(short[:5]) + ", ..., " + ", ".join(short[-2:])
    return ", ".join(short)


def derive(doc):
    ev = [(e["ref"], e["value"]) for e in doc.get("evidence", [])]
    L = ["LT VERDICT DERIVED FACTS",
         "(computed by arithmetic and lookup from the JSON above; refs are evidence refs; not model output)"]

    overall = [(r, v) for r, v in ev if v.get("type") == "metric_summary" and v.get("scope", {}).get("kind") == "overall"]
    txn = [(r, v) for r, v in ev if v.get("type") == "metric_summary" and v.get("scope", {}).get("kind") == "transaction"]
    L.append("")
    L.append("1. Scope counts (metric_summary entries, whole-run scope)")
    for r, v in overall:
        L.append("   overall: sample_count=%s error_count=%s [%s]" % (v.get("sample_count"), v.get("error_count"), r.split("#/")[-1]))
    if txn:
        s = sum(v.get("sample_count", 0) for _, v in txn)
        e = sum(v.get("error_count", 0) for _, v in txn)
        L.append("   transaction scope: %d entries [%s]: sum(sample_count)=%d sum(error_count)=%d" % (len(txn), _refs([r for r, _ in txn]), s, e))
        if overall:
            oc = overall[0][1].get("sample_count")
            if isinstance(oc, int):
                L.append("   sum of transaction sample_count %s overall sample_count%s" % (
                    "EQUALS" if s == oc else "DIFFERS from", "" if s == oc else " (difference %d)" % (oc - s)))
        with_err = [(r, v.get("error_count")) for r, v in txn if (v.get("error_count") or 0) > 0]
        if with_err:
            L.append("   transaction-scope entries with error_count>0: " + "; ".join("%s error_count=%s" % (r.split("#/")[-1], c) for r, c in with_err))
        else:
            L.append("   transaction-scope entries with error_count>0: none")
    else:
        L.append("   transaction scope: no entries")

    L.append("")
    L.append("2. Windows (a window covers only part of the run; counts inside a window are a different quantity from whole-run counts)")
    binding = [(r, v) for r, v in ev if v.get("type") == "resource_binding"]
    for r, v in binding:
        rf, rt = v.get("run_from_epoch_ms"), v.get("run_to_epoch_ms")
        ef, et = v.get("evaluation_from_epoch_ms"), v.get("evaluation_to_epoch_ms")
        if all(isinstance(x, int) for x in (rf, rt, ef, et)) and rt > rf:
            L.append("   run interval %d ms; evaluation window %d ms (%.1f%% of run); dropped leading %s ms, trailing %s ms; mode=%s [%s]" % (
                rt - rf, et - ef, 100.0 * (et - ef) / (rt - rf), v.get("dropped_leading_millis"), v.get("dropped_trailing_millis"),
                _safe(v.get("mode")), r.split("#/")[-1]))
        L.append("   clock_alignment=%s" % _safe(v.get("clock_alignment")))
    wids = sorted({_safe(v.get("window_id")) for _, v in ev if v.get("window_id")})
    L.append("   window ids referenced: %s" % (", ".join(wids) if wids else "none"))
    oc = overall[0][1].get("sample_count") if overall else None
    for r, v in ev:
        if v.get("type") == "policy_check":
            obs = v.get("observed") or {}
            den = obs.get("denominator")
            wid = v.get("window_id")
            line = "   policy_check [%s] rule=%s status=%s observed=%s/%s" % (r.split("#/")[-1], _safe(v.get("rule_id")), _safe(v.get("status")), obs.get("numerator"), den)
            if wid:
                line += " window=%s (count inside the window)" % _safe(wid)
                if isinstance(oc, int) and isinstance(den, int) and oc != den:
                    line += "; whole-run overall sample_count=%d, so %d samples lie outside this window" % (oc, oc - den)
            else:
                line += " (whole run, no window id)"
            L.append(line)
        elif v.get("type") == "window_metric_summary":
            L.append("   window_metric_summary [%s] window=%s error_count=%s denominator=%s" % (
                r.split("#/")[-1], _safe(v.get("window_id")), v.get("error_count"), (v.get("error_rate_ratio") or {}).get("denominator")))

    cap = doc.get("capacity_summary")
    L.append("")
    L.append("3. Capacity")
    if cap:
        c = cap["value"]
        L.append("   bound_type=%s lower_inclusive=%s upper_exclusive=%s load_axis=%s policy_verdict=%s reasons=%s" % (
            _safe(c.get("bound_type")), _j(c.get("lower_inclusive")), _j(c.get("upper_exclusive")), _safe(c.get("load_axis")),
            _safe(c.get("policy_verdict")), ",".join(_safe(x) for x in c.get("reasons", [])) or "none"))
        L.append("   capacity_knee=%s knee_reason=%s (fixed product limitation; the bound is computed independently of the knee)" % (_j(c.get("capacity_knee")), _safe(c.get("knee_reason"))))
        for st in c.get("stages", []):
            L.append("   stage %s: target=%s achieved=%s verdict=%s reasons=%s complete_bins=%s/%s verified_bound_load=%s" % (
                _safe(st.get("id")), st.get("target"), st.get("achieved"), _safe(st.get("verdict")),
                ",".join(_safe(x) for x in st.get("reasons", [])) or "none", st.get("complete_bins"), st.get("expected_bins"), _j(st.get("verified_bound_load"))))
        L.append("   stages listed: %d" % len(c.get("stages", [])))
    else:
        L.append("   no capacity_summary in the input")
    for r, v in ev:
        if v.get("type") == "resource_policy_check":
            L.append("   resource_policy_check [%s] rule=%s series=%s effect=%s status=%s window=%s" % (
                r.split("#/")[-1], _safe(v.get("rule_id")), _safe(v.get("series_id")), _safe(v.get("effect")), _safe(v.get("status")), _safe(v.get("window_id"))))
    gaps = [(r, v) for r, v in ev if v.get("type") == "resource_summary" and (v.get("missing_cells") or 0) > 0]
    if gaps:
        L.append("   resource_summary entries with missing_cells>0: " + "; ".join("%s missing=%s of expected=%s" % (r.split("#/")[-1], v.get("missing_cells"), v.get("expected_cells")) for r, v in gaps))

    types = {}
    for _, v in ev:
        types[v.get("type")] = types.get(v.get("type"), 0) + 1
    L.append("")
    L.append("4. Inventory of the input")
    L.append("   present evidence types: " + ", ".join("%s x%d" % (_safe(k), n) for k, n in sorted(types.items(), key=lambda kv: str(kv[0]))))
    os_refs = [r for r, v in ev if v.get("type") == "opensearch_errors"]
    if os_refs:
        for r, v in ev:
            if v.get("type") == "opensearch_errors":
                groups = v.get("groups", [])
                L.append("   OpenSearch error profile PRESENT [%s]: %d error groups, total count %s, sampled messages are included; this is not a full log body" % (
                    r.split("#/")[-1], len(groups), sum(g.get("count", 0) for g in groups if isinstance(g.get("count"), int))))
    else:
        L.append("   OpenSearch error profile: not present")
    L.append("   resource series entities: " + (", ".join(sorted({_safe(v.get("entity")) for _, v in ev if v.get("type") == "resource_summary"})) or "none"))
    L.append("   STAGE_UNSPECIFIED present: %s; load stage definitions present only as capacity stages listed in section 3" % ("yes" if "STAGE_UNSPECIFIED" in json.dumps(doc) else "no"))
    L.append("   NOT in the input: individual samples, the number of samples that hit a reported max latency, call graph or topology of the system under test, log bodies, traces, release or configuration data")
    return "\n".join(L)


def user_message(stage, evidence_bytes):
    text = evidence_bytes.decode("utf-8")
    if stage == "S2":
        text = text + "\n\n" + derive(json.loads(text))
    return text


def build_request(stage, evidence_bytes, model=MODEL):
    schema = json.loads(read_text(SCHEMA_PATH))
    return {
        "model": model,
        "n": 1,
        "stream": False,
        "parallel_tool_calls": False,
        "messages": [
            {"role": "system", "content": system_prompt(stage)},
            {"role": "user", "content": user_message(stage, evidence_bytes)},
        ],
        "tools": [{"type": "function", "function": {"name": "structured_output", "description": TOOL_DESCRIPTION, "parameters": schema}}],
    }


def request_sha256(body):
    return sha256_bytes(json.dumps(body, sort_keys=True, ensure_ascii=False).encode("utf-8"))
