- Baseline принимается только из анализа `VALID`, `COMPLETE`, `PASS` в обоих
  режимах `POST /api/baseline` (ADR 0019, срез R1). Ручной выбор больше не
  принимает `FAIL`, `NO_POLICY`, `NO_VERDICT`, невалидный и неполный анализ;
  статистический режим добавил `BASELINE_CANDIDATE_NOT_PASS`. Сервер проверяет
  сохранённый результат: сверяет SHA-256 файла результата с манифестом вне
  замка хранилища и отказывает для результата больше 64 MiB (`422
  BASELINE_CANDIDATE_TOO_LARGE`); подмена результата при сохранённом размере
  даёт `500 CORRUPT_BASELINE`. Новые коды 422: `BASELINE_CANDIDATE_NOT_PASS`
  (сообщение называет фактический вердикт) и `BASELINE_CANDIDATE_TOO_LARGE`;
  текст ошибок выбора baseline теперь «Baseline candidate is unavailable»,
  а не «Statistical baseline is unavailable». Ранее сохранённый
  `baseline.json` читается без миграции; формат, identity анализа и ключ
  сопоставимости не менялись. Анализ без policy эталоном не становится:
  заново проанализируйте вход с policy. Тексты новых кодов в интерфейсе
  остаются серверными до отдельного среза.
