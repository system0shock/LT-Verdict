<script setup lang="ts">
import { computed, toRaw } from 'vue'
import { stringifyPolicy } from './api'
import type { Policy, PolicyError, PolicyRule } from './types'

interface PolicyEditorLabels {
  legend: string
  policyId: string
  rule: (index: number) => string
  ruleId: string
  metricField: string
  operatorField: string
  threshold: string
  scopeField: string
  scopeOverall: string
  scopeTransaction: string
  transactionName: string
  removeRule: string
  addRule: string
  download: string
  metricName: (code: string) => string
  operatorName: (code: string) => string
  thresholdHint?: (metric: PolicyRule['metric'], value: string) => string | null
  // Sample minimum fields (U4c). Absent in the old interface, which keeps its editor unchanged.
  samples?: { floorField: string; defaultMinField: string; ruleMinField: string; defaultsHint: string; ruleHint: string; lowFloor: string }
  // Gap tolerance defaults (ADR 0018, S7). Absent in the old interface, which keeps its editor unchanged.
  tolerance?: { fractionField: string; gapField: string; hint: string }
  errorLang?: string
}

const ENGLISH_LABELS: PolicyEditorLabels = {
  legend: 'Policy draft', policyId: 'Policy ID', rule: (index) => `Rule ${index}`,
  ruleId: 'Rule ID', metricField: 'Metric', operatorField: 'Operator', threshold: 'Threshold',
  scopeField: 'Scope', scopeOverall: 'Overall', scopeTransaction: 'Transaction',
  transactionName: 'Transaction name', removeRule: 'Remove rule', addRule: 'Add rule',
  download: 'Download policy', metricName: (code) => code, operatorName: (code) => code,
}

const props = defineProps<{
  policy: Policy
  errors: PolicyError[]
  status: string
  labels?: PolicyEditorLabels
}>()
const labels = computed(() => props.labels ?? ENGLISH_LABELS)

const lowFloor = computed(() => props.policy.defaults?.sample_floor !== undefined && props.policy.defaults.sample_floor < 20)

const emit = defineEmits<{ update: [policy: Policy] }>()

const metrics = ['response_time_p95_ms', 'response_time_p99_ms', 'error_rate_ratio', 'throughput_rps']

function update(mutator: (policy: Policy) => void) {
  const policy = structuredClone(toRaw(props.policy))
  mutator(policy)
  emit('update', policy)
}

// An empty number input also means unfinished typing ("1e", "-"): only a really empty field clears the key.
function sampleCount(input: HTMLInputElement): number | undefined | null {
  if (input.validity.badInput) return null
  return input.value.trim() === '' ? undefined : Number(input.value)
}

function setDefault(field: 'sample_floor' | 'min_samples' | 'max_missing_fraction' | 'max_gap_cells', input: HTMLInputElement) {
  const value = sampleCount(input)
  if (value === null) return
  update((policy) => {
    const defaults = { ...policy.defaults }
    if (value === undefined) delete defaults[field]
    else defaults[field] = value
    if (Object.keys(defaults).length) policy.defaults = defaults
    else delete policy.defaults
  })
}

function setRuleMinimum(index: number, input: HTMLInputElement) {
  const value = sampleCount(input)
  if (value === null) return
  update((policy) => {
    if (value === undefined) delete policy.rules[index].min_samples
    else policy.rules[index].min_samples = value
  })
}

function setMetric(index: number, metric: PolicyRule['metric']) {
  update((policy) => {
    policy.rules[index].metric = metric
    // The core rejects min_samples on throughput_rps (FIELD_NOT_APPLICABLE) and the field is hidden there.
    if (labels.value.samples && metric === 'throughput_rps') delete policy.rules[index].min_samples
  })
}

function addRule() {
  update((policy) => {
    policy.rules.push({
      id: `rule-${policy.rules.length + 1}`,
      metric: 'response_time_p95_ms',
      operator: 'lte',
      threshold: '0',
      scope: { kind: 'overall' },
    })
  })
}

function downloadPolicy() {
  const url = URL.createObjectURL(new Blob([stringifyPolicy(props.policy, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'policy.json'
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <fieldset class="policy-editor">
    <legend>{{ labels.legend }}</legend>
    <p class="field__hint">
      {{ status }}
    </p>
    <div class="field">
      <label for="policy-id">{{ labels.policyId }}</label>
      <input
        id="policy-id"
        class="control"
        :value="policy.policy_id"
        @input="update((draft) => { draft.policy_id = ($event.target as HTMLInputElement).value })"
      >
    </div>
    <template v-if="labels.samples">
      <div class="field">
        <label for="policy-sample-floor">{{ labels.samples.floorField }}</label>
        <input
          id="policy-sample-floor"
          class="control"
          type="number"
          min="1"
          max="1000000"
          step="1"
          :value="policy.defaults?.sample_floor"
          :aria-describedby="lowFloor ? 'policy-floor-warning policy-defaults-hint' : 'policy-defaults-hint'"
          @input="setDefault('sample_floor', $event.target as HTMLInputElement)"
        >
        <p
          v-if="lowFloor"
          id="policy-floor-warning"
          class="field__hint"
        >
          {{ labels.samples.lowFloor }}
        </p>
      </div>
      <div class="field">
        <label for="policy-min-samples">{{ labels.samples.defaultMinField }}</label>
        <input
          id="policy-min-samples"
          class="control"
          type="number"
          min="1"
          max="1000000"
          step="1"
          :value="policy.defaults?.min_samples"
          aria-describedby="policy-defaults-hint"
          @input="setDefault('min_samples', $event.target as HTMLInputElement)"
        >
        <p
          id="policy-defaults-hint"
          class="field__hint"
        >
          {{ labels.samples.defaultsHint }}
        </p>
      </div>
    </template>
    <template v-if="labels.tolerance">
      <div class="field">
        <label for="policy-max-missing-fraction">{{ labels.tolerance.fractionField }}</label>
        <input
          id="policy-max-missing-fraction"
          class="control"
          type="number"
          min="0"
          step="any"
          :value="policy.defaults?.max_missing_fraction"
          aria-describedby="policy-tolerance-hint"
          @input="setDefault('max_missing_fraction', $event.target as HTMLInputElement)"
        >
      </div>
      <div class="field">
        <label for="policy-max-gap-cells">{{ labels.tolerance.gapField }}</label>
        <input
          id="policy-max-gap-cells"
          class="control"
          type="number"
          min="0"
          max="100000"
          step="1"
          :value="policy.defaults?.max_gap_cells"
          aria-describedby="policy-tolerance-hint"
          @input="setDefault('max_gap_cells', $event.target as HTMLInputElement)"
        >
        <p
          id="policy-tolerance-hint"
          class="field__hint"
        >
          {{ labels.tolerance.hint }}
        </p>
      </div>
    </template>

    <div
      v-for="(rule, index) in policy.rules"
      :key="`${rule.id}-${index}`"
      class="policy-rule"
    >
      <h3>{{ labels.rule(index + 1) }}</h3>
      <div class="form-grid">
        <div class="field">
          <label :for="`rule-id-${index}`">{{ labels.ruleId }}</label>
          <input
            :id="`rule-id-${index}`"
            class="control"
            :value="rule.id"
            @input="update((draft) => { draft.rules[index].id = ($event.target as HTMLInputElement).value })"
          >
        </div>
        <div class="field">
          <label :for="`rule-metric-${index}`">{{ labels.metricField }}</label>
          <select
            :id="`rule-metric-${index}`"
            class="control"
            :value="rule.metric"
            @change="setMetric(index, ($event.target as HTMLSelectElement).value as typeof rule.metric)"
          >
            <option
              v-for="metric in metrics"
              :key="metric"
              :value="metric"
            >
              {{ labels.metricName(metric) }}
            </option>
          </select>
        </div>
        <div class="field">
          <label :for="`rule-operator-${index}`">{{ labels.operatorField }}</label>
          <select
            :id="`rule-operator-${index}`"
            class="control"
            :value="rule.operator"
            @change="update((draft) => { draft.rules[index].operator = ($event.target as HTMLSelectElement).value as typeof rule.operator })"
          >
            <option value="lte">
              {{ labels.operatorName('lte') }}
            </option>
            <option value="gte">
              {{ labels.operatorName('gte') }}
            </option>
          </select>
        </div>
        <div class="field">
          <label :for="`rule-threshold-${index}`">{{ labels.threshold }}</label>
          <input
            :id="`rule-threshold-${index}`"
            class="control"
            type="number"
            :value="rule.threshold"
            :aria-describedby="labels.thresholdHint?.(rule.metric, rule.threshold) ? `rule-threshold-hint-${index}` : undefined"
            @input="update((draft) => { draft.rules[index].threshold = ($event.target as HTMLInputElement).value })"
          >
          <p
            v-if="labels.thresholdHint?.(rule.metric, rule.threshold)"
            :id="`rule-threshold-hint-${index}`"
            class="field__hint"
          >
            {{ labels.thresholdHint(rule.metric, rule.threshold) }}
          </p>
        </div>
        <div
          v-if="labels.samples && rule.metric !== 'throughput_rps'"
          class="field"
        >
          <label :for="`rule-min-samples-${index}`">{{ labels.samples.ruleMinField }}</label>
          <input
            :id="`rule-min-samples-${index}`"
            class="control"
            type="number"
            min="1"
            max="1000000"
            step="1"
            :value="rule.min_samples"
            :aria-describedby="`rule-min-samples-hint-${index}`"
            @input="setRuleMinimum(index, $event.target as HTMLInputElement)"
          >
          <p
            :id="`rule-min-samples-hint-${index}`"
            class="field__hint"
          >
            {{ labels.samples.ruleHint }}
          </p>
        </div>
        <div class="field">
          <label :for="`rule-scope-${index}`">{{ labels.scopeField }}</label>
          <select
            :id="`rule-scope-${index}`"
            class="control"
            :value="rule.scope.kind"
            @change="update((draft) => { draft.rules[index].scope = ($event.target as HTMLSelectElement).value === 'transaction' ? { kind: 'transaction', name: '' } : { kind: 'overall' } })"
          >
            <option value="overall">
              {{ labels.scopeOverall }}
            </option>
            <option value="transaction">
              {{ labels.scopeTransaction }}
            </option>
          </select>
        </div>
        <div
          v-if="rule.scope.kind === 'transaction'"
          class="field"
        >
          <label :for="`rule-transaction-${index}`">{{ labels.transactionName }}</label>
          <input
            :id="`rule-transaction-${index}`"
            class="control"
            :value="rule.scope.name"
            @input="update((draft) => { const scope = draft.rules[index].scope; if (scope.kind === 'transaction') scope.name = ($event.target as HTMLInputElement).value })"
          >
        </div>
      </div>
      <button
        class="control button"
        type="button"
        @click="update((draft) => { draft.rules.splice(index, 1) })"
      >
        {{ labels.removeRule }}
      </button>
    </div>

    <div class="policy-editor__actions">
      <button
        class="control button"
        type="button"
        @click="addRule"
      >
        {{ labels.addRule }}
      </button>
      <button
        class="control button"
        type="button"
        @click="downloadPolicy"
      >
        {{ labels.download }}
      </button>
    </div>
    <ul
      v-if="errors.length"
      class="field__errors"
      aria-live="polite"
    >
      <li
        v-for="error in errors"
        :key="`${error.code}-${error.json_pointer}`"
        :lang="error.code === 'MALFORMED_JSON' ? undefined : labels.errorLang"
      >
        {{ error.json_pointer }}: {{ error.message }}
      </li>
    </ul>
  </fieldset>
</template>
