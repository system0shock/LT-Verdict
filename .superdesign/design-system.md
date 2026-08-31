# LT Verdict UI design system — Slice 1

## Product context

LT Verdict is a local-first engineering tool that imports a JMeter JTL or
Gatling log, evaluates explicit policy rules, and produces an inspectable
verdict. Slice 1 is a desktop application shell for one local operator. It is
not a marketing site and must remain useful before charts or draggable widgets
exist.

The primary screen shows one completed run. Supporting states include file and
policy selection, validation errors, queued/processing progress, a bounded-queue
BUSY notice, empty results, and a failed or indeterminate verdict.

## Visual direction

Use a technical-minimalist, grid-led composition adapted from the selected
`mosaic-grid-architecture-style`: flat rectangular regions, precise 1px
dividers, compact metadata, and deliberate negative space. Replace its landing
page typography, decorative mosaic, imagery, and forest palette with the
existing LT Verdict report cues: Segoe UI, restrained blue, dense tables, and
neutral engineering surfaces.

- No gradients, decorative imagery, glass effects, large hero type, or neon.
- No shadows. Separate regions with surface color and 1px borders.
- Corners are square or subtly rounded; never pill-shaped except compact tags.
- PASS, WARN, and FAIL are the only strong accents. Brand blue is restrained.
- Use tabular figures and explicit units for all measurements.

## Theme behavior

Both themes use the same layout, spacing, hierarchy, labels, and semantic
meaning. Default to the operating-system preference and provide a visible
`Light / Dark` toggle in the header. The design is one application view, not a
side-by-side theme comparison.

### Semantic colors

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| `canvas` | `#F4F7FA` | `#0D1218` | Application background |
| `surface` | `#FFFFFF` | `#151C24` | Main panels and tables |
| `surface-muted` | `#EAF0F6` | `#1D2833` | Headers, selected rows, secondary regions |
| `surface-inset` | `#F7FAFC` | `#10171F` | Inputs and evidence regions |
| `text` | `#18212B` | `#F1F5F8` | Primary text |
| `text-muted` | `#526170` | `#AEBAC6` | Secondary text and metadata |
| `border` | `#C5D0DB` | `#384858` | Non-interactive dividers |
| `control-border` | `#6B7A89` | `#8496A8` | Interactive control boundary |
| `border-strong` | `#8FA1B3` | `#637587` | Emphasized boundaries |
| `brand` | `#1F4E79` | `#78B7E8` | Links, selection, restrained emphasis |
| `brand-contrast` | `#FFFFFF` | `#0A1A28` | Text on brand fill |
| `focus` | `#126FD6` | `#8CC7FF` | Keyboard focus ring |
| `pass` | `#17633D` | `#6FD49C` | PASS text and icon |
| `pass-bg` | `#E7F4EC` | `#163326` | PASS surface |
| `warn` | `#805500` | `#F2C96D` | WARN text and icon |
| `warn-bg` | `#FFF4D6` | `#382D13` | WARN surface |
| `fail` | `#A4261D` | `#FF9188` | FAIL text and icon |
| `fail-bg` | `#FCEBE9` | `#3B1D1D` | FAIL surface |
| `info` | `#1F4E79` | `#78B7E8` | Processing and neutral notices |
| `info-bg` | `#E7F0F8` | `#152D40` | Processing and neutral notice surface |

Text must meet WCAG AA contrast: 4.5:1 for normal text and 3:1 for large text
and non-text controls. Never communicate verdict or validation state by color
alone; pair color with a word and a simple icon.

## Typography

- UI and headings: `"Segoe UI Variable", "Segoe UI", system-ui, sans-serif`.
- Metrics, timestamps, IDs, operators, and compact metadata:
  `"Cascadia Mono", Consolas, monospace` with tabular figures.
- Page title: 24px / 32px, weight 650.
- Section title: 16px / 24px, weight 650.
- Body and controls: 14px / 20px, weight 400–600.
- Table and metadata text: 12px / 18px; never smaller than 12px.
- Use sentence case. Uppercase is limited to short verdict and status labels.

## Spacing, shape, and motion

- Base spacing: 8px. Allowed values: 4, 8, 12, 16, 24, and 32px.
- Panel padding: 16px; page gutters: 24px; major section gap: 24px.
- Dividers: 1px solid `border`; interactive controls use 1px
  `control-border`; use `border-strong` only for active non-control boundaries.
- Radius: 2px for tables/panels, 4px for controls, 999px only for tiny tags.
- No elevation or box shadow.
- Color and border transitions only, 120ms ease-out. Disable nonessential motion
  under `prefers-reduced-motion`.

## Desktop application layout

Design at 1440px wide. Slice 1 has a stable desktop layout only.

- Left navigation: 224px wide, full height, product name at top, `Runs` active,
  `Policies` secondary.
- Header: 56px high, run identity and source metadata on the left and the theme
  control on the right. Do not render an export action in Slice 1.
- Content: remaining width, 24px padding, flat 12-column CSS Grid.
- Verdict strip spans the content width and is the first result after the header.
- Summary metrics use four equal cards in one row.
- Policy results and transactions are dense full-width tables.
- One-second normalized data is an inspectable table, not a graph.

Do not add a frontend grid, chart, icon, or drag-and-drop library for this
slice. Stable semantic widget IDs and native CSS Grid are sufficient groundwork.

## Component rules

### Navigation and header

Navigation items are 44px high with persistent text labels. The active item has
a brand-colored 2px left border plus a muted surface; it is not color-only. The
theme control contains both a theme icon and the current mode label. Critical
actions are never icon-only.

### Verdict strip

Show the verdict word, icon, short explanation, rule count, duration, sample
count, and error rate in one bordered strip. FAIL uses `fail` and `fail-bg` but
keeps body text in `text`. NO_VERDICT must be spelled out and explained.

### Metric cards

Show a persistent label, prominent tabular value, unit, and optional neutral
context. Use a 2px status edge only when the metric maps directly to a policy
result. Do not add sparklines or chart placeholders.

### Tables

Use semantic-looking headers, 40px minimum row height, aligned numeric columns,
tabular figures, and visible row hover/focus. Policy rows include transaction,
metric, operator and threshold, measured value, normalized scope, and a textual
PASS/FAIL status. Transaction rows are sorted by impact. The one-second data
table includes time bin, RPS, errors, p95, and a textual missing-data marker.

### Inputs and actions

Use a native-looking file input, labelled policy selector, regular buttons, and
inline validation text. Controls are at least 44px high and use
`control-border` against their adjacent surface. Primary actions use a brand
fill; destructive/cancel actions use a bordered neutral control unless
destruction is immediate.

### Notices and progress

Queued or processing work is a compact status row with processed bytes,
determinate progress where known, and a labelled Cancel action. BUSY is a clear
warning notice with the next action. Validation errors name the exact unknown
transaction or invalid field. Empty results require a supported log; policy is
optional, but any supplied policy must be valid.

## Required content for the first draft

Create one coherent completed-run dashboard, not a component catalog. Use a
realistic failed run with:

- run name, JTL filename metadata, local timestamp, and theme toggle;
- `FAIL — 1 of 3 rules failed`, duration, samples, and error rate;
- p50, p95, p99, throughput, and error-rate values;
- policy result rows with exact transaction names, thresholds, measurements,
  scope, and PASS/FAIL text;
- transactions ordered by impact;
- a compact 1-second normalized-data table with range/filter controls and one
  explicit missing-data row;
- one slim background processing row;
- if the approved preview retains its unknown-transaction editor error, label
  it explicitly as an unapplied policy draft, separate from the active FAIL
  checks. A missing transaction in the evaluated policy is a `NO_VERDICT`
  state, never part of a completed `FAIL` result.

Do not show response bodies, headers, raw XML, charts, sparklines, graph
placeholders, drag handles, or drag-and-drop zones.

## Accessibility and interaction

- Keyboard focus: 2px `focus` outline with 2px offset on every interactive item.
- All actions and navigation are operable by keyboard; logical focus order
  follows the visual hierarchy.
- Minimum action target is 44×44px.
- Labels remain visible after values are entered.
- Tables retain explicit column headers and status text.
- Theme changes never alter information density or status semantics.
