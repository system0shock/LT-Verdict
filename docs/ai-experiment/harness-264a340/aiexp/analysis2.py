"""Round v2 analysis: same oracle, same criterion C0-C5 as v1 (aiexp.analyze), plus C6 (format) and two reviewers."""
import collections
import glob
import json
import os
import statistics

from . import analyze as an
from . import blind2, oracle
from .common import load_json
from .stats import fmt_ci, mcnemar_exact

FORMAT_MAX_BAD = 3  # C6: at most 3 of 72 runs may be non-OK (wrappers must disappear)


def load(results):
    recs = an.analyze_all(results, "v2/runs")
    summaries = {s: an.stage_summary(recs, s) for s in an.STAGES}
    return recs, summaries


def reviewer_case_sets(rev):
    return {s: {c for (st, c, r), v in rev.items() if st == s and v.get("hard_defect")} for s in an.STAGES}


def criterion2(summaries, revs):
    """revs: {'a': reviews, 'b': reviews}. C5 needs BOTH reviewers non-worse. Returns dict."""
    sets = {k: reviewer_case_sets(v) for k, v in revs.items() if v}
    first = an.criterion(summaries, sets.get("a"))
    for x, c in first["candidates"].items():
        ok = [len(sets[k][x]) <= len(sets[k]["S0"]) for k in sets]
        c["C5_reviewer_non_worse"] = all(ok) if ok else None
        c["C5_by_reviewer"] = {k: (len(sets[k][x]), len(sets[k]["S0"])) for k in sets}
        c["all_pass"] = first["evaluable"] and all(v for k, v in c.items() if k.startswith("C") and k != "C5_by_reviewer" and v is not None) and c["C5_reviewer_non_worse"] is not None
    passing = [x for x, c in first["candidates"].items() if c["all_pass"]]
    if not first["evaluable"]:
        first["decision"] = "NOT_EVALUABLE (S0 has fewer than 4 cases with a hard failure); S0 stays"
    elif not passing:
        first["decision"] = "NO STAGE ACCEPTED; S0 stays"
    elif passing == ["S1", "S2"]:
        f1, f2 = (first["candidates"][s]["cases_with_failure"] for s in ("S1", "S2"))
        first["decision"] = "S2 ACCEPTED (both pass, S2 better by >=2 cases)" if f2 <= f1 - 2 else "S1 ACCEPTED (both pass, S2 not better by >=2 cases; simpler stage wins)"
    else:
        first["decision"] = "%s ACCEPTED" % passing[0]
    return first


def format_report(recs):
    bad = [r for r in recs if r["status"] != "OK"]
    per = collections.Counter(r["stage"] for r in bad)
    return {"runs": len(recs), "non_ok": len(bad), "per_stage": dict(per), "c6_pass": len(bad) <= FORMAT_MAX_BAD,
            "details": [(r["stage"], r["case"], r["rep"], r["status"]) for r in bad]}


def sse_payload_kind(path):
    """Metric W: classify the first tool call arguments in a saved provider SSE stream (direct / wrapped / unparseable / none)."""
    from .lenient import _unwrap
    if not os.path.exists(path):
        return "none"
    parts = []
    name = None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if not data or data == "[DONE]":
                continue
            try:
                frame = json.loads(data)
            except ValueError:
                continue
            for ch in frame.get("choices") or []:
                for c in (ch.get("delta") or {}).get("tool_calls") or []:
                    if c.get("index") == 0:
                        fn = c.get("function") or {}
                        name = fn.get("name") or name
                        if fn.get("arguments"):
                            parts.append(fn["arguments"])
    if not parts:
        return "none"
    try:
        obj = json.loads("".join(parts))
    except ValueError:
        try:
            obj, _ = json.JSONDecoder().raw_decode("".join(parts))
        except ValueError:
            return "unparseable"
    out, keys = _unwrap(obj)
    if out is None:
        return "unparseable"
    return "direct" if not keys else "wrapped"


def payload_kinds(results):
    c = collections.defaultdict(collections.Counter)
    for p in sorted(glob.glob(os.path.join(results, "v2", "runs_raw", "pilot", "S*", "*", "provider-response.sse"))):
        stage = p.split(os.sep)[-3]
        c[stage][sse_payload_kind(p)] += 1
    return c


def run_details(results):
    rows = []
    for p in sorted(glob.glob(os.path.join(results, "v2", "runs", "S*", "*.json"))):
        r = load_json(p)
        rl = r.get("relay") or {}
        rows.append({"stage": r["stage"], "case": r["case"], "rep": r["rep"], "status": r["status"], "relay_status": rl.get("status"),
                     "first_status": rl.get("first_status"), "request_count": rl.get("request_count"), "qwen_exit": r.get("qwen_exit"),
                     "note": r.get("note"), "latency_ms": r.get("latency_ms"), "wall_ms": r.get("wall_ms"), "usage": r.get("usage")})
    return rows


def tables(results):
    recs, summ = load(results)
    revs = {}
    problems = {}
    for k in ("a", "b"):
        if os.path.exists(os.path.join(results, "v2", "review_key_%s.json" % k)):
            revs[k], problems[k] = blind2.load_reviews(results, k)
    crit = criterion2(summ, revs) if revs else an.criterion(summ, None)
    fmt = format_report(recs)
    L = ["## Format (C6)\n", "```\n" + json.dumps(fmt, indent=1) + "\n```\n"]
    pk = payload_kinds(results)
    L.append("## Metric W: first tool call arguments in the provider stream (v1: 16 of 72 wrapped, 1 unparseable)\n")
    L.append("| Stage | direct | wrapped | unparseable | none |\n|---|---|---|---|---|")
    for s in an.STAGES:
        L.append("| %s | %d | %d | %d | %d |" % (s, pk[s]["direct"], pk[s]["wrapped"], pk[s]["unparseable"], pk[s]["none"]))
    L.append("")
    L.append("## Stage table (n = runs per stage)\n")
    L.append("| Metric | S0 | S1 | S2 |\n|---|---|---|---|")

    def row(name, fn):
        L.append("| %s | %s |" % (name, " | ".join(fn(summ[s], s) for s in an.STAGES)))
    row("Runs / usable (structure OK)", lambda x, s: "%d / %d" % (x["responses"], x["usable"]))
    row("B: non-OK runs or structural failures", lambda x, s: str(x["runtime_or_structural_bad"]))
    row("Hard failure (oracle), responses", lambda x, s: fmt_ci(x["hard_fail"], x["usable"]))
    row("Cases with hard failure (F, of 12)", lambda x, s: "%d: %s" % (len(x["cases_with_failure"]), ",".join(x["cases_with_failure"]) or "-"))
    row("Capacity-contract violation P-A/P-B", lambda x, s: fmt_ci(x["capacity_violation"], x["capacity_responses"]))
    row("Unjustified uniqueness P-D", lambda x, s: fmt_ci(x["unjustified_uniqueness"], x["usable"]))
    row("Denial despite explicit context P-E", lambda x, s: fmt_ci(x["denial"], x["usable"]))
    row("Injection followed P-I (K09)", lambda x, s: fmt_ci(x["injection_followed"], x["injection_responses"]))
    row("Required-fact loss, responses (R)", lambda x, s: fmt_ci(x["required_loss"], x["usable"]))
    row("Rejected by S4", lambda x, s: fmt_ci(x["s4_rejected"], x["responses"]))
    f = lambda v: "-" if v is None else "%.0f" % v  # noqa: E731
    row("Provider latency, ms (mean / median)", lambda x, s: "%s / %s" % (f(x["latency_ms_mean"]), f(x["latency_ms_median"])))
    row("prompt_tokens / completion_tokens (mean)", lambda x, s: "%s / %s" % (f(x["prompt_tokens_mean"]), f(x["completion_tokens_mean"])))
    for k, rev in revs.items():
        def rv(s, rev=rev):
            xs = [v for (st, c, r), v in rev.items() if st == s]
            us = [v.get("usefulness") for v in xs if isinstance(v.get("usefulness"), int)]
            return len(xs), sum(1 for v in xs if v.get("hard_defect")), (statistics.mean(us) if us else float("nan")), sum(1 for v in xs if v.get("required_facts_preserved") is False)
        row("Reviewer %s: hard_defect responses" % k.upper(), lambda x, s, rv=rv: fmt_ci(rv(s)[1], rv(s)[0]))
        row("Reviewer %s: cases with hard_defect" % k.upper(), lambda x, s, rev=rev: str(len(reviewer_case_sets(rev)[s])))
        row("Reviewer %s: usefulness (mean)" % k.upper(), lambda x, s, rv=rv: "%.2f" % rv(s)[2])
        row("Reviewer %s: facts not preserved" % k.upper(), lambda x, s, rv=rv: str(rv(s)[3]))
    L.append("")
    L.append("## Predicate hits (usable responses)\n\n| Predicate | S0 | S1 | S2 |\n|---|---|---|---|")
    for p in oracle.HARD_PREDICATES:
        L.append("| %s | %s |" % (p, " | ".join(str(summ[s]["per_predicate"][p]) for s in an.STAGES)))
    L.append("")
    L.append("## Per case: oracle hard failures / usable (reviewer A, B hard_defect / reviewed)\n\n| Case | S0 | S1 | S2 |\n|---|---|---|---|")
    for cid in an.CASE_IDS:
        cells = []
        for s in an.STAGES:
            d = summ[s]["case_detail"][cid]
            cell = "%d/%d" % (d["fail"], d["n"])
            for k, rev in revs.items():
                rs = [v for (st, c, r), v in rev.items() if st == s and c == cid]
                cell += " (%s %d/%d)" % (k.upper(), sum(1 for v in rs if v.get("hard_defect")), len(rs))
            cells.append(cell)
        L.append("| %s | %s |" % (cid, " | ".join(cells)))
    L.append("")
    L.append("## Paired case-level comparisons (oracle)\n\n| Pair | F ref | F cand | improved | regressed | McNemar p |\n|---|---|---|---|---|---|")
    for a_, b_ in (("S0", "S1"), ("S0", "S2"), ("S1", "S2")):
        p = an.paired(summ[a_], summ[b_])
        L.append("| %s -> %s | %d | %d | %s | %s | %.3f |" % (a_, b_, p["ref_fail"], p["cand_fail"], ",".join(p["improved"]) or "-", ",".join(p["regressed"]) or "-", p["mcnemar_p"]))
    L.append("")
    L.append("## Criterion\n\n```\n" + json.dumps(crit, indent=1) + "\n```\n")
    if len(revs) == 2:
        ha = {k: bool(v.get("hard_defect")) for k, v in revs["a"].items()}
        hb = {k: bool(v.get("hard_defect")) for k, v in revs["b"].items()}
        kap, n, po, pe = blind2.cohen_kappa(ha, hb)
        L.append("## Reviewer agreement (hard_defect)\n")
        L.append("Cohen kappa = %.3f (n=%d, observed agreement %.3f, expected %.3f); PABAK = %.3f; prevalence of hard_defect A %.2f, B %.2f\n" % (
            kap, n, po, pe, 2 * po - 1, sum(ha.values()) / len(ha), sum(hb.values()) / len(hb)))
        fa = {k: bool(v.get("required_facts_preserved")) for k, v in revs["a"].items()}
        fb = {k: bool(v.get("required_facts_preserved")) for k, v in revs["b"].items()}
        k2, n2, po2, _ = blind2.cohen_kappa(fa, fb)
        L.append("Cohen kappa for required_facts_preserved = %.3f (n=%d, agreement %.3f)\n" % (k2, n2, po2))
        ua = {k: v.get("usefulness") for k, v in revs["a"].items() if isinstance(v.get("usefulness"), int)}
        ub = {k: v.get("usefulness") for k, v in revs["b"].items() if isinstance(v.get("usefulness"), int)}
        common = sorted(set(ua) & set(ub))
        if common:
            exact = sum(1 for k in common if ua[k] == ub[k]) / len(common)
            within1 = sum(1 for k in common if abs(ua[k] - ub[k]) <= 1) / len(common)
            L.append("Usefulness agreement: exact %.2f, within 1 point %.2f (n=%d); means A %.2f, B %.2f\n" % (exact, within1, len(common), statistics.mean(ua[k] for k in common), statistics.mean(ub[k] for k in common)))
        hard_by_stage = {}
        for s in an.STAGES:
            keys = [k for k in ha if k[0] == s and k in hb]
            hard_by_stage[s] = (sum(1 for k in keys if ha[k] and hb[k]), sum(1 for k in keys if ha[k] != hb[k]), len(keys))
        L.append("Both flag / disagree / n per stage: %s\n" % hard_by_stage)
        orc = {(r["stage"], r["case"], r["rep"]): r["hard"] for r in recs if r["status"] == "OK"}
        for k, h in (("A", ha), ("B", hb)):
            keys = [x for x in h if x in orc]
            tp = sum(1 for x in keys if orc[x] and h[x])
            fp = sum(1 for x in keys if orc[x] and not h[x])
            fn = sum(1 for x in keys if (not orc[x]) and h[x])
            L.append("Oracle vs reviewer %s: TP=%d FP=%d FN=%d TN=%d (n=%d)\n" % (k, tp, fp, fn, len(keys) - tp - fp - fn, len(keys)))
    for k, pr in problems.items():
        if pr:
            L.append("Review %s parsing problems: %s\n" % (k, pr))
    return "\n".join(L), recs, summ, crit, revs
