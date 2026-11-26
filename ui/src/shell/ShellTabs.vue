<script setup lang="ts">
import { nextTick } from 'vue'
import { SHELL_LABELS, SHELL_TABS, type ShellTabKey } from './labels'
import { SHELL_PANEL_ID, shellTabId } from './shell'

const props = withDefaults(defineProps<{
  badges?: Partial<Record<ShellTabKey, { text: string; tone?: 'crit' | 'accent' | 'neutral' }>>
}>(), {
  badges: () => ({}),
})

const model = defineModel<ShellTabKey>({ required: true })

const ICONS: Record<ShellTabKey, string> = {
  setup: '+',
  overview: '⊞',
  deep: '📈',
  tables: '▤',
  compare: '⇄',
  history: '↺',
  rules: '☰',
  advice: '✦',
}

const PROJECT_KEYS = new Set<ShellTabKey>(['history', 'rules'])

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
      <template
        v-for="(tab, index) in SHELL_TABS"
        :key="tab.key"
      >
        <span
          v-if="index === 1"
          class="shell-nav-group"
          aria-hidden="true"
        >Открытый прогон</span>
        <span
          v-if="PROJECT_KEYS.has(tab.key) && !PROJECT_KEYS.has(SHELL_TABS[index - 1]?.key)"
          class="shell-nav-group"
          aria-hidden="true"
        >Проект</span>
        <button
          :id="shellTabId(tab.key)"
          type="button"
          role="tab"
          class="nav-item shell-tab"
          :class="{ 'shell-tab--primary': tab.key === 'setup' }"
          :aria-selected="model === tab.key"
          :aria-controls="SHELL_PANEL_ID"
          :tabindex="model === tab.key ? 0 : -1"
          @click="model = tab.key"
          @keydown="onKeydown($event, index)"
        >
          <span
            class="shell-tab__label"
            :data-icon="ICONS[tab.key]"
          >{{ tab.label }}</span>
          <span
            v-if="props.badges[tab.key]"
            class="shell-tab__badge"
            :class="props.badges[tab.key]?.tone ? `shell-tab__badge--${props.badges[tab.key]!.tone}` : ''"
          >{{ props.badges[tab.key]!.text }}</span>
          <span
            v-else-if="tab.pending"
            class="shell-tab__badge"
          >{{ SHELL_LABELS.pendingBadge }}</span>
        </button>
      </template>
    </div>
  </nav>
</template>
