# Advisory AI: локальный runtime

Advisory AI запускается только по явному запросу пользователя для уже
сохранённого deterministic analysis. Он создаёт отдельный advice artifact и не
меняет verdict или файлы analysis. Для live-запуска нужны Docker Desktop,
локально доступный pinned image, Qwen Code 0.21.1 и ModelStudio credential.

## Что и куда отправляется

Продукт **не запрашивает, не проверяет и не хранит согласие** на отправку данных
анализа в ИИ: ни в интерфейсе, ни в API. Это решение владельца продукта вместе с
принятым им риском (ADR [0023](../adr/0023-advisory-ai-consent-removal-and-model-config.md)).
Совет по-прежнему создаётся только явным действием пользователя: кнопка
«Получить рекомендации» на вкладке «ИИ-разбор» или переключатель «Запросить
ИИ-разбор после анализа» на экране «Новый анализ» (по умолчанию выключен;
переключатель выражает намерение запустить разбор, а не разрешение).

- **Что уходит.** Evidence анализа (проверенные поля `analysis-result.json` по
  allowlist: метки и пути групп транзакций, метки ресурсов и сервисов,
  идентификаторы правил политики, числовые метрики и статусы, причины вердикта) и
  системный prompt. Credential, профили, сырые SQL и log-тела и файлы репозитория
  не уходят. Секреты в свободных метках редактируются по набору шаблонов (см.
  «Изоляция и ограничения»); секрет, не подошедший под шаблоны, дойдёт до
  endpoint. Имена транзакций и сервисов остаются в evidence и могут быть
  чувствительными для организации.
- **Куда.** На endpoint, который использует runner. Сегодня runner один, и его
  endpoint закреплён: ModelStudio (Alibaba, Singapore), модель
  `deepseek-v4-flash-0731`. Это внешний сервис, данные анализа за пределы
  вашей сети уходят. Файл конфигурации моделей и смена endpoint описаны в
  ADR 0023 как следующие срезы и пока не реализованы. Документация не утверждает,
  что данные остаются внутри периметра: когда endpoint станет настраиваемым,
  продукт не будет отличать внутренний от внешнего, а адрес будет задавать
  развёртывание.
- **Необратимость.** Совет неизменяем: один совет на анализ, отправка evidence не
  отзывается.
- **Явный запуск без ответа.** Если ИИ не настроен или занят, анализ и его
  вердикт не меняются: ошибка видна на вкладке «ИИ-разбор» (см. «Fail-soft
  состояния»).

### Запрос API

`POST /api/runs/{run}/analyses/{analysis}/advice` принимает пустое тело, `{}` или
`{"confirm_external_transfer":true}`. Поле `confirm_external_transfer` **устарело
и игнорируется**: оно оставлено, чтобы клиенты, всегда присылавшие `true`
(скрипты и интерфейс до этого изменения), не сломались; интерфейс теперь шлёт
`{}`. Ответ `400 MALFORMED_REQUEST` получают тела с
`{"confirm_external_transfer":false}` (клиент, явно отказавшийся от отправки, не
получает отправку молча), с любыми другими ключами, с повторяющимися ключами, не
объект и тела больше 512 байт (раньше предел был 256 байт и давал 413).
Остальные ответы (`202`, `409 AI_BUSY`, `503 AI_UNAVAILABLE`) не менялись.

## Runtime prerequisites

Runtime принимает только следующие закреплённые artifacts:

- image
  `mcr.microsoft.com/playwright/mcp@sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2`;
- npm package `@qwen-code/qwen-code@0.21.1` с SHA-256 файла
  `cli-entry.js`
  `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38`;
- endpoint
  `https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions`;
- model `deepseek-v4-flash-0731`.

Runtime ничего не скачивает. Image нужно заранее получить и оставить в Docker
image store:

```powershell
docker pull mcr.microsoft.com/playwright/mcp@sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2
```

Qwen package можно установить в отдельный каталог, например:

```powershell
npm install --prefix C:\lt-verdict-runtime\qwen-code-0.21.1 --ignore-scripts --omit=optional @qwen-code/qwen-code@0.21.1
```

После установки значением `LT_VERDICT_AI_QWEN_ROOT` должен быть каталог
`C:\lt-verdict-runtime\qwen-code-0.21.1\node_modules\@qwen-code\qwen-code`.
Launcher сам проверяет version и SHA-256 entrypoint; другое содержимое даёт
`RUNNER_ARTIFACT_MISMATCH` без model request.

## Credential и environment

Создайте вне repository и data directory файл размером не более 8192 bytes с
ровно одной строкой:

```text
OPENAI_API_KEY=<ModelStudio token>
```

Укажите абсолютные пути перед запуском LT Verdict:

```powershell
$env:LT_VERDICT_AI_CREDENTIAL_ENV_FILE = 'C:\lt-verdict-secrets\modelstudio.env'
$env:LT_VERDICT_AI_QWEN_ROOT = 'C:\lt-verdict-runtime\qwen-code-0.21.1\node_modules\@qwen-code\qwen-code'
```

Из установленного application distribution runtime root определяется по
расположению JAR: ожидаются `tools/advisory_ai_runtime.ps1` и
`docs/contracts/advice/v1/system-prompt.md` рядом с каталогом `lib`.
`LT_VERDICT_AI_RUNTIME_ROOT` нужен только для нестандартной раскладки или
запуска unpacked artifacts:

```powershell
$env:LT_VERDICT_AI_RUNTIME_ROOT = 'C:\path\to\lt-verdict-distribution'
```

Credential path передаётся host launcher. Значение credential получает только
relay container через Docker `--env-file`; оно не передаётся Qwen container,
argv, stdout/stderr, advice или provenance. Не помещайте env-file внутрь
repository, distribution или data directory.

## Изоляция и ограничения

Qwen container работает с read-only root, без capabilities и host ports. Ему
read-only доступны только package, evidence, prompt, schema и launcher. User
home, repository и Docker socket не mounted. Qwen подключён только к internal
Docker network и обращается к relay по `http://modelstudio-relay:18080/v1`.
Relay пересылает фиксированному ModelStudio endpoint запросы с фиксированной
model, удаляет token-cap и provider-routing fields и принимает только один
`structured_output` call за запрос. Один совет использует не более двух
запросов к провайдеру: второй (повтор) relay пересылает только если первый ответ
нарушил схему на верхнем уровне (`schema_version`, `summary`, `hypotheses`,
`recommendations`, `caveats`), повтор пришёл в пределах 300 s от начала первого
запроса и является продолжением первого, а любой третий запрос получает отказ.
Ошибка схемы глубже верхнего уровня, ошибка провайдера или транспорта повтор не
запускают. Если повтор не дал валидного ответа, анализ получает состояние
`FAILED/INVALID_OUTPUT`. Evidence уходит провайдеру максимум дважды за один совет.

Перед отправкой evidence очищается от секретов. Поля с именами `password`,
`token`, `api_key`, `cookie` и подобными заменяются целиком. В свободном тексте
(label транзакции или sampler, URL, сообщения) маскируется только значение:
пары `key=value` и `key: value` с ключами `password`, `passwd`, `pwd`, `secret`,
`token`, `api_key`, `authorization`, `cookie`, `session` (в том числе параметры
query-string, например `access_token`, `JSESSIONID`), `Bearer`-значения, пароль
в `https://user:pass@host`, JWT и длинные base64-подобные токены. Значение
становится `[REDACTED]`, остальной label сохраняется: `login password=hunter2`
превращается в `login password=[REDACTED]`. Маскирование эвристическое и не
ловит секрет без ключа в тексте (например `/reset/hunter2`), короткий токен
в path, ключи не из списка и значения, разбитые между полями; не помещайте
секреты в labels тестов. Результат детерминирован, поэтому `evidence_input_sha256`
не зависит от самого значения секрета. Ранее сохранённый advice, чей evidence
содержал такие строки, после обновления не проходит проверку
совпадения evidence и читается как `CORRUPT_AI_ADVICE`.

Лимиты: evidence `262144` bytes, advice `131072` bytes, stderr `16384` bytes,
provider response `67108864` bytes. Qwen timeout — `600s`, container deadline —
`605s`, host launcher deadline — `613s`, Kotlin outer timeout — `620s`.
После успеха, ошибки, timeout или cancellation launcher ограниченно по времени
пытается удалить процессы, containers, network и host temporary files по
уникальным именам. Если Docker daemon недоступен или удаление нельзя подтвердить,
runtime публикует `cleanup_incomplete: true`; потенциальный `SUCCESS` при этом
становится `FAILED/PROCESS_FAILED`. Автоматический broad prune не выполняется.
Docker command ограничен 10s, cleanup command — 1.5s; завершение дерева
процессов Windows — 5s. Kotlin даёт launcher до 20s на cleanup, затем
принудительно завершает дочерние процессы с дополнительным ожиданием до 20s.

## Fail-soft состояния

Отсутствующие credential, Docker, pinned image или Qwen artifact возвращают
`UNAVAILABLE`; основной analysis остаётся доступен. Timeout, invalid/oversized
output и process error возвращают bounded `FAILED`. Cancellation завершает
задание и cleanup. Активные и последние job statuses хранятся только в памяти:
после перезапуска процесса они исчезают, а `close` отменяет текущие jobs.
Сохранённый advice остаётся на диске и читается после перезапуска без нового
model request. Повторный submit для той же пары `(run_id, analysis_id)` во время
активного job получает `Busy`.

## Что показывает интерфейс

Панель «Рекомендации AI» (вкладка «ИИ-разбор» новой оболочки и тот же компонент
в прежнем интерфейсе) не называет модель заранее: до запроса она говорит только,
что совет создаёт языковая модель по данным анализа, и не просит согласия.
Кнопка «Получить рекомендации» активна, пока нет активного задания. Фактическую
модель, версию prompt и ошибки панель берёт из ответа сервера и показывает
дословно:

| Что видно | Откуда берётся |
| --- | --- |
| Модель | `provenance.model_id` готового совета |
| Версия prompt | `provenance.prompt_version`; `advisory-system.v1` помечена «прежняя версия», неизвестная метка выводится как есть, пустая даёт «не указана» |
| Запросов к модели | `provenance.provider_requests`, только если поле есть в ответе (аддитивное поле ADR 0021, Д4; отсутствие поля не заменяется единицей); значение больше 1 означает повтор запроса при ошибке схемы (ADR 0021, Д2) |
| Время ответа, исполнитель, идентификатор вызова | `provenance.duration_ms`, `runner_id` и `runner_version`, `invocation_id` |
| Состояние задания | `state`: В очереди, Выполняется, Готово, Ошибка, Недоступно, Отменено |
| Причина ошибки или недоступности | `failure` и `unavailable_reason` задания: русская фраза, сырой код в скобках и строка «Что делать» |
| Ответы API `AI_BUSY` и `AI_UNAVAILABLE` | русская фраза, код и исходное сообщение сервера (на английском) |

Основания гипотез (раскрывающийся блок «Основания» под каждой гипотезой) показывают
код evidence из ответа модели и подпись словами, найденную в результате этого
анализа: «Правило checkout-p95» для проверки правила, «Метрики: <транзакция>» и
«Метрики всего прогона» для сводки метрик, тип evidence (например, «Проверка
тренда») для остальных. В новой оболочке для проверки правила и сводки метрик
транзакции рядом стоит кнопка «Открыть в таблицах»: она переключает вкладку на
«Таблицы» и ставит фокус на строку этого evidence; для метрик всего прогона
кнопка прокручивает к карточкам сводных метрик и ставит на них фокус (строки у них нет). Код, которого
нет в результате, остаётся кодом без кнопки. В прежнем интерфейсе кнопок нет,
подписи те же. Интерфейс не проверяет факты ответа модели: ссылка только
показывает, на какую строку evidence модель сослалась.

Совет без блока `provenance` (усечённый или чужой документ) показывается без
строки происхождения, панель при этом не падает. Незнакомый код ошибки или
причины недоступности выводится кодом с нейтральной фразой, поэтому новые коды
не требуют правки интерфейса. Предупреждение «Рекомендации могут содержать ошибки; гипотезы
требуют проверки. Вердикт SLA не меняется» остаётся: совет вердикт не меняет.

Ошибки заданий и что делать:

| Код | Смысл | Что делать |
| --- | --- | --- |
| `INPUT_LIMIT` | evidence анализа больше допустимого размера | сократить период или число транзакций, запустить анализ заново |
| `OUTPUT_LIMIT` | ответ модели больше допустимого размера | запросить совет ещё раз |
| `INVALID_ANALYSIS` | результат анализа не прошёл проверку перед отправкой | запустить анализ заново |
| `INVALID_OUTPUT` | ответ модели не соответствует контракту совета | запросить совет ещё раз |
| `UNKNOWN_EVIDENCE_REFERENCE` | модель сослалась на evidence, которого нет | запросить совет ещё раз |
| `TIMEOUT` | модель не ответила за отведённое время | повторить позже |
| `PROCESS_FAILED` | сбой runner при выполнении разбора | повторить; при повторе сообщить администратору |
| `CREDENTIAL_NOT_CONFIGURED` | нет файла с ключом | администратору: `LT_VERDICT_AI_CREDENTIAL_ENV_FILE` |
| `DOCKER_UNAVAILABLE` | Docker недоступен | запустить Docker и повторить |
| `RUNTIME_IMAGE_MISSING` | нет закреплённого образа | администратору: `docker pull` из этого документа |
| `OS_ISOLATION_NOT_PROVEN` | изоляция не подтверждена | администратору: см. раздел про изоляцию |
| `RUNNER_ARTIFACT_MISSING` | нет файлов runtime или пакета Qwen Code | администратору: `LT_VERDICT_AI_QWEN_ROOT` и состав поставки |
| `RUNNER_ARTIFACT_MISMATCH` | пакет Qwen Code не совпадает с закреплённым | администратору: установить 0.21.1 |
| `MODEL_ENDPOINT_UNAVAILABLE` | сервис модели недоступен | проверить доступ и повторить позже |
| `AI_BUSY` (ответ 409) | сервер занят заданием или это задание уже выполняется | дождаться завершения и запросить ещё раз |
| `AI_UNAVAILABLE` (ответ 503) | ИИ-runner на сервере не настроен | администратору: настроить runtime по этому документу |

Что придёт позже: relay с повтором уже влит, но поле `provider_requests` пока не
записывается в `provenance` совета (контракт `ai-advice.v1` его ещё не содержит),
а метка `advisory-system.v2` появится с новым prompt. Интерфейс покажет их без
правок, когда схема и сервер начнут их отдавать. Исход повтора после исчерпания
(оба запроса неудачны) отдельным полем задания не передаётся: такое задание
остаётся `FAILED` с кодом из таблицы выше (`INVALID_OUTPUT` или `PROCESS_FAILED`).

## Локальная проверка без ModelStudio

Developer preflight использует fake response внутри Docker и не читает
credential:

```powershell
node --test tools/test_advisory_ai_runtime_relay.mjs
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/test_advisory_ai_runtime.ps1
```

Preflight проверяет fixed forwarding policy, предел двух запросов и повтор после
ошибки схемы, pinned artifacts, internal network, structured output и cleanup. Он не выполняет live
ModelStudio request и не подтверждает смысловое качество advice.

## Оставшееся ограничение приёмки

Быстрая проверка предыдущего live corpus выявила фактические и необоснованные
формулировки. Prompt теперь жёстче сохраняет units, policy states, statistical
limitations и clock uncertainty, но повторная semantic acceptance в этой
поставке не выполнялась. Advice остаётся явно advisory; полная cross-system и
semantic acceptance выполняется отдельным этапом.
