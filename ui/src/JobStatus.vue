<script setup lang="ts">
import type { JobStatus } from './types'

const props = defineProps<{
  job: JobStatus | null
  uploadProgress: number
  busy: boolean
  uploading: boolean
  uploadCancelled: boolean
  uploadLabels: { cancel: string; cancelled: string }
  uploadLang?: string
  pollIssue: 'none' | 'retrying' | 'lost'
  labels: {
    retrying: string; lost: string; retry: string
    states: Record<string, string>; uploading: string; uploaded: (percent: number) => string
    bytes: (processed: string, total: string) => string; busyTitle: string; busyText: string; cancel: string
  }
  noticeLang?: string
}>()

defineEmits<{ cancel: []; cancelUpload: []; retry: [] }>()

const active = () => props.job?.state === 'QUEUED' || props.job?.state === 'PROCESSING'
const processed = () => props.job?.processed_bytes ?? props.uploadProgress
const total = () => props.job?.total_bytes ?? 100
</script>

<template>
  <section
    v-if="job || uploadProgress > 0 || busy || uploadCancelled"
    id="job-status"
    class="job-status"
    aria-live="polite"
  >
    <div
      v-if="busy"
      class="notice notice-warn"
      data-testid="busy-notice"
      role="status"
    >
      <strong :lang="noticeLang">{{ labels.busyTitle }}</strong>
      <span :lang="noticeLang">{{ labels.busyText }}</span>
    </div>
    <template v-else-if="uploadCancelled && !job && uploadProgress === 0">
      <p
        class="notice notice-info"
        data-testid="upload-cancelled"
        role="status"
        :lang="uploadLang"
      >
        {{ uploadLabels.cancelled }}
      </p>
    </template>
    <template v-else>
      <div class="job-copy">
        <strong :lang="uploadLang">{{ job ? (labels.states[job.state] ?? job.state) : labels.uploading }}</strong>
        <span
          v-if="job"
          :lang="uploadLang"
        >{{ labels.bytes(job.processed_bytes.toLocaleString(), job.total_bytes.toLocaleString()) }}</span>
        <span
          v-else
          :lang="uploadLang"
        >{{ labels.uploaded(uploadProgress) }}</span>
      </div>
      <progress
        data-testid="job-progress"
        :value="processed()"
        :max="total()"
        :aria-valuenow="processed()"
        :aria-valuemax="total()"
      />
      <button
        v-if="active()"
        type="button"
        class="button-secondary"
        :lang="uploadLang"
        @click="$emit('cancel')"
      >
        {{ labels.cancel }}
      </button>
      <button
        v-if="uploading"
        type="button"
        class="button-secondary"
        :lang="uploadLang"
        @click="$emit('cancelUpload')"
      >
        {{ uploadLabels.cancel }}
      </button>
      <template v-if="active()">
        <p
          v-if="pollIssue === 'retrying'"
          class="notice notice-warn"
          data-testid="poll-retrying"
          role="status"
          :lang="noticeLang"
        >
          {{ labels.retrying }}
        </p>
        <div
          v-else-if="pollIssue === 'lost'"
          class="notice notice-warn"
          data-testid="poll-lost"
          role="alert"
          :lang="noticeLang"
        >
          <span>{{ labels.lost }}</span>
          <button
            type="button"
            class="button-secondary"
            @click="$emit('retry')"
          >
            {{ labels.retry }}
          </button>
        </div>
      </template>
      <span
        v-if="job?.diagnostic"
        class="validation-error"
      >{{ job.diagnostic.code }} — {{ job.diagnostic.message }}</span>
    </template>
  </section>
</template>
