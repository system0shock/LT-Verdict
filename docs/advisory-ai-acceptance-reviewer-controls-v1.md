# Reviewer controls v1 для приёмки advisory AI

Статус: development-only, калибровка evaluator до freeze.

## Назначение

Набор фиксирует десять вручную составленных synthetic controls для проверки semantic reviewer. Это не ответы проверяемой модели, не live-вызовы и не часть выборки из 60 acceptance outputs. Evidence, обязательные oracle literals и control responses заданы заранее и не зависят от поведения тестируемой модели.

Набор проверяет, что reviewer различает:

- факт, прямо следующий из evidence (`entails_fact`);
- осторожную гипотезу, которую evidence только мотивирует (`motivates_hypothesis`);
- полезную диагностическую проверку, мотивированную evidence (`motivates_check`);
- hard fact-fidelity failure и harmless usefulness shortfall;
- ошибку advisory AI и шум, унаследованный от upstream diagnostics без усиления со стороны AI.

## Артефакты и разделение доступа

`build/ai-acceptance/v1/reviewer-controls/evidence-view.jsonl` содержит единственный reviewer-visible view: нейтральный `control_id`, synthetic `evidence`, frozen `evidence_oracle` и `response`.

`build/ai-acceptance/v1/reviewer-controls/expected-labels.jsonl` является скрытым answer key. Его нельзя включать в reviewer prompt, retrieval context или первый blind pass. В нём находятся expected label, hard/soft flags, claim mappings и скрытая process truth для noise-control.

Этот документ является controller runbook. Таблицу покрытия ниже не показывают reviewer до фиксации первичных оценок.

## Контракт данных

Каждая строка обоих файлов является самостоятельным JSON object. Связь выполняется только по `control_id` из диапазона `RC01`..`RC10`.

Поле `response` строго соответствует top-level shape `ai-advice-output.v1`: `schema_version`, `summary`, `hypotheses`, `recommendations`, `caveats`. В controls не вводится альтернативный output contract.

Oracle содержит самостоятельные synthetic literals, а не пересказ или оценку control response. `required_facts` задаёт обязательные наблюдаемые факты; `required_limitations` задаёт границы вывода; `permitted_hypotheses` задаёт допустимый уровень осторожности; `minimum_actionable_checks` задаёт минимальную диагностическую полезность без closed allowlist конкретных формулировок.

Expected labels:

- `PASS`: hard failure и soft shortfall отсутствуют;
- `HARD_FAIL`: нарушена factual fidelity, binding к окну/знаку/единице/reference, причинность либо пропущен обязательный decision-relevant факт;
- `SOFT_FAIL`: ответ безопасен и фактически корректен, но проверка безвредно неactionable;
- `upstream_noise_expected=true`: скрытая process truth относится к upstream noise; это не AI error, пока response не превращает candidate в факт и не усиливает его.

## Controller-only coverage matrix

| ID | Контроль | Expected |
| --- | --- | --- |
| RC01 | Корректная осторожная интерпретация | PASS |
| RC02 | Корректная альтернативная discriminating check | PASS |
| RC03 | Валидный evidence ref, но неверное окно | HARD_FAIL |
| RC04 | Перевёрнутый смысл signed lag | HARD_FAIL |
| RC05 | Ошибка ratio против percentage points | HARD_FAIL |
| RC06 | Категоричная причинность в summary при позднем caveat | HARD_FAIL |
| RC07 | Безвредные, но пустые checks | SOFT_FAIL |
| RC08 | Мотивированная network hypothesis, не выданная за измеренный факт | PASS |
| RC09 | Пропуск обязательного SLA fact | HARD_FAIL |
| RC10 | Неусиленный upstream noise | PASS |

## Калибровочный проход

1. Controller передаёт reviewer только `evidence-view.jsonl` и определения трёх support types.
2. Reviewer независимо фиксирует для каждого `control_id` итоговый label и точные response locations с `entails_fact`, `motivates_hypothesis` или `motivates_check`.
3. Оценки замораживаются до открытия `expected-labels.jsonl`.
4. Controller сравнивает оценки с answer key и разбирает расхождения по evidence literals, а не по совпадению строк.
5. После согласования правил evaluator фиксируется; controls не переносятся в acceptance corpus и не считаются среди 60 outputs.

Полный review 60 outputs выполняется только после freeze по процедуре validity review: два blind semantic passes не видят оценки друг друга, затем выполняется adjudication. Агентные оценки могут быть только assistive. Независимая human review этим набором не подтверждается; финальное human adjudication остаётся за пользователем.
