<script setup lang="ts">
import PolicyEditor from './PolicyEditor.vue'
import type { Policy, PolicyError, SourceProfile } from './types'

defineProps<{
  inputFile: File | null
  resourceFile: File | null
  diagnosticFile: File | null
  sourceProfiles: SourceProfile[]
  sourceProfileId: string
  sourceStart: string
  sourceEnd: string
  sourceStep: string
  sourceRequestError: string
  policy: Policy | null
  policyStatus: string
  policyErrors: PolicyError[]
  busy: boolean
}>()

const emit = defineEmits<{
  input: [file: File | null]
  resources: [file: File | null]
  diagnostics: [file: File | null]
  'source-profile': [id: string]
  'source-start': [value: string]
  'source-end': [value: string]
  'source-step': [value: string]
  'policy-file': [file: File | null]
  'update-policy': [policy: Policy]
  analyze: []
}>()

function selectedFile(event: Event) {
  return (event.target as HTMLInputElement).files?.item(0) ?? null
}
</script>

<template>
  <section
    id="run-setup"
    class="panel run-setup"
    aria-labelledby="run-setup-title"
  >
    <header class="panel__header">
      <h2 id="run-setup-title">
        Run setup
      </h2>
      <p>Choose a supported JMeter JTL or Gatling log.</p>
    </header>

    <div class="form-grid">
      <div class="field">
        <label for="input-file">Load test log</label>
        <input
          id="input-file"
          data-testid="input-file"
          class="control control--file"
          type="file"
          accept=".jtl,.xml,.log"
          :disabled="busy"
          @change="emit('input', selectedFile($event))"
        >
        <p class="field__hint">
          {{ inputFile?.name ?? 'No log selected.' }}
        </p>
      </div>

      <div class="field">
        <label for="policy-file">Policy file <span class="muted">(optional)</span></label>
        <input
          id="policy-file"
          data-testid="policy-file"
          class="control control--file"
          type="file"
          accept="application/json,.json"
          :disabled="busy"
          @change="emit('policy-file', selectedFile($event))"
        >
        <p class="field__hint">
          {{ policyStatus || 'No policy selected.' }}
        </p>
        <ul
          v-if="policyErrors.length"
          class="field__errors"
          aria-live="polite"
        >
          <li
            v-for="error in policyErrors"
            :key="`${error.code}-${error.json_pointer}`"
          >
            {{ error.json_pointer }}: {{ error.message }}
          </li>
        </ul>
      </div>

      <div class="field">
        <label for="resource-snapshot-file">Resource snapshot <span class="muted">(optional)</span></label>
        <input
          id="resource-snapshot-file"
          data-testid="resource-snapshot-file"
          class="control control--file"
          type="file"
          accept="application/json,.json"
          :disabled="busy || !!sourceProfileId"
          @change="emit('resources', selectedFile($event))"
        >
        <p class="field__hint">
          {{ resourceFile?.name ?? 'No resource snapshot selected.' }}
        </p>
      </div>

      <div class="field">
        <label for="correlation-plan-file">Correlation plan <span class="muted">(optional)</span></label>
        <input
          id="correlation-plan-file"
          data-testid="correlation-plan-file"
          class="control control--file"
          type="file"
          accept="application/json,.json"
          :disabled="busy || !!sourceProfileId"
          aria-describedby="correlation-plan-hint"
          @change="emit('diagnostics', selectedFile($event))"
        >
        <p
          id="correlation-plan-hint"
          class="field__hint"
        >
          {{ diagnosticFile?.name ?? 'No correlation plan selected.' }} Requires a matching resource snapshot.
        </p>
      </div>

      <div class="field">
        <label for="source-profile">Online source profile <span class="muted">(optional)</span></label>
        <select
          id="source-profile"
          data-testid="source-profile"
          :value="sourceProfileId"
          :disabled="busy || sourceProfiles.length === 0"
          @change="emit('source-profile', ($event.target as HTMLSelectElement).value)"
        >
          <option value="">
            No online source
          </option>
          <option
            v-for="profile in sourceProfiles"
            :key="profile.id"
            :value="profile.id"
          >
            {{ profile.id }} — {{ profile.source_kind }} / {{ profile.transport }}
          </option>
        </select>
        <p class="field__hint">
          Acquires a resource snapshot, which you can download and use for offline correlation only with its matching snapshot hash.
        </p>
      </div>

      <template v-if="sourceProfileId">
        <div class="field">
          <label for="source-start">Source start (UTC epoch ms)</label>
          <input
            id="source-start"
            type="number"
            min="0"
            step="1"
            :value="sourceStart"
            :disabled="busy"
            @input="emit('source-start', ($event.target as HTMLInputElement).value)"
          >
        </div>
        <div class="field">
          <label for="source-end">Source end (UTC epoch ms)</label>
          <input
            id="source-end"
            type="number"
            min="0"
            step="1"
            :value="sourceEnd"
            :disabled="busy"
            @input="emit('source-end', ($event.target as HTMLInputElement).value)"
          >
        </div>
        <div class="field">
          <label for="source-step">Source step (ms)</label>
          <input
            id="source-step"
            type="number"
            min="1000"
            step="1"
            :value="sourceStep"
            :disabled="busy"
            @input="emit('source-step', ($event.target as HTMLInputElement).value)"
          >
        </div>
        <p
          v-if="sourceRequestError"
          data-testid="source-request-error"
          class="validation-error"
          role="alert"
        >
          {{ sourceRequestError }}
        </p>
      </template>
    </div>

    <PolicyEditor
      v-if="policy"
      :policy="policy"
      :errors="policyErrors"
      :status="policyStatus"
      @update="emit('update-policy', $event)"
    />

    <button
      class="control button button--primary"
      type="button"
      :disabled="!inputFile || busy || !!sourceRequestError"
      @click="emit('analyze')"
    >
      Analyze run
    </button>
  </section>
</template>
