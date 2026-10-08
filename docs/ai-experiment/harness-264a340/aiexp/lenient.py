"""EXPLORATORY, post-hoc: lenient extraction of the model payload.

The frozen (primary) analysis accepts only a tool call whose arguments ARE the advice object, like the product
validator. In the pilot many responses wrapped the object in a single key ("parameters", "output", "return_value")
or contained a stray trailing brace. This module unwraps such payloads so that their SEMANTICS can be compared.
It is not part of the preregistered criterion and every table produced from it must be labelled exploratory.
"""
import glob
import json
import os

from . import analyze as an
from .common import load_json

TOP = {"schema_version", "summary", "hypotheses", "recommendations", "caveats"}


def _arguments(run):
    try:
        msg = run["response"]["choices"][0]["message"]
        calls = msg.get("tool_calls") or []
        if len(calls) == 1 and calls[0]["function"]["name"] == "structured_output":
            return calls[0]["function"]["arguments"]
    except (KeyError, IndexError, TypeError):
        pass
    return None


def _unwrap(obj, depth=0):
    """Return (advice_dict or None, list_of_wrapper_keys)."""
    keys = []
    while depth < 4:
        if isinstance(obj, str):
            try:
                obj = json.loads(obj)
            except ValueError:
                return None, keys
        if not isinstance(obj, dict):
            return None, keys
        if set(obj.keys()) == TOP or "schema_version" in obj:
            return obj, keys
        if len(obj) == 1:
            k = next(iter(obj))
            keys.append(k)
            obj = obj[k]
            depth += 1
            continue
        return None, keys
    return None, keys


def extract(run):
    """Return (output, kind); kind is direct, unwrapped:<keys>, salvaged (raw_decode), or none."""
    args = _arguments(run)
    if args is None:
        return None, "none"
    salvaged = False
    try:
        obj = json.loads(args)
    except ValueError:
        try:
            obj, _ = json.JSONDecoder().raw_decode(args)
            salvaged = True
        except ValueError:
            return None, "none"
    out, keys = _unwrap(obj)
    if out is None:
        return None, "none"
    kind = "direct" if not keys else "unwrapped:" + ">".join(keys)
    if salvaged:
        kind += "+salvaged"
    return out, kind


def analyze_lenient(results):
    an.RESULTS_HOLDER[0] = results
    manifest = {c["id"]: c for c in an.cases_mod.load_manifest(results)}
    cache = {}
    recs = []
    for p in sorted(glob.glob(os.path.join(results, "runs", "S*", "*.json"))):
        run = load_json(p)
        out, kind = extract(run)
        r2 = dict(run)
        if out is not None:
            r2["status"] = "OK"
            r2["output"] = out
        rec = an.analyze_run(r2, manifest, cache)
        rec["payload_kind"] = kind
        recs.append(rec)
    return recs
