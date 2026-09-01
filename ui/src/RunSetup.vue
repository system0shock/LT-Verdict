<script setup lang="ts">
import PolicyEditor from './PolicyEditor.vue'
import type { Policy, PolicyError } from './types'

defineProps<{
  inputFile: File | null
  policy: Policy | null
  policyStatus: string
  policyErrors: PolicyError[]
  busy: boolean
}>()

const emit = defineEmits<{
  input: [file: File | null]
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
      :disabled="!inputFile || busy"
      @click="emit('analyze')"
    >
      Analyze run
    </button>
  </section>
</template>
