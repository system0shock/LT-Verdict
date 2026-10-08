"""Round v2: blind review materials for two independent reviewers and agreement (Cohen's kappa)."""
import glob
import json
import os
import random
import secrets

from . import prompts2
from .blind import parse_review_text
from .common import PROMPT_DIR, WORKTREE, dump_json, load_json, read_text

ROOT = os.path.join(WORKTREE, "_materials")


def build(results, tag, seed=None):
    """tag: 'a' or 'b'. Different shuffles and ids for each reviewer."""
    v2 = os.path.join(results, "v2")
    seed = seed if seed is not None else secrets.randbits(48)
    rng = random.Random(seed)
    runs = []
    for p in sorted(glob.glob(os.path.join(v2, "runs", "S*", "*.json"))):
        r = load_json(p)
        if r.get("status") == "OK" and r.get("output") is not None:
            runs.append((r["stage"], r["case"], r["rep"], r["output"]))
    rng.shuffle(runs)
    key, by_case = {}, {}
    for i, (stage, case, rep, out) in enumerate(runs, 1):
        rid = "R%03d" % i
        key[rid] = {"stage": stage, "case": case, "rep": rep}
        by_case.setdefault(case, []).append({"id": rid, "output": out})
    rubric = read_text(os.path.join(PROMPT_DIR, "reviewer_rubric_v2.txt"))
    base = os.path.join(ROOT, "v2" + tag)
    os.makedirs(base, exist_ok=True)
    prompts = {}
    for case, items in sorted(by_case.items()):
        d = os.path.join(base, case)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(results, "cases", case + ".evidence.json"), "rb") as fh:
            ev = fh.read()
        with open(os.path.join(d, "evidence.json"), "wb") as fh:
            fh.write(ev)
        with open(os.path.join(d, "derived.txt"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(prompts2.derive2(json.loads(ev.decode("utf-8"))))
        with open(os.path.join(d, "responses.json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(items, fh, ensure_ascii=False, indent=1)
        rel = "_materials/v2%s/%s" % (tag, case)
        text = (rubric + "\nTASK\nCase files (read-only): %s/evidence.json, %s/derived.txt and %s/responses.json "
                "(a JSON list of {id, output}). Review the responses with ids: %s. Read only these three files. "
                "Answer with the JSON array only.\n" % (rel, rel, rel, ", ".join(i["id"] for i in items)))
        pp = os.path.join(base, case + ".prompt.txt")
        with open(pp, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        prompts[case] = pp
    dump_json(os.path.join(v2, "review_key_%s.json" % tag), {"seed": seed, "key": key})
    return prompts, key


def load_reviews(results, tag):
    v2 = os.path.join(results, "v2")
    key = load_json(os.path.join(v2, "review_key_%s.json" % tag))["key"]
    out, problems = {}, []
    for p in sorted(glob.glob(os.path.join(v2, "review_%s" % tag, "K*.json"))):
        try:
            arr = parse_review_text(read_text(p))
        except Exception as e:  # noqa: BLE001
            problems.append((os.path.basename(p), str(e)))
            continue
        for item in arr:
            k = key.get(item.get("id"))
            if k:
                out[(k["stage"], k["case"], k["rep"])] = item
    return out, problems


def cohen_kappa(a, b):
    """a, b: dict key->bool. Returns (kappa, n, observed_agreement, expected_agreement)."""
    keys = sorted(set(a) & set(b))
    n = len(keys)
    if n == 0:
        return float("nan"), 0, float("nan"), float("nan")
    agree = sum(1 for k in keys if a[k] == b[k])
    pa = sum(1 for k in keys if a[k]) / n
    pb = sum(1 for k in keys if b[k]) / n
    po = agree / n
    pe = pa * pb + (1 - pa) * (1 - pb)
    kappa = 1.0 if pe == 1 else (po - pe) / (1 - pe)
    return kappa, n, po, pe
