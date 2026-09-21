# C06 whole-report forbidden-claims audit

Архивы `.zip`, упомянутые ниже, хранятся локально и не включены в Git.
Они сохранены без изменений; исторические проверки требуют отдельного доступа
к этим evidence-артефактам. Свежий clone содержит код и отчёты, но не архивы.

## Freeze before actual-artifact inspection

- Rule source: tools/claims_audit.py
- Rule SHA-256: 4866360375f189da72f6b891c7b003c891e2f6f9f19516b57a0dff2023e57d6d
- Actual source, context regression (107 records):
  docs/statistical-validation/v1-context-regression.zip,
  SHA-256 b3c730652877ff00eee796dc9f563bf4b30859e3f916202b7ced303c24a6b33d.
- Actual source, confirmation context (2 records):
  docs/statistical-validation/v1-confirmation-context.zip,
  SHA-256 e08e4702e13f349e1f82ab2084b9518855a16cd747e5237b2d381f852c7b748a.

Проверяется raw actual.jsonl production runner: целиком output, включая
result, identity, evidence, findings, diagnostic_summary,
correlation_pairs и raw baseline comparison. Это соответствует структурам
AnalysisResult, DiagnosticAnalysis и BaselineComparison.

Зафиксированные правила C06:

1. Структурные поля p_value, pvalue, p-value запрещены, если не null.
2. confidence: "HIGH" и affirmative prose HIGH confidence запрещены.
3. Affirmative prose о causal proof/established causality/causes запрещён.
4. Affirmative healthy запрещён.
5. Явная локальная отрицательная форма (no, not, without, never,
   neither) разрешена: например, No p-values и not a causal proof.
6. Значения ключей ID/metric и всё под truth_metadata не являются prose
   claims и не сканируются.

Malformed JSONL, пустой input, blank line, duplicate ID, record с error,
отсутствующий output или non-finite JSON завершают audit fail-closed. Статус
PASS означает только нулевые findings данного конечного lexical audit; он не
означает APPLICABILITY, USEFULNESS или общий acceptance PASS.

## Actual-artifact result

Frozen rule was executed unchanged against both archived raw JSONL streams:

| Artifact | Records | Findings | Result |
| --- | ---: | ---: | --- |
| context regression | 107 | 0 | PASS |
| confirmation context | 2 | 0 | PASS |
| total | 109 | 0 | PASS |

Command (native byte pipe preserves JSONL from the ZIP member):

    cmd /c "tar -xOf docs\statistical-validation\v1-context-regression.zip actual.jsonl | python tools\claims_audit.py --input -"
    cmd /c "tar -xOf docs\statistical-validation\v1-confirmation-context.zip actual.jsonl | python tools\claims_audit.py --input -"

Ни один из 109 outputs не содержит запрещённого claim по зафиксированным
правилам. Это не меняет общий статус исследования: APPLICABILITY и USEFULNESS
остаются вне объёма данного audit.

## Revision 2 freeze before re-audit

Первый raw result выше сохранён без изменения. Review обнаружил четыре
fail-closed пробела в первоначальном parser rule: scalar/null output,
overflow JSON number, structured causal_proof/causality и перезапись
output report. До повторного доступа к actual зафиксирована revision 2:

- Rule source: tools/claims_audit.py
- Rule SHA-256: e01f1ea0a85338870e8e2553518101b24b3ffef5c57a1fa18529ccb80d921954
- Те же неизменённые source ZIP и SHA-256, перечисленные в первом freeze.

Новые exact rules: output обязан быть JSON object; любой non-finite float
после parsing отвергается; causal_proof: true и causality со значением
PROVEN, ESTABLISHED или CONFIRMED запрещены. CLI создаёт --output только
exclusive mode x; существующий audit artifact не перезаписывается.

## Revision 2 actual-artifact result

Revision 2 frozen rule was executed against the preserved same 109 raw outputs:

| Artifact | Records | Findings | Result |
| --- | ---: | ---: | --- |
| context regression | 107 | 0 | PASS |
| confirmation context | 2 | 0 | PASS |
| total | 109 | 0 | PASS |

Таким образом revised fail-closed rule также не находит forbidden claims в
этих artifacts. Как и первый результат, это не общий acceptance PASS.

## Scope limit

Это конечный lexical scan, а не NLP: он не доказывает отсутствие семантически
эквивалентной формулировки вне зафиксированных English patterns. Новые
пользовательские prose-поля или иные языки требуют отдельного review и
дополнения rule freeze до следующего запуска.
