- Малая выборка пригодна как baseline с предупреждением (ADR 0019, срез R4,
  решение владельца 2026-10-04). `POST /api/baseline` в ручном и статистическом
  режимах принимает анализ `VALID` с вердиктом `PASS`, у которого покрытие
  `INCOMPLETE` только из-за причины `SMALL_SAMPLE` (все причины в
  `analysis_coverage.reasons` равны `SMALL_SAMPLE`); любая другая причина рядом,
  пустой или нестроковый список по-прежнему даёт `422
  BASELINE_CANDIDATE_INCOMPLETE`, а `INSUFFICIENT_SAMPLES` (`NO_VERDICT`) и
  не-`PASS` остаются отказом. В ответ `GET
  /api/runs/{runId}/analyses/{analysisId}/comparison` в массив `warnings`
  добавляется `BASELINE_SMALL_SAMPLE`, если в результате анализа-эталона среди
  причин покрытия есть `SMALL_SAMPLE`; предупреждение не блокирует сравнение и
  не меняет метрики, `comparability` и статусы. Интерфейс показывает его русским
  текстом. Формат результата, identity и ключ сопоставимости не менялись; отбор
  по блоку `verdict_gates` (ADR 0018, срез S10) в срез не входит.
