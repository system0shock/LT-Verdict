"""Independent reference for correlation headline selection."""

from dataclasses import dataclass
import hashlib
import math

import numpy as np


def ranks(values) -> list[float]:
    values = list(values)
    if not all(math.isfinite(value) for value in values):
        raise ValueError("non-finite rank input")
    result = [0.0] * len(values)
    order = sorted(range(len(values)), key=values.__getitem__)
    start = 0
    while start < len(values):
        end = start + 1
        while end < len(values) and values[order[start]] == values[order[end]]:
            end += 1
        rank = (start + 1 + end) / 2
        for position in order[start:end]:
            result[position] = rank
        start = end
    return result


def lag_profile(x, y, max_lag) -> list[float | None]:
    xr, yr = ranks(x), ranks(y)
    anchors = len(xr) - 2 * max_lag
    if anchors <= 0:
        return [None] * (2 * max_lag + 1)
    profile = []
    for lag in range(-max_lag, max_lag + 1):
        a = xr[max_lag:max_lag + anchors]
        b = yr[max_lag + lag:max_lag + lag + anchors]
        am = bm = 0.0
        for value in a:
            am += value
        for value in b:
            bm += value
        am /= anchors
        bm /= anchors
        numerator = xs = ys = 0.0
        for u, v in zip(a, b):
            cx, cy = u - am, v - bm
            numerator += cx * cy
            xs += cx * cx
            ys += cy * cy
        denominator = math.sqrt(xs * ys)
        profile.append(max(-1.0, min(1.0, numerator / denominator)) if denominator and math.isfinite(denominator) else None)
    return profile


def lag_max_abs(x, y, max_lag) -> float | None:
    if len(x) != len(y) or len(x) - 2 * max_lag < 30:
        return None
    profile = lag_profile(x, y, max_lag)
    if any(value is None for value in profile):
        return None
    return max(abs(max(-1.0, min(1.0, value))) for value in profile)


def holm(p_values) -> list[float]:
    result = [0.0] * len(p_values)
    running = 0.0
    for rank, index in enumerate(sorted(range(len(p_values)), key=lambda i: (p_values[i], i))):
        running = max(running, (len(p_values) - rank) * p_values[index])
        result[index] = min(1.0, running)
    return result


def _wilson(k, n):
    z = 1.959963984540054
    p = k / n
    denominator = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denominator
    radius = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denominator
    return center - radius, center + radius


def wilson_upper(k, n) -> float:
    return _wilson(k, n)[1]


def wilson_lower(k, n) -> float:
    return max(0.0, _wilson(k, n)[0])


class JavaRandom:
    _MASK = (1 << 48) - 1
    _MULTIPLIER = 0x5DEECE66D

    def __init__(self, seed):
        seed &= (1 << 64) - 1
        self.seed = (seed ^ self._MULTIPLIER) & self._MASK

    def _next(self, bits):
        self.seed = (self.seed * self._MULTIPLIER + 0xB) & self._MASK
        return self.seed >> (48 - bits)

    def next_int(self, bound=None):
        if bound is None:
            value = self._next(32)
            return value if value < 1 << 31 else value - (1 << 32)
        if bound <= 0:
            raise ValueError("bound must be positive")
        r = self._next(31)
        m = bound - 1
        if bound & m == 0:
            return (bound * r) >> 31
        while True:
            u = r
            r = u % bound
            if u - r + m < 1 << 31:
                return r
            r = self._next(31)


def product_seed(seed_material, block, side, method="mbb-lag-max-holm.v1") -> int:
    payload = f"{method}/{seed_material}/{block}/{side}".encode("utf-8")
    return int.from_bytes(hashlib.sha256(payload).digest()[:8], "big", signed=True)


def longest_run(series) -> list[float]:
    best_start = best_end = start = 0
    for index, value in enumerate(series):
        if value is None:
            if index - start > best_end - best_start:
                best_start, best_end = start, index
            start = index + 1
    if len(series) - start > best_end - best_start:
        best_start, best_end = start, len(series)
    return series[best_start:best_end]


def first_differences(series) -> list[float]:
    run = longest_run(series)
    return [b - a for a, b in zip(run, run[1:])]


@dataclass
class Hypothesis:
    pair_id: str
    window_id: str
    resource: list[float]
    outcome: list[float]
    max_lag: int
    material_candidate: bool = True
    unavailable_reason: str | None = None


@dataclass
class Selection:
    pair_id: str
    window_id: str
    status: str
    family_hypotheses: int
    p_b10: float | None
    p_b20: float | None
    max_p: float | None
    holm_adjusted: float | None
    selected: bool
    reasons: list[str]


def _rank_rows(rows):
    order = np.argsort(rows, axis=1, kind="stable")
    sorted_rows = np.take_along_axis(rows, order, axis=1)
    count, width = rows.shape
    positions = np.broadcast_to(np.arange(width), (count, width))
    starts = np.where(np.concatenate((np.ones((count, 1), dtype=bool), sorted_rows[:, 1:] != sorted_rows[:, :-1]), axis=1), positions, 0)
    starts = np.maximum.accumulate(starts, axis=1)
    ends = np.where(np.concatenate((sorted_rows[:, :-1] != sorted_rows[:, 1:], np.ones((count, 1), dtype=bool)), axis=1), positions + 1, width)
    ends = np.minimum.accumulate(ends[:, ::-1], axis=1)[:, ::-1]
    result = np.empty(rows.shape, dtype=float)
    np.put_along_axis(result, order, (starts + 1 + ends) / 2, axis=1)
    return result


def _lag_max_abs_rows(x, y, max_lag):
    xr = _rank_rows(np.asarray(x, dtype=float))
    yr = _rank_rows(np.asarray(y, dtype=float))
    anchors = xr.shape[1] - 2 * max_lag
    result = np.zeros(xr.shape[0], dtype=float)
    for lag in range(-max_lag, max_lag + 1):
        a = xr[:, max_lag:max_lag + anchors]
        b = yr[:, max_lag + lag:max_lag + lag + anchors]
        ac = a - (np.cumsum(a, axis=1)[:, -1] / anchors)[:, None]
        bc = b - (np.cumsum(b, axis=1)[:, -1] / anchors)[:, None]
        xs = np.cumsum(ac * ac, axis=1)[:, -1]
        ys = np.cumsum(bc * bc, axis=1)[:, -1]
        numerator = np.cumsum(ac * bc, axis=1)[:, -1]
        denominator = np.sqrt(xs * ys)
        with np.errstate(divide="ignore", invalid="ignore"):
            rho = numerator / denominator
        rho = np.where(np.isfinite(denominator) & (denominator != 0), rho, np.nan)
        result = np.maximum(result, np.abs(np.clip(rho, -1.0, 1.0)))
    return result


def moving_blocks(n, block, rng):
    indices = []
    while len(indices) < n:
        start = rng.next_int(n - block + 1)
        indices.extend(range(start, min(start + block, start + n - len(indices))))
    return indices


def select(hypotheses, seed_material, representation="levels") -> list[Selection]:
    """Select from complete, gap-free series sharing one outcome per call.

    Callers trim gaps with longest_run before building a Hypothesis for
    first_difference; pass levels because this function applies a plain diff.
    first_differences is for preparing a separate differenced series.
    Product epoch-grid, cost-limit, and multi-window checks are intentionally
    not reproduced here.
    """
    if representation not in ("levels", "first_difference"):
        raise ValueError("unsupported representation")
    method = "mbb-lag-max-holm.v1" if representation == "levels" else "mbb-lag-max-holm.v2"
    hypotheses = list(hypotheses)
    available = [h.outcome for h in hypotheses if h.unavailable_reason is None]
    if available and any(len(outcome) != len(available[0]) or any(a != b for a, b in zip(outcome, available[0])) for outcome in available[1:]):
        raise ValueError("family outcome mismatch")
    size = len(hypotheses)
    results = [Selection(h.pair_id, h.window_id, "UNAVAILABLE", size, None, None, None, None, False, []) for h in hypotheses]
    if size > 16:
        for result, h in zip(results, hypotheses):
            result.reasons = ([h.unavailable_reason] if h.unavailable_reason and h.unavailable_reason != "FAMILY_SIZE_UNSUPPORTED" else []) + ["FAMILY_SIZE_UNSUPPORTED"]
        return results
    active = []
    observed = []
    resources = []
    for index, h in enumerate(hypotheses):
        resource = np.asarray(h.resource, dtype=float)
        outcome = np.asarray(h.outcome, dtype=float)
        if representation == "first_difference":
            resource, outcome = np.diff(resource), np.diff(outcome)
        n = len(outcome)
        reason = h.unavailable_reason
        if reason is None and not 30 <= n <= 1920:
            reason = "OBSERVATION_COUNT_UNSUPPORTED"
        if reason is None and (not 0 <= h.max_lag <= 10 or n - 2 * h.max_lag < 30):
            reason = "LAG_ANCHOR_COUNT_UNSUPPORTED"
        statistic = None if reason else lag_max_abs(resource, outcome, h.max_lag)
        if reason is None and statistic is None:
            reason = "PAIR_NOT_EVALUABLE"
        if reason is not None:
            results[index].reasons = [reason]
        else:
            active.append(index)
            observed.append(statistic)
            resources.append(resource)
    if not active:
        return results
    n = len(hypotheses[active[0]].outcome) - (representation == "first_difference")
    outcome = np.asarray(hypotheses[active[0]].outcome, dtype=float)
    if representation == "first_difference":
        outcome = np.diff(outcome)
    p_values = {}
    for block in (10, 20):
        resource_rng = JavaRandom(product_seed(seed_material, block, "resources", method))
        outcome_rng = JavaRandom(product_seed(seed_material, block, "outcome", method))
        x_indices = np.empty((999, n), dtype=int)
        y_indices = np.empty((999, n), dtype=int)
        for replicate in range(999):
            x_indices[replicate] = moving_blocks(n, block, resource_rng)
            y_indices[replicate] = moving_blocks(n, block, outcome_rng)
        resampled_y = outcome[y_indices]
        for position, index in enumerate(active):
            resampled_x = resources[position][x_indices]
            values = _lag_max_abs_rows(resampled_x, resampled_y, hypotheses[index].max_lag)
            if np.isnan(values).any():
                for unavailable in active:
                    results[unavailable].reasons = ["BOOTSTRAP_REPLICATE_NOT_EVALUABLE"]
                return results
            p_values[index, block] = (1 + int(np.count_nonzero(values >= observed[position]))) / 1000
    family_p = [max(p_values[index, 10], p_values[index, 20]) if index in active else 1.0 for index in range(size)]
    adjusted = holm(family_p)
    for index in active:
        result = results[index]
        result.p_b10 = p_values[index, 10]
        result.p_b20 = p_values[index, 20]
        result.max_p = family_p[index]
        result.holm_adjusted = adjusted[index]
        rejected = adjusted[index] <= 0.05
        result.selected = rejected and hypotheses[index].material_candidate
        result.status = "SELECTED" if result.selected else "NOT_SELECTED"
        result.reasons = [] if result.selected else (["HOLM_NOT_REJECTED"] if not rejected else ["MATERIALITY_NOT_MET"])
    return results
