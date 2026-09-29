<script setup lang="ts">
import { toRaw } from 'vue'
import { stringifyPolicy } from './api'
import type { Policy, PolicyError } from './types'

const props = defineProps<{
  policy: Policy
  errors: PolicyError[]
  status: string
}>()

const emit = defineEmits<{ update: [policy: Policy] }>()

const metrics = ['response_time_p95_ms', 'response_time_p99_ms', 'error_rate_ratio', 'throughput_rps']

function update(mutator: (policy: Policy) => void) {
  const policy = structuredClone(toRaw(props.policy))
  mutator(policy)
  emit('update', policy)
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
    <legend>Policy draft</legend>
    <p class="field__hint">
      {{ status }}
    </p>
    <div class="field">
      <label for="policy-id">Policy ID</label>
      <input
        id="policy-id"
        class="control"
        :value="policy.policy_id"
        @input="update((draft) => { draft.policy_id = ($event.target as HTMLInputElement).value })"
      >
    </div>

    <div
      v-for="(rule, index) in policy.rules"
      :key="`${rule.id}-${index}`"
      class="policy-rule"
    >
      <h3>Rule {{ index + 1 }}</h3>
      <div class="form-grid">
        <div class="field">
          <label :for="`rule-id-${index}`">Rule ID</label>
          <input
            :id="`rule-id-${index}`"
            class="control"
            :value="rule.id"
            @input="update((draft) => { draft.rules[index].id = ($event.target as HTMLInputElement).value })"
          >
        </div>
        <div class="field">
          <label :for="`rule-metric-${index}`">Metric</label>
          <select
            :id="`rule-metric-${index}`"
            class="control"
            :value="rule.metric"
            @change="update((draft) => { draft.rules[index].metric = ($event.target as HTMLSelectElement).value as typeof rule.metric })"
          >
            <option
              v-for="metric in metrics"
              :key="metric"
              :value="metric"
            >
              {{ metric }}
            </option>
          </select>
        </div>
        <div class="field">
          <label :for="`rule-operator-${index}`">Operator</label>
          <select
            :id="`rule-operator-${index}`"
            class="control"
            :value="rule.operator"
            @change="update((draft) => { draft.rules[index].operator = ($event.target as HTMLSelectElement).value as typeof rule.operator })"
          >
            <option value="lte">
              lte
            </option>
            <option value="gte">
              gte
            </option>
          </select>
        </div>
        <div class="field">
          <label :for="`rule-threshold-${index}`">Threshold</label>
          <input
            :id="`rule-threshold-${index}`"
            class="control"
            type="number"
            :value="rule.threshold"
            @input="update((draft) => { draft.rules[index].threshold = ($event.target as HTMLInputElement).value })"
          >
        </div>
        <div class="field">
          <label :for="`rule-scope-${index}`">Scope</label>
          <select
            :id="`rule-scope-${index}`"
            class="control"
            :value="rule.scope.kind"
            @change="update((draft) => { draft.rules[index].scope = ($event.target as HTMLSelectElement).value === 'transaction' ? { kind: 'transaction', name: '' } : { kind: 'overall' } })"
          >
            <option value="overall">
              Overall
            </option>
            <option value="transaction">
              Transaction
            </option>
          </select>
        </div>
        <div
          v-if="rule.scope.kind === 'transaction'"
          class="field"
        >
          <label :for="`rule-transaction-${index}`">Transaction name</label>
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
        Remove rule
      </button>
    </div>

    <div class="policy-editor__actions">
      <button
        class="control button"
        type="button"
        @click="addRule"
      >
        Add rule
      </button>
      <button
        class="control button"
        type="button"
        @click="downloadPolicy"
      >
        Download policy
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
      >
        {{ error.json_pointer }}: {{ error.message }}
      </li>
    </ul>
  </fieldset>
</template>
