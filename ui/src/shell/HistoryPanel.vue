<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ApiError, createRelease, getBaseline, getSavedAnalytics, listReleases, setBaseline } from '../api'
import type { AnalysisReference, BaselineSlotView, Release, ReleaseAnalysis } from '../types'
import type { SavedAnalytics } from '../analyticsTypes'
import {
  DEFAULT_VISIBLE_RELEASES, PROFILE_FIELDS, dynamicsAnchor, emptyProfileForm, formatStarted, profileSummary, releaseRequest, utf8Length, visibleReleases,
} from './history'
import { HISTORY_LABELS, historyErrorText } from './labels.history'

type RowAction = { release: Release; analysis: ReleaseAnalysis }

const props = defineProps<{ selection: AnalysisReference | null; active: boolean; version: number; working: boolean }>()
const emit = defineEmits<{ open: [action: RowAction]; compare: [action: RowAction]; 'baseline-changed': [series: string] }>()

const L = HISTORY_LABELS
const series = ref('')
const seriesList = ref<Array<{ series: string; count: number }>>([])
const releases = ref<Release[]>([])
const corruptCount = ref(0)
const showAll = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const errorLang = ref<string | undefined>(undefined)
const notice = ref('')
const slots = ref<BaselineSlotView[]>([])
const dynamics = ref<SavedAnalytics | null>(null)
const dynamicsFailed = ref(false)
const form = ref({ series: '', label: '', notes: '', profile: emptyProfileForm() })
let revision = 0

const rows = computed(() => visibleReleases(releases.value, showAll.value))
const total = computed(() => seriesList.value.find((entry) => entry.series === series.value)?.count ?? releases.value.length)
const canToggle = computed(() => total.value > DEFAULT_VISIBLE_RELEASES)
const numbers = computed(() => new Map((dynamics.value?.dynamics?.rows ?? []).map((row) => [row.reference.analysis_id, row])))
const tooLong = computed(() => utf8Length(form.value.series.trim()) > 128 || utf8Length(form.value.label.trim()) > 128
  || utf8Length(form.value.notes) > 1024 || PROFILE_FIELDS.some((name) => utf8Length(form.value.profile[name].trim()) > 128))
const formValid = computed(() => props.selection !== null && form.value.series.trim() !== '' && form.value.label.trim() !== '' && !tooLong.value)
const busyAny = computed(() => loading.value || busy.value || props.working)

watch(() => props.active, (active) => { if (active) void load() }, { immediate: true })
watch(() => props.version, () => { if (props.active) void load() })
watch(series, (value) => { if (value && form.value.series === '') form.value.series = value })

function showError(failure: unknown) {
  const phrase = failure instanceof ApiError ? historyErrorText(failure.code, failure.limit) : null
  error.value = phrase ?? (failure instanceof Error ? failure.message : L.requestFailed)
  errorLang.value = phrase !== null || !(failure instanceof Error) ? undefined : 'en'
}

async function load() {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const head = await listReleases({ limit: 1 })
    if (current !== revision) return
    seriesList.value = head.series_summary
    corruptCount.value = head.corrupt_count
    if (!seriesList.value.some((entry) => entry.series === series.value)) series.value = head.releases[0]?.series ?? seriesList.value[0]?.series ?? ''
    const loaded: Release[] = []
    if (series.value) {
      let after: string | undefined
      do {
        const page = await listReleases({ series: series.value, after, limit: showAll.value ? 100 : DEFAULT_VISIBLE_RELEASES })
        if (current !== revision) return
        loaded.push(...page.releases)
        after = showAll.value ? page.next_after ?? undefined : undefined
      } while (after)
    }
    releases.value = loaded
    const [listed, saved] = await Promise.all([getBaseline(), loadDynamics(loaded)])
    if (current !== revision) return
    slots.value = listed.baselines ?? []
    dynamics.value = saved
  } catch (failure) {
    if (current === revision) {
      // The rows of the previous series must not stay under the newly chosen one.
      releases.value = []
      dynamics.value = null
      showError(failure)
    }
  } finally {
    if (current === revision) loading.value = false
  }
}

// The numbers come from the saved analytics of the newest readable release; a failure only leaves the rows without numbers.
async function loadDynamics(list: readonly Release[]): Promise<SavedAnalytics | null> {
  const anchor = dynamicsAnchor(list)
  dynamicsFailed.value = false
  if (!anchor) return null
  try {
    dynamicsFailed.value = false
    return await getSavedAnalytics(anchor, 100, '', 1)
  } catch {
    dynamicsFailed.value = true
    return null
  }
}

function metricOf(analysisId: string, metric: string): string | null {
  return numbers.value.get(analysisId)?.metrics.find((item) => item.metric === metric)?.value ?? null
}

function noNumbers(analysis: ReleaseAnalysis): string {
  if (analysis.analysis_state === 'MISSING') return L.noNumbersMissing
  if (analysis.analysis_state === 'CORRUPT') return L.noNumbersCorrupt
  if (dynamicsFailed.value) return L.noNumbersFailed
  return dynamics.value?.history_scan_truncated ? L.noNumbersTruncated : L.noNumbersOldRules
}

const valueFormat = new Intl.NumberFormat('ru-RU', { maximumSignificantDigits: 4 })
function shown(value: string | null): string {
  if (value === null) return L.noValue
  const number = Number(value)
  return Number.isFinite(number) ? valueFormat.format(number) : value
}

function isBaseline(release: Release, analysis: ReleaseAnalysis): boolean {
  return slots.value.some((slot) => slot.baseline.reference.run_id === release.run_id && slot.baseline.reference.analysis_id === analysis.analysis_id)
}

function reasonText(analysis: ReleaseAnalysis): string {
  return analysis.ineligible_reasons.map((reason) => L.reasons[reason] ?? reason).join('; ')
}

async function makeBaseline(action: RowAction) {
  if (busyAny.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await setBaseline({ mode: 'manual', series: action.release.series, reference: { run_id: action.release.run_id, analysis_id: action.analysis.analysis_id } })
    emit('baseline-changed', action.release.series)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function toggleAll() {
  showAll.value = !showAll.value
  await load()
}

async function changeSeries(value: string) {
  series.value = value
  showAll.value = false
  await load()
}

async function save() {
  if (!formValid.value || busyAny.value || !props.selection) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await createRelease(releaseRequest(form.value, props.selection))
    series.value = created.series
    notice.value = L.saved(created.label)
    busy.value = false
    await load()
    await nextTick()
    // An older analysis can land outside the shown rows: the table region takes the focus then.
    const target = document.getElementById(`release-${created.release_id}`) ?? document.getElementById('history-table-region')
    target?.focus()
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section
    id="history-panel"
    class="panel history-panel"
    data-testid="history-panel"
    lang="ru"
    aria-labelledby="history-title"
  >
    <header class="panel__header">
      <h2 id="history-title">
        {{ L.title }}
      </h2>
      <p>{{ L.intro }}</p>
    </header>

    <div
      v-if="seriesList.length"
      class="field"
    >
      <label for="history-series">{{ L.seriesLabel }}</label>
      <select
        id="history-series"
        :value="series"
        data-testid="history-series"
        :disabled="busyAny"
        @change="changeSeries(($event.target as HTMLSelectElement).value)"
      >
        <option
          v-for="entry in seriesList"
          :key="entry.series"
          :value="entry.series"
        >
          {{ entry.series }} ({{ entry.count }})
        </option>
      </select>
    </div>

    <p
      v-if="loading"
      role="status"
    >
      {{ L.loading }}
    </p>
    <p
      v-else-if="!seriesList.length && !error"
      role="status"
    >
      {{ L.noSeries }}
    </p>
    <p
      v-else-if="!releases.length && !error"
      role="status"
    >
      {{ L.noReleases }}
    </p>
    <p
      v-if="corruptCount > 0"
      data-testid="history-corrupt"
      class="notice notice-warn"
      role="status"
    >
      {{ L.corrupt(corruptCount) }}
    </p>
    <p
      v-if="error"
      class="notice notice-fail"
      role="alert"
      :lang="errorLang"
    >
      {{ error }}
    </p>
    <p
      v-if="notice"
      class="notice notice-info"
      role="status"
    >
      {{ notice }}
    </p>

    <div
      v-if="releases.length"
      id="history-table-region"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="L.regionLabel"
    >
      <table data-testid="history-table">
        <caption>{{ L.tableCaption(series) }}</caption>
        <thead>
          <tr>
            <th scope="col">
              {{ L.heads.release }}
            </th>
            <th scope="col">
              {{ L.heads.started }}
            </th>
            <th scope="col">
              {{ L.heads.verdict }}
            </th>
            <th scope="col">
              {{ L.heads.profile }}
            </th>
            <th scope="col">
              {{ L.heads.p95 }}
            </th>
            <th scope="col">
              {{ L.heads.errors }}
            </th>
            <th scope="col">
              {{ L.heads.rps }}
            </th>
            <th scope="col">
              {{ L.heads.actions }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="release in rows"
            :id="`release-${release.release_id}`"
            :key="release.release_id"
            tabindex="-1"
            data-testid="release-row"
          >
            <th scope="row">
              <span>{{ release.label }}</span>
              <small class="mono"> {{ release.release_id.slice(-8) }}</small>
            </th>
            <td>{{ formatStarted(release.started_at) }}</td>
            <td>
              <ul class="history-list">
                <li
                  v-for="analysis in release.analyses"
                  :key="analysis.analysis_id"
                >
                  {{ analysis.arm === null ? '' : `${analysis.arm}: ` }}{{ analysis.policy_verdict }}
                  <span
                    v-if="isBaseline(release, analysis)"
                    class="history-badge"
                    data-testid="baseline-badge"
                    :title="L.baselineBadgeNote"
                  >{{ L.baselineBadge }}</span>
                </li>
              </ul>
            </td>
            <td>{{ profileSummary(release) ?? L.noProfile }}</td>
            <template v-if="release.analyses[0] && numbers.has(release.analyses[0].analysis_id)">
              <td>{{ shown(metricOf(release.analyses[0].analysis_id, 'response_time_p95_ms')) }}</td>
              <td>{{ shown(metricOf(release.analyses[0].analysis_id, 'error_rate_ratio')) }}</td>
              <td>{{ shown(metricOf(release.analyses[0].analysis_id, 'throughput_rps')) }}</td>
            </template>
            <td
              v-else
              colspan="3"
            >
              {{ release.analyses[0] ? noNumbers(release.analyses[0]) : L.noValue }}
            </td>
            <td>
              <ul class="history-list">
                <li
                  v-for="analysis in release.analyses"
                  :key="analysis.analysis_id"
                  class="history-actions"
                >
                  <button
                    type="button"
                    :disabled="busyAny || analysis.analysis_state !== 'OK'"
                    :aria-label="L.actionAria(L.open, release.label, analysis.arm, release.release_id.slice(-8))"
                    @click="emit('open', { release, analysis })"
                  >
                    {{ L.open }}
                  </button>
                  <button
                    type="button"
                    :disabled="busyAny || !analysis.baseline_eligible"
                    :aria-label="L.actionAria(L.makeBaseline, release.label, analysis.arm, release.release_id.slice(-8))"
                    :aria-describedby="analysis.baseline_eligible ? undefined : `why-${release.release_id}-${analysis.analysis_id.slice(0, 12)}`"
                    @click="makeBaseline({ release, analysis })"
                  >
                    {{ L.makeBaseline }}
                  </button>
                  <button
                    type="button"
                    :disabled="busyAny || analysis.analysis_state !== 'OK'"
                    :aria-label="L.actionAria(L.compare, release.label, analysis.arm, release.release_id.slice(-8))"
                    @click="emit('compare', { release, analysis })"
                  >
                    {{ L.compare }}
                  </button>
                  <small
                    v-if="!analysis.baseline_eligible"
                    :id="`why-${release.release_id}-${analysis.analysis_id.slice(0, 12)}`"
                    class="field__hint"
                  >{{ reasonText(analysis) }}</small>
                </li>
              </ul>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="canToggle">
      <button
        type="button"
        data-testid="history-toggle"
        :disabled="busyAny"
        @click="toggleAll"
      >
        {{ showAll ? L.showLatest(DEFAULT_VISIBLE_RELEASES) : L.showAll }}
      </button>
    </div>

    <form
      class="history-form"
      data-testid="history-form"
      aria-labelledby="history-form-title"
      @submit.prevent="save"
    >
      <h3 id="history-form-title">
        {{ L.saveTitle }}
      </h3>
      <p
        v-if="!selection"
        role="status"
      >
        {{ L.saveHint }}
      </p>
      <p v-else>
        {{ L.saveTarget(selection.analysis_id) }}
      </p>
      <div class="form-grid">
        <div class="field">
          <label for="history-form-series">{{ L.formSeries }}</label>
          <input
            id="history-form-series"
            v-model="form.series"
            list="history-series-options"
            aria-describedby="history-form-series-hint"
            :disabled="busyAny || !selection"
          >
          <datalist id="history-series-options">
            <option
              v-for="entry in seriesList"
              :key="entry.series"
              :value="entry.series"
            />
          </datalist>
          <p
            id="history-form-series-hint"
            class="field__hint"
          >
            {{ L.formSeriesHint }}
          </p>
        </div>
        <div class="field">
          <label for="history-form-label">{{ L.formLabel }}</label>
          <input
            id="history-form-label"
            v-model="form.label"
            aria-describedby="history-form-label-hint"
            :disabled="busyAny || !selection"
          >
          <p
            id="history-form-label-hint"
            class="field__hint"
          >
            {{ L.formLabelHint }}
          </p>
        </div>
      </div>
      <fieldset
        class="field history-profile"
        :disabled="busyAny || !selection"
      >
        <legend>{{ L.profileLegend }}</legend>
        <p class="field__hint">
          {{ L.profileHint }}
        </p>
        <div class="form-grid">
          <div
            v-for="name in PROFILE_FIELDS"
            :key="name"
            class="field"
          >
            <label :for="`history-profile-${name}`">{{ L.profileFields[name] }}</label>
            <input
              :id="`history-profile-${name}`"
              v-model="form.profile[name]"
            >
          </div>
        </div>
      </fieldset>
      <div class="field">
        <label for="history-form-notes">{{ L.formNotes }}</label>
        <textarea
          id="history-form-notes"
          v-model="form.notes"
          rows="3"
          aria-describedby="history-form-notes-hint"
          :disabled="busyAny || !selection"
        />
        <p
          id="history-form-notes-hint"
          class="field__hint"
        >
          {{ L.formNotesHint }}
        </p>
      </div>
      <p
        v-if="tooLong"
        class="notice notice-fail"
        role="status"
      >
        {{ L.tooLong }}
      </p>
      <button
        type="submit"
        :disabled="busyAny || !formValid"
      >
        {{ busy ? L.saving : L.save }}
      </button>
    </form>
  </section>
</template>

<style>
.history-panel { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.history-panel .panel__header { margin-bottom: 0; }
.history-panel p, .history-panel li, .history-panel th { overflow-wrap: anywhere; }
.history-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.history-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.history-badge { display: inline-block; margin-left: 4px; padding: 0 6px; border: 1px solid currentColor; border-radius: 2px; font-size: 12px; }
.history-form { display: grid; gap: 16px; }
.history-profile { margin: 0; padding: 0; border: 0; }
.history-profile > legend { padding: 0; font-weight: 600; }
.history-panel textarea { width: 100%; font: inherit; }
</style>
