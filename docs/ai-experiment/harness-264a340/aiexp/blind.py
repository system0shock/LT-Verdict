"""Blind review materials and review-result loading. The key stays in the results directory (outside the worktree)."""
import glob
import json
import os
import random
import re
import secrets

from . import lenient
from .common import PROMPT_DIR, WORKTREE, dump_json, load_json, read_text

MATERIALS = os.path.join(WORKTREE, "_materials", "review")


def build(results, seed=None):
    seed = seed if seed is not None else secrets.randbits(48)
    rng = random.Random(seed)
    runs = []
    for p in sorted(glob.glob(os.path.join(results, "runs", "S*", "*.json"))):
        r = load_json(p)
        out, _kind = lenient.extract(r)  # single-key wrappers are unwrapped for review (deviation from the frozen parser)
        if out is not None:
            runs.append((r["stage"], r["case"], r["rep"], out))
    rng.shuffle(runs)
    key = {}
    by_case = {}
    for i, (stage, case, rep, out) in enumerate(runs, 1):
        rid = "R%03d" % i
        key[rid] = {"stage": stage, "case": case, "rep": rep}
        by_case.setdefault(case, []).append({"id": rid, "output": out})
    rubric = read_text(os.path.join(PROMPT_DIR, "reviewer_rubric.txt"))
    os.makedirs(MATERIALS, exist_ok=True)
    prompts = {}
    for case, items in sorted(by_case.items()):
        d = os.path.join(MATERIALS, case)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(results, "cases", case + ".evidence.json"), "rb") as fh:
            ev = fh.read()
        with open(os.path.join(d, "evidence.json"), "wb") as fh:
            fh.write(ev)
        with open(os.path.join(d, "responses.json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(items, fh, ensure_ascii=False, indent=1)
        ids = ", ".join(i["id"] for i in items)
        text = (rubric + "\nTASK\nCase files (read-only): _materials/review/%s/evidence.json (the exact model input) and "
                "_materials/review/%s/responses.json (a JSON list of {id, output}). Review the responses with ids: %s. "
                "Read only these two files. Answer with the JSON array only.\n" % (case, case, ids))
        pp = os.path.join(MATERIALS, case + ".prompt.txt")
        with open(pp, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        prompts[case] = pp
    dump_json(os.path.join(results, "review_key.json"), {"seed": seed, "key": key})
    return prompts, key


def parse_review_text(text):
    t = text.strip()
    t = re.sub(r"^```(?:json)?\s*|\s*```$", "", t)
    a, b = t.find("["), t.rfind("]")
    if a < 0 or b < a:
        raise ValueError("no JSON array")
    return json.loads(t[a:b + 1])


def load_reviews(results):
    """Return ({(stage,case,rep): review_dict}, problems)."""
    key = load_json(os.path.join(results, "review_key.json"))["key"]
    out, problems = {}, []
    for p in sorted(glob.glob(os.path.join(results, "review", "K*.json"))):
        try:
            arr = parse_review_text(read_text(p))
        except Exception as e:  # noqa: BLE001
            problems.append((os.path.basename(p), str(e)))
            continue
        for item in arr:
            rid = item.get("id")
            if rid in key:
                k = key[rid]
                out[(k["stage"], k["case"], k["rep"])] = item
    return out, problems
