<script setup lang="ts">
import type { ShellTabKey } from './labels'
import { SHELL_PANEL_ID, shellTabId } from './shell'

defineProps<{ enabled: boolean; activeTab: ShellTabKey }>()
</script>

<template>
  <div
    v-if="enabled"
    :id="SHELL_PANEL_ID"
    class="shell-panel"
    role="tabpanel"
    :aria-labelledby="shellTabId(activeTab)"
  >
    <slot />
  </div>
  <slot v-else />
</template>
