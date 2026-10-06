import type { AnalysisSummary, Release, ReleaseAnalysis, ReleaseProfile, ReleaseRequest, ReleaseUpdate } from '../types'

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

export type ProfileRelation = 'match' | 'mismatch' | 'unknown'

// Same rule as the server (compareReleaseProfiles): unknown unless both releases declare a profile.
export function profileRelation(a: Release, b: Release): ProfileRelation {
  if (!a.profile || !b.profile) return 'unknown'
  const left = a.profile
  const right = b.profile
  return PROFILE_FIELDS.every((name) => left[name] === right[name]) ? 'match' : 'mismatch'
}

export function profileToForm(profile: ReleaseProfile | null): ProfileForm {
  const form = emptyProfileForm()
  if (profile) for (const name of PROFILE_FIELDS) form[name] = profile[name] ?? ''
  return form
}

export interface BaselineChoice { release: Release; analysis: ReleaseAnalysis }

// The newest release older than the opened one of the same series with a MATCHING declared profile and an eligible analysis of the same arm.
// Two releases without a declared profile are not a match (ADR 0019, section 4): no suggestion until a profile is declared.
export function suggestManualBaseline(releases: readonly Release[], opened: Release, arm: string | null): BaselineChoice | null {
  for (const release of releases) {
    if (release.release_id >= opened.release_id || release.series !== opened.series) continue
    if (profileRelation(release, opened) !== 'match') continue
    const analysis = release.analyses.find((item) => item.arm === arm && item.baseline_eligible)
    if (analysis) return { release, analysis }
  }
  return null
}

export const STATISTICAL_MINIMUM = 3

export interface StatisticalAvailability { available: boolean; eligibleRuns: number; needed: number }

// Statistical selection needs 3..20 eligible releases of one DECLARED profile and arm from distinct runs; the opened release declares it too.
export function statisticalAvailability(releases: readonly Release[], opened: Release, arm: string | null): StatisticalAvailability {
  const runs = new Set<string>()
  for (const release of releases) {
    if (release.series !== opened.series || profileRelation(release, opened) !== 'match') continue
    if (release.analyses.some((item) => item.arm === arm && item.baseline_eligible)) runs.add(release.run_id)
  }
  return { available: runs.size >= STATISTICAL_MINIMUM, eligibleRuns: runs.size, needed: STATISTICAL_MINIMUM }
}

export interface DynamicsPoint { release: Release; verdict: ReleaseAnalysis['policy_verdict']; value: number }

// Points of the p95 chart in ascending time order; a release without a number is not drawn.
export function dynamicsPoints(releases: readonly Release[], valueOf: (analysisId: string) => string | null): DynamicsPoint[] {
  const points: DynamicsPoint[] = []
  for (const release of releases) {
    const analysis = release.analyses[0]
    const raw = analysis ? valueOf(analysis.analysis_id) : null
    const value = raw === null ? Number.NaN : Number(raw)
    if (analysis && Number.isFinite(value)) points.push({ release, verdict: analysis.policy_verdict, value })
  }
  return points.sort((a, b) => (a.release.started_at < b.release.started_at ? -1 : a.release.started_at > b.release.started_at ? 1 : 0))
}

export function releaseUpdate(release: Release, analysisId: string): ReleaseUpdate {
  return { label: release.label, analyses: [{ analysis_id: analysisId }], profile: release.profile, notes: release.notes }
}
