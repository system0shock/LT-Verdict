# Baseline conditions confirmation design

**Статус:** согласовано для реализации 2026-09-06.

## Цель и критерии приёмки

Пользователь явно сохраняет `CONFIRMED`, `NOT_CONFIRMED` или `UNKNOWN` для
конкретной manual baseline/current пары. Решение переживает reload/reselect,
учитывает оба window id при window comparison и никогда не применяется к
другой паре. Comparison numbers, SLA и deterministic verdict не меняются.

## Contract

```text
Reference = {run_id:string, analysis_id:string}
Windows = null | {baseline_window:string, current_window:string}
ConditionRecord = {
  schema_version:"local-baseline-conditions.v1",
  baseline:Reference,
  current:Reference,
  windows:Windows,
  decision:"CONFIRMED"|"NOT_CONFIRMED"|"UNKNOWN",
  provenance:"EXPLICIT_LOCAL_ACTION",
  updated_at:<canonical java.time.Instant string>
}

GET  /api/runs/{runId}/analyses/{analysisId}/baseline-conditions
POST /api/runs/{runId}/analyses/{analysisId}/baseline-conditions
POST body = {decision:ConditionRecord.decision}
response = {conditions:ConditionRecord|null}
```

Оба window query parameter передаются вместе или отсутствуют. Thresholds не
являются частью подтверждаемых условий и не входят в binding. GET comparison
добавляет `conditions: ConditionRecord|null`; active statistical baseline
condition routes отклоняет и продолжает использовать только ADR 0004 default.

## Хранение и поток

`BaselineComparison.kt` канонизирует binding/record, валидирует exact fields и
преобразует decision в nullable Boolean. `RunBundleStore` вычисляет
`sha256(canonicalJson(binding))`, атомарно пишет один record в
`baseline-conditions/<sha256>.json` и при чтении повторно проверяет canonical
record и совпадение binding. Ни HTTP path, ни пользовательское поле не могут
задавать filesystem path.

`LocalApi` получает active manual baseline, проверяет обе immutable analysis
references, создаёт `updated_at = Instant.now()` и сохраняет record. Comparison
делает exact lookup и передаёт только его nullable decision. Замена baseline
сохраняет старые exact-bound records; явный clear baseline удаляет каталог.

Vue panel показывает нативную radio group и отдельное действие `Save condition
decision`. Смена current analysis или window binding немедленно скрывает
старую comparison и загружает только запись нового binding. UI показывает,
сохранён ли `UNKNOWN`, поэтому default формы не выдаётся за persisted action.
Существующие CSS tokens, typography и layout сохраняются; новый visual system
не нужен.

## Validation и ошибки

- POST/GET наследуют Host/session/Origin/CSRF boundary; GET не мутирует.
- POST exact body, 16 KiB, depth 8, UTF-8; unknown fields/decision дают 400.
- Invalid/duplicate query, id или неполная window pair дают 400; missing
  analysis/baseline — 404; statistical active baseline — 422.
- Window id содержит 1–128 UTF-8 bytes без control characters.
- Stored record <=4 KiB, canonical, regular file; unsafe/corrupt state явно
  возвращается как existing `CORRUPT_BASELINE` boundary.

## Вне scope

Нет auth identity, audit ledger, multi-user state, formulas/SLA changes,
automatic condition inference, Jenkins, CLI, global result schema или новых
production dependencies.
