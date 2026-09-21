<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { grafanaPanel, listGrafana } from './api'
import type { AnalysisReference } from './types'

const props = defineProps<{ selection: AnalysisReference }>()
const profiles = ref<Array<{ id: string; base_url: string }>>([])
const profile = ref('')
const dashboard = ref('')
const panel = ref(1)
const busy = ref(false)
const error = ref('')
const link = ref('')
const png = ref('')
let revision = 0
watch(() => [props.selection.run_id, props.selection.analysis_id, profile.value, dashboard.value, panel.value], () => {
  revision++
  link.value = ''; png.value = ''; error.value = ''; busy.value = false
})
onMounted(async () => {
  try { profiles.value = (await listGrafana()).profiles; profile.value = profiles.value[0]?.id ?? '' }
  catch { error.value = 'Grafana configuration is unavailable.' }
})
async function load(render: boolean) {
  const current = ++revision
  busy.value = true; error.value = ''; png.value = ''
  try {
    const result = await grafanaPanel(props.selection, profile.value, dashboard.value.trim(), panel.value, render)
    if (current !== revision) return
    link.value = result.source_link
    png.value = result.png_base64 ? `data:image/png;base64,${result.png_base64}` : ''
    if (result.failure_code) error.value = `Render unavailable: ${result.failure_code}. The source link remains available.`
  } catch (failure) {
    if (current === revision) error.value = failure instanceof Error ? failure.message : 'Grafana request failed.'
  } finally { if (current === revision) busy.value = false }
}
</script>

<template>
  <details
    v-if="profiles.length || error"
    class="panel"
  >
    <summary>Grafana evidence</summary>
    <p>The panel uses the saved run time range. Rendering requests the configured Grafana server.</p>
    <form @submit.prevent="load(false)">
      <label>Grafana profile <select
        v-model="profile"
        :disabled="busy"
      ><option
        v-for="item in profiles"
        :key="item.id"
        :value="item.id"
      >{{ item.id }}</option></select></label>
      <label>Dashboard UID <input
        v-model="dashboard"
        required
        maxlength="128"
        pattern="[A-Za-z0-9_-]+"
        :disabled="busy"
      ></label>
      <label>Panel ID <input
        v-model.number="panel"
        required
        type="number"
        min="1"
        max="2147483647"
        :disabled="busy"
      ></label>
      <button :disabled="busy || !profile">
        Prepare link
      </button>
      <button
        type="button"
        :disabled="busy || !link"
        @click="load(true)"
      >
        Render PNG
      </button>
    </form>
    <p
      v-if="busy"
      role="status"
    >
      Loading Grafana evidence…
    </p>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <a
      v-if="link"
      :href="link"
      target="_blank"
      rel="noopener noreferrer"
    >Open source panel</a>
    <img
      v-if="png"
      :src="png"
      alt="Grafana panel for the selected run"
      class="grafana-image"
    >
  </details>
</template>

<style scoped>
.grafana-image { display: block; max-width: 100%; }
</style>
