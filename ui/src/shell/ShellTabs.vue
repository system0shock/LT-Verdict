<script setup lang="ts">
import { nextTick } from 'vue'
import { SHELL_LABELS, SHELL_TABS, type ShellTabKey } from './labels'
import { SHELL_PANEL_ID, shellTabId } from './shell'

const model = defineModel<ShellTabKey>({ required: true })

async function moveTo(index: number) {
  const count = SHELL_TABS.length
  const tab = SHELL_TABS[((index % count) + count) % count]
  model.value = tab.key
  await nextTick()
  document.getElementById(shellTabId(tab.key))?.focus()
}

function onKeydown(event: KeyboardEvent, index: number) {
  const target: Record<string, number> = {
    ArrowDown: index + 1,
    ArrowRight: index + 1,
    ArrowUp: index - 1,
    ArrowLeft: index - 1,
    Home: 0,
    End: SHELL_TABS.length - 1,
  }
  if (!(event.key in target)) return
  event.preventDefault()
  void moveTo(target[event.key])
}
</script>

<template>
  <nav
    class="shell-tabs"
    lang="ru"
    :aria-label="SHELL_LABELS.navLabel"
  >
    <div
      role="tablist"
      aria-orientation="vertical"
      :aria-label="SHELL_LABELS.navLabel"
    >
      <button
        v-for="(tab, index) in SHELL_TABS"
        :id="shellTabId(tab.key)"
        :key="tab.key"
        type="button"
        role="tab"
        class="nav-item shell-tab"
        :aria-selected="model === tab.key"
        :aria-controls="SHELL_PANEL_ID"
        :tabindex="model === tab.key ? 0 : -1"
        @click="model = tab.key"
        @keydown="onKeydown($event, index)"
      >
        <span>{{ tab.label }}</span>
        <span
          v-if="tab.pending"
          class="shell-tab__badge"
        >{{ SHELL_LABELS.pendingBadge }}</span>
      </button>
    </div>
  </nav>
</template>
