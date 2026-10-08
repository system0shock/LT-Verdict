#!/usr/bin/env python3
"""Recompute the key figures of ADR 0021 from the committed experiment data.

Usage: python docs/ai-experiment/recompute_adr0021.py [--data-dir DIR]

Reads (never writes) docs/ui-mockup/ai-experiment-2026-09-30 next to this script. Standard library
only; the oracle rows use the frozen analysis modules in harness-264a340/ (copied from commit 264a340
of the local branch test/ai-experiment-harness). One line per check: OK or FAIL, expected, actual.
Exit code: 0 all checks passed, 1 a figure differs, 2 an input file is missing or corrupt.
"""
import argparse
import collections
import glob
import hashlib
import json
import os
import re
import sys
import tarfile
import tempfile

sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DATA = os.path.normpath(os.path.join(HERE, "..", "ui-mockup", "ai-experiment-2026-09-30"))
STAGES = ["S0", "S1", "S2"]
CAP = {"K02", "K03", "K12"}  # capacity cases of the 12-case corpus


class InputError(Exception):
    pass


def read_bytes(path):
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError as exc:
        raise InputError("cannot read %s: %s" % (path, exc))


def read_json(path):
    try:
        return json.loads(read_bytes(path).decode("utf-8"))
    except ValueError as exc:
        raise InputError("corrupt JSON %s: %s" % (path, exc))


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def parse_review(text):
    """Reviewer answer file: a JSON array, possibly inside a code fence."""
    t = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    a, b = t.find("["), t.rfind("]")
    if a < 0 or b < a:
        raise ValueError("no JSON array")
    return json.loads(t[a:b + 1])


def load_reviews(review_dir, key_file, pattern, tuple_of):
    key = read_json(key_file)["key"]
    out = {}
    files = sorted(glob.glob(os.path.join(review_dir, pattern)))
    if not files:
        raise InputError("no review files in %s" % review_dir)
    for path in files:
        try:
            items = parse_review(read_bytes(path).decode("utf-8"))
        except ValueError as exc:
            raise InputError("corrupt review %s: %s" % (path, exc))
        for item in items:
            if item.get("id") in key:
                out[tuple_of(key[item["id"]])] = item
    return out


def knee(review):
    return any(d.get("type") == "T1" and re.search("knee", str(d.get("quote", "")) + str(d.get("why", "")), re.I)
               for d in review.get("defects", []))


def kappa(pairs):
    n = len(pairs)
    po = sum(1 for a, b in pairs if a == b) / n
    pa = sum(1 for a, _ in pairs if a) / n
    pb = sum(1 for _, b in pairs if b) / n
    pe = pa * pb + (1 - pa) * (1 - pb)
    return (po - pe) / (1 - pe)


class Report:
    def __init__(self):
        self.rows = []

    def check(self, cid, what, expected, actual):
        ok = expected == actual
        self.rows.append(ok)
        print("%s %s %s expected=%s actual=%s" % ("OK  " if ok else "FAIL", cid, what, expected, actual))

    def check_close(self, cid, what, expected, actual, places=3):
        self.check(cid, what, round(expected, places), round(actual, places))


def integrity(d, r):
    pairs = [("INT-1", "preregistration.md", "PREREG_SHA256.txt"),
             ("INT-2", "v2/preregistration-v2.md", "v2/PREREG_V2_SHA256.txt"),
             ("INT-3", "v3/preregistration-v3.md", "v3/PREREG_V3_SHA256.txt"),
             ("INT-4", "holdout/preregistration-holdout.md", "holdout/PREREG_HOLDOUT_SHA256.txt")]
    for cid, doc, sumfile in pairs:
        recorded = read_bytes(os.path.join(d, sumfile)).decode("utf-8").split()[0]
        r.check(cid, "sha256 of " + doc, recorded, sha256(read_bytes(os.path.join(d, doc))))
    recorded = read_bytes(os.path.join(d, "holdout/PLAN_HOLDOUT_SHA256.txt")).decode("utf-8").split()[0]
    plan = read_json(os.path.join(d, "holdout/plan-holdout.json"))
    r.check("INT-5", "sha256 of canonical holdout plan attempts", recorded, sha256(canonical(plan["attempts"]).encode("utf-8")))


def ledger(d, r):
    rows = []
    for line in read_bytes(os.path.join(d, "ledger.jsonl")).decode("utf-8").splitlines():
        if line.strip():
            try:
                rows.append(json.loads(line))
            except ValueError as exc:
                raise InputError("corrupt ledger line: %s" % exc)
    req = {x["seq"] for x in rows if x.get("kind") == "request"}
    res = {x["seq"] for x in rows if x.get("kind") == "result"}
    upto = {s for s in req if s <= 221}  # ADR 0021 (Р4) counts the ledger up to the end of round v3
    r.check("LED-1", "requests with seq <= 221 (ADR: 221 used)", 221, len(upto))
    r.check("LED-2", "results for them (ADR: 220)", 220, len(upto & res))
    r.check("LED-3", "request without a result (ADR: seq 173)", [173], sorted(upto - res))
    r.check("LED-4", "last seq of the experiment before the holdout", 221, max(upto))


def v1_numbers(d, r):
    rev = load_reviews(os.path.join(d, "review"), os.path.join(d, "review_key.json"), "K*.json",
                       lambda k: (k["stage"], k["case"], k["rep"]))
    r.check("V1-1", "reviewer hard_defect responses, S0/S1/S2 (ADR 70/54/71 %)",
            [(16, 23), (13, 24), (17, 24)],
            [(sum(1 for k, v in rev.items() if k[0] == s and v["hard_defect"]), sum(1 for k in rev if k[0] == s)) for s in STAGES])
    r.check("V1-2", "reviewer cases with hard_defect, S0/S1/S2",
            [12, 9, 10], [len({k[1] for k, v in rev.items() if k[0] == s and v["hard_defect"]}) for s in STAGES])
    r.check("V1-3", "knee dependence of the capacity bound in K02 K03 K12, S0/S1/S2 (ADR 6/6, 0/6, 0/6)",
            [(6, 6), (0, 6), (0, 6)],
            [(sum(1 for k, v in rev.items() if k[0] == s and k[1] in CAP and knee(v)), sum(1 for k in rev if k[0] == s and k[1] in CAP)) for s in STAGES])
    summary = read_json(os.path.join(d, "analysis/stage_summary.json"))
    sets = {s: set(summary[s]["cases_with_failure"]) for s in STAGES}
    r.check("V1-4", "cases with hard failure by the oracle, S0/S1/S2 (ADR 5/2/4)", [5, 2, 4], [len(sets[s]) for s in STAGES])
    r.check("V1-5", "regressions S1 against S0 by the oracle (ADR K06, K09)", ["K06", "K09"], sorted(sets["S1"] - sets["S0"]))
    lenient = read_json(os.path.join(d, "analysis/per_response_lenient.json"))
    pairs = [(x["hard"], bool(rev[(x["stage"], x["case"], x["rep"])]["hard_defect"])) for x in lenient
             if x["status"] == "OK" and (x["stage"], x["case"], x["rep"]) in rev]
    tp = sum(1 for o, h in pairs if o and h)
    fp = sum(1 for o, h in pairs if o and not h)
    fn = sum(1 for o, h in pairs if not o and h)
    r.check("V1-6", "oracle against reviewer: TP, FP, FN (ADR precision 13/17, recall 13/46)", (13, 4, 33), (tp, fp, fn))
    r.check("V1-7", "responses wrapped in one key, v1 (ADR 16 of 72)", (16, 72),
            (sum(1 for x in lenient if x["payload_kind"].startswith("unwrapped")), len(lenient)))
    per_response = read_json(os.path.join(d, "analysis/per_response.json"))
    mean_prompt = {}
    for s in STAGES:  # the report averages the prompt tokens of the responses with status OK (S0: 23 of 24)
        toks = [x["usage"]["prompt_tokens"] for x in per_response if x["stage"] == s and x["status"] == "OK" and x.get("usage")]
        mean_prompt[s] = sum(toks) / len(toks)
    r.check("V1-8", "mean prompt tokens S0 / S1 of the OK responses, v1, rounded, growth percent (ADR 6161, 6643, +8 %)",
            (6161, 6643, 8), (round(mean_prompt["S0"]), round(mean_prompt["S1"]), round((mean_prompt["S1"] / mean_prompt["S0"] - 1) * 100)))


def v2_numbers(d, r):
    v2 = os.path.join(d, "v2")
    a = load_reviews(os.path.join(v2, "review_a"), os.path.join(v2, "review_key_a.json"), "K*.json", lambda k: (k["stage"], k["case"], k["rep"]))
    b = load_reviews(os.path.join(v2, "review_b"), os.path.join(v2, "review_key_b.json"), "K*.json", lambda k: (k["stage"], k["case"], k["rep"]))
    both = [k for k in a if k in b]
    r.check("V2-1", "responses reviewed by both reviewers", 72, len(both))
    r.check("V2-2", "hard_defect flagged by BOTH reviewers, S0/S1/S2 of 24 (ADR 42/50/38 %)", [10, 12, 9],
            [sum(1 for k in both if k[0] == s and a[k]["hard_defect"] and b[k]["hard_defect"]) for s in STAGES])
    r.check("V2-3", "reviewer A cases with hard_defect, S0/S1/S2 (ADR 8/10/9)", [8, 10, 9],
            [len({k[1] for k in a if k[0] == s and a[k]["hard_defect"]}) for s in STAGES])
    r.check("V2-4", "knee dependence in K02 K03 K12 flagged by both reviewers, S0/S1/S2 (ADR 6/6, 1/6, 0/6)",
            [(6, 6), (1, 6), (0, 6)],
            [(sum(1 for k in both if k[0] == s and k[1] in CAP and knee(a[k]) and knee(b[k])),
              sum(1 for k in both if k[0] == s and k[1] in CAP)) for s in STAGES])
    r.check_close("V2-5", "Cohen kappa of hard_defect between reviewers (ADR 0.441)", 0.441,
                  kappa([(bool(a[k]["hard_defect"]), bool(b[k]["hard_defect"])) for k in both]))
    means = {}
    for s in STAGES:
        toks = [read_json(p)["usage"]["prompt_tokens"] for p in sorted(glob.glob(os.path.join(v2, "runs", s, "*.json")))
                if read_json(p).get("usage")]
        means[s] = sum(toks) / len(toks)
    r.check("V2-6", "mean prompt tokens S0 -> S1, v2, rounded (ADR 6568 -> 6932, +5.5 %)",
            (6568, 6932, 5.5), (round(means["S0"]), round(means["S1"]), round((means["S1"] / means["S0"] - 1) * 100, 1)))
    return a, b


def oracle_numbers(d, r, a, b):
    """Rows of the ADR table that rest on the oracle: run the frozen analysis of commit 264a340."""
    sys.path.insert(0, os.path.join(HERE, "harness-264a340"))
    try:
        from aiexp import analysis2, analyze
    except ImportError as exc:
        raise InputError("cannot import the frozen analysis modules: %s" % exc)
    # v1: the committed stage summary is the output of the same code
    recs1 = analyze.analyze_all(d)
    regenerated = {s: analyze.stage_summary(recs1, s) for s in STAGES}
    committed = read_json(os.path.join(d, "analysis/stage_summary.json"))
    r.check("V1-9", "analysis/stage_summary.json regenerated from runs/ equals the committed file", True,
            json.loads(json.dumps(regenerated)) == committed)
    _, recs, summ, crit, _ = analysis2.tables(d)
    sets = {s: set(summ[s]["cases_with_failure"]) for s in STAGES}
    r.check("V2-7", "cases with hard failure by the oracle, S0/S1/S2 (ADR 7/7/5)", [7, 7, 5], [len(sets[s]) for s in STAGES])
    r.check("V2-8", "regressions S1 against S0 by the oracle (ADR K01, K08, K09)", ["K01", "K08", "K09"], sorted(sets["S1"] - sets["S0"]))
    hits = [(x["stage"], x["case"], x["rep"]) for x in recs if x["status"] == "OK" and x["hard"]]
    r.check("V2-9", "oracle hits; confirmed by A, by B, by both (ADR 26; 16, 16, 13)", (26, 16, 16, 13),
            (len(hits), sum(1 for k in hits if a[k]["hard_defect"]), sum(1 for k in hits if b[k]["hard_defect"]),
             sum(1 for k in hits if a[k]["hard_defect"] and b[k]["hard_defect"])))
    r.check("V2-11", "reviewer hard_defect totals A, B (ADR recall 16/37 = 0.43, 16/45 = 0.36)", (37, 45),
            (sum(1 for v in a.values() if v["hard_defect"]), sum(1 for v in b.values() if v["hard_defect"])))
    r.check("V2-12", "decision by the preregistered criterion, v2 (ADR: no stage accepted)", "NO STAGE ACCEPTED; S0 stays", crit["decision"])
    # Metric W: first tool call arguments in the provider stream, from the archive of the raw v2 streams
    archive = os.path.join(d, "v2", "runs_raw.tar.gz")
    kinds = collections.Counter()
    with tempfile.TemporaryDirectory() as tmp:
        try:
            with tarfile.open(archive, "r:gz") as tf:
                for m in tf.getmembers():
                    parts = m.name.split("/")
                    if m.name.startswith("/") or ".." in parts or not m.isfile():
                        raise InputError("unsafe member in %s: %s" % (archive, m.name))
                    if parts[-1] != "provider-response.sse":
                        continue
                    target = os.path.join(tmp, *parts)
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    with open(target, "wb") as fh:
                        fh.write(tf.extractfile(m).read())
        except (OSError, tarfile.TarError) as exc:
            raise InputError("cannot read %s: %s" % (archive, exc))
        for path in sorted(glob.glob(os.path.join(tmp, "runs_raw", "*", "**", "provider-response.sse"), recursive=True)):
            kinds[analysis2.sse_payload_kind(path)] += 1
    r.check("V2-10", "provider streams: first tool call arguments direct / wrapped (ADR 0 of 72 wrapped in the pilot, 1 of 2 in the smoke)",
            {"direct": 73, "wrapped": 1}, dict(kinds))


def holdout_numbers(d, r):
    h = os.path.join(d, "holdout")
    a = load_reviews(os.path.join(h, "review_a"), os.path.join(h, "review_keys/review_key_a.json"), "case-*.json",
                     lambda k: (k["case"], k["arm"], k["rep"]))
    b = load_reviews(os.path.join(h, "review_b"), os.path.join(h, "review_keys/review_key_b.json"), "case-*.json",
                     lambda k: (k["case"], k["arm"], k["rep"]))
    cases = read_json(os.path.join(h, "keys/slot-key.json"))["cases"]
    both = [k for k in a if k in b]
    r.check("HO-1", "answers reviewed by both reviewers (report-holdout.md: 152)", 152, len(both))
    r.check("HO-2", "hard_defect per reviewer and arm of 76 (report: A 66/46, B 64/33)", (66, 46, 64, 33),
            (sum(1 for k in a if k[1] == "A" and a[k]["hard_defect"]), sum(1 for k in a if k[1] == "B" and a[k]["hard_defect"]),
             sum(1 for k in b if k[1] == "A" and b[k]["hard_defect"]), sum(1 for k in b if k[1] == "B" and b[k]["hard_defect"])))
    r.check_close("HO-3", "Cohen kappa of hard_defect, holdout (report 0.288)", 0.288,
                  kappa([(bool(a[k]["hard_defect"]), bool(b[k]["hard_defect"])) for k in both]))

    def t1(k):
        return all(any(x.get("type") == "T1" for x in rv[k].get("defects", [])) for rv in (a, b))
    capacity = [c for c, v in cases.items() if v["group"] == "X"]
    r.check("HO-4", "P1: capacity cases with a T1 confirmed by both reviewers, arm A / arm B of 8 (report 8, 0)", (8, 0, 8),
            (sum(1 for c in capacity if any(t1((c, "A", n)) for n in (1, 2))), sum(1 for c in capacity if any(t1((c, "B", n)) for n in (1, 2))), len(capacity)))
    slot_case = {v["slot"]: c for c, v in cases.items()}
    independent = "S03 S05 D01 D02 D03 D04 C01 C02 C03 C04 C05 N01 N02 N03 N04 U05 H02 H03 H04".split()
    matrix = [c for c, v in cases.items() if v["group"] != "X"]

    def defective(c, arm):
        return any(a[(c, arm, n)]["hard_defect"] and b[(c, arm, n)]["hard_defect"] for n in (1, 2))
    r.check("HO-5", "P2 primary: 19 independent matrix cases with a confirmed hard_defect, A / B (report 18, 10)", (18, 10, 19),
            (sum(1 for s in independent if defective(slot_case[s], "A")), sum(1 for s in independent if defective(slot_case[s], "B")), len(independent)))
    r.check("HO-6", "P2 secondary: all 30 matrix cases, A / B (report 26, 16)", (26, 16, 30),
            (sum(1 for c in matrix if defective(c, "A")), sum(1 for c in matrix if defective(c, "B")), len(matrix)))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--data-dir", default=DEFAULT_DATA, help="experiment data directory (default: %(default)s)")
    args = ap.parse_args(argv)
    d = os.path.abspath(args.data_dir)
    r = Report()
    try:
        integrity(d, r)
        ledger(d, r)
        v1_numbers(d, r)
        a, b = v2_numbers(d, r)
        oracle_numbers(d, r, a, b)
        holdout_numbers(d, r)
    except (InputError, KeyError, IndexError, ZeroDivisionError, TypeError) as exc:
        print("ERROR input is missing or corrupt: %s: %s" % (type(exc).__name__, exc), file=sys.stderr)
        return 2
    failed = r.rows.count(False)
    print("%d checks, %d OK, %d FAIL" % (len(r.rows), len(r.rows) - failed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
