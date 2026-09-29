"""Declared applicability fixture configurations; this module does not execute them."""

from copy import deepcopy

try:
    from .synthetic_service import US_PER_SECOND, scenario_parameters
except ImportError:  # Direct script execution has no package parent.
    from synthetic_service import US_PER_SECOND, scenario_parameters


def _pair(case_id, scenario, seed, noisy, variant, conditions_confirmed, baseline, intervention):
    return {
        "id": case_id, "scenario": scenario, "seed": seed, "noisy": noisy,
        "variant": variant, "conditions_confirmed": conditions_confirmed,
        "members": [{"name": "baseline", "parameters": baseline},
                    {"name": "intervention", "parameters": intervention}],
    }


def _stress_parameters(scenario, intervention, noisy, variant):
    parameters = scenario_parameters(scenario, intervention=intervention, noisy=noisy)
    if variant not in parameters["stress_variants"]:
        return parameters
    overrides = deepcopy(parameters["stress_variants"][variant])
    sampling = overrides.pop("sampling", None)
    parameters.update(overrides)
    if sampling:
        parameters["sampling"].update(sampling)
    return parameters


def _temporal(case_id, schedule, step_seconds, clock_alignment):
    cpu_start, downstream_start = {"A": (40, 40), "B": (40, 45), "C": (45, 40)}[schedule]
    parameters = scenario_parameters("NT07")
    parameters["scenario"] = "TEMPORAL"
    parameters.pop("downstream_changes", None)
    parameters["stages"] = [{"rate_rps": 60, "duration_us": 120 * US_PER_SECOND}]
    parameters["background_cpu_intervals"] = [{
        "from_us": cpu_start * US_PER_SECOND, "to_us": (cpu_start + 20) * US_PER_SECOND, "workers": 1,
    }]
    parameters["downstream_changes"] = [{
        "from_us": downstream_start * US_PER_SECOND, "to_us": (downstream_start + 20) * US_PER_SECOND,
        "add_us": 50_000,
    }]
    parameters["sampling"].update(step_us=step_seconds * US_PER_SECOND,
                                  clock_alignment=clock_alignment)
    return {
        "id": case_id, "scenario": "TEMPORAL", "seed": 2000, "noisy": False,
        "variant": f"{schedule}-{step_seconds}s-{clock_alignment}",
        "conditions_confirmed": clock_alignment == "declared_aligned",
        "members": [{"name": "current", "parameters": parameters}],
    }


def configurations():
    """Return the 421 declared cases (830 simulations) from methodology sections 7.2–7.3."""
    cases = []
    for scenario_number in range(1, 11):
        scenario = f"NT{scenario_number:02d}"
        for seed in range(2000, 2020):
            for noisy, variant in ((False, "clean"), (True, "noisy")):
                cases.append(_pair(f"{scenario}-{variant}-{seed}", scenario, seed, noisy, variant, True,
                                   scenario_parameters(scenario, noisy=noisy),
                                   scenario_parameters(scenario, intervention=True, noisy=noisy)))

    stresses = [
        ("NT01", "warmup_cpu_demand_x2_first_60s", True),
        ("NT02", "periodic_background_cpu", True),
        ("NT03", "missing_db_290s_320s", True),
        ("NT03", "independent_db_mask_5pct", True),
        ("NT07", "resource_clock_offset_unknown", False),
        ("NT07", "resource_clock_offset_corrected", True),
        ("NT08", "coarse_10s_sampling", True),
        ("NT10", "same_stage_user_confirmed", True),
        ("NT10", "same_stage_unconfirmed", False),
    ]
    for scenario, variant, conditions_confirmed in stresses:
        cases.append(_pair(f"{scenario}-{variant}-2000", scenario, 2000, False, variant,
                           conditions_confirmed,
                           _stress_parameters(scenario, False, False, variant),
                           _stress_parameters(scenario, True, False, variant)))

    for schedule in "ABC":
        for step_seconds in (1, 10):
            for clock_alignment in ("declared_aligned", "unknown"):
                cases.append(_temporal(f"TEMPORAL-{schedule}-{step_seconds}s-{clock_alignment}-2000",
                                       schedule, step_seconds, clock_alignment))
    return cases
