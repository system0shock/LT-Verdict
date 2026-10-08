"""Markdown tables for the report (analysis + optional blind review)."""
import json
import os
import statistics

from . import analyze as an
from . import blind
from . import oracle
from .common import load_json
from .stats import clopper_pearson, fmt_ci


def _f(x, nd=0):
    return "-" if x is None else ("%.*f" % (nd, x))


def ledger_summary(results):
    p = os.path.join(results, "ledger.jsonl")
    rows = [json.loads(x) for x in open(p, encoding="utf-8") if x.strip()]
    req = [r for r in rows if r["kind"] == "request"]
    res = [r for r in rows if r["kind"] == "result"]
    outcomes = {}
    for r in res:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
    phases = {}
    for r in req:
        phases[r.get("phase")] = phases.get(r.get("phase"), 0) + 1
    return {"requests": len(req), "results": len(res), "outcomes": outcomes, "phases": phases, "remaining_task": 130 - len(req), "remaining_total": 300 - len(req)}


def build(results, recs=None):
    lenient = recs is not None
    if recs is None:
        recs = an.analyze_all(results)
    summaries = {s: an.stage_summary(recs, s) for s in an.STAGES}
    if not lenient:
        an.save(results, recs, summaries)
    reviews, problems = ({}, [])
    if os.path.exists(os.path.join(results, "review_key.json")):
        reviews, problems = blind.load_reviews(results)
    rev_hard = {k: bool(v.get("hard_defect")) for k, v in reviews.items()}
    rev_cases = {s: {c for (st, c, r), h in rev_hard.items() if st == s and h} for s in an.STAGES} if reviews else None
    crit = an.criterion(summaries, rev_cases)
    L = []
    L.append("## Ledger\n")
    L.append("```\n" + json.dumps(ledger_summary(results), indent=1) + "\n```\n")

    L.append("## Stage table (n = responses per stage)\n")
    L.append("| Metric | S0 | S1 | S2 |\n|---|---|---|---|")

    def row(name, fn):
        L.append("| %s | %s |" % (name, " | ".join(fn(summaries[s], s) for s in an.STAGES)))
    row("Responses received / usable (structure OK)", lambda x, s: "%d / %d" % (x["responses"], x["usable"]))
    row("Format, network or structural failure (B)", lambda x, s: str(x["runtime_or_structural_bad"]))
    row("Hard failure (oracle), responses", lambda x, s: fmt_ci(x["hard_fail"], x["usable"]))
    row("Cases with hard failure (F), of 12", lambda x, s: "%d: %s" % (len(x["cases_with_failure"]), ",".join(x["cases_with_failure"]) or "-"))
    row("Capacity-contract violation P-A/P-B (K02,K03,K12)", lambda x, s: fmt_ci(x["capacity_violation"], x["capacity_responses"]))
    row("Unjustified uniqueness P-D", lambda x, s: fmt_ci(x["unjustified_uniqueness"], x["usable"]))
    row("Denial despite explicit context P-E", lambda x, s: fmt_ci(x["denial"], x["usable"]))
    row("Injection followed P-I (K09)", lambda x, s: fmt_ci(x["injection_followed"], x["injection_responses"]))
    row("Required-fact loss, responses (R)", lambda x, s: fmt_ci(x["required_loss"], x["usable"]))
    row("Fact groups lost / total groups", lambda x, s: "%d / %d" % (x["required_groups_missing"], x["required_groups_total"]))
    row("Rejected by S4 (structure or P-A/B/C/I)", lambda x, s: fmt_ci(x["s4_rejected"], x["responses"]))
    row("Latency, ms (mean / median)", lambda x, s: "%s / %s" % (_f(x["latency_ms_mean"]), _f(x["latency_ms_median"])))
    row("prompt_tokens (mean)", lambda x, s: _f(x["prompt_tokens_mean"]))
    row("completion_tokens (mean)", lambda x, s: _f(x["completion_tokens_mean"]))
    if reviews:
        def rv(s):
            xs = [v for (st, c, r), v in reviews.items() if st == s]
            hd = sum(1 for v in xs if v.get("hard_defect"))
            us = [v.get("usefulness") for v in xs if isinstance(v.get("usefulness"), int)]
            rp = sum(1 for v in xs if v.get("required_facts_preserved") is False)
            return len(xs), hd, statistics.mean(us) if us else None, rp
        row("Reviewer: hard_defect, responses", lambda x, s: fmt_ci(rv(s)[1], rv(s)[0]))
        row("Reviewer: cases with hard_defect", lambda x, s: str(len(rev_cases[s])) + ": " + (",".join(sorted(rev_cases[s])) or "-"))
        row("Reviewer: usefulness 1-5 (mean)", lambda x, s: _f(rv(s)[2], 2))
        row("Reviewer: facts not preserved, responses", lambda x, s: str(rv(s)[3]))
    L.append("")

    L.append("## Predicate hits per stage (responses)\n")
    L.append("| Predicate | S0 | S1 | S2 |\n|---|---|---|---|")
    for p in oracle.HARD_PREDICATES:
        L.append("| %s | %s |" % (p, " | ".join(str(summaries[s]["per_predicate"][p]) for s in an.STAGES)))
    L.append("")

    L.append("## Per case: responses with oracle hard failure (of usable); reviewer hard_defect in parentheses\n")
    L.append("| Case | S0 | S1 | S2 |\n|---|---|---|---|")
    for cid in an.CASE_IDS:
        cells = []
        for s in an.STAGES:
            d = summaries[s]["case_detail"][cid]
            cell = "%d/%d" % (d["fail"], d["n"])
            if reviews:
                rs = [v for (st, c, r), v in reviews.items() if st == s and c == cid]
                cell += " (%d/%d)" % (sum(1 for v in rs if v.get("hard_defect")), len(rs))
            cells.append(cell)
        L.append("| %s | %s |" % (cid, " | ".join(cells)))
    L.append("")

    L.append("## Paired case-level comparisons (F = cases with hard failure)\n")
    L.append("| Pair | F ref | F cand | improved | regressed | McNemar exact two-sided p |\n|---|---|---|---|---|---|")
    for a_, b_ in (("S0", "S1"), ("S0", "S2"), ("S1", "S2")):
        p = an.paired(summaries[a_], summaries[b_])
        L.append("| %s -> %s | %d | %d | %s | %s | %.3f |" % (a_, b_, p["ref_fail"], p["cand_fail"], ",".join(p["improved"]) or "-", ",".join(p["regressed"]) or "-", p["mcnemar_p"]))
    L.append("")

    L.append("## Application of the preregistered criterion\n")
    L.append("```\n" + json.dumps(crit, indent=1, ensure_ascii=False) + "\n```\n")

    if reviews:
        L.append("## S4 and oracle against the reviewer\n")
        s4 = an.s4_vs_reviewer(recs, rev_hard)
        prec = s4["tp"] / (s4["tp"] + s4["fp"]) if s4["tp"] + s4["fp"] else None
        rec = s4["tp"] / (s4["tp"] + s4["fn"]) if s4["tp"] + s4["fn"] else None
        L.append("S4 (rejected = prediction, reviewer hard_defect = truth): %s; precision=%s recall=%s\n" % (s4, _f(prec, 2), _f(rec, 2)))
        ov = an.oracle_vs_reviewer(recs, rev_hard)
        L.append("Oracle (hard failure) vs reviewer: agreement %d of %d.\n" % (ov["agree"], ov["n"]))
        L.append("Oracle only: %s\n" % ["%s/%s-%d" % k for k in ov["oracle_only"]])
        L.append("Reviewer only: %s\n" % ["%s/%s-%d" % k for k in ov["reviewer_only"]])
    if problems:
        L.append("Review parsing problems: %s\n" % problems)
    return "\n".join(L), recs, summaries, crit, reviews
