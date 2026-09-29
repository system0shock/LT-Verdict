# Advisory AI production readiness — scoped implementation plan

Дата: 2026-09-22. Исполняет AI-трек общего плана
`2026-09-21-mvp-acceptance-readiness.md`; commit/staging и внешние model calls
не входят в работу.

## Граница

Довести существующий `AdvisoryAiService` до локально запускаемой функции через
уже проверенную ModelStudio/Qwen topology: pinned container image, внутреннюю
Docker network для Qwen и единственный guarded relay с внешней сетью.

Не входят provider registry, retries, изменение deterministic analysis,
повторная semantic acceptance и shared API/UI files.

## Зафиксированные решения

- Qwen Code: `0.21.1`, CLI SHA-256
  `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38`.
- Runtime image:
  `mcr.microsoft.com/playwright/mcp@sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2`.
- ModelStudio model: `deepseek-v4-flash-0731`; endpoint:
  `https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions`.
- Qwen видит только internal relay. Credential передаётся только relay через
  файл из `LT_VERDICT_AI_CREDENTIAL_ENV_FILE`; значение не попадает в argv,
  Kotlin result, stdout/stderr или advice provenance.
- Qwen/provider/host deadlines: `600s`/`605s`/`613s`; retries: `0`.
- Evidence/output/stderr/provider-response limits:
  `262144`/`131072`/`16384`/`67108864` bytes.
- Production dependencies не добавляются. Отсутствующие Docker/image/package,
  credential или hash match дают `UNAVAILABLE`; timeout/process/overflow дают
  bounded `FAILED` и не меняют analysis/advice.

## Публичные контракты

- `ai-advice.v1.provenance.model_id` фиксируется как
  `deepseek-v4-flash-0731`; `duration_ms.maximum` становится `613000`.
- `AdvisoryAiService.generate/read` сохраняются.
- Добавляется `AdvisoryAiJobs`: `submit`, `status`, `cancel`, `close`;
  состояния `QUEUED`, `PROCESSING`, `COMPLETE`, `FAILED`, `UNAVAILABLE`,
  `CANCELLED`.
- Добавляется `ModelStudioAdvisoryRunner.fromEnvironment()`. Единственная
  секретная настройка — путь `LT_VERDICT_AI_CREDENTIAL_ENV_FILE`.

## RED–GREEN и проверки

1. RED: manifest больше bounded limit отклоняется до `readAllBytes`; затем
   минимальный size guard.
2. RED: jobs публикуют terminal result и cancellation; затем минимальный
   однопоточный bounded executor по существующему `AnalysisJobs` pattern.
3. RED: production runner отображает unavailable/timeout/failure/success,
   удаляет временные файлы и не передаёт credential value; затем Kotlin runner.
4. Node/PowerShell self-check запускает relay/launcher только с fake endpoint;
   live ModelStudio request запрещён в этой работе.
5. Root запускает:
   `gradlew.bat test --tests io.ltverdict.ai.AdvisoryAiTest`; затем общий Gradle
   regression. AI-агент запускает только isolated Node/PowerShell checks.

## Ожидаемые файлы

`src/main/kotlin/io/ltverdict/ai/**`, `src/test/kotlin/io/ltverdict/ai/**`,
`docs/contracts/advice/v1/**`, этот план, AI runtime documentation и новые
`tools/advisory_ai_runtime*`. Shared API/UI/build/README/CHANGELOG принадлежат
root-интеграции.
