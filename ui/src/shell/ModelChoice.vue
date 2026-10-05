<script setup lang="ts">
import { computed, useId } from 'vue'
import type { AdvisoryAiConfig } from '../types'
import { chosenModel, modelCaption, modelOptions } from './advice'
import { ADVICE_LABELS } from './labels.advice'

// Выбор модели ИИ-разбора (ADR 0023). Один компонент на вкладку «ИИ-разбор» и экран «Новый анализ»:
// обе панели лежат в DOM одновременно, поэтому id у каждого экземпляра свой.
const props = defineProps<{ config: AdvisoryAiConfig | null | undefined; selected: string; disabled?: boolean }>()
const emit = defineEmits<{ select: [id: string] }>()
const selectId = useId()
const current = computed(() => (props.config ? chosenModel(props.config, props.selected) : null))
const options = computed(() => (props.config ? modelOptions(props.config) : []))
</script>

<template>
  <div
    v-if="config && current"
    class="field model-choice"
    data-testid="model-choice"
  >
    <template v-if="config.models.length > 1">
      <label :for="selectId">{{ ADVICE_LABELS.modelChoice.label }}</label>
      <select
        :id="selectId"
        :value="current.id"
        :disabled="disabled"
        @change="emit('select', ($event.target as HTMLSelectElement).value)"
      >
        <option
          v-for="option in options"
          :key="option.id"
          :value="option.id"
        >
          {{ option.text }}
        </option>
      </select>
    </template>
    <p
      v-else
      data-testid="model-caption"
    >
      {{ modelCaption(current) }}
    </p>
    <p
      v-if="!current.measured"
      class="field__hint"
      data-testid="model-unmeasured"
    >
      {{ ADVICE_LABELS.modelChoice.unmeasuredNote }}
    </p>
  </div>
</template>
