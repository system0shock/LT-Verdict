import type { AnalysisSummary, Release, ReleaseAnalysis, ReleaseProfile, ReleaseRequest } from '../types'

export const DEFAULT_VISIBLE_RELEASES = 4

export const PROFILE_FIELDS = ['scenario_mix', 'environment_dataset', 'load_model', 'targets_stages', 'pacing', 'generator_limits'] as const

export type ProfileForm = Record<(typeof PROFILE_FIELDS)[number], string>

export function emptyProfileForm(): ProfileForm {
  return { scenario_mix: '', environment_dataset: '', load_model: '', targets_stages: '', pacing: '', generator_limits: '' }
}

// Same normal form as the server: empty fields are null, all-empty is no profile.
export function profileFromForm(form: ProfileForm): ReleaseProfile | null {
  const profile = Object.fromEntries(PROFILE_FIELDS.map((name) => [name, form[name].trim() === '' ? null : form[name].trim()])) as unknown as ReleaseProfile
  return PROFILE_FIELDS.every((name) => profile[name] === null) ? null : profile
}

// "name=value; name=value" in the fixed field order, like the dynamics export; null without a declared profile.
export function profileSummary(release: Release): string | null {
  if (!release.profile) return null
  const profile = release.profile
  return PROFILE_FIELDS.flatMap((name) => (profile[name] ? [`${name}=${profile[name]}`] : [])).join('; ')
}

// Rows arrive newest first; the table shows the newest N unless the user expands it.
export function visibleReleases(releases: readonly Release[], showAll: boolean): readonly Release[] {
  return showAll ? releases : releases.slice(0, DEFAULT_VISIBLE_RELEASES)
}

export function utf8Length(text: string): number {
  return new TextEncoder().encode(text).length
}

export function releaseRequest(
  form: { series: string; label: string; notes: string; profile: ProfileForm },
  selection: { run_id: string; analysis_id: string },
): ReleaseRequest {
  return {
    series: form.series.trim(),
    label: form.label.trim(),
    run_id: selection.run_id,
    analyses: [{ analysis_id: selection.analysis_id }],
    profile: profileFromForm(form.profile),
    notes: form.notes.trim() === '' ? null : form.notes,
  }
}

// "2026-10-05T21:56:29Z" -> "2026-10-05 21:56 UTC"; any other form stays as is.
export function formatStarted(value: string): string {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(value)
  return match ? `${match[1]} ${match[2]} UTC` : value
}

// A release analysis shaped like the list of saved analyses of a run, so the app can open it.
export function analysisSummary(analysis: ReleaseAnalysis): AnalysisSummary {
  return {
    analysis_id: analysis.analysis_id,
    policy_sha256: analysis.policy_sha256,
    policy_verdict: analysis.policy_verdict,
    run_validity: analysis.run_validity,
  }
}

// Run ids are "<source type>-<sha256>" (the store builds them so); used only when the run is not on the loaded page of runs.
export function runSummaryFromId(runId: string) {
  const cut = runId.lastIndexOf('-')
  return {
    run_id: runId,
    source_type: cut > 0 ? runId.slice(0, cut) : '',
    sha256: cut > 0 ? runId.slice(cut + 1) : '',
    original_filename: '',
    size_bytes: 0,
  }
}

// The newest release of the loaded page that still has a readable analysis: its analysis anchors the dynamics request.
export function dynamicsAnchor(releases: readonly Release[]): { run_id: string; analysis_id: string } | null {
  for (const release of releases) {
    const analysis = release.analyses.find((item) => item.analysis_state === 'OK')
    if (analysis) return { run_id: release.run_id, analysis_id: analysis.analysis_id }
  }
  return null
}

export interface BaselineFacts {
  run_validity: string
  policy_verdict: string
  coverage_status: string
  coverage_reasons: readonly string[]
}

// Mirror of the server rule baselineCandidateRejection (ADR 0019, section 6), used only to explain a disabled button:
// the server decides again from the real result. INCOMPLETE coverage caused only by SMALL_SAMPLE is admitted.
export function baselineIneligibility(facts: BaselineFacts): string | null {
  if (facts.run_validity !== 'VALID') return 'BASELINE_CANDIDATE_INVALID'
  const admitted = facts.coverage_status === 'COMPLETE'
    || (facts.coverage_status === 'INCOMPLETE' && facts.coverage_reasons.length > 0 && facts.coverage_reasons.every((reason) => reason === 'SMALL_SAMPLE'))
  if (!admitted) return 'BASELINE_CANDIDATE_INCOMPLETE'
  if (facts.policy_verdict !== 'PASS') return 'BASELINE_CANDIDATE_NOT_PASS'
  return null
}
