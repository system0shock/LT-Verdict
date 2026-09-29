<script setup lang="ts">
import PolicyEditor from './PolicyEditor.vue'
import type { Policy, PolicyError, SourceProfile } from './types'

defineProps<{
  inputFile: File | null
  resourceFile: File | null
  diagnosticFile: File | null
  capacityFile: File | null
  trendFile: File | null
  sourceContextFiles: File[]
  sourceProfiles: SourceProfile[]
  sourceProfileIds: string[]
  postgresProfiles: SourceProfile[]
  postgresProfileId: string
  postgresPreFile: File | null
  postgresPostFile: File | null
  pgProfileHtmlFile: File | null
  sourceWindowOrigin: 'auto' | 'explicit'
  sourceStart: string
  sourceEnd: string
  sourceStep: string
  sourceMargin: string
  sourceMaxIdleGap: string
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
  capacity: [file: File | null]
  trend: [file: File | null]
  'source-contexts': [files: File[]]
  'source-profiles': [ids: string[]]
  'postgres-profile': [id: string]
  'postgres-pre': [file: File | null]
  'postgres-post': [file: File | null]
  'pg-profile-html': [file: File | null]
  'capture-postgres': [phase: 'pre' | 'post']
  'source-window-origin': [value: 'auto' | 'explicit']
  'source-start': [value: string]
  'source-end': [value: string]
  'source-step': [value: string]
  'source-margin': [value: string]
  'source-max-idle-gap': [value: string]
  'policy-file': [file: File | null]
  'update-policy': [policy: Policy]
  analyze: []
}>()

function selectedFile(event: Event) {
  return (event.target as HTMLInputElement).files?.item(0) ?? null
}

function selectedFiles(event: Event) {
  return Array.from((event.target as HTMLInputElement).files ?? [])
}

function selectedValues(event: Event) {
  return Array.from((event.target as HTMLSelectElement).selectedOptions, (option) => option.value).filter(Boolean)
}

function selectedWindowOrigin(event: Event) {
  return (event.target as HTMLSelectElement).value as 'auto' | 'explicit'
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
          :disabled="busy || sourceProfileIds.length > 0"
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
          :disabled="busy || sourceProfileIds.length > 0"
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
        <label for="capacity-plan-file">Capacity plan <span class="muted">(optional)</span></label>
        <input
          id="capacity-plan-file"
          data-testid="capacity-plan-file"
          class="control control--file"
          type="file"
          accept="application/json,.json"
          :disabled="busy || sourceProfileIds.length > 0"
          aria-describedby="capacity-plan-hint"
          @change="emit('capacity', selectedFile($event))"
        >
        <p
          id="capacity-plan-hint"
          class="field__hint"
        >
          {{ capacityFile?.name ?? 'No capacity plan selected.' }} Requires a matching resource snapshot.
        </p>
      </div>

      <div class="field">
        <label for="trend-plan-file">Trend plan <span class="muted">(optional)</span></label>
        <input
          id="trend-plan-file"
          data-testid="trend-plan-file"
          class="control control--file"
          type="file"
          accept="application/json,.json"
          :disabled="busy || sourceProfileIds.length > 0"
          aria-describedby="trend-plan-hint"
          @change="emit('trend', selectedFile($event))"
        >
        <p
          id="trend-plan-hint"
          class="field__hint"
        >
          {{ trendFile?.name ?? 'No trend plan selected.' }} Requires a matching resource snapshot.
        </p>
      </div>

      <div class="field">
        <label for="source-context-file">OpenSearch context <span class="muted">(optional)</span></label>
        <input
          id="source-context-file"
          class="control control--file"
          type="file"
          multiple
          accept="application/json,.json"
          :disabled="busy || sourceProfileIds.length > 0"
          @change="emit('source-contexts', selectedFiles($event))"
        >
        <p class="field__hint">
          {{ sourceContextFiles.length ? sourceContextFiles.map((file) => file.name).join(', ') : 'No error context selected.' }} Must belong to the same load input.
        </p>
      </div>

      <div class="field">
        <label for="source-profile">Online source profile <span class="muted">(optional)</span></label>
        <select
          id="source-profile"
          data-testid="source-profile"
          multiple
          :disabled="busy || sourceProfiles.length === 0"
          @change="emit('source-profiles', selectedValues($event))"
        >
          <option
            value=""
            :selected="sourceProfileIds.length === 0"
          >
            No online source
          </option>
          <option
            v-for="profile in sourceProfiles"
            :key="profile.id"
            :value="profile.id"
            :selected="sourceProfileIds.includes(profile.id)"
          >
            {{ profile.id }} — {{ profile.source_kind }} / {{ profile.transport }}
          </option>
        </select>
        <p class="field__hint">
          Select up to 16 profiles. They share one time grid and produce downloadable metrics or error context.
        </p>
      </div>

      <template v-if="sourceProfileIds.length">
        <div class="field">
          <label for="source-window-origin">Source window</label>
          <select
            id="source-window-origin"
            :value="sourceWindowOrigin"
            :disabled="busy"
            @input="emit('source-window-origin', selectedWindowOrigin($event))"
            @change="emit('source-window-origin', selectedWindowOrigin($event))"
          >
            <option value="auto">
              Auto (from the load file)
            </option>
            <option value="explicit">
              Explicit period
            </option>
          </select>
        </div>
        <template v-if="sourceWindowOrigin === 'explicit'">
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
        </template>
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
        <template v-if="sourceWindowOrigin === 'auto'">
          <div class="field">
            <label for="source-margin">Margin (ms)</label>
            <input
              id="source-margin"
              type="number"
              min="0"
              step="1000"
              :value="sourceMargin"
              :disabled="busy"
              aria-describedby="source-margin-hint"
              @input="emit('source-margin', ($event.target as HTMLInputElement).value)"
            >
            <p
              id="source-margin-hint"
              class="field__hint"
            >
              Extends the recognized run period on both sides before grid alignment; must be a multiple of the step.
            </p>
          </div>
          <div class="field">
            <label for="source-max-idle-gap">Max idle gap (ms)</label>
            <input
              id="source-max-idle-gap"
              type="number"
              min="1000"
              step="1000"
              :value="sourceMaxIdleGap"
              :disabled="busy"
              aria-describedby="source-max-idle-gap-hint"
              @input="emit('source-max-idle-gap', ($event.target as HTMLInputElement).value)"
            >
            <p
              id="source-max-idle-gap-hint"
              class="field__hint"
            >
              Refuses the auto window when the load file contains a longer idle gap; at least the step and a multiple of it.
            </p>
          </div>
        </template>
      </template>
      <p
        v-if="sourceRequestError"
        data-testid="source-request-error"
        class="validation-error"
        role="alert"
      >
        {{ sourceRequestError }}
      </p>

      <div class="field">
        <label for="postgres-profile">PostgreSQL profile <span class="muted">(optional)</span></label>
        <select
          id="postgres-profile"
          :value="postgresProfileId"
          :disabled="busy || postgresProfiles.length === 0"
          @change="emit('postgres-profile', ($event.target as HTMLSelectElement).value)"
        >
          <option value="">
            No PostgreSQL capture
          </option>
          <option
            v-for="profile in postgresProfiles"
            :key="profile.id"
            :value="profile.id"
          >
            {{ profile.id }} — {{ profile.source_kind }} / {{ profile.transport }}
          </option>
        </select>
        <div class="policy-editor__actions">
          <button
            class="button-secondary"
            type="button"
            :disabled="busy || !postgresProfileId"
            @click="emit('capture-postgres', 'pre')"
          >
            Capture pre
          </button>
          <button
            class="button-secondary"
            type="button"
            :disabled="busy || !postgresProfileId"
            @click="emit('capture-postgres', 'post')"
          >
            Capture post
          </button>
        </div>
        <p class="field__hint">
          Download pre before the load test, attach it below, then capture post after the test.
        </p>
      </div>

      <div class="field">
        <label for="postgres-pre-file">PostgreSQL pre capture <span class="muted">(optional)</span></label>
        <input
          id="postgres-pre-file"
          class="control control--file"
          type="file"
          accept="application/json,.json"
          :disabled="busy"
          @change="emit('postgres-pre', selectedFile($event))"
        >
        <p class="field__hint">
          {{ postgresPreFile?.name ?? 'No pre capture selected.' }} Also binds an explicit post capture.
        </p>
      </div>

      <div class="field">
        <label for="postgres-post-file">PostgreSQL post capture <span class="muted">(optional)</span></label>
        <input
          id="postgres-post-file"
          class="control control--file"
          type="file"
          accept="application/json,.json"
          :disabled="busy"
          @change="emit('postgres-post', selectedFile($event))"
        >
        <p class="field__hint">
          {{ postgresPostFile?.name ?? 'No post capture selected.' }}
        </p>
      </div>

      <div class="field">
        <label for="pg-profile-html-file">pg_profile HTML <span class="muted">(optional, download only)</span></label>
        <input
          id="pg-profile-html-file"
          class="control control--file"
          type="file"
          accept="text/html,.html"
          :disabled="busy"
          @change="emit('pg-profile-html', selectedFile($event))"
        >
        <p class="field__hint">
          {{ pgProfileHtmlFile?.name ?? 'No pg_profile report selected.' }} Stored as an inert download; never rendered.
        </p>
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
      :disabled="!inputFile || busy || !!sourceRequestError"
      @click="emit('analyze')"
    >
      Analyze run
    </button>
  </section>
</template>
