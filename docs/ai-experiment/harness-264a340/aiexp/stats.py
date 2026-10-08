"""Exact small-sample statistics (standard library only)."""
import math


def _cdf(k, n, p):
    """P(X <= k) for X ~ Bin(n, p)."""
    if k < 0:
        return 0.0
    if k >= n:
        return 1.0
    return sum(math.comb(n, i) * (p ** i) * ((1.0 - p) ** (n - i)) for i in range(0, k + 1))


def clopper_pearson(k, n, alpha=0.05):
    """Two-sided exact interval for a binomial proportion. Returns (low, high)."""
    if n <= 0:
        return (float("nan"), float("nan"))
    lo = 0.0
    hi = 1.0
    if k > 0:
        a, b = 0.0, 1.0
        for _ in range(80):
            m = (a + b) / 2
            if 1.0 - _cdf(k - 1, n, m) < alpha / 2:  # P(X >= k | m)
                a = m
            else:
                b = m
        lo = (a + b) / 2
    if k < n:
        a, b = 0.0, 1.0
        for _ in range(80):
            m = (a + b) / 2
            if _cdf(k, n, m) > alpha / 2:
                a = m
            else:
                b = m
        hi = (a + b) / 2
    return (lo, hi)


def mcnemar_exact(b, c):
    """Two-sided exact p-value for discordant pair counts b and c."""
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    p = 2.0 * _cdf(k, n, 0.5)
    return min(1.0, p)


def fmt_ci(k, n):
    lo, hi = clopper_pearson(k, n)
    return "%d/%d (%.0f%%; 95%% CI %.0f-%.0f%%)" % (k, n, 100.0 * k / n if n else float("nan"), 100 * lo, 100 * hi)
