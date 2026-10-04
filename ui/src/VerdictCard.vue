<script setup lang="ts">
import type { VerdictSummary } from './verdictSummary'

defineProps<{ summary: VerdictSummary }>()
</script>

<template>
  <section
    id="verdict"
    tabindex="-1"
    class="panel verdict-strip verdict-card"
    :data-verdict="summary.verdict"
    aria-labelledby="verdict-title"
  >
    <p
      class="status-icon"
      aria-hidden="true"
    >
      {{ summary.verdict === 'PASS' ? '✓' : summary.verdict === 'FAIL' ? '×' : '!' }}
    </p>
    <div class="verdict-main">
      <p class="eyebrow">
        Вердикт политики: <strong
          class="verdict-badge"
          data-testid="verdict-badge"
        >{{ summary.verdict }}</strong>
      </p>
      <h2 id="verdict-title">
        {{ summary.headline }}
      </h2>
      <p>{{ summary.lead }}</p>

      <template v-if="summary.linesTitle">
        <h3>{{ summary.linesTitle }}</h3>
        <ul
          class="verdict-lines"
          data-testid="verdict-lines"
        >
          <li
            v-for="line in summary.lines"
            :key="line.key"
          >
            <strong>{{ line.title }}</strong>
            <span>{{ line.detail }}</span>
          </li>
        </ul>
        <p
          v-if="summary.linesHidden"
          class="muted"
        >
          Ещё {{ summary.linesHidden }} — в таблицах правил ниже.
        </p>
      </template>

      <template v-if="summary.causes.length">
        <h3>Почему вердикта нет</h3>
        <ul
          class="verdict-lines"
          data-testid="verdict-causes"
        >
          <li
            v-for="cause in summary.causes"
            :key="`${cause.code}|${cause.subjectsLabel}`"
          >
            <span>{{ cause.text }}</span>
            <span
              v-if="cause.subjects.length"
              class="muted"
            >{{ cause.subjectsLabel }}: {{ cause.subjects.join(', ') }}{{ cause.subjectsHidden ? ` и ещё ${cause.subjectsHidden}` : '' }}</span>
            <span
              v-if="cause.detail"
              class="muted"
            >{{ cause.detail }}</span>
            <span
              v-if="cause.code"
              class="muted"
            >Код: <code>{{ cause.code }}</code></span>
          </li>
        </ul>
      </template>

      <template v-if="summary.notes.length">
        <h3>{{ summary.notesTitle }}</h3>
        <ul
          class="verdict-lines"
          data-testid="verdict-notes"
        >
          <li
            v-for="note in summary.notes"
            :key="`${note.code}|${note.subjectsLabel}`"
          >
            <span>{{ note.text }}</span>
            <span
              v-if="note.code"
              class="muted"
            >Код: <code>{{ note.code }}</code></span>
          </li>
        </ul>
      </template>
    </div>
    <dl class="verdict-facts">
      <div
        v-for="fact in summary.facts"
        :key="fact.label"
      >
        <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
      </div>
    </dl>
  </section>
</template>
