# ADR 0001 — публичные контракты Slice 0

**Дата:** 2026-08-30

**Статус:** принят

## Контекст

Slice 0 публикует два versioned-контракта до появления runtime:
[`run.v1`](../contracts/run/v1/run.schema.json) описывает идентичность запуска
и входы, а
[`analysis-result.v1`](../contracts/result/v1/analysis-result.schema.json) —
независимые validity, verdict, coverage, findings и evidence. Их версия и
правила эволюции должны быть однозначны для будущих producers и consumers.

## Решение

- Контракты остаются раздельными JSON Schema Draft 2020-12 с неизменяемыми
  `$id` и `schema_version` внутри major-версии.
- Неизвестная `schema_version` обрабатывается fail-closed.
- Consumers обязаны проверять `format: date-time` и LT Verdict RFC 3339
  profile: timezone обязательна, leap seconds не поддерживаются. Проверка
  выполняется через schema `pattern` вместе с Format Assertion либо
  эквивалентным application-level parser; отсутствие такой поддержки является
  ошибкой конфигурации, а не разрешением принять значение.
- После merge набор документов, принимаемых `v1`, не меняется. Внутри `v1`
  допустимы только правки annotations/examples и checks, не меняющие
  validation semantics.
- Добавление, удаление или переименование поля, изменение required/enum,
  validation или семантики требует новой major-версии рядом с `v1`.
- Изменение schema сопровождается embedded example, verifier/regression checks
  и записью в `CHANGELOG.md`; значимое изменение решения получает новый ADR.

Решение реализуется по
[`local-first MVP delta design`](../superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md)
и
[`плану Slice 0`](../superpowers/plans/2026-08-26-v06-slice-0-contracts-evidence.md).

## Альтернативы

- Отложить contracts до Slice 1 — отклонено: runtime получил бы нестабильный
  вход без заранее проверяемой границы.
- Перезаписывать unversioned schema — отклонено: невозможно доказать
  совместимость сохранённых RunBundle.
- Объединить run и result — отклонено: input identity и analysis result имеют
  разные жизненные циклы.

## Последствия

- После merge `v1` становится публичной совместимой границей; breaking change
  публикуется отдельной major-версией.
- Slice 0 verifier остаётся намеренно частичным offline gate, а полная runtime
  validation принадлежит использующему контракт slice. Verifier проверяет
  timestamps embedded example по LT Verdict RFC 3339 profile стандартной
  библиотекой.
