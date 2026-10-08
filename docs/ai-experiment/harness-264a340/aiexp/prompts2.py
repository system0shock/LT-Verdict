"""Round v2 prompts. S0 and S1 are identical to v1 (aiexp.prompts). S2 is rebuilt:
the derived-facts block no longer states that transaction sums EQUAL the overall count and no longer
subtracts a window denominator from a whole-run count ("N samples lie outside this window").
The block is computed from the evidence only, so the reviewer receives the same block for every response.
"""
import json
import os

from . import prompts as p1
from .common import PROMPT_DIR, read_text

STAGES = ["S0", "S1", "S2"]
MAX_SYSTEM_PROMPT_BYTES = 16384


def system_prompt(stage):
    if stage in ("S0", "S1"):
        return p1.system_prompt(stage)
    parts = [p1.base_system_prompt(), read_text(os.path.join(PROMPT_DIR, "s1_invariants.txt")).strip("\n"),
             read_text(os.path.join(PROMPT_DIR, "s2v2_addendum.txt")).strip("\n")]
    text = "\n\n".join(parts)
    assert len(text.encode("utf-8")) <= MAX_SYSTEM_PROMPT_BYTES
    return text


def derive2(doc):
    ev = [(e["ref"], e["value"]) for e in doc.get("evidence", [])]
    _j, _safe = p1._j, p1._safe
    L = ["LT VERDICT DERIVED FACTS",
         "(computed by lookup and addition from the JSON above; refs are evidence refs; not model output)"]
    overall = [(r, v) for r, v in ev if v.get("type") == "metric_summary" and v.get("scope", {}).get("kind") == "overall"]
    txn = [(r, v) for r, v in ev if v.get("type") == "metric_summary" and v.get("scope", {}).get("kind") == "transaction"]
    L += ["", "1. Scope counts (metric_summary entries, whole-run scope; each scope is reported separately)"]
    for r, v in overall:
        L.append("   overall: sample_count=%s error_count=%s [%s]" % (v.get("sample_count"), v.get("error_count"), r.split("#/")[-1]))
    if txn:
        s = sum(v.get("sample_count", 0) for _, v in txn)
        e = sum(v.get("error_count", 0) for _, v in txn)
        L.append("   transaction scope: %d entries [%s]: sum(sample_count)=%d sum(error_count)=%d" % (len(txn), p1._refs([r for r, _ in txn]), s, e))
        with_err = [(r, v.get("error_count")) for r, v in txn if (v.get("error_count") or 0) > 0]
        if with_err:
            L.append("   transaction-scope entries with error_count>0: " + "; ".join("%s error_count=%s" % (r.split("#/")[-1], c) for r, c in with_err))
        else:
            L.append("   transaction-scope entries with error_count>0: none")
    else:
        L.append("   transaction scope: no entries")

    L += ["", "2. Windows (a window covers only part of the run; a count inside a window and a whole-run count are different quantities)"]
    for r, v in ev:
        if v.get("type") == "resource_binding":
            rf, rt, ef, et = (v.get(k) for k in ("run_from_epoch_ms", "run_to_epoch_ms", "evaluation_from_epoch_ms", "evaluation_to_epoch_ms"))
            if all(isinstance(x, int) for x in (rf, rt, ef, et)) and rt > rf:
                L.append("   run interval %d ms; evaluation window %d ms (%.1f%% of run); dropped leading %s ms, trailing %s ms; mode=%s [%s]" % (
                    rt - rf, et - ef, 100.0 * (et - ef) / (rt - rf), v.get("dropped_leading_millis"), v.get("dropped_trailing_millis"), _safe(v.get("mode")), r.split("#/")[-1]))
            L.append("   clock_alignment=%s" % _safe(v.get("clock_alignment")))
    wids = sorted({_safe(v.get("window_id")) for _, v in ev if v.get("window_id")})
    L.append("   window ids referenced: %s" % (", ".join(wids) if wids else "none"))
    for r, v in ev:
        if v.get("type") == "policy_check":
            obs = v.get("observed") or {}
            wid = v.get("window_id")
            line = "   policy_check [%s] rule=%s status=%s observed=%s/%s" % (r.split("#/")[-1], _safe(v.get("rule_id")), _safe(v.get("status")), obs.get("numerator"), obs.get("denominator"))
            line += (" window=%s (count inside the window)" % _safe(wid)) if wid else " (whole run, no window id)"
            L.append(line)
        elif v.get("type") == "window_metric_summary":
            L.append("   window_metric_summary [%s] window=%s error_count=%s denominator=%s" % (
                r.split("#/")[-1], _safe(v.get("window_id")), v.get("error_count"), (v.get("error_rate_ratio") or {}).get("denominator")))

    cap = doc.get("capacity_summary")
    L += ["", "3. Capacity"]
    if cap:
        c = cap["value"]
        L.append("   bound_type=%s lower_inclusive=%s upper_exclusive=%s load_axis=%s policy_verdict=%s reasons=%s" % (
            _safe(c.get("bound_type")), _j(c.get("lower_inclusive")), _j(c.get("upper_exclusive")), _safe(c.get("load_axis")),
            _safe(c.get("policy_verdict")), ",".join(_safe(x) for x in c.get("reasons", [])) or "none"))
        L.append("   capacity_knee=%s knee_reason=%s (fixed product limitation; the bound is computed independently of the knee)" % (_j(c.get("capacity_knee")), _safe(c.get("knee_reason"))))
        for st in c.get("stages", []):
            L.append("   stage %s: target=%s achieved=%s verdict=%s reasons=%s complete_bins=%s/%s verified_bound_load=%s" % (
                _safe(st.get("id")), st.get("target"), st.get("achieved"), _safe(st.get("verdict")), ",".join(_safe(x) for x in st.get("reasons", [])) or "none",
                st.get("complete_bins"), st.get("expected_bins"), _j(st.get("verified_bound_load"))))
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
    L += ["", "4. Inventory of the input",
          "   present evidence types: " + ", ".join("%s x%d" % (_safe(k), n) for k, n in sorted(types.items(), key=lambda kv: str(kv[0])))]
    found = False
    for r, v in ev:
        if v.get("type") == "opensearch_errors":
            found = True
            groups = v.get("groups", [])
            L.append("   OpenSearch error profile PRESENT [%s]: %d error groups, total count %s, sampled messages are included; this is not a full log body" % (
                r.split("#/")[-1], len(groups), sum(g.get("count", 0) for g in groups if isinstance(g.get("count"), int))))
    if not found:
        L.append("   OpenSearch error profile: not present")
    L.append("   resource series entities: " + (", ".join(sorted({_safe(v.get("entity")) for _, v in ev if v.get("type") == "resource_summary"})) or "none"))
    L.append("   STAGE_UNSPECIFIED present: %s; load stage definitions present only as capacity stages listed in section 3" % ("yes" if "STAGE_UNSPECIFIED" in json.dumps(doc) else "no"))
    L.append("   NOT in the input: individual samples, the number of samples that hit a reported max latency, call graph or topology of the system under test, log bodies, traces, release or configuration data")
    return "\n".join(L)


def stdin_text(stage, evidence_bytes):
    text = evidence_bytes.decode("utf-8")
    if stage == "S2":
        text = text + "\n\n" + derive2(json.loads(text))
    return text
