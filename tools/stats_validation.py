"""Independent reference definitions for statistical-validation methodology v1.

Test-only stdlib code. No production imports and no acceptance claims from
oracle self-checks. Corpus preparation/execution is a separate implementation step.
"""

from decimal import Decimal, localcontext
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re
import argparse
import platform
import subprocess
import zipfile
from copy import deepcopy


def quantile(values, probability):
    """Type 7, including endpoints; inputs are exact decimal/rational values."""
    probability = Fraction(probability)
    if not 0 <= probability <= 1:
        raise ValueError("probability must be in [0, 1]")
    ordered = sorted(Fraction(value) for value in values)
    if not ordered:
        return None
    position = (len(ordered) - 1) * probability
    index = position.numerator // position.denominator
    if index == len(ordered) - 1:
        return ordered[index]
    return ordered[index] + (position - index) * (ordered[index + 1] - ordered[index])


def _decimal(value):
    return Decimal(value.numerator) / Decimal(value.denominator)


def resource_statistics(values, step_ms):
    """Reference uses pairwise variance and keeps the original time grid."""
    if step_ms <= 0:
        raise ValueError("step_ms must be positive")
    observed = [(i, Fraction(v)) for i, v in enumerate(values) if v is not None]
    samples = [v for _, v in observed]
    count = len(samples)
    gap = longest_gap = 0
    for value in values:
        gap = gap + 1 if value is None else 0
        longest_gap = max(longest_gap, gap)
    mean = sum(samples, Fraction()) / count if count else None
    median = quantile(samples, Fraction(1, 2))
    quartiles = [quantile(samples, p) for p in [Fraction(1, 20), Fraction(1, 4),
                                              Fraction(3, 4), Fraction(19, 20)]]
    variance = slope = standard_deviation = None
    if count >= 2:
        # ponytail: O(n²) exact oracle for short controls; never run per MC report.
        variance = sum((a - b) ** 2 for i, a in enumerate(samples) for b in samples[i + 1:]) / (count * (count - 1))
        times = [Fraction(i * step_ms, 1000) for i, _ in observed]
        time_mean = sum(times) / count
        slope = sum((t - time_mean) * (v - mean) for t, v in zip(times, samples)) / sum((t - time_mean) ** 2 for t in times)
        with localcontext() as ctx:
            ctx.prec = 50
            standard_deviation = _decimal(variance).sqrt()
    halves = [[v for i, v in observed if (i < len(values) // 2) == first] for first in [True, False]]
    return {
        "expected_cells": len(values), "observed_cells": count,
        "missing_cells": len(values) - count, "longest_gap_cells": longest_gap,
        "min": min(samples) if count else None, "max": max(samples) if count else None,
        "mean": mean, "median": median,
        "q05": quartiles[0], "q25": quartiles[1], "q75": quartiles[2], "q95": quartiles[3],
        "iqr": quartiles[2] - quartiles[1] if count else None,
        "mad": quantile([abs(v - median) for v in samples], Fraction(1, 2)),
        "sample_variance": variance, "sample_standard_deviation": standard_deviation,
        "slope_per_second": slope,
        "split_half_shift": quantile(halves[1], Fraction(1, 2)) - quantile(halves[0], Fraction(1, 2))
        if all(halves) else None,
    }


def ranks(values):
    # ponytail: O(n²) rank definition intentionally independent of production sort.
    return [Fraction(1 + sum(other < value for other in values)) +
            Fraction(sum(other == value for other in values) - 1, 2) for value in values]


def _solve(matrix, rhs):
    """Exact Gaussian elimination; singular systems explicitly have no answer."""
    rows = [list(row) + [value] for row, value in zip(matrix, rhs)]
    for col in range(len(rows)):
        pivot = next((i for i in range(col, len(rows)) if rows[i][col]), None)
        if pivot is None:
            return None
        rows[col], rows[pivot] = rows[pivot], rows[col]
        divisor = rows[col][col]
        rows[col] = [value / divisor for value in rows[col]]
        for i in range(len(rows)):
            if i != col:
                factor = rows[i][col]
                rows[i] = [a - factor * b for a, b in zip(rows[i], rows[col])]
    return [row[-1] for row in rows]


def _residual(values, columns):
    gram = [[sum(a * b for a, b in zip(x, y)) for y in columns] for x in columns]
    rhs = [sum(a * b for a, b in zip(column, values)) for column in columns]
    coefficients = _solve(gram, rhs)
    if coefficients is None:
        return None
    return [value - sum(c * column[i] for c, column in zip(coefficients, columns))
            for i, value in enumerate(values)]


def _pearson(x, y):
    if len(x) < 2:
        return None
    mx, my = sum(x) / len(x), sum(y) / len(y)
    xx = sum((v - mx) ** 2 for v in x)
    yy = sum((v - my) ** 2 for v in y)
    if not xx or not yy:
        return None
    xy = sum((a - mx) * (b - my) for a, b in zip(x, y))
    with localcontext() as ctx:
        ctx.prec = 50
        return _decimal(xy) / _decimal(xx * yy).sqrt()


def rank_correlation(x, y, controls=(), max_lag=0):
    """Rank/residualize whole segment once, then compute the signed lag profile.

    Caller supplies one complete segment. Segment selection and production
    capability reasons are checked separately, not inferred by this oracle.
    """
    if len(x) != len(y) or any(len(c) != len(x) for c in controls):
        raise ValueError("length mismatch")
    if not isinstance(max_lag, int) or max_lag < 0 or max_lag >= len(x):
        raise ValueError("invalid lag range")
    columns = [[Fraction(1)] * len(x)] + [ranks(c) for c in controls]
    rx, ry = _residual(ranks(x), columns), _residual(ranks(y), columns)
    if rx is None or ry is None:
        return {"status": "SINGULAR_CONTROLS", "profile": []}
    if not any(rx) or not any(ry):
        return {"status": "NO_RESIDUAL_VARIATION", "profile": []}
    profile = []
    # Contract: identical anchors t=L..N-L-1 at every lag, not variable overlap.
    anchors = range(max_lag, len(rx) - max_lag)
    for lag in range(-max_lag, max_lag + 1):
        a, b = [rx[t] for t in anchors], [ry[t + lag] for t in anchors]
        profile.append({"lag_cells": lag, "coefficient": _pearson(a, b)})
    return {"status": "OK", "profile": profile}


def _json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True,
                       separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def _sha(value):
    return hashlib.sha256(value).hexdigest()


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key: " + key)
        result[key] = value
    return result


def _read_json(raw):
    def reject_constant(value):
        raise ValueError("non-finite JSON number: " + value)
    return json.loads(raw, object_pairs_hook=_unique_object, parse_constant=reject_constant)


def _numeric(value):
    if isinstance(value, bool) or not isinstance(value, (str, int, float, Decimal)):
        raise ValueError("expected a finite decimal")
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError("non-finite decimal")
    return result


def freeze_cases(output, cases):
    """Freeze assertions only; source archive/full study coverage are not implied.

    Return digest for the caller to retain outside this directory. Existing
    output is never replaced, even if its previous preparation was interrupted.
    """
    if not cases:
        raise ValueError("empty corpus")
    # ponytail: short fixtures in memory; stream artifacts before bulk calibration.
    artifacts, entries, ids = {}, [], set()
    for case in cases:
        case_id = case["id"]
        if not isinstance(case_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", case_id) or case_id in ids:
            raise ValueError("invalid or duplicate case ID")
        ids.add(case_id)
        expected = case["expected"]
        if set(expected) != {"exact", "numeric"} or not all(isinstance(v, dict) for v in expected.values()):
            raise ValueError("invalid assertion document")
        if not expected["exact"] and not expected["numeric"]:
            raise ValueError("vacuous assertions")
        for assertions in expected.values():
            if any(not isinstance(p, str) or not p.startswith("/") or re.search(r"~(?![01])", p)
                   for p in assertions):
                raise ValueError("invalid JSON pointer")
        for value in expected["numeric"].values():
            if not isinstance(value, str):
                raise ValueError("expected numeric assertion must be a string")
            _numeric(value)
        entry = {"id": case_id, "family": case["family"], "configuration": case["configuration"]}
        for kind, value in [("inputs", case["input"]), ("expected", expected)]:
            name = f"{kind}/{case_id}.json"
            raw = _json_bytes(value)
            artifacts[name] = raw
            entry[kind] = {"path": name, "sha256": _sha(raw)}
        entries.append(entry)
    manifest = _json_bytes({"schema_version": "stats-cases.v1", "cases": sorted(entries, key=lambda e: e["id"])})
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    for name, raw in artifacts.items():
        path = output / name
        path.parent.mkdir(exist_ok=True)
        with path.open("xb") as stream:
            stream.write(raw)
    with (output / "manifest.json").open("xb") as stream:
        stream.write(manifest)
    return _sha(manifest)


def _at_pointer(output, pointer):
    for part in pointer[1:].split("/"):
        key = part.replace("~1", "/").replace("~0", "~")
        if isinstance(output, list):
            if not re.fullmatch(r"0|[1-9][0-9]*", key):
                raise ValueError("invalid array index")
            output = output[int(key)]
        else:
            output = output[key]
    return output


def check_cases(directory, manifest_sha256, actual_path):
    """Fail closed on missing/corrupted records; MATCH is not study-level PASS."""
    directory = Path(directory).resolve()
    summary = {"status": "INCOMPLETE", "planned": 0, "completed": 0, "cases": [], "errors": []}
    try:
        raw = (directory / "manifest.json").read_bytes()
        if _sha(raw) != manifest_sha256:
            raise ValueError("manifest hash mismatch")
        manifest = _read_json(raw)
        if manifest["schema_version"] != "stats-cases.v1" or not manifest["cases"]:
            raise ValueError("invalid manifest")
        entries = {entry["id"]: entry for entry in manifest["cases"]}
        summary["planned"] = len(manifest["cases"])
        if len(entries) != summary["planned"]:
            raise ValueError("duplicate manifest case")
        expectations = {}
        for case_id, entry in entries.items():
            for kind in ["inputs", "expected"]:
                path = (directory / entry[kind]["path"]).resolve()
                if not path.is_relative_to(directory):
                    raise ValueError("artifact outside frozen directory")
                raw = path.read_bytes()
                if _sha(raw) != entry[kind]["sha256"]:
                    raise ValueError(f"artifact hash mismatch: {case_id}/{kind}")
                if kind == "expected":
                    expectations[case_id] = _read_json(raw)
        actual = {}
        with Path(actual_path).open(encoding="utf-8") as stream:
            for line in stream:
                record = _read_json(line)
                _json_bytes(record)
                case_id = record["id"]
                if case_id not in entries or case_id in actual:
                    raise ValueError("unknown or duplicate actual ID: " + str(case_id))
                actual[case_id] = record
        for case_id in entries:
            record = actual.get(case_id)
            if record is None or "error" in record or "output" not in record:
                summary["errors"].append({"id": case_id, "reason": "MISSING_OR_ERROR", "record": record})
                continue
            summary["completed"] += 1
            mismatches = []
            for kind, assertions in expectations[case_id].items():
                for pointer, expected in assertions.items():
                    try:
                        observed = _at_pointer(record["output"], pointer)
                        if kind == "exact":
                            matches = _json_bytes(observed) == _json_bytes(expected)
                        elif kind == "numeric":
                            with localcontext() as ctx:
                                ctx.prec = 50
                                a, e = _numeric(observed), _numeric(expected)
                                matches = abs(a - e) <= Decimal("1e-9") * (1 + abs(e))
                        else:
                            raise ValueError("unknown assertion kind")
                        if not matches:
                            mismatches.append({"pointer": pointer, "expected": expected, "actual": observed})
                    except (KeyError, IndexError, TypeError, ValueError, ArithmeticError) as exc:
                        mismatches.append({"pointer": pointer, "expected": expected, "reason": str(exc)})
            summary["cases"].append({"id": case_id, "status": "FAIL" if mismatches else "MATCH", "mismatches": mismatches})
        if not summary["errors"]:
            summary["status"] = "FAIL" if any(c["status"] == "FAIL" for c in summary["cases"]) else "MATCH"
    except (OSError, KeyError, TypeError, ValueError, ArithmeticError) as exc:
        summary["errors"].append({"reason": str(exc)})
    return summary


EPOCH = 1767225600000


def _utf16_order(text):
    """Sort key equal to JVM String order (UTF-16 code units), unlike code-point order for non-BMP text."""
    return text.encode('utf-16-be', 'surrogatepass')


def _canonical(value):
    """Wire contract canonicalization (ADR-0003), independent of production imports."""
    if isinstance(value, dict):
        return '{' + ','.join(json.dumps(k, ensure_ascii=False) + ':' + _canonical(value[k])
                              for k in sorted(value, key=_utf16_order)) + '}'
    if isinstance(value, list):
        return '[' + ','.join(map(_canonical, value)) + ']'
    if isinstance(value, (int, float, Decimal)) and not isinstance(value, bool):
        number = Decimal(str(value))
        if not number.is_finite():
            raise ValueError('nonfinite input')
        text = format(number, 'f')
        if '.' in text:
            text = text.rstrip('0').rstrip('.')
        return '0' if text in ('-0', '') else text
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def snapshot_hash(snapshot):
    """Semantic hash of a snapshot object; read files with parse_float=Decimal so 18+ digit values stay exact."""
    semantic = {key: value for key, value in snapshot.items() if key != 'provenance'}
    semantic['series'] = sorted([{'labels': {}} | value for value in snapshot['series']],
                                key=lambda v: _utf16_order(v['id']))
    semantic['windows'] = sorted(snapshot.get('windows', []),
                                 key=lambda v: (v['from_epoch_ms'], v['to_epoch_ms'], _utf16_order(v['id'])))
    semantic['rules'] = list(snapshot.get('rules', []))
    return _sha(_canonical(semantic).encode())


def _snapshot(series, windows, step=1000, epoch=EPOCH, rules=()):
    return {'schema_version': 'resource-snapshot.v1', 'load_input_sha256': '0' * 64,
            'start_epoch_ms': epoch, 'step_ms': step, 'point_count': len(series[0]['values']),
            'series': series, 'windows': [{'id': name, 'from_epoch_ms': epoch + start * step,
                                         'to_epoch_ms': epoch + end * step} for name, start, end in windows],
            'rules': list(rules), 'provenance': {'source_kind': 'fixture',
            'query_semantics': 'declared interval aggregation', 'clock_alignment': 'declared_aligned'}}


def _series(values, name='value', unit='synthetic_unit'):
    return {'id': name, 'metric': name, 'unit': unit, 'entity': 'service', 'role': 'system',
            'aggregation': 'interval_mean', 'labels': {}, 'values': values}


def _load(latencies, counts=None, errors=None, epoch=EPOCH):
    counts = counts if counts is not None else [20] * len(latencies)
    errors = errors if errors is not None else [0] * len(latencies)
    rows = ['timeStamp,elapsed,label,success']
    for cell, (latency, count, error) in enumerate(zip(latencies, counts, errors, strict=True)):
        for request in range(count):
            # Explicit deterministic sampling: first start at cell boundary,
            # last at +999ms. No extra boundary/support requests are fabricated.
            timestamp = epoch + cell * 1000 + (request * 999 // max(1, count - 1))
            rows.append(f'{timestamp},{latency},request,{str(request >= error).lower()}')
    return '\n'.join(rows) + '\n'


def _run(snapshot, latencies, counts=None, errors=None, pairs=(), anomalies=(), policy=None):
    load = _load(latencies, counts, errors, snapshot['start_epoch_ms'])
    snapshot['load_input_sha256'] = _sha(load.encode())
    result = {'load_jtl': load, 'resources': snapshot}
    if pairs or anomalies:
        result['diagnostics'] = {'schema_version': 'correlation-plan.v1',
                                 'resource_snapshot_sha256': snapshot_hash(snapshot),
                                 'pairs': list(pairs), 'anomalies': list(anomalies)}
    if policy is not None:
        result['policy'] = policy
    return result


def _number(value):
    with localcontext() as ctx:
        ctx.prec = 50
        return str(_decimal(value) if isinstance(value, Fraction) else value)


def correctness_cases():
    """Named initial deterministic batch; explicitly NOT the entire §4 matrix.

    Every expected number comes from literals/reference definitions before
    execution. Coverage omissions remain visible in preparation metadata.
    """
    cases = []

    def add(name, operation, exact, numeric=None):
        cases.append({'id': name, 'family': name[:3], 'configuration': name,
                      'input': operation, 'expected': {'exact': exact,
                      'numeric': {p: _number(v) for p, v in (numeric or {}).items()}}})

    for name, values, step, shift in [
        ('S01', [0, 1, 2, 3], 10000, 0), ('S02_constant', [5] * 4, 10000, 0),
        ('S02_missing', [None] * 4, 10000, 0), ('S02_single', [5], 10000, 0),
        ('S03', [1, None, 3, None], 10000, 0), ('S04_time', [0, 1, 2, 3], 10000, 876543000),
        ('S04_affine', [7, 10, 13, 16], 10000, 0), ('S04_step', [0, 1, 2, 3], 1000, 0),
    ]:
        snapshot = _snapshot([_series(values)], [('evaluation', 0, len(values))], step, EPOCH + shift)
        stats = resource_statistics(values, step)
        prefix = '/resource_summaries/evaluation/value/'
        exact, numeric = {}, {}
        for field, value in stats.items():
            if field == 'sample_variance':  # Product exposes standard deviation, not variance.
                continue
            path = prefix + (field if field.endswith('_cells') else 'statistics/' + field)
            (exact if value is None or field.endswith('_cells') else numeric)[path] = value
        add(name, {'operation': 'resource', 'resources': snapshot}, exact, numeric)

    # Inclusive duration/absolute gates, missing cells and sign switches, for
    # every supported signal. Missing RPS is not representable by zero arrivals;
    # that special missing variant stays unimplemented rather than forged.
    variants = [
        ('A01', [99, 100, 101] * 10, [100] * 10 + [160] * 5 + [100] * 10, [(10, 15, 'increase')], 0),
        ('A02_short', [99, 100, 101] * 10, [100] * 10 + [160] * 2 + [100] * 10, [], 1),
        ('A02_signs', [99, 100, 101] * 10, [100] * 10 + [160] * 3 + [40] * 3 + [100] * 10,
         [(10, 13, 'increase'), (13, 16, 'decrease')], 0),
        ('A03_zero', [100] * 30, [100] * 10 + [120] * 3 + [100] * 10, [(10, 13, 'increase')], 0),
        ('A03_ref29', [100] * 29, [160] * 10, [], 0),
        ('A03_abs_equal', [99, 100, 101] * 10, [120] * 3, [(0, 3, 'increase')], 0),
    ]
    for name, ref, evaluation, episodes, suppressed in variants:
        for signal in ['resource', 'response_time_p95_ms', 'throughput_rps', 'error_rate']:
            values = ref + evaluation
            scaled = [v / 1000 for v in values] if signal == 'error_rate' else values
            snapshot = _snapshot([_series(scaled)], [('reference', 0, len(ref)), ('evaluation', len(ref), len(values))])
            rule = {'id': 'anomaly', 'signal': {'series_id': 'value'} if signal == 'resource' else {'load_metric': signal},
                    'reference_window_id': 'reference', 'window_id': 'evaluation', 'direction': 'either',
                    'min_abs_delta': .02 if signal == 'error_rate' else 20, 'min_duration_ms': 3000, 'z_threshold': 3.5}
            run = _run(snapshot, values if signal == 'response_time_p95_ms' else [100] * len(values),
                       counts=values if signal == 'throughput_rps' else ([1000] * len(values) if signal == 'error_rate' else None),
                       errors=values if signal == 'error_rate' else None, anomalies=[rule])
            exact = {'/persisted_equal': True, '/anomaly_checks/anomaly/episodes_reported': len(episodes),
                     '/anomaly_checks/anomaly/suppressed_short_episodes': suppressed}
            if len(ref) < 30:
                exact['/anomaly_checks/anomaly/status'] = 'INSUFFICIENT_DATA'
            for i, (start, end, direction) in enumerate(episodes):
                exact[f'/findings/{i}/type'] = 'anomaly_episode'
                exact[f'/findings/{i}/from_epoch_ms'] = EPOCH + (len(ref) + start) * 1000
                exact[f'/findings/{i}/to_epoch_ms'] = EPOCH + (len(ref) + end) * 1000
                exact[f'/findings/{i}/direction'] = direction
            numeric = {} if len(ref) < 30 else {
                '/anomaly_checks/anomaly/reference_median': Fraction(1, 10) if signal == 'error_rate' else 100,
                '/anomaly_checks/anomaly/reference_mad': (Fraction(1, 1000) if signal == 'error_rate' else 1) if name not in ['A03_zero'] else 0}
            add(name + '_' + signal, {'operation': 'analysis', 'run': run}, exact, numeric)

    x = [1, 2, 3, 4, 5] * 6
    y = [100, 300, 200, 500, 400] * 6
    correlation_variants = [
        ('C01_positive', x, y, [], 0, 'DESCRIPTIVE'),
        ('C01_negative', x, [600-v for v in y], [], 0, 'DESCRIPTIVE'),
        ('C01_ties', x, [v * 100 for v in x], [], 0, 'DESCRIPTIVE'),
        ('C01_constant', x, [100] * 30, [], 0, 'INSUFFICIENT_DATA'),
        ('C02_one_control', [1, 2, 3, 4] * 10, [100, 200, 400, 300] * 10, [[1, 4, 2, 3] * 10], 0, 'CANDIDATE'),
        ('C02_two_controls', [1, 2, 3, 4, 5, 6] * 7, [100, 300, 200, 600, 400, 500] * 7,
         [[2, 1, 5, 3, 6, 4] * 7, [6, 2, 1, 5, 3, 4] * 7], 0, None),
    ]
    signal = [(i * 17 % 47) + 1 for i in range(47)]
    for lag in [-3, 3]:
        # Explicit cyclic permutation is §4 C03, not the no-wrap MC family.
        correlation_variants.append(('C03_' + ('lead' if lag > 0 else 'follow'), signal,
                                     [signal[(i-lag) % 47] * 10 for i in range(47)], [], 3, 'DESCRIPTIVE'))
    for name, values, latency, controls, lag, status in correlation_variants:
        series = [_series(values)] + [_series(v, f'control{i}', 'requests/s' if i == 0 else 'count') for i, v in enumerate(controls)]
        snapshot = _snapshot(series, [('evaluation', 0, len(values))])
        pair = {'id': 'pair', 'resource_series_id': 'value', 'load_metric': 'response_time_p95_ms',
                'window_ids': ['evaluation'], 'expected_sign': 'either', 'max_lag_ms': lag * 1000,
                'min_abs_effect': .3, 'min_resource_delta': .1, 'min_load_delta': 20,
                'topology_basis': 'single service', 'clock_alignment': 'declared_aligned',
                'controls': [{'meaning': 'target_rps' if i == 0 else 'concurrency', 'series_id': f'control{i}'} for i in range(len(controls))]}
        prefix = '/correlation_pairs/evaluation/pair/'
        exact = {'/persisted_equal': True, prefix + 'paired_cells': len(values), prefix + 'uncertainty': 'NOT_ESTIMATED'}
        if status is not None:
            exact[prefix + 'status'] = status
        numeric = {}
        raw = rank_correlation(values, latency)
        if raw['status'] == 'OK':
            numeric[prefix + 'raw_rho'] = raw['profile'][0]['coefficient']
        else:
            exact[prefix + 'raw_rho'] = None
        association = rank_correlation(values, latency, controls, lag)
        if association['status'] == 'OK':
            if controls:
                numeric[prefix + 'partial_rho'] = rank_correlation(values, latency, controls)['profile'][0]['coefficient']
            exact[prefix + 'lag_used_cells'] = len(values) - 2 * lag
            for i, point in enumerate(association['profile']):
                exact[prefix + f'lag_profile/{i}/lag_ms'] = point['lag_cells'] * 1000
                numeric[prefix + f'lag_profile/{i}/rho'] = point['coefficient']
        add(name, {'operation': 'analysis', 'run': _run(snapshot, latency, pairs=[pair])}, exact, numeric)

    for name, values, effect, expected in [
        ('V01_resource_pass', [50] * 30, 'sla', 'PASS'),
        ('V01_resource_fail', [90] * 30, 'sla', 'FAIL'),
        ('V01_resource_gap', [90] * 10 + [None] + [90] * 19, 'sla', 'NO_VERDICT'),
        ('V01_diagnostic_only', [90] * 30, 'diagnostic', 'NO_POLICY'),
    ]:
        rule = {'id': 'limit', 'series_id': 'value', 'unit': 'synthetic_unit', 'operator': 'gt',
                'threshold': 80, 'min_consecutive_cells': 3, 'effect': effect}
        snapshot = _snapshot([_series(values)], [('evaluation', 0, 30)], rules=[rule])
        add(name, {'operation': 'analysis', 'run': _run(snapshot, [100] * 30)},
            {'/persisted_equal': True, '/result/policy_verdict': expected})

    def comparison_run(latency, counts=None, errors=None, resource_value=100):
        snapshot = _snapshot([_series([resource_value] * len(latency))], [('evaluation', 0, len(latency))])
        pair = {'id': 'declared', 'resource_series_id': 'value', 'load_metric': 'response_time_p95_ms',
                'window_ids': ['evaluation'], 'expected_sign': 'either', 'max_lag_ms': 0,
                'min_abs_effect': .3, 'min_resource_delta': .1, 'min_load_delta': 20,
                'topology_basis': 'single service', 'clock_alignment': 'declared_aligned', 'controls': []}
        return _run(snapshot, latency, counts, errors, pairs=[pair])

    for name, baseline, current, exact, numeric in [
        ('W01_latency', comparison_run([100] * 30), comparison_run([120] * 30),
         {'/comparison/window_comparison/metrics/1/status': 'DESCRIPTIVE'},
         {'/comparison/window_comparison/metrics/1/delta': 20, '/comparison/window_comparison/metrics/1/delta_percent': 20}),
        ('W01_equal_rate_duration', comparison_run([100] * 30), comparison_run([100] * 60),
         {'/comparison/window_comparison/metrics/3/status': 'NO_MATERIAL_CHANGE'},
         {'/comparison/window_comparison/metrics/3/delta': 0}),
        ('W03_error_zero', comparison_run([100] * 30, [1000] * 30), comparison_run([100] * 30, [1000] * 30, [1] * 30),
         {'/comparison/window_comparison/metrics/4/delta_percent': None,
          '/comparison/window_comparison/metrics/4/status': 'DESCRIPTIVE'},
         {'/comparison/window_comparison/metrics/4/delta': Fraction(1, 1000)}),
        ('W04_union_not_mean', comparison_run([100] * 2, [210, 210]), comparison_run([1000, 100], [20, 400]),
         {'/comparison/window_comparison/metrics/1/status': 'NO_MATERIAL_CHANGE'},
         {'/comparison/window_comparison/metrics/1/current': 100, '/comparison/window_comparison/metrics/1/delta': 0}),
    ]:
        add(name, {'operation': 'comparison', 'baseline': baseline, 'current': current,
                   'baseline_window_id': 'evaluation', 'current_window_id': 'evaluation'},
            {'/persisted_equal': True, '/comparison/comparability': 'UNCONFIRMED'} | exact, numeric)
    return cases


def supplemental_cases():
    """Additional predeclared branch fixtures; never read prior actual outputs."""
    initial = {case['id']: case for case in correctness_cases()}
    cases = []

    def add(name, operation, exact, numeric=None):
        runs = ([operation['run']] if operation['operation'] == 'analysis' else
                [operation['baseline'], operation['current']] if operation['operation'] == 'comparison' else [])
        for run in runs:
            run['resources']['load_input_sha256'] = _sha(run['load_jtl'].encode())
            if 'diagnostics' in run:
                run['diagnostics']['resource_snapshot_sha256'] = snapshot_hash(run['resources'])
        cases.append({'id': name, 'family': name[:3], 'configuration': name, 'input': operation,
                      'expected': {'exact': ({'/persisted_equal': True} if runs else {}) | exact,
                                   'numeric': {p: _number(v) for p, v in (numeric or {}).items()}}})

    for signal in ['resource', 'response_time_p95_ms', 'throughput_rps', 'error_rate']:
        for gate in ['abs', 'z']:
            for direction, adjustment in [('below', Decimal('-.000001')), ('equal', Decimal(0)), ('above', Decimal('.000001'))]:
                if gate == 'abs' and direction == 'equal':
                    continue  # Already frozen and executed in the initial batch.
                operation = deepcopy(initial['A03_abs_equal_' + signal]['input'])
                rule = operation['run']['diagnostics']['anomalies'][0]
                field, boundary = ('min_abs_delta', Decimal('.02') if signal == 'error_rate' else Decimal(20)) if gate == 'abs' else ('z_threshold', Decimal('13.49'))
                rule[field] = float(boundary + adjustment)
                add(f'A03_{gate}_{direction}_{signal}', operation,
                    {'/anomaly_checks/anomaly/episodes_reported': int(adjustment <= 0),
                     '/anomaly_checks/anomaly/suppressed_short_episodes': 0})

    for signal in ['resource', 'response_time_p95_ms', 'error_rate']:
        operation = deepcopy(initial['A02_short_' + signal]['input'])
        run = operation['run']
        values = [99, 100, 101] * 10 + [100] * 10 + [160, 160, None, 160, 160] + [100] * 10
        resource_values = [None if v is None else v / 1000 for v in values] if signal == 'error_rate' else values
        run['resources'] = _snapshot([_series(resource_values)], [('reference', 0, 30), ('evaluation', 30, len(values))])
        counts = [0 if v is None else (1000 if signal == 'error_rate' else 20) for v in values]
        run['load_jtl'] = _load([v or 100 for v in values] if signal == 'response_time_p95_ms' else [100]*len(values),
                                counts if signal != 'resource' else None,
                                [v or 0 for v in values] if signal == 'error_rate' else None)
        add('A02_gap_' + signal, operation, {'/anomaly_checks/anomaly/episodes_reported': 0,
                                            '/anomaly_checks/anomaly/suppressed_short_episodes': 2})

    prefix = '/correlation_pairs/evaluation/pair/'
    for name, original, threshold, sign, status in [
        ('C05_submaterial', 'C01_ties', .3, 'either', 'BELOW_EFFECT'),
        ('C05_wrong_sign', 'C01_negative', .3, 'positive', 'OPPOSITE_SIGN'),
        ('C06_effect_equal', 'C01_positive', .8, 'either', 'DESCRIPTIVE'),
        ('C06_effect_below', 'C01_positive', .799999, 'either', 'DESCRIPTIVE'),
        ('C06_effect_above', 'C01_positive', .800001, 'either', 'BELOW_EFFECT'),
    ]:
        operation = deepcopy(initial[original]['input'])
        pair = operation['run']['diagnostics']['pairs'][0]
        pair.update(min_abs_effect=threshold, expected_sign=sign)
        if name == 'C05_submaterial':
            pair['min_load_delta'] = 5000
        add(name, operation, {prefix+'status': status, '/findings': [], prefix+'controls_used': []},
            {prefix+'raw_rho': {'C01_ties': 1, 'C01_negative': '-.8', 'C01_positive': '.8'}[original]})

    operation = deepcopy(initial['C01_positive']['input'])
    run = operation['run']
    run['resources']['series'].append(_series([20]*30, 'target', 'requests/s'))
    run['diagnostics']['pairs'][0]['controls'] = [{'meaning': 'target_rps', 'series_id': 'target'}]
    add('C02_constant_control', operation,
        {prefix+'controls_dropped': ['target'], prefix+'controls_used': [], prefix+'status': 'CANDIDATE'},
        {prefix+'partial_rho': '.8'})

    operation = deepcopy(initial['C02_one_control']['input'])
    run = operation['run']
    original_control = run['resources']['series'][1]['values']
    run['resources']['series'].append(_series(original_control, 'redundant', 'count'))
    run['diagnostics']['pairs'][0]['controls'].append({'meaning': 'concurrency', 'series_id': 'redundant'})
    add('C02_redundant_control', operation, {prefix+'controls_dropped': ['redundant'], prefix+'controls_used': ['control0']},
        {prefix+'partial_rho': rank_correlation([1,2,3,4]*10, [100,200,400,300]*10, [original_control])['profile'][0]['coefficient']})

    operation = deepcopy(initial['C01_ties']['input'])
    run = operation['run']
    run['resources']['series'].append(_series([1,2,3,4,5]*6, 'target', 'requests/s'))
    run['diagnostics']['pairs'][0]['controls'] = [{'meaning': 'target_rps', 'series_id': 'target'}]
    add('C02_no_residual', operation, {prefix+'partial_rho': None, prefix+'status': 'INSUFFICIENT_DATA', '/findings': []})

    for count in [19, 20]:
        operation = deepcopy(initial['C01_positive']['input'])
        operation['run']['load_jtl'] = _load([100,300,200,500,400]*6, [count]*30)
        add(f'W04_support_{count}', operation,
            {prefix+'paired_cells': 0 if count == 19 else 30,
             prefix+'status': 'INSUFFICIENT_DATA' if count == 19 else 'DESCRIPTIVE'},
            {prefix+'raw_rho': '.8'} if count == 20 else {})

    operation = deepcopy(initial['C01_positive']['input'])
    run = operation['run']
    run['resources']['point_count'] = 29
    run['resources']['series'][0]['values'] = run['resources']['series'][0]['values'][:29]
    run['resources']['windows'][0]['to_epoch_ms'] = EPOCH + 29000
    run['load_jtl'] = _load(([100,300,200,500,400]*6)[:29])
    add('C04_support29', operation, {prefix+'paired_cells': 29, prefix+'status': 'INSUFFICIENT_DATA', prefix+'lag_used_cells': 0, '/findings': []})

    operation = deepcopy(initial['C03_lead']['input'])
    operation['run']['diagnostics']['pairs'][0]['clock_alignment'] = 'unknown'
    add('C04_clock_unknown', operation,
        {prefix+'status': 'DESCRIPTIVE', prefix+'reasons': ['CLOCK_ALIGNMENT_UNKNOWN', 'CONTROL_CONTEXT_MISSING']},
        initial['C03_lead']['expected']['numeric'])

    operation = deepcopy(initial['C01_positive']['input'])
    run = operation['run']
    x = [1,2,3,4,5]*6 + [None] + [1,2,3,4,5]*7
    y = [500,400,300,200,100]*6 + [100] + [100,300,200,500,400]*7
    run['resources'] = _snapshot([_series(x)], [('evaluation',0,len(x))])
    run['load_jtl'] = _load(y)
    run['diagnostics']['pairs'][0]['max_lag_ms'] = 2000
    profile = rank_correlation(x[31:], y[31:], max_lag=2)['profile']
    exact = {prefix+'paired_cells':65, prefix+'lag_used_cells':31}
    numeric = {prefix+'raw_rho':rank_correlation(x[:30]+x[31:],y[:30]+y[31:])['profile'][0]['coefficient']}
    for i, point in enumerate(profile):
        exact[prefix+f'lag_profile/{i}/lag_ms'] = point['lag_cells']*1000
        numeric[prefix+f'lag_profile/{i}/rho'] = point['coefficient']
    add('C04_longest_not_strongest', operation, exact, numeric)

    operation = deepcopy(initial['C01_positive']['input'])
    run = operation['run']
    run['resources'] = _snapshot([_series([1,2,3,4,5]*8)], [('first',0,20), ('second',20,40)])
    run['load_jtl'] = _load([100,300,200,500,400]*8)
    run['diagnostics']['pairs'][0]['window_ids'] = ['first','second']
    add('C04_windows_not_joined', operation,
        {f'/correlation_pairs/{window}/pair/{field}': value for window in ['first','second']
         for field,value in [('paired_cells',20),('lag_used_cells',0),('status','INSUFFICIENT_DATA')]})

    operation = deepcopy(initial['C02_one_control']['input'])
    run = operation['run']
    counts = [30,50,20,40]*10
    run['load_jtl'] = _load([100,200,400,300]*10, counts)
    run['diagnostics']['pairs'][0]['controls'].append({'meaning':'achieved_rps'})
    add('C06_achieved_sensitivity', operation,
        {prefix+'controls_requested':['achieved_rps','control0'], prefix+'controls_used':['achieved_rps','control0']},
        {prefix+'partial_rho':1, prefix+'sensitivity_without_achieved_rps':
         rank_correlation([1,2,3,4]*10,[100,200,400,300]*10,[[1,4,2,3]*10])['profile'][0]['coefficient']})

    operation = deepcopy(initial['S01']['input'])
    operation['resources']['series'] = [_series([3,2,1,0], 'z'), _series([0,1,2,3], 'a')]
    add('S04_series_permutation', operation, {},
        {'/resource_summaries/evaluation/a/statistics/slope_per_second': '.1',
         '/resource_summaries/evaluation/z/statistics/slope_per_second': '-.1'})

    for mismatch in ['labels', 'unit', 'aggregation']:
        operation = deepcopy(initial['W01_latency']['input'])
        series = operation['current']['resources']['series'][0]
        series[mismatch] = {'stage': 'other'} if mismatch == 'labels' else ('other_unit' if mismatch == 'unit' else 'interval_rate')
        add('W02_binding_' + mismatch, operation,
            {f'/comparison/window_comparison/metrics/{i}/reason': 'RESOURCE_BINDING_MISSING' for i in range(5,9)})

    operation = deepcopy(initial['W01_latency']['input'])
    operation['current_window_id'] = 'absent'
    add('W03_missing_window', operation, {'/comparison/window_comparison/status': 'NOT_EVALUATED',
                                         '/comparison/window_comparison/reasons': ['CURRENT_WINDOW_NOT_FOUND']})

    operation = deepcopy(initial['W01_latency']['input'])
    for member, tail in [('baseline', 1), ('current', 30)]:
        run = operation[member]
        run['resources'] = _snapshot([_series([100]*30 + [200]*tail)], [('evaluation',0,30), ('tail',30,30+tail)])
        run['load_jtl'] = _load([100]*30 + [900]*tail)
    add('W02_matched_mixture', operation, {'/comparison/window_comparison/metrics/1/status': 'NO_MATERIAL_CHANGE'},
        {'/comparison/window_comparison/metrics/1/delta': 0, '/comparison/window_comparison/metrics/3/delta': 0})

    for name, resource, business_threshold, verdict in [
        ('V01_combined_pass', 'V01_resource_pass', 120, 'PASS'),
        ('V01_business_fail', 'V01_resource_pass', 80, 'FAIL'),
        ('V01_resource_business_fail', 'V01_resource_fail', 80, 'FAIL'),
        ('V02_missing_and_violation', 'V01_resource_gap', 80, 'NO_VERDICT'),
    ]:
        operation = deepcopy(initial[resource]['input'])
        operation['run']['policy'] = {'schema_version':'policy.v1', 'policy_id':'business', 'rules':[
            {'id':'latency','metric':'response_time_p95_ms','operator':'lte','threshold':business_threshold,'scope':{'kind':'overall'}}]}
        add(name, operation, {'/result/policy_verdict': verdict})

    for enabled in [False, True]:
        operation = deepcopy(initial['A01_resource']['input'])
        operation['run']['resources']['rules'] = [{'id':'mandatory', 'series_id':'value', 'unit':'synthetic_unit',
            'operator':'gt', 'threshold':120, 'min_consecutive_cells':3, 'effect':'sla'}]
        if not enabled:
            del operation['run']['diagnostics']
        add('V02_diagnostics_' + ('enabled' if enabled else 'disabled'), operation, {'/result/policy_verdict':'FAIL'})
    return cases


def completion_cases():
    """Remaining deterministic branches, expectations fixed before execution."""
    initial = {case['id']: case for case in correctness_cases()}
    cases = []

    def add(name, operation, exact, numeric=None):
        runs = ([operation['run']] if operation['operation'] == 'analysis' else
                [operation['baseline'], operation['current']] if operation['operation'] == 'comparison' else [])
        for run in runs:
            run['resources']['load_input_sha256'] = _sha(run['load_jtl'].encode())
            if 'diagnostics' in run:
                run['diagnostics']['resource_snapshot_sha256'] = snapshot_hash(run['resources'])
        cases.append({'id': name, 'family': name[:3], 'configuration': name, 'input': operation,
                      'expected': {'exact': ({'/persisted_equal': True} if runs else {}) | exact,
                                   'numeric': {p: _number(v) for p, v in (numeric or {}).items()}}})

    add('S02_empty_input', {'operation': 'resource', 'resources': _snapshot([_series([])], [])},
        {'/validation_status': 'INVALID'})
    prefix = '/correlation_pairs/evaluation/pair/'
    operation = deepcopy(initial['C01_positive']['input'])
    run = operation['run']
    x = [1,2,3,4,5]*7 + [None] + [1,2,3,4,5]*7
    y = [100,300,200,500,400]*7 + [100] + [500,400,300,200,100]*7
    run['resources'] = _snapshot([_series(x)], [('evaluation',0,len(x))])
    run['load_jtl'] = _load(y)
    run['diagnostics']['pairs'][0]['max_lag_ms'] = 2000
    exact = {prefix+'paired_cells':70, prefix+'lag_used_cells':31}
    numeric = {}
    for i, point in enumerate(rank_correlation(x[:35], y[:35], max_lag=2)['profile']):
        exact[prefix+f'lag_profile/{i}/lag_ms'] = point['lag_cells']*1000
        numeric[prefix+f'lag_profile/{i}/rho'] = point['coefficient']
    add('C04_longest_tie_earliest', operation, exact, numeric)

    operation = deepcopy(initial['C02_one_control']['input'])
    run = operation['run']
    run['resources']['series'][0]['id'] = 'renamed_value'
    run['resources']['series'][1]['id'] = 'renamed_control'
    run['resources']['series'].reverse()
    run['resources']['windows'][0]['id'] = 'renamed_window'
    pair = run['diagnostics']['pairs'][0]
    pair.update(id='renamed_pair', resource_series_id='renamed_value', window_ids=['renamed_window'])
    pair['controls'][0]['series_id'] = 'renamed_control'
    renamed = '/correlation_pairs/renamed_window/renamed_pair/'
    add('C06_ID_permutation', operation,
        {renamed+'status':'CANDIDATE', renamed+'paired_cells':40, renamed+'controls_used':['renamed_control']},
        {renamed+'raw_rho': '.8', renamed+'partial_rho':
         rank_correlation([1,2,3,4]*10, [100,200,400,300]*10, [[1,4,2,3]*10])['profile'][0]['coefficient']})

    operation = deepcopy(initial['W01_latency']['input'])
    operation['current']['resources']['series'][0]['values'] = [None]*30
    add('W03_missing_metric', operation,
        {f'/comparison/window_comparison/metrics/{i}/{field}': value for i in [5,6]
         for field,value in [('current',None), ('delta',None), ('status','INSUFFICIENT_DATA'), ('reason','MISSING_METRIC')]})

    x = [1,2,3,4,5]*6 + [1]
    latency = [100,300,200,500,400]*6 + [100]
    counts = [20]*31
    counts[15] = 0
    errors = [0,1,2,3,4]*6 + [0]
    errors[15] = 0
    for metric in ['response_time_p95_ms', 'throughput_rps', 'error_rate']:
        operation = deepcopy(initial['C01_positive']['input'])
        run = operation['run']
        run['resources'] = _snapshot([_series(x)], [('evaluation',0,31)])
        run['load_jtl'] = _load(latency, counts, errors)
        run['diagnostics']['pairs'][0]['load_metric'] = metric
        indices = list(range(31)) if metric == 'throughput_rps' else [i for i in range(31) if i != 15]
        values = counts if metric == 'throughput_rps' else latency if metric == 'response_time_p95_ms' else [Fraction(e,20) for e in errors]
        rho = rank_correlation([x[i] for i in indices], [values[i] for i in indices])['profile'][0]['coefficient']
        add('W04_empty_' + metric, operation, {prefix+'paired_cells':len(indices)}, {prefix+'raw_rho':rho})

    for enabled in [True, False]:
        operation = deepcopy(initial['A01_resource']['input'])
        run = operation['run']
        values = [99,100,101]*10 + [160,160,160,100]*1001
        rule = {'id':'mandatory', 'series_id':'value', 'unit':'synthetic_unit',
                'operator':'gt', 'threshold':120, 'min_consecutive_cells':3, 'effect':'sla'}
        run['resources'] = _snapshot([_series(values)], [('reference',0,30), ('evaluation',30,len(values))], rules=[rule])
        run['load_jtl'] = _load([100]*len(values))
        exact = {'/result/policy_verdict':'FAIL'}
        if enabled:
            exact.update({'/diagnostic_summary/status':'LIMIT_EXCEEDED',
                          '/diagnostic_summary/reasons':['DIAGNOSTIC_EPISODE_LIMIT_EXCEEDED']})
        else:
            del run['diagnostics']
        add('V02_episode_limit' + ('' if enabled else '_disabled'), operation, exact)
    return cases


def context_cases():
    """Same real pair, predeclared context only; no third run or changed metrics."""
    source = next(case for case in correctness_cases() if case['id'] == 'W01_latency')
    cases = []
    for confirmed in [False, True]:
        case = deepcopy(source)
        case['id'] = 'W03_context_' + ('confirmed' if confirmed else 'unconfirmed')
        case['configuration'] = case['id']
        case['family'] = 'W03'
        case['input']['conditions_confirmed'] = confirmed
        case['expected']['exact']['/comparison/comparability'] = 'USER_CONFIRMED' if confirmed else 'UNCONFIRMED'
        case['expected']['exact']['/comparison/window_comparison/metrics/1/status'] = 'CANDIDATE' if confirmed else 'DESCRIPTIVE'
        cases.append(case)
    return cases


def prepare(output, batch='initial'):
    output = Path(output)
    digest = freeze_cases(output, {'initial': correctness_cases, 'supplemental': supplemental_cases,
                                  'completion': completion_cases, 'context': context_cases}[batch]())
    root = Path(__file__).resolve().parents[1]
    # Explicit source allowlist: never collect connections, secrets, .git or caches.
    prefixes = ('src/main/', 'src/test/', 'docs/contracts/', 'tools/', 'ui/src/', 'ui/public/', 'gradle/wrapper/')
    names = {'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle.lockfile',
             'gradle/verification-metadata.xml', 'gradle/wrapper/gradle-wrapper.properties',
             'docs/statistical-validation-methodology-v1.md', 'docs/superpowers/plans/2026-09-06-statistical-validation.md',
             'gradlew', 'gradlew.bat', 'ui/package.json', 'ui/package-lock.json', 'ui/index.html',
             'ui/tsconfig.json', 'ui/vite.config.ts', 'ui/env.d.ts', '.editorconfig', '.gitattributes'}
    paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard'], cwd=root, text=True).splitlines()
    selected = sorted({name for name in paths if (name.startswith(prefixes) or name in names)
                       and '__pycache__' not in name and (root/name).is_file()})
    source_hashes = {}
    with zipfile.ZipFile(output/'source.zip', 'x', zipfile.ZIP_DEFLATED) as archive:
        for name in selected:
            raw = (root/name).read_bytes()
            archive.writestr(name, raw)
            source_hashes[name] = _sha(raw)
    diff = subprocess.check_output(['git', 'diff', '--binary', 'HEAD', '--', *selected], cwd=root)
    (output/'source.diff').write_bytes(diff)
    metadata = {'method': 'v1', 'batch': 'correctness-' + batch, 'full_matrix_complete': False,
                'manifest_sha256': digest, 'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                'python': platform.python_version(), 'platform': platform.platform(),
                'java': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr.strip(),
                'source_files': source_hashes,
                'source_zip_sha256': _sha((output/'source.zip').read_bytes()), 'source_diff_sha256': _sha(diff),
                'applicability': 'NOT_RUN', 'usefulness': 'NOT_RUN',
                'remaining': ['S02 empty via contract rejection', 'S04 series permutation', 'A02 missing masks',
                              'A03 z/effect +/-1e-6', 'C02 redundant/constant controls', 'C04-C06',
                              'W02 matched stages/bindings', 'W03 missing windows', 'W04 cell support/empty', 'V01 combined business', 'V02',
                              '830 applicability runs and rubric', '28000 usefulness gate'],
                'known_capability_gaps': ['two-run manual comparison cannot declare USER_CONFIRMED']}
    if batch == 'supplemental':
        metadata['remaining'] = ['S02 empty-input rejection', 'A02 missing throughput not representable as zero requests',
                                 'C04 longest-segment tie-break', 'C06 ID permutation and whole-report forbidden-claims checks',
                                 'W03 missing metric', 'W04 empty cell metric-specific null/zero',
                                 'V02 diagnostic limit-exceeded', '830 applicability runs and rubric', '28000 usefulness gate']
    if batch == 'completion':
        metadata['remaining'] = ['A02 missing throughput not representable as zero requests',
                                 'C06 whole-report forbidden-claims checks',
                                 '830 applicability runs and rubric', '28000 usefulness gate']
    if batch == 'context':
        metadata['remaining'] = ['C06 whole-report forbidden-claims checks',
                                 '830 applicability runs and rubric', '28000 usefulness gate']
        metadata['known_capability_gaps'] = ['UI/API and persistent pair confirmation not implemented; internal explicit context available']
    (output/'preparation.json').write_bytes(_json_bytes(metadata))
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    preparation = commands.add_parser('prepare')
    preparation.add_argument('--method', choices=['v1'], required=True)
    preparation.add_argument('--output', type=Path, required=True)
    preparation.add_argument('--batch', choices=['initial', 'supplemental', 'completion', 'context'], default='initial')
    report = commands.add_parser('report')
    report.add_argument('--input', type=Path, required=True)
    report.add_argument('--manifest-sha256', required=True)
    report.add_argument('--actual', type=Path, required=True)
    report.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.command == 'prepare':
        metadata = prepare(args.output, args.batch)
        print(json.dumps({k: v for k, v in metadata.items() if k != 'source_files'}, indent=2))
        return 0
    summary = check_cases(args.input, args.manifest_sha256, args.actual)
    if args.output:
        with args.output.open('xb') as stream:
            stream.write(_json_bytes(summary))
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0 if summary['status'] == 'MATCH' else 1


if __name__ == '__main__':
    raise SystemExit(main())
