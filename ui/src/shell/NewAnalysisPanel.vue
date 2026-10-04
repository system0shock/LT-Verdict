<script setup lang="ts">
import { computed } from 'vue'
import type { Policy, PolicyError, SourceProfile } from '../types'
import { SETUP_LABELS } from './labels'
import { RULES_LABELS } from './labels.rules'
import { summarizePolicy } from './rules'
import { buildReadiness, msToSeconds, secondsToMs, type ReadinessLevel } from './setup'

const props = defineProps<{
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
  aiRequested: boolean
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
  'open-rules': []
  'ai-requested': [value: boolean]
  analyze: []
}>()

const readiness = computed(() => buildReadiness({
  busy: props.busy,
  aiRequested: props.aiRequested,
  inputName: props.inputFile?.name ?? null,
  policyId: props.policy ? props.policy.policy_id : null,
  policyHasErrors: props.policyErrors.length > 0,
  resourceName: props.resourceFile?.name ?? null,
  plans: { diagnostic: !!props.diagnosticFile, capacity: !!props.capacityFile, trend: !!props.trendFile },
  onlineProfileCount: props.sourceProfileIds.length,
  sourceRequestError: props.sourceRequestError,
  contextCount: props.sourceContextFiles.length,
  postgres: { pre: !!props.postgresPreFile, post: !!props.postgresPostFile, html: !!props.pgProfileHtmlFile },
}))

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

function levelLabel(level: ReadinessLevel) {
  return {
    ok: SETUP_LABELS.levelOk,
    info: SETUP_LABELS.levelInfo,
    warn: SETUP_LABELS.levelWarn,
    block: SETUP_LABELS.levelBlock,
  }[level]
}
</script>

<template>
  <section
    id="run-setup"
    class="panel run-setup new-analysis"
    lang="ru"
    aria-labelledby="run-setup-title"
  >
    <header class="panel__header">
      <h2 id="run-setup-title">
        {{ SETUP_LABELS.title }}
      </h2>
      <p>{{ SETUP_LABELS.lead }}</p>
    </header>

    <div class="new-analysis__layout">
      <div class="new-analysis__sections">
        <section
          class="new-analysis__section"
          aria-labelledby="setup-input-title"
        >
          <h3 id="setup-input-title">
            {{ SETUP_LABELS.inputTitle }}
          </h3>
          <div class="field">
            <label for="input-file">{{ SETUP_LABELS.inputLabel }}</label>
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
              {{ inputFile?.name ?? SETUP_LABELS.inputNone }} {{ SETUP_LABELS.inputHint }}
            </p>
          </div>
        </section>

        <section
          class="new-analysis__section"
          aria-labelledby="setup-rules-title"
        >
          <h3 id="setup-rules-title">
            {{ SETUP_LABELS.rulesTitle }}
          </h3>
          <div class="field">
            <label for="policy-file">{{ SETUP_LABELS.policyLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
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
              {{ policyStatus || SETUP_LABELS.policyNone }}
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
          </div>
          <template v-if="policy">
            <p>{{ RULES_LABELS.summary(summarizePolicy(policy).id, summarizePolicy(policy).rules, summarizePolicy(policy).transactions) }}</p>
            <button
              type="button"
              class="control button"
              @click="emit('open-rules')"
            >
              {{ RULES_LABELS.openRules }}
            </button>
          </template>
        </section>

        <section
          class="new-analysis__section"
          aria-labelledby="setup-system-title"
        >
          <h3 id="setup-system-title">
            {{ SETUP_LABELS.systemTitle }}
          </h3>
          <p>{{ SETUP_LABELS.systemLead }}</p>
          <div class="form-grid">
            <div class="field">
              <label for="resource-snapshot-file">{{ SETUP_LABELS.resourcesLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
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
                {{ resourceFile?.name ?? SETUP_LABELS.resourcesNone }}
              </p>
            </div>

            <div class="field">
              <label for="source-context-file">{{ SETUP_LABELS.contextLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
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
                {{ sourceContextFiles.length ? sourceContextFiles.map((file) => file.name).join(', ') : SETUP_LABELS.contextNone }} {{ SETUP_LABELS.contextHint }}
              </p>
            </div>

            <div class="field">
              <label for="source-profile">{{ SETUP_LABELS.sourceProfileLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
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
                  {{ SETUP_LABELS.sourceProfileNone }}
                </option>
                <option
                  v-for="profile in sourceProfiles"
                  :key="profile.id"
                  :value="profile.id"
                  :selected="sourceProfileIds.includes(profile.id)"
                >
                  {{ profile.id }} - {{ profile.source_kind }} / {{ profile.transport }}
                </option>
              </select>
              <p class="field__hint">
                {{ SETUP_LABELS.sourceProfileHint }}
              </p>
              <p
                v-if="sourceProfiles.length === 0"
                class="field__hint"
              >
                {{ SETUP_LABELS.sourceProfilesEmpty }}
              </p>
            </div>

            <template v-if="sourceProfileIds.length">
              <div class="field">
                <label for="source-window-origin">{{ SETUP_LABELS.windowLabel }}</label>
                <select
                  id="source-window-origin"
                  :value="sourceWindowOrigin"
                  :disabled="busy"
                  @input="emit('source-window-origin', selectedWindowOrigin($event))"
                  @change="emit('source-window-origin', selectedWindowOrigin($event))"
                >
                  <option value="auto">
                    {{ SETUP_LABELS.windowAuto }}
                  </option>
                  <option value="explicit">
                    {{ SETUP_LABELS.windowExplicit }}
                  </option>
                </select>
              </div>
              <template v-if="sourceWindowOrigin === 'explicit'">
                <div class="field">
                  <label for="source-start">{{ SETUP_LABELS.startLabel }}</label>
                  <input
                    id="source-start"
                    type="number"
                    min="0"
                    step="1"
                    :value="msToSeconds(sourceStart)"
                    :disabled="busy"
                    @input="emit('source-start', secondsToMs(($event.target as HTMLInputElement).value))"
                  >
                </div>
                <div class="field">
                  <label for="source-end">{{ SETUP_LABELS.endLabel }}</label>
                  <input
                    id="source-end"
                    type="number"
                    min="0"
                    step="1"
                    :value="msToSeconds(sourceEnd)"
                    :disabled="busy"
                    @input="emit('source-end', secondsToMs(($event.target as HTMLInputElement).value))"
                  >
                </div>
              </template>
              <div class="field">
                <label for="source-step">{{ SETUP_LABELS.stepLabel }}</label>
                <input
                  id="source-step"
                  type="number"
                  min="1"
                  step="1"
                  :value="msToSeconds(sourceStep)"
                  :disabled="busy"
                  @input="emit('source-step', secondsToMs(($event.target as HTMLInputElement).value))"
                >
              </div>
              <template v-if="sourceWindowOrigin === 'auto'">
                <div class="field">
                  <label for="source-margin">{{ SETUP_LABELS.marginLabel }}</label>
                  <input
                    id="source-margin"
                    type="number"
                    min="0"
                    step="1"
                    :value="msToSeconds(sourceMargin)"
                    :disabled="busy"
                    aria-describedby="source-margin-hint"
                    @input="emit('source-margin', secondsToMs(($event.target as HTMLInputElement).value))"
                  >
                  <p
                    id="source-margin-hint"
                    class="field__hint"
                  >
                    {{ SETUP_LABELS.marginHint }}
                  </p>
                </div>
                <div class="field">
                  <label for="source-max-idle-gap">{{ SETUP_LABELS.idleLabel }}</label>
                  <input
                    id="source-max-idle-gap"
                    type="number"
                    min="1"
                    step="1"
                    :value="msToSeconds(sourceMaxIdleGap)"
                    :disabled="busy"
                    aria-describedby="source-max-idle-gap-hint"
                    @input="emit('source-max-idle-gap', secondsToMs(($event.target as HTMLInputElement).value))"
                  >
                  <p
                    id="source-max-idle-gap-hint"
                    class="field__hint"
                  >
                    {{ SETUP_LABELS.idleHint }}
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
              <label for="postgres-profile">{{ SETUP_LABELS.postgresProfileLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
              <select
                id="postgres-profile"
                :value="postgresProfileId"
                :disabled="busy || postgresProfiles.length === 0"
                @change="emit('postgres-profile', ($event.target as HTMLSelectElement).value)"
              >
                <option value="">
                  {{ SETUP_LABELS.postgresProfileNone }}
                </option>
                <option
                  v-for="profile in postgresProfiles"
                  :key="profile.id"
                  :value="profile.id"
                >
                  {{ profile.id }} - {{ profile.source_kind }} / {{ profile.transport }}
                </option>
              </select>
              <div class="policy-editor__actions">
                <button
                  class="button-secondary"
                  type="button"
                  :disabled="busy || !postgresProfileId"
                  @click="emit('capture-postgres', 'pre')"
                >
                  {{ SETUP_LABELS.captureBefore }}
                </button>
                <button
                  class="button-secondary"
                  type="button"
                  :disabled="busy || !postgresProfileId"
                  @click="emit('capture-postgres', 'post')"
                >
                  {{ SETUP_LABELS.captureAfter }}
                </button>
              </div>
              <p class="field__hint">
                {{ SETUP_LABELS.postgresHint }}
              </p>
            </div>

            <div class="field">
              <label for="postgres-pre-file">{{ SETUP_LABELS.postgresPreLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
              <input
                id="postgres-pre-file"
                class="control control--file"
                type="file"
                accept="application/json,.json"
                :disabled="busy"
                @change="emit('postgres-pre', selectedFile($event))"
              >
              <p class="field__hint">
                {{ postgresPreFile?.name ?? SETUP_LABELS.postgresPreNone }} {{ SETUP_LABELS.postgresPreHint }}
              </p>
            </div>

            <div class="field">
              <label for="postgres-post-file">{{ SETUP_LABELS.postgresPostLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
              <input
                id="postgres-post-file"
                class="control control--file"
                type="file"
                accept="application/json,.json"
                :disabled="busy"
                @change="emit('postgres-post', selectedFile($event))"
              >
              <p class="field__hint">
                {{ postgresPostFile?.name ?? SETUP_LABELS.postgresPostNone }}
              </p>
            </div>

            <div class="field">
              <label for="pg-profile-html-file">{{ SETUP_LABELS.pgHtmlLabel }} <span class="muted">{{ SETUP_LABELS.pgHtmlOptional }}</span></label>
              <input
                id="pg-profile-html-file"
                class="control control--file"
                type="file"
                accept="text/html,.html"
                :disabled="busy"
                @change="emit('pg-profile-html', selectedFile($event))"
              >
              <p class="field__hint">
                {{ pgProfileHtmlFile?.name ?? SETUP_LABELS.pgHtmlNone }} {{ SETUP_LABELS.pgHtmlHint }}
              </p>
            </div>
          </div>
        </section>

        <section
          class="new-analysis__section"
          aria-labelledby="setup-plans-title"
        >
          <h3 id="setup-plans-title">
            {{ SETUP_LABELS.plansTitle }}
          </h3>
          <p>{{ SETUP_LABELS.plansLead }}</p>
          <div class="form-grid">
            <div class="field">
              <label for="correlation-plan-file">{{ SETUP_LABELS.correlationLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
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
                {{ diagnosticFile?.name ?? SETUP_LABELS.correlationNone }} {{ SETUP_LABELS.planNeedsSnapshot }}
              </p>
            </div>

            <div class="field">
              <label for="capacity-plan-file">{{ SETUP_LABELS.capacityLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
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
                {{ capacityFile?.name ?? SETUP_LABELS.capacityNone }} {{ SETUP_LABELS.planNeedsSnapshot }}
              </p>
            </div>

            <div class="field">
              <label for="trend-plan-file">{{ SETUP_LABELS.trendLabel }} <span class="muted">{{ SETUP_LABELS.optional }}</span></label>
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
                {{ trendFile?.name ?? SETUP_LABELS.trendNone }} {{ SETUP_LABELS.planNeedsSnapshot }}
              </p>
            </div>
          </div>
        </section>

        <section
          class="new-analysis__section"
          aria-labelledby="setup-ai-title"
        >
          <h3 id="setup-ai-title">
            {{ SETUP_LABELS.aiTitle }}
          </h3>
          <p>{{ SETUP_LABELS.aiText }}</p>
          <div class="field">
            <label for="ai-requested">
              <input
                id="ai-requested"
                data-testid="ai-requested"
                type="checkbox"
                role="switch"
                :checked="aiRequested"
                :disabled="busy"
                @change="emit('ai-requested', ($event.target as HTMLInputElement).checked)"
              >
              {{ SETUP_LABELS.aiRequestedLabel }}
            </label>
          </div>
        </section>
      </div>

      <section
        class="new-analysis__readiness"
        aria-labelledby="readiness-title"
      >
        <h3 id="readiness-title">
          {{ SETUP_LABELS.readinessTitle }}
        </h3>
        <ul class="readiness__list">
          <li
            v-for="item in readiness.items"
            :key="item.key"
            class="readiness__item"
            data-testid="readiness-item"
            :data-key="item.key"
            :data-level="item.level"
          >
            <span class="readiness__state">{{ levelLabel(item.level) }}</span>
            <span><strong>{{ item.title }}</strong> <span class="readiness__detail">{{ item.detail }}</span></span>
          </li>
        </ul>
        <h4>{{ SETUP_LABELS.willTitle }}</h4>
        <ul
          class="readiness__will"
          data-testid="readiness-will"
        >
          <li
            v-for="(line, index) in readiness.will"
            :key="`${line}-${index}`"
          >
            {{ line }}
          </li>
        </ul>
        <div
          id="readiness-status"
          role="status"
        >
          <p>{{ readiness.canStart ? SETUP_LABELS.startReady : SETUP_LABELS.startBlocked }}</p>
          <ul v-if="!readiness.canStart">
            <li
              v-for="item in readiness.items.filter((entry) => entry.level === 'block')"
              :key="item.key"
            >
              {{ item.title }}: {{ item.detail }}
            </li>
          </ul>
        </div>
        <button
          type="button"
          class="control button button--primary readiness__start"
          data-testid="start-analysis"
          aria-describedby="readiness-status"
          :disabled="!readiness.canStart"
          @click="emit('analyze')"
        >
          {{ SETUP_LABELS.startButton }}
        </button>
      </section>
    </div>
  </section>
</template>
