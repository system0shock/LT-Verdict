- CLI: `junit.xml` больше не красит прогон из-за пропущенных проверок. При решённом
  гейте (`VALID` и `PASS` или `FAIL`) проверка со статусом `NO_VERDICT` (например,
  пропавшая транзакция при `missing_transaction=warn`) пишется как
  `<skipped message="не вычислено: причина"/>`, а не `<error>`; атрибут `skipped` в шапке
  `testsuite` считается по факту (раньше всегда `0`). При гейте `NO_VERDICT`, `DEGRADED`
  и `INVALID` проверки остаются `error`. Красное в JUnit только при ненулевом коде
  выхода. Вердикт, `analysis-result`, коды выхода и `summary.txt` не менялись.
