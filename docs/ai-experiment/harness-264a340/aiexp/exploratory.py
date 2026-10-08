"""EXPLORATORY post-hoc tables (not part of the preregistered criterion). ASCII only."""
import collections
import glob
import os
import re
import statistics

from . import analyze as an
from . import blind, lenient, oracle
from .common import load_json
from .stats import fmt_ci, mcnemar_exact

CAP = {"K02", "K03", "K12"}


def build(results):
    lrecs = lenient.analyze_lenient(results)
    rev, _ = blind.load_reviews(results)
    L = ["# Exploratory tables (post-hoc; not part of the preregistered criterion)\n"]

    L.append("## Payload form per stage\n")
    kinds = collections.defaultdict(collections.Counter)
    for r in lrecs:
        k = r["payload_kind"]
        kinds[r["stage"]]["direct" if k == "direct" else ("wrapped" if k.startswith("unwrapped") else "unparseable")] += 1
        if any(e.startswith("UNKNOWN_EVIDENCE_REFERENCE") for e in r["structural"]):
            kinds[r["stage"]]["unknown_ref"] += 1
    L.append("| Stage | direct | wrapped in one key | unparseable | reference outside the input (incl. wrapped) |\n|---|---|---|---|---|")
    for s in an.STAGES:
        c = kinds[s]
        L.append("| %s | %d | %d | %d | %d |" % (s, c["direct"], c["wrapped"], c["unparseable"], c["unknown_ref"]))
    L.append("")

    L.append("## Reviewer: responses with a defect of type T (any severity, reviewed responses)\n")
    per = collections.defaultdict(lambda: collections.defaultdict(set))
    n_rev = collections.Counter()
    for (st, c, r), v in rev.items():
        n_rev[st] += 1
        for d in v.get("defects", []):
            per[st][d.get("type")].add((c, r))
    L.append("| Type | " + " | ".join("%s (n=%d)" % (s, n_rev[s]) for s in an.STAGES) + " |\n|---|---|---|---|")
    for t in ["T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8"]:
        L.append("| %s | %s |" % (t, " | ".join(str(len(per[s][t])) for s in an.STAGES)))
    L.append("")

    L.append("## Knee dependency (reviewer T1 mentioning knee) in capacity cases K02, K03, K12\n")
    knee = collections.defaultdict(set)
    tot = collections.Counter()
    for (st, c, r), v in rev.items():
        if c in CAP:
            tot[st] += 1
            for d in v.get("defects", []):
                if d.get("type") == "T1" and re.search(r"knee", str(d.get("quote", "")) + str(d.get("why", "")), re.I):
                    knee[st].add((c, r))
    L.append("| Stage | responses with a knee defect (reviewer) |\n|---|---|")
    for s in an.STAGES:
        L.append("| %s | %s |" % (s, fmt_ci(len(knee[s]), tot[s])))
    L.append("")

    L.append("## Oracle and S4 against the reviewer (parsed responses, lenient extraction)\n")
    rev_hard = {k: bool(v.get("hard_defect")) for k, v in rev.items()}
    pairs = [(r, rev_hard[(r["stage"], r["case"], r["rep"])]) for r in lrecs if (r["stage"], r["case"], r["rep"]) in rev_hard and r["status"] == "OK"]

    def conf(pred):
        tp = sum(1 for r, h in pairs if pred(r) and h)
        fp = sum(1 for r, h in pairs if pred(r) and not h)
        fn = sum(1 for r, h in pairs if (not pred(r)) and h)
        tn = sum(1 for r, h in pairs if (not pred(r)) and not h)
        return tp, fp, fn, tn

    for name, pred in (("Oracle (any predicate)", lambda r: r["hard"]),
                       ("S4 semantic part (P-A, P-B, P-C, P-I)", lambda r: bool(set(r.get("preds", [])) & set(oracle.S4_PREDICATES)))):
        tp, fp, fn, tn = conf(pred)
        L.append("- %s vs reviewer hard_defect: TP=%d FP=%d FN=%d TN=%d; precision=%.2f recall=%.2f (n=%d)" % (
            name, tp, fp, fn, tn, tp / (tp + fp) if tp + fp else float("nan"), tp / (tp + fn) if tp + fn else float("nan"), len(pairs)))
    L.append("")
    per_pred = collections.defaultdict(lambda: [0, 0])
    for r, h in pairs:
        for p in r.get("preds", []):
            per_pred[p][0 if h else 1] += 1
    L.append("| Predicate | fired on responses the reviewer marks hard_defect | fired on responses the reviewer does not mark (likely oracle FP) |\n|---|---|---|")
    for p in oracle.HARD_PREDICATES:
        L.append("| %s | %d | %d |" % (p, per_pred[p][0], per_pred[p][1]))
    L.append("")

    L.append("## Case-level comparison with the reviewer hard_defect in place of the oracle\n")
    rc = {s: {c for (st, c, r), h in rev_hard.items() if st == s and h} for s in an.STAGES}
    for a_, b_ in (("S0", "S1"), ("S0", "S2"), ("S1", "S2")):
        imp, reg = sorted(rc[a_] - rc[b_]), sorted(rc[b_] - rc[a_])
        L.append("- %s -> %s: cases with hard_defect %d -> %d; improved %s; regressed %s; McNemar p=%.3f" % (
            a_, b_, len(rc[a_]), len(rc[b_]), imp or "-", reg or "-", mcnemar_exact(len(imp), len(reg))))
    L.append("")

    L.append("## Cost (usage from the responses)\n")
    L.append("| Stage | prompt_tokens sum | completion_tokens sum | of which reasoning | mean reasoning per response |\n|---|---|---|---|---|")
    for s in an.STAGES:
        ru = [load_json(p) for p in sorted(glob.glob(os.path.join(results, "runs", s, "*.json")))]
        pt = sum(r["usage"]["prompt_tokens"] for r in ru if r.get("usage"))
        ct = sum(r["usage"]["completion_tokens"] for r in ru if r.get("usage"))
        rt = [r["usage"].get("completion_tokens_details", {}).get("reasoning_tokens", 0) for r in ru if r.get("usage")]
        L.append("| %s | %d | %d | %d | %.0f |" % (s, pt, ct, sum(rt), statistics.mean(rt) if rt else 0))
    L.append("")
    return "\n".join(L)
