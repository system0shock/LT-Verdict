<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

export interface FloatingTooltipRow {
  key: string
  label: string
  value: string
  sub?: string
  color?: string
  dashed?: boolean
  breach?: boolean
  emphasis?: boolean
}

export interface FloatingTooltipSection {
  title?: string
  rows: FloatingTooltipRow[]
}

const props = withDefaults(defineProps<{
  visible: boolean
  clientX: number
  clientY: number
  title: string
  meta?: string
  sections: FloatingTooltipSection[]
  pinned?: boolean
}>(), {
  meta: '',
  pinned: false,
})

const emit = defineEmits<{ unpin: [] }>()

const tipRef = ref<HTMLElement | null>(null)
const pos = ref({ x: 0, y: 0 })

function updatePosition() {
  if (!props.visible) return
  const el = tipRef.value
  const tw = el?.offsetWidth || 280
  const th = el?.offsetHeight || 140
  const pad = 16
  const vw = window.innerWidth
  const vh = window.innerHeight
  let tx = props.clientX + 18
  if (tx + tw + pad > vw) tx = Math.max(pad, props.clientX - tw - 18)
  const ty = Math.max(pad, Math.min(vh - th - pad, props.clientY - th * 0.35))
  pos.value = { x: Math.round(tx), y: Math.round(ty) }
}

watch(
  () => [props.visible, props.clientX, props.clientY, props.title, props.sections.length, props.pinned],
  async () => {
    if (!props.visible) return
    await nextTick()
    updatePosition()
  },
  { immediate: true },
)
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible && sections.some((section) => section.rows.length > 0)"
      ref="tipRef"
      class="chart-floating-tooltip"
      :class="{ 'chart-floating-tooltip--pinned': pinned }"
      data-testid="chart-floating-tooltip"
      :data-pinned="pinned ? 'true' : 'false'"
      :style="{ transform: `translate3d(${pos.x}px, ${pos.y}px, 0)` }"
      aria-hidden="true"
    >
      <div class="chart-floating-tooltip__head">
        <span class="chart-floating-tooltip__time">{{ title }}</span>
        <span
          v-if="meta"
          class="chart-floating-tooltip__meta"
        >{{ meta }}</span>
      </div>
      <div
        v-for="(section, sIdx) in sections"
        :key="section.title ?? sIdx"
        class="chart-floating-tooltip__section"
      >
        <div
          v-if="section.title"
          class="chart-floating-tooltip__section-title"
        >
          {{ section.title }}
        </div>
        <div class="chart-floating-tooltip__rows">
          <div
            v-for="row in section.rows"
            :key="row.key"
            class="chart-floating-tooltip__row"
            :class="{ 'chart-floating-tooltip__row--emphasis': row.emphasis }"
          >
            <span
              class="chart-floating-tooltip__swatch"
              :class="{ 'chart-floating-tooltip__swatch--dash': row.dashed }"
              :style="row.color ? { backgroundColor: row.color } : undefined"
            />
            <span class="chart-floating-tooltip__label">{{ row.label }}</span>
            <span
              class="chart-floating-tooltip__value"
              :class="{ 'chart-floating-tooltip__value--breach': row.breach }"
            >
              {{ row.value }}<span
                v-if="row.sub"
                class="chart-floating-tooltip__sub"
              >{{ row.sub }}</span>
            </span>
          </div>
        </div>
      </div>
      <div class="chart-floating-tooltip__foot">
        <span>{{ pinned ? 'Подсказка закреплена' : 'Клик по графику — закрепить' }}</span>
        <button
          v-if="pinned"
          type="button"
          class="chart-floating-tooltip__unpin"
          data-testid="chart-tooltip-unpin"
          @click.stop="emit('unpin')"
        >
          Отпустить (Esc)
        </button>
        <span
          v-else
          class="chart-floating-tooltip__hint"
        >←/→ шаг</span>
      </div>
    </div>
  </Teleport>
</template>

<style>
.chart-floating-tooltip {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 95;
  min-width: 256px;
  max-width: 380px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1.5px solid #475970;
  background: #111822;
  opacity: 1;
  color: #f8fafc;
  font-family: "Segoe UI Variable", "Segoe UI", system-ui, sans-serif;
  font-size: 12.5px;
  line-height: 1.35;
  box-shadow: 0 16px 38px rgba(0, 0, 0, 0.48), 0 3px 10px rgba(0, 0, 0, 0.28);
  pointer-events: none;
}

.chart-floating-tooltip--pinned {
  pointer-events: auto;
  border-color: #60a5fa;
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.58), 0 0 0 2px rgba(96, 165, 250, 0.45);
}

.chart-floating-tooltip__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 7px;
  margin-bottom: 7px;
  border-bottom: 1px solid #2a394a;
}

.chart-floating-tooltip__time {
  font-family: "Cascadia Mono", Consolas, monospace;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  font-size: 12.5px;
  color: #ffffff;
}

.chart-floating-tooltip__meta {
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 11px;
  color: #cbd5e1;
  background: #1e293b;
  padding: 2px 6px;
  border-radius: 4px;
  white-space: nowrap;
}

.chart-floating-tooltip__section + .chart-floating-tooltip__section {
  margin-top: 7px;
  padding-top: 6px;
  border-top: 1px solid #223040;
}

.chart-floating-tooltip__section-title {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: #94a3b8;
  margin-bottom: 4px;
}

.chart-floating-tooltip__rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chart-floating-tooltip__row {
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr) auto;
  align-items: baseline;
  gap: 7px;
}

.chart-floating-tooltip__row--emphasis {
  margin-top: 3px;
  padding-top: 4px;
  border-top: 1px dotted #334155;
}

.chart-floating-tooltip__row--emphasis .chart-floating-tooltip__label {
  color: #f8fafc;
  font-weight: 600;
}

.chart-floating-tooltip__swatch {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  align-self: center;
  background: #60a5fa;
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.22);
}

.chart-floating-tooltip__swatch--dash {
  height: 3px;
  border-radius: 1px;
  box-shadow: none;
}

.chart-floating-tooltip__label {
  color: #cbd5e1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-floating-tooltip__value {
  font-family: "Cascadia Mono", Consolas, monospace;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  color: #ffffff;
  text-align: right;
  white-space: nowrap;
}

.chart-floating-tooltip__value--breach {
  color: #ff7b72;
}

.chart-floating-tooltip__sub {
  margin-left: 5px;
  font-weight: 500;
  font-size: 11px;
  color: #94a3b8;
}

.chart-floating-tooltip__foot {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid #2a394a;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: #94a3b8;
}

.chart-floating-tooltip__hint {
  font-family: "Cascadia Mono", Consolas, monospace;
}

.chart-floating-tooltip__unpin {
  min-height: 22px;
  padding: 0 7px;
  border-radius: 4px;
  border: 1px solid #475970;
  background: #1e293b;
  color: #f8fafc;
  font-size: 11px;
  cursor: pointer;
}

.chart-floating-tooltip__unpin:hover {
  background: #334155;
}
</style>
