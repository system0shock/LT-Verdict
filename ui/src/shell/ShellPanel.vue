<script setup lang="ts">
import type { ShellTabKey } from './labels'
import { SHELL_PANEL_ID, shellTabId } from './shell'

defineProps<{ activeTab: ShellTabKey }>()
</script>

<template>
  <div
    :id="SHELL_PANEL_ID"
    class="shell-panel"
    role="tabpanel"
    :aria-labelledby="shellTabId(activeTab)"
  >
    <slot />
  </div>
</template>
