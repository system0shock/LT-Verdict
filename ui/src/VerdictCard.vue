<script setup lang="ts">
import type { VerdictSummary } from './verdictSummary'

defineProps<{ summary: VerdictSummary }>()

const badgeSubtext: Record<VerdictSummary['verdict'], string> = {
  FAIL: 'пороги нарушены',
  PASS: 'пороги в норме',
  NO_VERDICT: 'не оценено',
  NO_POLICY: 'без политики',
}
</script>

<template>
  <section
    id="verdict"
    tabindex="-1"
    class="panel verdict-strip verdict-card"
    :data-verdict="summary.verdict"
    aria-labelledby="verdict-title"
  >
    <div class="vcard-top">
      <div class="vcard-lead">
        <div
          class="vbadge"
          :data-verdict="summary.verdict"
        >
          <strong
            class="verdict-badge"
            data-testid="verdict-badge"
          >{{ summary.verdict }}</strong>
          <span>{{ badgeSubtext[summary.verdict] }}</span>
        </div>
        <div class="vcard-copy">
          <h2 id="verdict-title">
            {{ summary.headline }}
          </h2>
          <p class="v4-sub">
            {{ summary.lead }}
          </p>
          <dl
            v-if="summary.facts.length"
            class="vcard-passport verdict-facts"
          >
            <div
              v-for="fact in summary.facts"
              :key="fact.label"
            >
              <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
            </div>
          </dl>
        </div>
      </div>
    </div>

    <div
      v-if="summary.kpis.length"
      class="verdict-kpis"
      data-testid="verdict-kpis"
      role="region"
      aria-label="Ключевые пороги правил"
    >
      <div
        v-for="kpi in summary.kpis"
        :key="kpi.key"
        class="verdict-kpi"
        data-testid="verdict-kpi"
        :data-status="kpi.status"
      >
        <div class="verdict-kpi__top">
          <span
            class="verdict-kpi__label"
            :title="kpi.label"
          >{{ kpi.label }}</span>
          <span
            class="v4-pill"
            :class="kpi.status === 'FAIL' ? 'v4-pill--crit' : kpi.status === 'PASS' ? 'v4-pill--good' : 'v4-pill--warn'"
          >{{ kpi.status === 'FAIL' ? 'FAIL' : kpi.status === 'PASS' ? 'OK' : 'N/A' }}</span>
        </div>
        <strong class="verdict-kpi__value">{{ kpi.observed }}</strong>
        <span class="verdict-kpi__threshold">порог {{ kpi.threshold }}</span>
      </div>
    </div>

    <div
      v-if="summary.linesTitle || summary.causes.length || summary.stages || summary.notes.length"
      class="vcard-extra"
    >
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

      <section
        v-if="summary.stages"
        data-testid="verdict-stages"
        aria-labelledby="verdict-stages-title"
      >
        <h3 id="verdict-stages-title">
          {{ summary.stages.title }}
        </h3>
        <div
          class="v4-table-wrap"
          tabindex="0"
          role="region"
          aria-label="Стадии нагрузки"
        >
          <table>
            <thead>
              <tr>
                <th
                  v-for="head in summary.stages.heads"
                  :key="head"
                  scope="col"
                >
                  {{ head }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in summary.stages.rows"
                :key="row[0]"
              >
                <td
                  v-for="(cell, index) in row"
                  :key="index"
                >
                  {{ cell }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p data-testid="verdict-stages-totals">
          {{ summary.stages.totals }}
        </p>
        <p class="muted">
          {{ summary.stages.note }}
        </p>
      </section>

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
  </section>
</template>
