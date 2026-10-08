"""Shared helpers for the AI advice experiment harness (standard library only, ASCII only)."""
import hashlib
import json
import os
import re

MODEL = "deepseek-v4-flash-0731"
PROVIDER_ENDPOINT = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
TASK_ID = "ai-experiment-pilot-2026-09-30"
TASK_LIMIT = 130
TOTAL_LIMIT = 300

HARNESS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKTREE = os.path.dirname(os.path.dirname(HARNESS_DIR))
DEFAULT_RESULTS = r"F:\Coding\LT-Verdict\docs\ui-mockup\ai-experiment-2026-09-30"
SYSTEM_PROMPT_PATH = os.path.join(WORKTREE, "docs", "contracts", "advice", "v1", "system-prompt.md")
SCHEMA_PATH = os.path.join(WORKTREE, "docs", "contracts", "advice", "v1", "ai-advice-output.schema.json")
PROMPT_DIR = os.path.join(HARNESS_DIR, "aiexp", "prompts")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    with open(path, "rb") as fh:
        return sha256_bytes(fh.read())


def read_text(path):
    with open(path, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def load_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def canonical(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def dump_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1, sort_keys=False)
        fh.write("\n")


_ABBREV = [("e.g.", "e_g_"), ("i.e.", "i_e_"), ("vs.", "vs_"), ("etc.", "etc_"), ("approx.", "approx_")]


def split_sentences(text):
    t = text
    for a, b in _ABBREV:
        t = t.replace(a, b)
    parts = re.split(r"(?<=[.!?;])\s+(?=[A-Z0-9(\"'`\[])|\n+", t)
    out = []
    for p in parts:
        for a, b in _ABBREV:
            p = p.replace(b, a)
        p = p.strip()
        if p:
            out.append(p)
    return out
