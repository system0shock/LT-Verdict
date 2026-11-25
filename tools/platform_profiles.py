"""Generate source-connections profiles for the OpenShift label contract (plan P0b/P0d).

One query returns exactly one series (PromqlSource), so every (service, signal) pair is one query and
queries are packed into profiles of at most 64 queries, at most 16 profiles (1024 series).
"""

import argparse
import json
import re
import sys

from tools.platform_profile_templates import PRESET_SIGNALS, SIDECARS, SIGNALS, render, render_preset

MAX_QUERIES_PER_PROFILE = 64
MAX_PROFILES = 16
MAX_IDENTIFIER_BYTES = 128
MAX_QUERY_BYTES = 65_536
MAX_SOURCE_CONFIG_BYTES = 1_048_576
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
LABEL_VALUE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
SUBQUERY_STEP = re.compile(r"[1-9][0-9]*s")
DEFAULT_PREFIX_BY_PRESET = {
    "openshift": "ocp",
    "jvm-standalone": "jvm",
    "postgres-exporter": "pg",
}
SCHEMA_VERSION = "source-connections.v1"
# Controls the correlation catalog binds by name (docs/contracts/diagnostics/v1/correlation-catalog.v1.json):
# key of the load_generator block -> (metric, unit). The expression is the profile author's; the names are fixed here.
LOAD_CONTROLS = {"target_rps": ("target_rps", "requests/s"), "concurrency": ("concurrency", "count")}
DEFAULT_GOVERNOR = {"requests_per_second": 5, "burst": 5, "timeout_ms": 30000, "max_attempts": 3}


def _resolve_targets(config: dict, preset: str) -> list[dict[str, str]]:
    if preset == "openshift":
        services = config["services"]
        names = [config["namespace"], *services]
        for value in names:
            if not isinstance(value, str) or not NAME.fullmatch(value):
                raise ValueError(f"invalid namespace/service/arm name: {value}")
        if not services or len(set(services)) != len(services):
            raise ValueError("services must be non-empty and unique")
        return [
            {
                "entity": service,
                "namespace": config["namespace"],
                "service": service,
            }
            for service in services
        ]

    raw_targets = config.get("targets")
    if raw_targets is None:
        if preset == "jvm-standalone" and "job" in config:
            raw_targets = [{"job": config["job"], "entity": config.get("entity", config["job"])}]
        elif preset == "postgres-exporter" and "instance" in config and "database" in config:
            raw_targets = [
                {
                    "instance": config["instance"],
                    "database": config["database"],
                    "entity": config.get("entity", config["database"]),
                }
            ]
        else:
            raise ValueError(f"preset {preset} requires targets")
    if not isinstance(raw_targets, list) or not raw_targets:
        raise ValueError("targets must be a non-empty list")

    resolved = []
    entities = []
    for item in raw_targets:
        if not isinstance(item, dict):
            raise ValueError("each target must be an object")
        if preset == "jvm-standalone":
            job = item.get("job")
            entity = item.get("entity", job)
            if not isinstance(job, str) or not LABEL_VALUE.fullmatch(job):
                raise ValueError(f"invalid target job: {job}")
            if not isinstance(entity, str) or not NAME.fullmatch(entity):
                raise ValueError(f"invalid target entity: {entity}")
            resolved.append({"entity": entity, "job": job})
        elif preset == "postgres-exporter":
            instance = item.get("instance")
            database = item.get("database")
            entity = item.get("entity", database)
            if not isinstance(instance, str) or not LABEL_VALUE.fullmatch(instance):
                raise ValueError(f"invalid target instance: {instance}")
            if not isinstance(database, str) or not LABEL_VALUE.fullmatch(database):
                raise ValueError(f"invalid target database: {database}")
            if not isinstance(entity, str) or not NAME.fullmatch(entity):
                raise ValueError(f"invalid target entity: {entity}")
            resolved.append({"entity": entity, "instance": instance, "database": database})
        entities.append(entity)
    if len(set(entities)) != len(entities):
        raise ValueError("target entities must be unique")
    return resolved


def build_connections(config: dict) -> dict:
    preset = config.get("preset", "openshift")
    if preset not in PRESET_SIGNALS:
        raise ValueError(f"unknown preset: {preset}")
    signal_catalog = PRESET_SIGNALS[preset]
    targets = _resolve_targets(config, preset)
    signals = config["signals"]
    peak = bool(config.get("peak_aggregation", False))
    names = []
    if "arm" in config:
        names.append(config["arm"])
    if "profile_prefix" in config:
        names.append(config["profile_prefix"])
    load = config.get("load_generator")
    if "load_generator" in config:
        valid = isinstance(load, dict) and set(load) <= {"entity", *LOAD_CONTROLS} and "entity" in load             and any(key in load for key in LOAD_CONTROLS)
        if not valid or not all(isinstance(load[key], str) and load[key] for key in load if key != "entity"):
            raise ValueError("load_generator must be {entity, target_rps and/or concurrency} with non-empty expressions")
        names.append(load["entity"])
    for value in names:
        if not isinstance(value, str) or not NAME.fullmatch(value):
            raise ValueError(f"invalid namespace/service/arm name: {value}")
    if not signals:
        raise ValueError("signals must be non-empty")
    unknown = [name for name in signals if name not in signal_catalog]
    if unknown or len(set(signals)) != len(signals):
        raise ValueError(f"unknown or duplicate signals: {unknown}")
    sidecars = config.get("sidecar_containers")
    if "sidecar_containers" in config:
        try:
            valid = isinstance(sidecars, str) and SIDECARS.fullmatch(sidecars) and re.compile(sidecars)
        except re.error:
            valid = False
        if not valid:
            raise ValueError("sidecar_containers must be a valid regular expression of up to 256 characters from A-Z a-z 0-9 . _ | ( ) * + ? -")
    if sidecars is None and any(signal_catalog[signal].sidecar for signal in signals):
        raise ValueError("sidecar signals need the sidecar container regular expression (sidecar_containers)")
    scrape = config.get("scrape_interval_ms")
    if "scrape_interval_ms" in config:
        if type(scrape) is not int or scrape not in range(1000, 3600001, 1000):
            raise ValueError("scrape_interval_ms must be a whole second from 1000 to 3600000")
    request_step = config.get("request_step_ms")
    if "request_step_ms" in config:
        if scrape is None:
            raise ValueError("request_step_ms requires scrape_interval_ms")
        if type(request_step) is not int or request_step not in range(1000, 60001, 1000):
            raise ValueError("request_step_ms must be a whole second from 1000 to 60000")
    sub = config.get("subquery_step", "15s")
    if not SUBQUERY_STEP.fullmatch(sub):
        raise ValueError("subquery_step must be between 1s and 60s")
    if not 1 <= int(sub[:-1]) <= 60:
        raise ValueError("subquery_step must be between 1s and 60s")
    if scrape is not None and int(sub[:-1]) * 1000 > scrape and any(":@sub@]" in signal_catalog[signal].expression for signal in signals):
        raise ValueError("PLATFORM_SUBQUERY_COARSER_THAN_SCRAPE")
    if request_step is not None:
        too_short = [signal for signal in signals if signal_catalog[signal].uses_rate and scrape * 2 > request_step]
        if too_short:
            raise ValueError(f"PLATFORM_RATE_WINDOW_TOO_SHORT: {', '.join(too_short)}")
        if request_step < scrape:
            raise ValueError("PLATFORM_STEP_BELOW_SCRAPE_INTERVAL")
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

    if load is not None:
        for key in LOAD_CONTROLS:
            expression = load.get(key)
            if expression is None:
                continue
            if "$__interval" not in expression:
                raise ValueError(f"load_generator.{key} must be bound to $__interval")
            if len(expression.encode()) > MAX_QUERY_BYTES:
                raise ValueError("PLATFORM_PROFILE_EXPRESSION_TOO_LARGE")
            if request_step is not None and "rate(" in expression and scrape * 2 > request_step:
                raise ValueError(f"PLATFORM_RATE_WINDOW_TOO_SHORT: load_generator.{key}")

    pairs = [(signal, target) for signal in signals for target in targets]
    if len(pairs) > MAX_QUERIES_PER_PROFILE * MAX_PROFILES:
        raise ValueError("PLATFORM_PROFILE_TOO_MANY_QUERIES")
    base_prefix = config.get("profile_prefix", DEFAULT_PREFIX_BY_PRESET[preset])
    prefix = base_prefix + (f"-{config['arm']}" if config.get("arm") else "")
    size = MAX_QUERIES_PER_PROFILE
    chunks = [pairs[i : i + size] for i in range(0, len(pairs), size)]
    connections = []
    for number, chunk in enumerate(chunks, start=1):
        profile_id = f"{prefix}-{number}"
        if len(profile_id.encode()) > MAX_IDENTIFIER_BYTES:
            raise ValueError(f"profile id too long: {profile_id}")
        queries = []
        rules = []
        for signal, target in chunk:
            entity = target["entity"]
            spec = signal_catalog[signal]
            use_peak = peak and spec.peak
            query_id = f"{entity}.{signal}"
            if len(query_id.encode()) > MAX_IDENTIFIER_BYTES:
                raise ValueError(f"query id too long: {query_id}")
            qualified_query_id = f"{profile_id}/{query_id}"
            if len(qualified_query_id.encode()) > MAX_IDENTIFIER_BYTES:
                raise ValueError(f"qualified id too long: {qualified_query_id}")
            if preset == "openshift":
                expression = render(spec, target["namespace"], target["service"], sub, peak=use_peak, sidecars=sidecars)
                query_labels = {"namespace": target["namespace"]}
            elif preset == "jvm-standalone":
                expression = render_preset(spec, preset, target, sub, peak=use_peak, sidecars=sidecars)
                query_labels = {"job": target["job"]}
            else:
                expression = render_preset(spec, preset, target, sub, peak=use_peak, sidecars=sidecars)
                query_labels = {"instance": target["instance"]}
            if len(expression.encode()) > MAX_QUERY_BYTES:
                raise ValueError("PLATFORM_PROFILE_EXPRESSION_TOO_LARGE")
            queries.append(
                {
                    "id": query_id,
                    "expression": expression,
                    "metric": spec.metric,
                    "unit": spec.unit,
                    "entity": entity,
                    "role": "system",
                    "aggregation": "interval_max" if use_peak else spec.aggregation,
                    "labels": query_labels,
                }
            )
            if signal in legacy:
                rule = legacy[signal]
                rule_id = f"{entity}.{signal}.{rule['operator']}"
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
        if config.get("arm"):
            connection["arm"] = config["arm"]
        if scrape is not None:
            connection["scrape_interval_ms"] = scrape
        connections.append(connection)
    if load is not None:
        if len(chunks) >= MAX_PROFILES:
            raise ValueError("PLATFORM_PROFILE_TOO_MANY_QUERIES")
        load_id = f"{prefix}-load"
        load_queries = []
        for key, (metric, unit) in LOAD_CONTROLS.items():
            if key not in load:
                continue
            query_id = f"{load['entity']}.{key}"
            if len(f"{load_id}/{query_id}".encode()) > MAX_IDENTIFIER_BYTES:
                raise ValueError(f"qualified id too long: {load_id}/{query_id}")
            load_queries.append(
                {
                    "id": query_id,
                    "expression": load[key],
                    "metric": metric,
                    "unit": unit,
                    "entity": load["entity"],
                    "role": "generator",
                    "aggregation": "interval_mean",
                }
            )
        # Same source settings as the service profiles; only id and queries differ.
        connections.append(
            {k: (load_id if k == "id" else load_queries if k == "queries" else v) for k, v in connections[-1].items() if k != "rules"}
        )
    v3 = scrape is not None or bool(config.get("arm"))
    document = {"schema_version": "source-connections.v3" if v3 else SCHEMA_VERSION, "connections": connections}
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
