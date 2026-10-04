"""Generate source-connections.v1 profiles for the OpenShift label contract (plan P0b).

One query returns exactly one series (PromqlSource), so every (service, signal) pair is one query and
queries are packed into profiles of at most 64 queries, at most 16 profiles (1024 series).
"""

import argparse
import json
import re
import sys

from tools.platform_profile_templates import SIGNALS, render

MAX_QUERIES_PER_PROFILE = 64
MAX_PROFILES = 16
MAX_IDENTIFIER_BYTES = 128
MAX_QUERY_BYTES = 65_536
MAX_SOURCE_CONFIG_BYTES = 1_048_576
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
SUBQUERY_STEP = re.compile(r"[1-9][0-9]*s")
SCHEMA_VERSION = "source-connections.v1"
DEFAULT_GOVERNOR = {"requests_per_second": 5, "burst": 5, "timeout_ms": 30000, "max_attempts": 3}


def build_connections(config: dict) -> dict:
    services = config["services"]
    signals = config["signals"]
    peak = bool(config.get("peak_aggregation", False))
    names = [config["namespace"], *services]
    if config.get("arm"):
        names.append(config["arm"])
    for value in names:
        if not isinstance(value, str) or not NAME.fullmatch(value):
            raise ValueError(f"invalid namespace/service/arm name: {value}")
    if not services or len(set(services)) != len(services):
        raise ValueError("services must be non-empty and unique")
    if not signals:
        raise ValueError("signals must be non-empty")
    unknown = [name for name in signals if name not in SIGNALS]
    if unknown or len(set(signals)) != len(signals):
        raise ValueError(f"unknown or duplicate signals: {unknown}")
    sub = config.get("subquery_step", "15s")
    if not SUBQUERY_STEP.fullmatch(sub):
        raise ValueError("subquery_step must be between 1s and 60s")
    if not 1 <= int(sub[:-1]) <= 60:
        raise ValueError("subquery_step must be between 1s and 60s")
    transport = config.get("transport", "direct")
    uid = config.get("datasource_uid")
    if transport not in ("direct", "grafana_proxy"):
        raise ValueError("transport must be direct or grafana_proxy")
    if transport == "grafana_proxy":
        if not isinstance(uid, str) or uid in (".", "..") or not re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", uid):
            raise ValueError("grafana_proxy requires a valid datasource_uid")
    elif "datasource_uid" in config:
        raise ValueError("direct transport must not carry datasource_uid")
    base_url = config["base_url"]
    if not isinstance(base_url, str) or not base_url.startswith(("http://", "https://")):
        raise ValueError("base_url must start with http:// or https://")
    auth = config.get("auth", {"type": "none"})
    if base_url.startswith("http://") and auth.get("type") != "none" and not config.get("allow_insecure_http"):
        raise ValueError("HTTP with auth requires allow_insecure_http")
    legacy = {rule["signal"]: rule for rule in config.get("legacy_sla_rules", [])}
    if set(legacy) - set(signals):
        raise ValueError("legacy_sla_rules reference a signal that is not generated")

    pairs = [(signal, service) for signal in signals for service in services]
    if len(pairs) > MAX_QUERIES_PER_PROFILE * MAX_PROFILES:
        raise ValueError("PLATFORM_PROFILE_TOO_MANY_QUERIES")
    prefix = "ocp" + (f"-{config['arm']}" if config.get("arm") else "")
    size = MAX_QUERIES_PER_PROFILE
    chunks = [pairs[i : i + size] for i in range(0, len(pairs), size)]
    connections = []
    for number, chunk in enumerate(chunks, start=1):
        profile_id = f"{prefix}-{number}"
        if len(profile_id.encode()) > MAX_IDENTIFIER_BYTES:
            raise ValueError(f"profile id too long: {profile_id}")
        queries = []
        rules = []
        for signal, service in chunk:
            spec = SIGNALS[signal]
            use_peak = peak and spec.peak
            query_id = f"{service}.{signal}"
            if len(query_id.encode()) > MAX_IDENTIFIER_BYTES:
                raise ValueError(f"query id too long: {query_id}")
            qualified_query_id = f"{profile_id}/{query_id}"
            if len(qualified_query_id.encode()) > MAX_IDENTIFIER_BYTES:
                raise ValueError(f"qualified id too long: {qualified_query_id}")
            expression = render(spec, config["namespace"], service, sub, peak=use_peak)
            if len(expression.encode()) > MAX_QUERY_BYTES:
                raise ValueError("PLATFORM_PROFILE_EXPRESSION_TOO_LARGE")
            queries.append(
                {
                    "id": query_id,
                    "expression": expression,
                    "metric": spec.metric,
                    "unit": spec.unit,
                    "entity": service,
                    "role": "system",
                    "aggregation": "interval_max" if use_peak else spec.aggregation,
                    "labels": {"namespace": config["namespace"]},
                }
            )
            if signal in legacy:
                rule = legacy[signal]
                rule_id = f"{service}.{signal}.{rule['operator']}"
                if len(rule_id.encode()) > MAX_IDENTIFIER_BYTES:
                    raise ValueError(f"rule id too long: {rule_id}")
                qualified_rule_id = f"{profile_id}/{rule_id}"
                if len(qualified_rule_id.encode()) > MAX_IDENTIFIER_BYTES:
                    raise ValueError(f"qualified id too long: {qualified_rule_id}")
                rules.append(
                    {
                        "id": rule_id,
                        "series_id": query_id,
                        "unit": spec.unit,
                        "operator": rule["operator"],
                        "threshold": rule["threshold"],
                        "min_consecutive_cells": rule["min_consecutive_cells"],
                        "effect": rule["effect"],
                    }
                )
        connection = {
            "id": profile_id,
            "source_kind": "prometheus",
            "transport": transport,
            "base_url": base_url,
            "auth": auth,
            "governor": config.get("governor", DEFAULT_GOVERNOR),
            "queries": queries,
        }
        if transport == "grafana_proxy":
            connection["datasource_uid"] = uid
        if config.get("allow_insecure_http"):
            connection["allow_insecure_http"] = True
        if rules:
            connection["rules"] = rules
        connections.append(connection)
    document = {"schema_version": SCHEMA_VERSION, "connections": connections}
    if len(serialize(document).encode()) > MAX_SOURCE_CONFIG_BYTES:
        raise ValueError("PLATFORM_PROFILE_TOO_LARGE")
    return document


def serialize(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    with open(args.config, encoding="utf-8") as handle:
        config = json.load(handle)
    document = build_connections(config)
    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(serialize(document))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
