<script setup lang="ts">
import { computed, ref, toRaw, watch } from 'vue'
import PolicyEditor from '../PolicyEditor.vue'
import type { AnalysisResult, Policy, PolicyError } from '../types'
import type { VerdictSummary } from '../verdictSummary'
import { RULES_LABELS } from './labels.rules'
import { MIN_SAMPLES_FLOOR, POLICY_TEMPLATES, expandPerTransaction, templateById, thresholdHintText, transactionRefs } from './rules'

const props = defineProps<{
  policy: Policy | null
  policyStatus: string
  policyErrors: PolicyError[]
  busy: boolean
  result: AnalysisResult | null
  runName: string
  canTrial: boolean
  trialBusy: boolean
  summary: VerdictSummary | null
}>()
const emit = defineEmits<{ 'policy-file': [file: File | null]; 'update-policy': [policy: Policy]; trial: []; 'open-overview': [] }>()
const editorLabels = { ...RULES_LABELS, thresholdHint: thresholdHintText }
const refs = computed(() => transactionRefs(props.result))
const message = ref('')
const skippedSmall = ref('')
const skippedAmbiguous = ref('')
const skippedUnnamed = ref('')
watch(() => props.result?.run_id, () => { message.value = ''; skippedSmall.value = ''; skippedAmbiguous.value = ''; skippedUnnamed.value = '' })

function expand() {
  if (!props.policy) return
  const plan = expandPerTransaction(toRaw(props.policy), refs.value)
  skippedSmall.value = ''
  skippedAmbiguous.value = ''
  skippedUnnamed.value = ''
  if (plan.refused) { message.value = RULES_LABELS.perTxRefused[plan.refused]; return }
  if (plan.added || plan.removed) emit('update-policy', plan.policy)
  message.value = plan.added || plan.removed ? RULES_LABELS.perTxDone(plan.added, props.runName, plan.removed) : RULES_LABELS.perTxNothingNew
  const floor = props.policy.defaults?.sample_floor ?? MIN_SAMPLES_FLOOR
  if (plan.skippedSmall.length) skippedSmall.value = RULES_LABELS.perTxSkippedSmall(floor, RULES_LABELS.perTxList(plan.skippedSmall, true))
  if (plan.skippedAmbiguous.length) skippedAmbiguous.value = RULES_LABELS.perTxSkippedAmbiguous(RULES_LABELS.perTxList(plan.skippedAmbiguous, false))
  if (plan.skippedUnnamed.length) skippedUnnamed.value = RULES_LABELS.perTxSkippedUnnamed(plan.skippedUnnamed.length)
}
</script>

<template>
  <section
    id="rules-panel"
    class="panel rules-panel__root"
    lang="ru"
    aria-labelledby="rules-title"
  >
    <h2 id="rules-title">
      {{ RULES_LABELS.title }}
    </h2>
    <p>{{ RULES_LABELS.lead }}</p>
    <p v-if="busy">
      {{ RULES_LABELS.busyNote }}
    </p>

    <section
      class="rules-panel__section"
      aria-labelledby="rules-templates-title"
    >
      <h3 id="rules-templates-title">
        {{ RULES_LABELS.templatesTitle }}
      </h3>
      <div
        v-for="template in POLICY_TEMPLATES"
        :key="template.id"
        class="rules-panel__template"
      >
        <button
          type="button"
          class="control button button-secondary"
          :disabled="busy"
          :aria-describedby="`rules-template-note-${template.id}`"
          @click="emit('update-policy', templateById(template.id)!)"
        >
          {{ RULES_LABELS.templateName(template.id) }}
        </button>
        <p
          :id="`rules-template-note-${template.id}`"
          class="field__hint"
        >
          {{ RULES_LABELS.templateNote(template.id) }}
        </p>
      </div>
    </section>

    <section
      class="rules-panel__section"
      aria-labelledby="rules-file-title"
    >
      <h3 id="rules-file-title">
        {{ RULES_LABELS.fileTitle }}
      </h3>
      <label for="rules-policy-file">{{ RULES_LABELS.fileLabel }}</label>
      <input
        id="rules-policy-file"
        data-testid="rules-policy-file"
        class="control control--file"
        type="file"
        accept="application/json,.json"
        :disabled="busy"
        @change="emit('policy-file', ($event.target as HTMLInputElement).files?.item(0) ?? null)"
      >
      <template v-if="!policy">
        <p>{{ RULES_LABELS.noPolicy }}</p>
        <p v-if="policyStatus">
          {{ policyStatus }}
        </p>
        <ul
          v-if="policyErrors.length"
          class="field__errors"
        >
          <li
            v-for="error in policyErrors"
            :key="`${error.code}-${error.json_pointer}`"
            :lang="error.code === 'MALFORMED_JSON' ? undefined : 'en'"
          >
            {{ error.json_pointer }}: {{ error.message }}
          </li>
        </ul>
      </template>
      <PolicyEditor
        v-else
        :policy="policy"
        :errors="policyErrors"
        :status="policyStatus"
        :labels="editorLabels"
        @update="emit('update-policy', $event)"
      />
    </section>

    <section
      class="rules-panel__section"
      aria-labelledby="rules-per-tx-title"
    >
      <h3 id="rules-per-tx-title">
        {{ RULES_LABELS.perTxTitle }}
      </h3>
      <p>{{ RULES_LABELS.perTxLead }}</p>
      <p>{{ RULES_LABELS.perTxScopeNote }}</p>
      <button
        type="button"
        class="control button"
        :disabled="busy || !policy || refs.length === 0"
        :aria-describedby="!policy ? 'rules-per-tx-no-policy' : refs.length === 0 ? 'rules-per-tx-no-result' : undefined"
        @click="expand"
      >
        {{ RULES_LABELS.perTxButton }}
      </button>
      <p
        v-if="!policy"
        id="rules-per-tx-no-policy"
      >
        {{ RULES_LABELS.perTxNoPolicy }}
      </p>
      <p
        v-else-if="refs.length === 0"
        id="rules-per-tx-no-result"
      >
        {{ RULES_LABELS.perTxNoResult }}
      </p>
      <div
        v-if="message"
        role="status"
      >
        <p class="notice notice-warn rules-panel__message">
          {{ message }}
        </p>
        <p
          v-if="skippedSmall"
          class="rules-panel__message"
        >
          {{ skippedSmall }}
        </p>
        <p
          v-if="skippedAmbiguous"
          class="rules-panel__message"
        >
          {{ skippedAmbiguous }}
        </p>
        <p
          v-if="skippedUnnamed"
          class="rules-panel__message"
        >
          {{ skippedUnnamed }}
        </p>
      </div>
    </section>

    <section
      class="rules-panel__section"
      aria-labelledby="rules-trial-title"
    >
      <h3 id="rules-trial-title">
        {{ RULES_LABELS.trialTitle }}
      </h3>
      <p>{{ RULES_LABELS.trialLead }}</p>
      <p v-if="runName">
        {{ RULES_LABELS.trialTarget(runName) }}
      </p>
      <button
        type="button"
        class="control button"
        data-testid="trial-button"
        :disabled="busy || !canTrial"
        :aria-describedby="!canTrial ? 'rules-trial-blocked' : undefined"
        @click="emit('trial')"
      >
        {{ RULES_LABELS.trialButton }}
      </button>
      <p
        v-if="!canTrial"
        id="rules-trial-blocked"
      >
        {{ !policy ? RULES_LABELS.trialNoPolicy : policyErrors.length ? RULES_LABELS.trialInvalid : RULES_LABELS.trialNoRun }}
      </p>
      <p
        v-else-if="trialBusy"
        role="status"
      >
        {{ RULES_LABELS.trialRunning }}
      </p>
      <div
        v-if="summary"
        class="rules-panel__message"
        data-testid="trial-summary"
        role="status"
      >
        <h4>{{ RULES_LABELS.trialSummaryTitle }}</h4>
        <p>
          <strong>{{ summary.headline }}</strong>
          <span> ({{ summary.chip }})</span>
        </p>
        <button
          type="button"
          class="control button button-secondary"
          @click="emit('open-overview')"
        >
          {{ RULES_LABELS.openOverview }}
        </button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.rules-panel__root, .rules-panel__section, .rules-panel__template, .rules-panel__message { min-width: 0; overflow-wrap: anywhere; }
.rules-panel__section { margin-top: 1.5rem; }
.rules-panel__template { margin-block: 0.75rem; }
.rules-panel__root .control { max-width: 100%; }
.rules-panel__root button { min-height: 44px; }
</style>
