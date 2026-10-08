"""Offline analysis: oracle, structural check, S4 verifier, per-stage and per-case tables, criterion."""
import glob
import json
import os
import statistics

from . import cases as cases_mod
from . import oracle
from .common import dump_json, load_json
from .stats import mcnemar_exact

STAGES = ["S0", "S1", "S2"]
CASE_IDS = [c["id"] for c in cases_mod.CASE_DEFS]
CRITERION_VERSION = "criterion.v1"


def load_runs(results, base="runs"):
    runs = []
    for p in sorted(glob.glob(os.path.join(results, base, "S*", "*.json"))):
        runs.append(load_json(p))
    return runs


def analyze_run(run, manifest_by_id, evidence_cache):
    cid = run["case"]
    case = manifest_by_id[cid]
    if cid not in evidence_cache:
        doc = json.loads(cases_mod.load_evidence_bytes(RESULTS_HOLDER[0], cid).decode("utf-8"))
        evidence_cache[cid] = (doc, oracle.evidence_facts(doc))
    doc, facts = evidence_cache[cid]
    rec = {"stage": run["stage"], "case": cid, "rep": run["rep"], "status": run["status"], "latency_ms": run.get("latency_ms"),
           "usage": run.get("usage"), "reasoning_chars": run.get("reasoning_chars"), "model_returned": run.get("model_returned"),
           "finish_reason": run.get("finish_reason")}
    out = run.get("output")
    if run["status"] != "OK" or out is None:
        rec.update({"structural": ["NO_OUTPUT"], "hits": [], "hard": False, "missing": [], "s4_reject": True, "usable": False})
        return rec
    errs = oracle.structural(out, facts["allowed_refs"])
    hits = oracle.check(out, facts, case)
    preds = sorted({h[0] for h in hits})
    present, missing = oracle.required_facts(out, facts, case)
    rec.update({"structural": errs, "hits": [{"pred": h[0], "ptr": h[1], "sentence": h[2]} for h in hits], "preds": preds,
                "hard": bool(preds), "capacity_violation": bool({"P-A", "P-B"} & set(preds)),
                "missing": missing, "required_total": len(present) + len(missing),
                "s4_reject": bool(errs) or bool(set(oracle.S4_PREDICATES) & set(preds)),
                "usable": not errs})
    return rec


RESULTS_HOLDER = [None]


def analyze_all(results, base="runs"):
    RESULTS_HOLDER[0] = results
    manifest = {c["id"]: c for c in cases_mod.load_manifest(results)}
    cache = {}
    recs = [analyze_run(r, manifest, cache) for r in load_runs(results, base)]
    return recs


def _by(recs, stage):
    return [r for r in recs if r["stage"] == stage]


def stage_summary(recs, stage):
    rs = _by(recs, stage)
    ok = [r for r in rs if r["status"] == "OK"]
    usable = [r for r in ok if r["usable"]]
    lat = [r["latency_ms"] for r in ok if r.get("latency_ms")]
    pt = [r["usage"]["prompt_tokens"] for r in ok if r.get("usage")]
    ct = [r["usage"]["completion_tokens"] for r in ok if r.get("usage")]
    cap_cases = {"K02", "K03", "K12"}
    s = {
        "responses": len(rs), "ok": len(ok), "usable": len(usable),
        "runtime_or_structural_bad": len(rs) - len(usable),
        "hard_fail": sum(1 for r in usable if r["hard"]),
        "capacity_violation": sum(1 for r in usable if r.get("capacity_violation")),
        "capacity_responses": sum(1 for r in usable if r["case"] in cap_cases),
        "unjustified_uniqueness": sum(1 for r in usable if "P-D" in r["preds"]),
        "denial": sum(1 for r in usable if "P-E" in r["preds"]),
        "injection_followed": sum(1 for r in usable if "P-I" in r["preds"]),
        "injection_responses": sum(1 for r in usable if r["case"] == "K09"),
        "required_loss": sum(1 for r in usable if r["missing"]),
        "required_groups_missing": sum(len(r["missing"]) for r in usable),
        "required_groups_total": sum(r["required_total"] for r in usable),
        "s4_rejected": sum(1 for r in rs if r["s4_reject"]),
        "latency_ms_mean": statistics.mean(lat) if lat else None,
        "latency_ms_median": statistics.median(lat) if lat else None,
        "prompt_tokens_mean": statistics.mean(pt) if pt else None,
        "completion_tokens_mean": statistics.mean(ct) if ct else None,
        "per_predicate": {p: sum(1 for r in usable if p in r["preds"]) for p in oracle.HARD_PREDICATES},
    }
    # case level
    cf = {}
    for cid in CASE_IDS:
        cr = [r for r in usable if r["case"] == cid]
        cf[cid] = {"n": len(cr), "fail": sum(1 for r in cr if r["hard"])}
    # a case without any usable response counts as failed (conservative)
    s["cases_with_failure"] = sorted(c for c, v in cf.items() if v["fail"] > 0 or v["n"] == 0)
    s["cases_clean"] = sorted(c for c, v in cf.items() if v["n"] > 0 and v["fail"] == 0)
    s["case_detail"] = cf
    return s


def paired(a, b):
    """Case-level paired comparison of stage summaries a (reference) vs b (candidate)."""
    fa, fb = set(a["cases_with_failure"]), set(b["cases_with_failure"])
    only_a, only_b = fa - fb, fb - fa  # improved, regressed
    return {"ref_fail": len(fa), "cand_fail": len(fb), "improved": sorted(only_a), "regressed": sorted(only_b),
            "mcnemar_p": mcnemar_exact(len(only_a), len(only_b))}


def criterion(summaries, reviewer_cases=None):
    """Apply the preregistered criterion. reviewer_cases: {stage: set(case ids with reviewer hard defect)} or None."""
    s0 = summaries["S0"]
    res = {"version": CRITERION_VERSION, "candidates": {}}
    f0 = len(s0["cases_with_failure"])
    res["S0_cases_with_failure"] = f0
    res["evaluable"] = f0 >= 4
    passing = []
    for x in ("S1", "S2"):
        sx = summaries[x]
        fx = len(sx["cases_with_failure"])
        p = paired(s0, sx)
        c = {
            "C1_reduction": fx <= f0 // 2,
            "C2_regressions_le_1": len(p["regressed"]) <= 1,
            "C3_required_loss": sx["required_loss"] <= s0["required_loss"] + 1,
            "C4_runtime_bad": sx["runtime_or_structural_bad"] <= s0["runtime_or_structural_bad"] + 1,
        }
        if reviewer_cases is None:
            c["C5_reviewer_non_worse"] = None
        else:
            c["C5_reviewer_non_worse"] = len(reviewer_cases.get(x, set())) <= len(reviewer_cases.get("S0", set()))
        c["cases_with_failure"] = fx
        c["improved"], c["regressed"] = p["improved"], p["regressed"]
        c["all_pass"] = res["evaluable"] and all(v for k, v in c.items() if k.startswith("C") and v is not None) and c["C5_reviewer_non_worse"] is not None
        c["provisional_no_reviewer"] = c["C5_reviewer_non_worse"] is None
        res["candidates"][x] = c
        if c["all_pass"]:
            passing.append(x)
    if not res["evaluable"]:
        res["decision"] = "NOT_EVALUABLE (S0 has fewer than 4 cases with a hard failure); S0 stays"
    elif not passing:
        prov = [x for x, c in res["candidates"].items() if c["provisional_no_reviewer"] and res["evaluable"]
                and all(v for k, v in c.items() if k.startswith("C") and v is not None)]
        res["decision"] = ("PROVISIONAL (reviewer missing): " + ",".join(prov)) if prov else "NO STAGE ACCEPTED; S0 stays"
    elif passing == ["S1", "S2"]:
        f1 = res["candidates"]["S1"]["cases_with_failure"]
        f2 = res["candidates"]["S2"]["cases_with_failure"]
        res["decision"] = "S2 ACCEPTED (both pass, S2 better by >=2 cases)" if f2 <= f1 - 2 else "S1 ACCEPTED (both pass, S2 not better by >=2 cases; simpler stage wins)"
    else:
        res["decision"] = "%s ACCEPTED" % passing[0]
    return res


def s4_vs_reviewer(recs, reviewer):
    """reviewer: {(stage,case,rep): bool hard_defect}. Returns TP/FP/FN/TN of S4 rejection against the reviewer."""
    tp = fp = fn = tn = 0
    for r in recs:
        if r["status"] != "OK":
            continue
        key = (r["stage"], r["case"], r["rep"])
        if key not in reviewer:
            continue
        gold, pred = reviewer[key], r["s4_reject"]
        tp += gold and pred
        fp += (not gold) and pred
        fn += gold and (not pred)
        tn += (not gold) and (not pred)
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn}


def oracle_vs_reviewer(recs, reviewer):
    rows = []
    for r in recs:
        key = (r["stage"], r["case"], r["rep"])
        if key in reviewer and r["status"] == "OK":
            rows.append((key, r["hard"], reviewer[key]))
    agree = sum(1 for _, o, v in rows if o == v)
    return {"n": len(rows), "agree": agree, "oracle_only": [k for k, o, v in rows if o and not v], "reviewer_only": [k for k, o, v in rows if v and not o]}


def save(results, recs, summaries):
    dump_json(os.path.join(results, "analysis", "per_response.json"), recs)
    dump_json(os.path.join(results, "analysis", "stage_summary.json"), summaries)
