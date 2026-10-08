# Дефекты ИИ-разбора: анализ данных приёмки 2026-09-28 и гипотезы (2026-09-30)

Статус: только чтение и анализ. Продукт и Lab не менялись, обращений к моделям и сети не было. Всё ниже
проверено по файлам; что найти не удалось, помечено «не найдено».

## 0. Соглашения и оговорки

- **Пути.** `L` = `F:\Coding\LT-Verdict-Lab\docs\live-acceptance-2026-09-28.md` (отчёт приёмки);
  `R` = `F:\Coding\LT-Verdict-Lab\artifacts\20260928-181222-live-acceptance`; `AR` = `R\ai-results`;
  `CORP` = `R\ai-corpus`; `W` = `F:\Coding\LT-Verdict-Lab\.work\live-acceptance\analyzer-resource`.
  Учётные файлы (`*credential*`, `*.env`, `temporary-credential-ids.json`) не читались.
- **Ссылки на ответы моделей.** `output.json` и `ai-advice.json` записаны одной строкой, поэтому «файл:строка»
  для них бессмыслен. Используется JSON Pointer: `AR\slot-6-missing-source-d06-b\output.json#/caveats/7`
  (индексы с нуля). Для кода и документов: `файл:строка`.
- **Версия кода.** Приёмка шла на замороженной поставке `2b122aa`; код цитируется по `origin/main` = `bf9fb71`
  (записи `origin/main:путь:строка`). Между `2b122aa` и `bf9fb71` в файлах ИИ-контура изменился только
  `ModelStudioAdvisoryRunner.kt` (коммит `f6424c2`, PATH/Path, PR #8; `git diff --stat`). Схема взаимодействия,
  prompt, schema, relay, ADR 0009 и `CapacityPlan.kt` идентичны.
- **«Qwen или GLM» - неточная рамка.** Qwen Code 0.21.1 - это агентная обвязка (harness) обеих когорт, а не
  модель. Слоты 1-13 отвечала `deepseek-v4-flash-0731` (`origin/main:src/main/kotlin/io/ltverdict/ai/QwenCode0211.kt:18`,
  `tools/advisory_ai_runtime_qwen.sh:19`, `tools/advisory_ai_runtime_relay.mjs:8`; `previous_model_cohort` в
  `R\ai-provider-budget-authorizations.jsonl:1`; поле `model` в init-записи `AR\slot-13-d13-cpu-resource-b\qwen-container.log`).
  Слоты 14-23 - `glm-5.3` через копию runtime, где заменён только id модели
  (`W\ai-glm53-runtime\model-id-only.diff`; init-запись `AR\slot-16-...\runtime-observation\qwen.stdout`, `"model":"glm-5.3"`).
  Подпись в UI жёстко задана «DeepSeek V4 Flash» (`origin/main:ui/src/AdvicePanel.vue:105`).
- **Слоты.** 22 каталога в `AR` - это слоты 2-23; слот 1 (UI-запуск) лежит в
  `R\ui-data\runs\jmeter_jtl_csv-b87363cf...\advice\ff2a9752...\ai-advice.json`. Сверка:
  11 ответов DeepSeek (слоты 1-6, 8-11, 13) + 8 ответов GLM (16-23) = 19 подтверждённых ответов, как в
  `R\ai-glm53-final-accounting.json` (`confirmedProviderRequests: 19`). Ответа нет у слотов 7, 12, 14, 15.
  Нумерация слотов в `W\ai-glm53-runtime\glm53-case-oracles.json` (15-21) не совпадает с ledger (17-23): сверять по
  `evidenceSha256`, не по номеру.
- **Что здесь «дефект».** Список экземпляров F1-F14 взят из ревью (`R\ai-root-review.md`,
  `W\AI-D16-SEMANTIC-REVIEW.md`, `W\AI-D04-SEMANTIC-REVIEW.md`, `W\AI-D13-SEMANTIC-REVIEW.md`,
  `R\ai-glm53-root-capacity-review.json`, `R\ai-glm53-consolidated-review-v2.json`). Мои собственные наблюдения
  помечены «кандидат, не подтверждён рецензентом».
- **Асимметрия ревью.** Слоты 1-6, 8-11, 13, 22, 23 прошли второй (root) review. Слоты 16-21 (GLM) - только
  агентный review (`R\ai-glm53-consolidated-review-v2.json`, поля `semanticAssessment`). При этом агентный review
  ошибочно поставил PASS слотам 22 и 23: в `AR\slot-23-...\semantic-assessment.json` флаг
  `noNondiagnosticGuardRecommendation: true`, а в тексте слота 23 есть «promote ... from diagnostic to binding»
  (root: `R\ai-glm53-root-capacity-review.json`, `checks[1].excerpts[1]`). Вывод «у GLM меньше мелких дефектов»
  по этим данным сделать нельзя.

## 1. Инвентарь слотов

Сокращения: DS = deepseek-v4-flash-0731, GLM = glm-5.3; «h/r/c» = число hypotheses / recommendations / caveats;
«вход» = размер evidence в байтах (лимит 262 144, `origin/main:src/main/kotlin/io/ltverdict/ai/AdvisoryAi.kt:22`);
токены - из `usage` записи `result` (есть только у слотов 8, 13 и 16-23, у остальных временные stdout/stderr
удалены блоком `finally`, `W\AI-D13-SEMANTIC-REVIEW.md`, раздел «Slot 12»).

Соответствие evidence-файлов (SHA-256 совпадают с `R\ai-provider-reservations.jsonl` и `glm53-attempt.json`):
`healthy-evidence.json` 33cbf06e (4 011 B); `policy-fail-d09-above-fixture.json` 77506e74 (1 992 B);
`degraded-source-d06.json` 044c7a8a (13 447 B); `d16-cpu-limited-capacity.json` 27df52c3 (14 573 B);
`d04-correlation-diagnostic.json` e5094a32 (22 201 B); `d13-cpu-resource-failure.json` 6dff9cff (20 530 B) - все в `CORP`;
`d14-memory-r1.json` 89f84964 (20 890 B), `d14-memory-r2.json` a3361d78 (20 876 B), `d08-capacity-r1.json` 4902d933
(31 101 B), `d08-capacity-r2.json` 1a9aefad (30 957 B) - в `W\ai-glm53-runtime\evidence\`.
Во всех 23 слотах входное evidence найдено.

| Слот | Сценарий | Модель | Вход | Исход runtime | Ревью | Дефект (цитата, указатель) |
|---|---|---|---|---|---|---|
| 1 | healthy, UI (D01) | DS | healthy 4 011 | COMPLETE, 52 364 мс (`ai-advice.json#/provenance/duration_ms`); 3/4/6 | root: мелкое замечание (`R\ai-root-review.md:9`) | F1: «confirm all six expected sampler labels are present» - `ai-advice.json#/output/recommendations/1/action` |
| 2 | healthy repeat | DS | healthy 4 011 | SUCCESS 62 389 мс; 4/5/8 | root: единственность max не обоснована (`ai-root-review.md:10`) | F2: «the overall max of 904ms is a single-sample outlier» - `slot-2-healthy-repeat\output.json#/summary` |
| 3 | policy FAIL, граница (D09) | DS | fixture 1 992 | SUCCESS 60 574 мс; 4/4/6 | root: чисто (`ai-root-review.md:11`) | нет |
| 4 | policy FAIL repeat | DS | fixture 1 992 | SUCCESS 87 259 мс; 5/4/8 | root: отрицание атрибуции (`ai-root-review.md:12`) | F3: «per-sampler attribution is not shown» - `slot-4-fail-boundary-b\output.json#/hypotheses/1/possible_explanation` |
| 5 | D06, OpenSearch недоступен | DS | D06 13 447 | SUCCESS 92 100 мс; 4/4/9 | root: чисто (`ai-root-review.md:13`) | нет |
| 6 | D06 repeat | DS | D06 13 447 | SUCCESS 57 509 мс; 4/4/9 | root + S2 AI-FACT-01 (`ai-root-review.md:14`, `L:72`) | F4: «Counts are slightly inconsistent across scopes: overall 37,349 samples and per-transaction 6,223-6,227 ...» - `slot-6-missing-source-d06-b\output.json#/caveats/7` |
| 7 | D16, capacity | - | D16 14 573 | FAILED validate_runtime, 172 мс, до провайдера; исключён из бюджета хэш-привязанным релизом (`R\ai-budget-release-7.json`) | - | ответа нет |
| 8 | D16, capacity | DS | D16 14 573 | восстановлен из лога контейнера, relay: ровно один `FORWARDED_STRUCTURED_OUTPUT` (`AR\slot-8-d16-capacity-a\salvage-record.json`); 6/5/8; 6 622 in / 12 444 out | root: AI-CAPACITY-01 (`ai-root-review.md:24`) | F5: «because knee detection is not implemented, no capacity bound can be produced from this data» - `slot-8-d16-capacity-a\salvaged-output.json#/hypotheses/1/possible_explanation` |
| 9 | D16 repeat | DS | D16 14 573 | SUCCESS 114 498 мс; 6/6/7 | root: CAP-01 и CAP-02 (`ai-root-review.md:30-32`) | F6: «Implement or enable the knee detector ...» - `slot-9-d16-capacity-b\output.json#/recommendations/2/action`; F7: «make the generator capacity guard non-diagnostic so capacity verification is actually enforced» - `#/recommendations/3/action` |
| 10 | D04, корреляция | DS | D04 22 201 | SUCCESS 131 207 мс; 5/6/10 | root + `W\AI-D04-SEMANTIC-REVIEW.md`: единственность max, топология (`ai-root-review.md:38`) | F8: «Checkout max latency 10,008ms is a single extreme value» - `slot-10-d04-correlation-a\output.json#/caveats/7`; F9: «Instrument the checkout call path (frontend -> paymentservice)» - `#/recommendations/3/action` |
| 11 | D04 repeat | DS | D04 22 201 | SUCCESS 95 943 мс; 5/6/8 | root: «load phases» (`ai-root-review.md:44`) | F10: «compare the three burst intervals against the load profile to identify which stages produced errors» - `slot-11-d04-correlation-b\output.json#/hypotheses/1/recommended_check` |
| 12 | D13, CPU | DS | D13 20 530 | FAILED run_qwen, PROCESS_FAILED, 127 234 мс; ответа нет, число обращений к провайдеру неизвестно, учтён консервативно (`W\AI-D13-SEMANTIC-REVIEW.md`) | - | ответа нет |
| 13 | D13 repeat | DS | D13 20 530 | SUCCESS 102 028 мс; 5/5/7; 8 899 in / 9 353 out | root: оговорка слишком широка (`ai-root-review.md:52`) | F11: «No server-side application logs, end-to-end traces, or JVMs/GC data are present» - `slot-13-d13-cpu-resource-b\output.json#/caveats/4` |
| 14 | D13 repeat, GLM | GLM | D13 20 530 | LOCAL_LAUNCH_FAILED: дублирующиеся ключи PATH/Path в `Start-Process`, provider 0, списан консервативно (`R\ai-request-ledger.jsonl`, attempt 14) | - | ответа нет |
| 15 | D13 repeat, GLM | GLM | D13 20 530 | UNAVAILABLE RUNTIME_IMAGE_MISSING (отказ Docker API без повышения прав), 417 мс, provider не вызван, списан консервативно | - | ответа нет |
| 16 | D13 repeat, GLM | GLM | D13 20 530 | SUCCESS 261 876 мс; 8/8/11; 8 871 in / 22 204 out | только агентный PASS | не отмечено (но см. асимметрию ревью) |
| 17 | D14 OOM r1, GLM | GLM | D14r1 20 890 | SUCCESS 238 314 мс; 6/6/10; 8 901 in / 20 446 out | только агентный PASS | не отмечено |
| 18 | D14 OOM r2, GLM | GLM | D14r2 20 876 | SUCCESS 218 577 мс; 9/8/11; 8 886 in / 19 868 out | только агентный PASS | не отмечено |
| 19 | D08 capacity r1, GLM | GLM | D08r1 31 101 | SUCCESS 209 377 мс; 10/8/11; 12 592 in / 18 020 out | только агентный PASS | кандидат, не подтверждён рецензентом (C1): «and/or enable a knee detector, to bracket the upper bound» - `slot-19-...\output.json#/hypotheses/0/recommended_check` |
| 20 | D08 capacity r2, GLM | GLM | D08r2 30 957 | SUCCESS 238 378 мс; 8/8/10; 12 521 in / 20 911 out | только агентный PASS | кандидат (C2): «or enable a knee detector, to establish an upper bound» - `slot-20-...\output.json#/hypotheses/0/recommended_check` |
| 21 | D06 (повтор AI-FACT-01), GLM | GLM | D06 13 447 | SUCCESS 197 137 мс; 7/7/11; 6 186 in / 18 055 out | агентный PASS: суммы 37 349 и 36 034 разделены (`R\ai-glm53-consolidated-review-v2.json`, slot 21) | не отмечено |
| 22 | D16 (повтор CAP-01), GLM | GLM | D16 14 573 | SUCCESS 222 497 мс; 7/8/11; 6 522 in / 19 198 out | агентный PASS -> root FAIL (`R\ai-glm53-root-capacity-review.json`) | F12: «Run a multi-stage user ramp ... with a working knee detector and an unconstrained generator to produce a verified capacity bound.» - `slot-22-...\output.json#/hypotheses/2/recommended_check` |
| 23 | D16 (повтор CAP-02), GLM | GLM | D16 14 573 | SUCCESS 232 788 мс; 7/7/9; 6 522 in / 19 946 out | агентный PASS -> root FAIL | F13: «interpret a bound only once a knee detector is implemented» - `slot-23-...\output.json#/recommendations/4/action`; F14: «add an enforcing resource policy (e.g., promote generator-cpu-throttle-ratio ... from diagnostic to binding)» - `#/hypotheses/2/recommended_check` |

Итоги по исходам: 23 слота; ответы получены в 19 (11 DS + 8 GLM); без ответа 4 (слот 7 и 14 и 15 - локальные
отказы до провайдера, слот 12 - неизвестный исход).

Различия ответов по объёму: DS 3-6 гипотез (среднее 4,6), GLM 6-10 (среднее 7,75). Длительность одного вызова: DS
57-131 с, GLM 197-262 с. У GLM выходных токенов 18-22 тыс. на вызов, из них рассуждение (блоки `thinking`) 56-68 тыс.
символов на вызов (разбор `qwen.stdout` слотов 16-23); все вызовы однопроходные (`num_turns: 1`).

## 2. Типология дефектов и частоты

Типология выведена по фактам (14 экземпляров F1-F14 в 11 ответах из 19). Серьёзность: S2 - по классификации
приёмки (`L:70-84`); «умеренная» - отмечена вторым рецензентом как ограничение
доказательности (`ai-root-review.md:16`); «мелкая» - оговорка формулировки.

| Тип | Суть | Экземпляры | Кол-во | Серьёзность |
|---|---|---|---|---|
| T1. Нарушение контракта предметной области (ложная зависимость) | «bound требует knee detector» (ADR 0009: bounds вычисляются из пригодных SLA-ступеней независимо от knee, `origin/main:docs/adr/0009-explicit-capacity-stages.md:52-53`, `:37-44`); «сделать generator guard binding» (guard обязан быть `effect=diagnostic`, `origin/main:src/main/kotlin/io/ltverdict/core/CapacityPlan.kt:151-158`, код `CAPACITY_GUARD_NOT_DIAGNOSTIC`) | F5, F6, F12, F13 (knee) + F7, F14 (guard) | 6 | S2 |
| T2. Выдуманная связь между несопоставимыми величинами | сумма transaction counts (6 x ~6 224 = 37 349) сравнивается с окном 36 034 как с «слегка несогласованной» | F4 | 1 | S2 |
| T3. Необоснованная единственность / кратность | «max - single sample», при этом в evidence нет числа samples с max | F2, F8 | 2 | умеренная |
| T4. Отрицание при явном контексте | «атрибуции по sampler нет» при `scope.kind=transaction, label=checkout, error_count=2`; «server-side logs нет» при наличии OpenSearch error-профиля | F3, F11 | 2 | умеренная / мелкая |
| T5. Домысливание структуры теста или системы, которой нет во входе | «ожидаемые шесть labels»; вызов `frontend -> paymentservice`; «стадии/load phases» при `STAGE_UNSPECIFIED` | F1, F9, F10 | 3 | мелкая |

Сумма: 6 + 1 + 2 + 2 + 3 = 14. Экземпляры S2: 7 (AI-FACT-01 - 1, AI-CAPACITY-01 - 4, AI-CAPACITY-02 - 2) в 5 ответах
(слоты 6, 8, 9, 22, 23). Форма без сути (пустой или дублирующий ответ) в данных не встретилась: все ответы
сохраняют verdict, coverage, числа и оговорки (`ai-root-review.md:16`, `:36`, `:42`); выдуманных чисел рецензенты не
нашли. Неверные интерпретации - в выводах о связях, единственности, отсутствии и необходимых условиях, не в арифметике
пересказа.

**Частоты по модели** (ответов с подтверждённым дефектом / всех ответов; экземпляров):

| Модель | Ответов с дефектом | Ответов с S2 | Экземпляры по типам |
|---|---|---|---|
| DS | 9 из 11 (2 чистых: слоты 3, 5); экземпляров 11 | 3 из 11 (слоты 6, 8, 9) | T1: 3, T2: 1, T3: 2, T4: 2, T5: 3 |
| GLM | 2 из 8 (слоты 22, 23); экземпляров 3 | 2 из 8 | T1: 3; остальные 6 ответов чисты по агентному ревью, вторично не проверялись |

Малые выборки (11 и 8): различия между моделями не доказаны. Единственное честное сравнение возможно на
одинаковом входе (см. §4).

**Частоты по сценарию** (ответов с дефектом / всех ответов):

| Сценарий | Слоты | С дефектом | Экземпляры |
|---|---|---|---|
| D01 healthy | 1, 2 | 2 из 2 | F1, F2 |
| D09 policy FAIL | 3, 4 | 1 из 2 | F3 |
| D06 источник недоступен | 5, 6, 21 | 1 из 3 | F4 |
| D16 capacity, генератор ограничен | 8, 9, 22, 23 | **4 из 4** | F5, F6, F7, F12, F13, F14 |
| D04 корреляция | 10, 11 | 2 из 2 | F8, F9, F10 |
| D13 CPU | 13, 16 | 1 из 2 | F11 |
| D14 OOM | 17, 18 | 0 из 2 (только агентное ревью) | - |
| D08 capacity, здоровый | 19, 20 | 0 из 2 подтверждённых (только агентное ревью) | кандидаты C1, C2 |

## 3. Как устроено взаимодействие с моделью сегодня

Один запрос на один сохранённый анализ. Диаграмма (номера ссылаются на код `origin/main`):

```
UI AdvicePanel.vue                          Kotlin (AdvisoryAi.kt, ModelStudioAdvisoryRunner.kt)
 |  флажок согласия (:108-115)              |
 |  startAdvice -> job, опрос 5 с (:32-49)  |  generate(runId, analysisId)  (:249)
 v                                          |  1. уже есть advice? -> вернуть без запроса (:253; ADR 0010:24-25)
                                            |  2. AdvisoryEvidenceBuilder.build (:55-132):
                                            |     из analysis-result.json берёт ТОЛЬКО поля
                                            |     run_validity, policy_verdict, analysis_coverage,
                                            |     findings[], evidence[], capacity_summary
                                            |     (белый список ключей :461-475), каждый элемент
                                            |     как {ref:"analysis-result.json#/evidence/N", value:...};
                                            |     sanitize секретов (:377-398); limit 262144 B (:129)
                                            |     НИКАКИХ производных полей, правил, описаний
                                            v
                              tools/advisory_ai_runtime.ps1 (Docker, 2 контейнера, internal network)
                                            |  mount ro: evidence.json, system-prompt.md, output.schema.json (:362-364)
                                            v
        контейнер Qwen Code 0.21.1 (advisory_ai_runtime_qwen.sh:4-34)
          stdin  = evidence.json (:32);  --system-prompt=<system-prompt.md, 34 строки> (:21)
          --json-schema=output.schema.json  -> единственный инструмент structured_output
          --exclude-tools=..., --max-tool-calls=0 (:25-26), --max-wall-time=600s (:27)
                                            |  HTTP к relay (modelstudio-relay:18080)
                                            v
        relay (advisory_ai_runtime_relay.mjs): фиксирует model (:8), n=1, stream=true,
          удаляет max_tokens/reasoning/provider... (:65-73), пропускает ровно один
          tool structured_output (:75-80, :246), ОДИН запрос к провайдеру, без повторов
          temperature/seed нигде не задаются (git grep по tools/, src/.../ai, docs/contracts/advice: 0 вхождений)
                                            v
                                  ответ: 1 ход (num_turns=1), JSON ai-advice-output.v1
                                            |
                              AdviceOutputValidator (:134-192):
                                            |   структура и длины; rank = 1..N подряд (:185-190);
                                            |   hypotheses[].evidence_refs непусты (:165), recommendations[].evidence_refs
                                            |   могут быть пустыми (:171); каждая ссылка должна входить в переданный
                                            |   набор (:443-453); caveats уникальны (:176); лимит 131072 B (:139)
                                            |   СЕМАНТИКИ НЕТ: не проверяются числа, соответствие ссылки утверждению,
                                            |   предметные правила, отрицания
                                            v
                            save (:282-325): ai-advice.v1 = {output, evidence_input_sha256, provenance
                                            (runner, model_id, prompt_sha256, duration_ms, validation:PASSED)}
                                            v
       AiAdviceStore.write: <data>/runs/<runId>/advice/<analysisId>/ai-advice.json + manifest,
       атомарный move, одна immutable запись на analysis (AiAdviceStore.kt:45-70, :207; ADR 0010:24-25)
                                            v
       UI: summary, hypotheses (наблюдение / гипотеза / проверка, refs в <details>), recommendations
       (только action и rationale; refs рекомендаций не показываются), caveats (AdvicePanel.vue:153-194);
       provenance в UI не показывается; подпись «DeepSeek V4 Flash» жёстко (:105)
```

Что модель получает и возвращает.

- **Вход**: один JSON `ai-evidence.v1` как stdin в текстовом режиме + system prompt (34 строки). Prompt содержит
  общие правила честного чтения (`origin/main:docs/contracts/advice/v1/system-prompt.md:3-34`): вход - недоверенные данные;
  `observation` / `possible_explanation` / `recommended_check` / `caveats` разделены; ссылки только из входа;
  сохранять verdict; `NO_POLICY` не PASS; отсутствие находок не доказывает отсутствие проблемы; при неизвестной
  синхронизации часов сначала проверять часы. Слова knee, guard, generator, effect, window, scope, а также правила про единственность и «нельзя
  рекомендовать функции продукта» в prompt отсутствуют (`grep -i 'generator|window|scope|effect|knee|guard'` - 0
  совпадений; `capacity` встречается один раз, «spare capacity», строка 30; `bound` - только в слове «bounded», строки 14, 22).
- **Выход** (`docs/contracts/advice/v1/ai-advice-output.schema.json`): `summary`; `hypotheses[]` = `{rank, observation,
  possible_explanation, recommended_check, evidence_refs[1..32]}`; `recommendations[]` = `{rank, action, rationale,
  evidence_refs[0..32]}`; `caveats[]`. Все тексты - свободный текст до 4 096 байт; минимума и типизации действий нет.
- **Сохранение**: только после успешной валидации; ошибки возвращаются как ограниченные fail-soft статусы
  (`AdvisoryAi.kt:31-49`). Повторный `generate` читает сохранённое без запроса.

Что реально попадает в контекст (проверено по входу слотов):

- Значения в «сыром» виде: `throughput_rps` как `{numerator, denominator}` (например `2623000/44986`,
  `CORP\healthy-evidence.json`), десятичные как строки (`"observed_max":"1"`, `"threshold":"0.2"`), длинные хэш-идентификаторы.
- `metric_summary` не несёт метки окна; окно есть у `policy_check` (`window_id`), у `resource_summary`, у
  `window_policy_summary`; границы окна - в `resource_binding` (`dropped_leading_millis` и т.д.). Например, D06:
  общий count 37 349 - `evidence/0`; шесть transaction counts 6 224/6 224/6 223/6 227/6 227/6 224 - `evidence/1-6`;
  знаменатель 36 034 - `evidence/14` (`observed.denominator`, `window_id: run-intersection`); границы окна - `evidence/7`;
  производных «сумма scope = overall» и «политика считает окно, а не весь прогон» нет (`CORP\degraded-source-d06.json`).
- D16 (`CORP\d16-cpu-limited-capacity.json`): в `capacity_summary` рядом стоят `bound_type: INDETERMINATE`,
  `capacity_knee: null`, `knee_reason: KNEE_DETECTOR_NOT_IMPLEMENTED`, `reasons: [CAPACITY_GUARD_FAILED,
  CAPACITY_STAGE_NOT_VERIFIED]`, единственная ступень; guard - `evidence/16` с `effect: diagnostic`. Самого плана
  (`generator_guard_rule_ids`, `required_capacity`) во входе нет: 0 вхождений. Контрастного примера «bound есть, а
  knee пуст» во входе нет.
- D08 (`W\ai-glm53-runtime\evidence\d08-capacity-r1.json`): `bound_type: LOWER_BOUND`, `lower_inclusive: 20`,
  `capacity_knee: null`, `knee_reason: KNEE_DETECTOR_NOT_IMPLEMENTED`, три ступени PASS - тот же ключ `knee_reason`
  при существующей границе.
- Нет: описания тестируемой системы, топологии, версии LT Verdict, правил анализа, определения полей,
  сырых samples, конфигурации политики кроме результатов проверок.

## 4. Что общего у ошибочных ответов во входе модели

1. **Одинаковый вход, разные модели: дефект стабилен.** D16 (`27df52c3`): AI-CAPACITY-01 в 4 из 4 ответах на двух
   моделях (слоты 8, 9, 22, 23); AI-CAPACITY-02 в 2 из 4 (слоты 9, 23). Значит это не разброс одной модели, а свойство
   входа и знаний.
2. **Контрастный вход снимает дефект.** D08 (bound есть, knee пуст, ступени PASS): 0 из 2 подтверждённых
   AI-CAPACITY-01 (слоты 19, 20). Замечание: те же слоты предлагают «or enable a knee detector, to establish an upper
   bound» - мягкая форма, кандидат C1/C2; по ADR 0009:39-44 верхняя граница даётся FAIL-суффиксом ступеней, а не knee.
   Решение о квалификации - за рецензентом, я её не переквалифицирую.
3. **Знание есть, инварианта нет.** В рассуждении слота 23 (`AR\slot-23-...\runtime-observation\qwen.stdout`, блоки
   `thinking`): «a diagnostic FAIL is not a policy FAIL»; тем не менее итоговая рекомендация - «promote ... from diagnostic
   to enforcing». Модель видит факт `effect: diagnostic`, но не знает, что guard по контракту обязан быть diagnostic.
   В рассуждении слота 22: «Knee detector not implemented: capacity_knee null. So no latency-vs-load knee. Caveat: capacity
   bound cannot be estimated from this run» - «knee» и «bound» слиты.
4. **Оракул был, применён не был.** `W\ai-glm53-runtime\glm53-case-oracles.json`, `cases[5]` и `cases[6]`
   (заморожен до вызовов, `frozenBeforeProviderCalls: true`), `mustNotClaim`: «Knee detection is required for a bound ...;
   making the required generator guard nondiagnostic invalidates the plan». Агентный review слотов 22 и 23 поставил PASS.
5. **D06 (AI-FACT-01): все нужные данные были во входе, но в сыром виде.** Модель получила шесть scope-counts и
   знаменатель окна, но не метку «знаменатель окна - другая величина» и не производную сумму. Её собственная гипотеза 4
   в слоте 6 (`output.json#/hypotheses/3`) верно описывает обрезку окна (10 309 мс в начале, 4 660 мс в конце) - и не
   связывается с 36 034 против 37 349; вместо этого в `#/caveats/7` (последняя оговорка) предположена несогласованность.
   Эта же evidence в слоте 21 (GLM) прочитана верно (`R\ai-glm53-consolidated-review-v2.json`, slot 21), в слоте 5 (DS)
   тоже: дефект нестабилен - 1 из 3.
6. **Отрицание при явном контексте: данные есть, «инвентаря» нет.** Слот 4: `evidence/1` содержит
   `scope.kind=transaction`, `label=checkout`, `error_count=2`, модель пишет, что атрибуции нет. Слот 13: во входе есть
   профиль OpenSearch (`frontend.request_error`, 2 sampled messages), модель пишет об отсутствии server-side logs
   (`W\AI-D13-SEMANTIC-REVIEW.md`).
7. **Кратность max не выводится из входа.** В `metric_summary` только `max`, числа samples с этим значением нет
   (`CORP\healthy-evidence.json`). Сырой JTL содержит ровно один sample со значением 10 008 мс, но модели он не
   передавался (`W\AI-D04-SEMANTIC-REVIEW.md`); утверждение верно по факту, но не обосновано входом.
8. **Модель сама считает производные.** В рассуждении слота 21: «throughput ~103.76 rps (37349000/359969 ...)»;
   в выходе слота 22 присутствует число `4262.8167741935483870961935483870962` (не найдено во входе). Арифметика
   выполняется в голове модели.

## 5. Гипотезы причин

Для каждой: прогноз, что видно в данных (за / против), дешёвый эксперимент. Сокращения экспериментов: «оффлайн» -
без запросов к провайдеру; «живой» - нужны вызовы (§8).

**H1. Сырое evidence без производных фактов.** Прогноз: ошибки выше там, где вывод требует арифметики или сопоставления
scope/окна. За: F4 (слот 6); во входе нет суммы и метки окна; модель считает в голове (§4, п. 8). Против: слоты 5 и 21 на
том же входе верны; F4 встречается в 1 из 3 - есть и разброс. Эксперимент: (оффлайн) скриптом добавить в копию evidence
блок `derived` (сумма counts по scope, доля окна от прогона, `throughput_rps` как число, число/доля ссылок с `effect`)
и сравнить в живом режиме D06 на 5+5 повторах. Здесь нужен дальнейший оракул: сумма counts = overall.

**H2. Отсутствие доменных правил в контексте.** Прогноз: дефекты T1 воспроизводятся на любой модели при любом числе
повторов, пока правила не добавлены; исчезают после добавления. За: D16 4 из 4 на двух моделях; в prompt нет knee/guard/bound
(§3); во входе нет плана и правил; D08 (контрастный вход) 0 из 2. Против: слоты 19, 20 показывают остаточную «enable a knee
detector». Эксперимент: справочник 6-10 строк (§7, ступень A1) + D16 x 5; базовая частота 4/4 уже есть (модель зависит
от выбранного runtime).

**H3. Свободный текст действия.** Прогноз: без типизации в `action` попадают шаги, недоступные пользователю (реализовать
функцию продукта). За: F5, F6, F12, F13 - рекомендации «implement / enable knee detector» (валидатор проверяет только
непустой текст ≤ 4 096 байт, `AdvisoryAi.kt:169`); `KNEE_DETECTOR_NOT_IMPLEMENTED` - ограничение продукта, а не действие
пользователя. Против: типизация не даёт правил; в полях действий (`recommendations[].action`, `hypotheses[].recommended_check`) сидят
F1, F5, F6, F7, F9, F10, F12, F13, F14 (9 из 14), а F2, F3, F4, F8, F11 (5 из 14) - в наблюдениях и оговорках. Эксперимент:
(оффлайн) классифицировать все рекомендации 19 ответов по закрытому словарю (перезапуск теста, добавить политику,
проверить часы, посмотреть сырые данные, изменить план); доля не укладывающихся оценивает стоимость.

**H4. Разброс модели и температуры.** Прогноз: дефект возникает случайно, частота падает при снижении температуры.
За: ответы на одном входе расходятся (D06: слот 5 чист, слот 6 - F4, слот 21 (GLM) чист; D09: слот 3 чист, слот 4 - F3; D01: F1 мелкое,
F2 умеренное). Против: D16 4 из 4 (систематика). Температура и seed нигде не задаются, relay их не передаёт (§3) - фактическое
значение определяется значениями по умолчанию Qwen Code/провайдера: не установлено. Проверить температуру по этим данным
нельзя. Эксперимент: (живой) D06 и D09 x 10 повторов на одной модели; при 0/10 верхняя граница 95 % примерно 26-30 %,
поэтому меньше 10 повторов бессмысленно.

**H5. Нет самопроверки.** Прогноз: второй проход по правилам ловит дефекты T2-T5, но не T1. За: один ход
(`num_turns: 1`, §1), валидатор структурный; 4 из 14 экземпляров сидят в оговорках с индексами 4-7
(F2, F4, F8, F11 - `#/caveats/4`, `#/caveats/7`, `#/caveats/7`, `#/caveats/4`): оговорки не проверяются. Против: у GLM
есть длинное рассуждение (56-68 тыс. символов), но оно не исправило F12-F14. Эксперимент: (оффлайн) детерминированный
верификатор на 19 ответах (§6); (живой, позже) критик-проход с списком правил.

**H6. Нет запрета «не делать выводов о причинах».** Прогноз: дефекты - причинные утверждения. Против: prompt уже
содержит запрет (`system-prompt.md:12-13`, `:19`, `:33-34`), и ни один рецензент не нашёл объявленной причины в 19 ответах
(`ai-root-review.md:36`, `:42`, `:50`; `W\AI-D13-SEMANTIC-REVIEW.md`). Гипотеза в исходной форме не подтверждается. Остаточная
форма: дефекты - это не причинность, а «условия / наличие / единственность / структура» (T1, T3-T5).

**H7. Нет инвентаря evidence («что есть, чего нет»).** Прогноз: отрицания и единственность исчезают, если модель
получает перечень фактически присутствующих типов данных (`scope kinds`, есть ли error details / logs / raw samples /
множественность max). За: F3, F11, F2, F8 (4 экземпляра). Против: не покрывает T1. Эксперимент: (оффлайн) вставить блок
`inventory` в копию evidence слотов 2, 4, 10, 13; (живой) x 5.

**H8. Объём и «паддинг».** Прогноз: чем больше требуется гипотез и оговорок, тем выше доля слабых утверждений. За:
GLM выдаёт 6-10 гипотез (DS 3-6), оговорки с индексами 4-7 содержат четыре дефекта (F2, F4, F8, F11). Против: подтверждённая частота дефектных ответов
у GLM не выше (2 из 8 против 9 из 11 у DS), но ревью у GLM слабее. Эксперимент: (живой) ограничение «не более 3 гипотез,
не более 3 рекомендаций» на D16 и D06; малая мощность.

**H-context. Модели не хватает контекста и пространства для манёвра (гипотеза владельца).** Три вида контекста (§7):
(1) справочник продукта, (2) карточка тестируемой системы, (3) код и документация тестируемой системы. По данным слотов:
(1) объясняет T1 (все S2 CAPACITY); (1) в форме «единицы и области» объясняет T2 (F4) частично; (2) лечит только мелкие
F9, частично F10; (3) не нужен ни одному наблюдённому дефекту.

**H-tools. Дозапрос данных (гипотеза владельца).** Прогноз: возможность запросить агрегаты убирает дефекты T2, T3, T4. За:
F2 и F8 (кратность max - нужен сырой JTL, которого в evidence нет); F4 (нужна `sum_by_scope`). Против: три S2 не
исправляются: данные были во входе, недостающим был инвариант; слот 23 видел `effect=diagnostic`. Ограничения: код
запрещает инструменты (`--exclude-tools`, `--max-tool-calls=0`, `QwenCode0211.kt:48-49`), relay пропускает ровно один
`structured_output` (`advisory_ai_runtime_relay.mjs:75-80`, `:246`), ADR 0010:33-34 и :44-45 требуют этого; каждый ход
цикла инструментов - отдельный запрос к провайдеру; текущее окно 600/605/613/620 с (`docs/user/advisory-ai.md:80-82`)
при 197-262 с на один ход GLM не вместит цикл. Эксперимент: см. ступень A3 (§7); до неё - оффлайн-оценка, сколько
дефектов F1-F14 потребовали бы данных вне evidence.

Итог сопоставления гипотез по данным: **сильнейшие три - H2/H-context(1), H1, H7.** H2 объясняет все S2 CAPACITY и
подтверждается четырьмя из четырёх ответов на одном входе; H1 объясняет AI-FACT-01 и производные числа; H7 объясняет
отрицания и единственность. H4 непроверяема без контроля температуры, H6 опровергается данными, H-tools не устраняет S2.

## 6. Набор для эксперимента и оракул

**Размеченный набор.** Пригодны: 19 пар «evidence-файл с известным SHA-256 + ответ» (слоты 1-6, 8-11, 13, 16-23), из
них 11 с подтверждённым вторым ревью дефектов и 8 без второго ревью. Оракулы фактов: `CORP\oracle-packs.v1.json` (2 кейса:
`expected_claims`, `forbidden_claims`, `useful_next_checks`), `W\ai-glm53-runtime\glm53-case-oracles.json` (7 кейсов с
`mustNotClaim`), `W\d13-resource-ai-oracle.json`, `d16-capacity-ai-oracle.json` и `d04-correlation-ai-oracle.json`
(последние два - в `.work\live-acceptance\generator-prep` и `oracles`, по ссылкам ревью; я их не читал). Позиционных
(на уровне JSON Pointer) меток дефектов у ревью нет для большинства случаев: только для слотов 22, 23
(`R\ai-glm53-root-capacity-review.json`, `AR\slot-22-...\semantic-assessment-v2.json`); остальные - в прозе.

**Чего не хватает.** (а) Исходных сырых ответов DS (кроме слотов 8 и 13): удалены блоком `finally`; рассуждения DS
недоступны. (б) Токенов у DS-слотов 2-6, 9-11. (в) Ответов «правильных эталонных» - человеческой разметки нет.
(г) Повторов одной модели на D14/D08 (только GLM). (д) Второго ревью слотов 16-21. (е) Предыдущий пилот 30 x 2
(`docs/advisory-ai-acceptance-preparation-v1.md:62`): «выполнено 0 live attempts», `build/ai-acceptance/v1/resume-2026-09-21`
в репозитории не найден; генератор синтетических входов есть в тесте
(`origin/main:src/test/kotlin/io/ltverdict/core/AdvisoryAcceptanceCorpusTest.kt:33-34`, «thirty neutral advisory inputs»).

**Детерминированные предикаты (прототип оракула).** Вычисляются по паре (evidence, ответ), без запросов:

| Предикат | Что проверяет | Ловит | Оговорка |
|---|---|---|---|
| P-A | предложение с `knee / breakpoint` и модальностью (implement / enable / until / without / requires) при `knee_reason=KNEE_DETECTOR_NOT_IMPLEMENTED`  /  F5, F6, F12, F13 (+ кандидаты C1, C2)  /  ложные срабатывания: невинные фразы «knee not implemented, not evidence of spare capacity» (слот 9, `#/caveats/6`) - нужен разбор по предложению |
| P-B | предложение с `guard / generator / throttle` и `non-diagnostic / binding / enforc / promot` | F7, F14 | агентный review этого не поймал (см. §0) - парафраз «promote ... to binding»; предикат должен требовать токен generator/guard, иначе сработает на SUT-правилах (слот 19 `#/hypotheses/1`) |
| P-C | арифметика: сумма `sample_count` по `scope.kind=transaction` = `overall`; при равенстве слова `inconsistent / mismatch / dedup / duplicate` рядом со «counts» - дефект | F4 | требует сверки в парсере evidence |
| P-D | `single-sample / single extreme / one sample / only one` рядом с `max / latency`, когда во входе нет поля кратности | F2, F8 | не срабатывать на «single group/stage» |
| P-E | отрицание (`no / not / absent`) + `sampler / attribution / logs` при наличии соответствующего типа в evidence | F3, частично F11 | нужен инвентарь типов evidence (H7) |
| P-F | `stage / phase` при `reasons` содержит `STAGE_UNSPECIFIED` и нет ступеней | F10 | узкое правило |
| P-G | число ответа отсутствует во входе | ни одного из F1-F14 не ловит | временный прототип `nums.py` (в scratchpad сессии, в репозиторий не сохранён) даёт 0-22 «отсутствующих» чисел на ответ, в основном округления и производные (например 0.0469 из 0.0469146521); нужны допуск на округление и разрешённая арифметика - иначе шум |

Не ловятся детерминированно: F1 (ожидаемый набор labels), F9 (граф вызовов; нужна заявленная топология).
Покрытие по экземплярам (14 подтверждённых; знаменатель явный): уверенно P-A, P-B, P-C, P-D - 9 из 14 (F2, F4, F5, F6, F7,
F8, F12, F13, F14), включая все 7 экземпляров S2; условно P-E, P-F - ещё 3 (F3, F10, F11) - итого 12 из 14 (86 %);
не покрыты 2 (F1, F9). Предупреждение: предикаты подобраны по уже известным дефектам, вне выборки покрытие не
измерено и оценивать его нельзя; после добавления справочника перечень предикатов будет отставать от новых видов ошибок.

## 7. Три вида контекста и ступенчатая абляция

Найдено в документах (проверено):

- **ИИ и код тестируемой системы.** `prc-lt-verdict-v0.5.md:174-178` (§2.9): RCA с ИИ: «RO-клон кода SUT на vcs-commit
  (из регистрации, §2.1) в эфемерном контейнере с закрытым egress -> ИИ-агент ... анализирует только узкий контекст (класс +
  соседи + деплой-конфиги)» (`:178`); `:227`: «Git SUT (внутренний): RO-клонирование на коммит для RCA-агента (§2.9) - эфемерное,
  без сборки и исполнения». `:24` - ИИ-треки в MVP, `:180` - ИИ-нарратив (вход - verdict.json + findings), `:203` - runtime.
  `:287` - риск «Галлюцинации ИИ-агента: правдоподобный шум вместо причины».
- **PRD v0.6.** Файл `F:\Coding\LT-Verdict\lt-verdict-prc-prd-v0.6.md` в рабочем дереве **не отслеживается git** (`??`
  при статусе на момент старта); в `origin/main` присутствует другая ревизия того же файла (1825 строк против 1821): цитаты
  `AI получает компактный evidence pack, а не все сырые metrics` - `:1344` (рабочая копия) / `:1348` (`origin/main`);
  `LLM создаёт убедительный ложный RCA | Потеря доверия | Structured evidence only, verdict immutable` - `:1580` / `:1584`;
  `APM/code agent близко к основному pipeline | Сначала incident/evidence, затем optional APM/AI` - `:1631` / `:1635`.
- **«Карточка тестируемой системы» как сущность не найдена.** Поиск (rg/grep без лимита) по `prc-*.md`,
  `lt-verdict-prc-prd-v0.6.md`, `docs/adr`, `docs/superpowers/specs`, `docs/*.md` по «карточк», `system card`, `SUT card`,
  `service map`, `system profile`, «профиль системы», «описание системы/SUT/тестируемой», `service catalog`: «карточка»
  встречается только как «карточка прогона» (`prc-lt-verdict-v0.4.md:125`, `prc-lt-verdict-v0.5.md:166`, `:180`,
  `prc-nt-analysis-platform.md:80`) и «карточка вердикта» (`docs/superpowers/plans/2026-09-29-ui-verdict-first.md`).
  Ближайшие сущности: регистрация прогона со стендом и `vcs-commit` (`prc-lt-verdict-v0.5.md:81`); «Application profile» -
  «Только selectors, topology aliases и optional overrides» (`origin/main:lt-verdict-prc-prd-v0.6.md:1176-1178`, пример с
  `topology.dependencies` - `:1736-1741`); `topology_basis` плана корреляции (`origin/main:docs/contracts/diagnostics/v1/correlation-plan.schema.json:66,84`,
  `docs/adr/0013-opt-in-evidence-triage.md:45`: значение не сочиняется ядром, а берётся из объявленного). Плечи - понятие
  продукта (`origin/main:docs/adr/0014-resource-series-limits-autostep-arm-api.md:33-41`), сайдкары упомянуты в
  `docs/ui-mockup/correlation-catalog.md:53`. Описания «~20 сервисов на плечо, 2 сайдкара, БД» в файлах не найдено;
  README Lab (`README.md:3`) описывает Online Boutique на одноузловом kind.
- **Существующий канал пользовательского текста в модель.** `topology_basis` в D04-evidence не попал (0 вхождений в
  `CORP\d04-correlation-diagnostic.json`): в модель сегодня не уходит ни одно поле, заданное пользователем как описание системы.
  В evidence есть свободные строки (label транзакции, сообщения OpenSearch), это канал prompt injection, уже существующий.
- **ADR-C (история релизов).** `docs/ui-mockup/implementation-plan.md:110`, `:180` (D5), `:242`: сущность релиза блокирует
  историю и выбор baseline - возможная привязка версии карточки к релизу.

**Ступени абляции.** A0 = текущий v1. Каждая следующая ступень добавляет одно; репозиторий - отдельно, в конце.

| Ступень | Что добавляется | Лечит из S2 (по данным слотов) | Не лечит | Риски |
|---|---|---|---|---|
| A1. Справочник продукта | статичный версионируемый файл в system prompt: правила ADR 0009 (bounds независимы от knee; `capacity_knee` всегда null; guard всегда diagnostic, исключает ступень, верхнюю границу не создаёт; `NO_POLICY` при отсутствии `required_capacity`; смысл `throughput_rps`, `effect`, окон; «советовать реализовать функции продукта нельзя») | AI-CAPACITY-01 и 02 (D16: 4 из 4 и 2 из 4 в базе); частично AI-FACT-01 (если есть правило «знаменатель окна другой») | F2, F3, F8, F11 (единственность и отрицания), F1, F9, F10 | устаревание (нужна версия и хэш в provenance; `provenance` сейчас без поля, `AdvisoryAi.kt:308-317`); лимит prompt 16 384 байта (`QwenCode0211.kt:32`); риск «ослабить» осторожность; справочник в prompt, а не в evidence - prompt объявляет stdin данными (`system-prompt.md:5-8`) |
| A2. Карточка системы | краткий пользовательский текст: сервисы и роли по плечам, сайдкары, зависимости, «что считать нормой»; лимит размера, версия, хэш, показ на экране согласия (сейчас перечисляет только evidence и prompt, `AdvicePanel.vue:114`); связь с сущностью релиза (ADR-C) и `topology_basis` | ни один S2 | F9 (вымышленный граф вызовов), частично F10, F1 | prompt injection (текст пользователя); устаревание; рост убедительной причинности («убедительный ложный RCA», PRD v0.6:1580); передача внешней модели; размер |
| A3. Дозапрос данных | read-only утилита запросов над выбранным evidence внутри контейнера, whitelist, лимит вызовов, журнал вызовов в артефакте совета | F4 (сумма) - но её лечит и H1 без инструментов; F2/F8 - только если доступны сырые samples | AI-CAPACITY-01/02 (данные были, не хватало правила) | каждый ход - запрос к провайдеру (лимит «один запрос на совет», ADR 0010:44-45, ledger); таймауты (197-262 с на ход GLM); согласие: каждый ответ инструмента уходит внешней модели; prompt injection через строки в результатах; воспроизводимость (журнал вызовов обязателен); нужен новый ADR |
| A4. Детерминированный верификатор | предикаты §6 после ответа: пометка/отклонение/повтор с указанием нарушенного правила | все 7 экземпляров S2 (post-hoc) | новые виды ошибок вне списка | ложные срабатывания; сопровождение правил; не заменяет ревью |
| A5. Репозиторий / документация SUT (отдельный поздний эксперимент) | RO-клон или выгрузка (v0.5 §2.9) | ни один наблюдённый дефект | - | согласие на передачу кода; утечки; прежде всего «убедительный ложный RCA»; размер; политика доступа. Рекомендую не начинать, пока A1-A4 не измерены |

Порядок: A0 -> A1 -> A1+H1/H7 (производные и инвентарь, копии evidence без изменения продукта) -> A2 -> A3 -> A4;
A4 в оффлайне измеряется сразу (шаг 0). A5 не входит в ближайший план.

## 8. Бюджет и ограничения

**Как работал бюджет.**

- Лимит по решению владельца - 12 запросов (6 кейсов x 2; `R\progress.md`, запись «AI: user approved 12 provider requests
  maximum, 6 cases x 2»); затем +12 (`R\ai-provider-budget-authorizations.jsonl:1`, `authorization_id:
  user-explicit-plus12-glm53-preference`, 2026-09-28T22:34:01Z, накопительный максимум 24, распределение слотов 14-25).
- Три журнала: `R\ai-provider-reservations.jsonl` (запись `reserved-before-provider-request` до запроса; строка = слот,
  23 строки), `R\ai-request-ledger.jsonl` (намерение перед отправкой и итог: SUCCESS / UNAVAILABLE / LOCAL_LAUNCH_FAILED,
  `chargedConservatively`), `R\ai-provider-budget-authorizations.jsonl` (разрешения и предполётные проверки). Правило:
  «all other pending or uncertain attempts count» (`R\ai-budget-release-7.json`); исключён только слот 7 по хэшу результата
  (172 мс, до сети). Файл блокировки `R\ai-budget.lock`.
- Итог (`R\ai-glm53-final-accounting.json`): учтено 22 из 24; подтверждённых ответов 19 (11 DS + 8 GLM); списано
  без подтверждённого ответа 3 (слот 12 - неизвестный исход, слоты 14 и 15 - локальные отказы); слот 7 исключён; слоты 24
  и 25 не использованы, остаток 2. UI-попытка 1 дала `UNAVAILABLE DOCKER_UNAVAILABLE` без запроса
  (`R\ai-request-ledger.jsonl`, attempt 1), причина - AI-ENV-01 (Path), исправлена в `f6424c2` (PR #8).
- Реальный расход на 12 списанных слотов DS - 11 подтверждённых ответов; на 10 слотов GLM - 8. Потери от локальных отказов
  и неизвестных исходов - 3 из 22 (около 14 %); закладывать запас порядка 15 %.
- Объём одного вызова: вход 6-13 тыс. токенов, выход DS 9-12 тыс., GLM 18-22 тыс. (`usage`).

**Что нужно для нового прогона** (не сделано мной, запрашивается у владельца): (1) новый учётный файл: прежний удалён
(`R\ai-glm53-credential-cleanup.json`: `existsAfter: false`; формат - файл вне репозитория с одной строкой `OPENAI_API_KEY=...`,
`docs/user/advisory-ai.md:41-46`); (2) новое явное разрешение на бюджет: остаток 2 привязан к старому разрешению и молча
использоваться не должен; (3) выбор модели; endpoint зашит: Singapore, `token-plan.ap-southeast-1.maas.aliyuncs.com`, без
подмены региона (`QwenCode0211.kt:20`; `route_configuration` в `R\ai-provider-budget-authorizations.jsonl:1`); (4) для GLM
нужна копия runtime с заменой id (`W\ai-glm53-runtime\model-id-only.diff`, `provenance.json`), так как замороженный
runtime жёстко задаёт DeepSeek; (5) Docker Desktop, закреплённый image
`mcr.microsoft.com/playwright/mcp@sha256:7b82f29c...` и Qwen Code 0.21.1 с проверкой SHA-256 (`docs/user/advisory-ai.md:10-19`);
(6) для ступеней A1-A3 - отдельная копия runtime с изменённым prompt (A1: файл монтируется как `system-prompt.md`, `:363`);
для A3 - переработка relay и ADR.

**Разумное число запросов** (оценка, не измерено): шаг 0 - 0 запросов (верификатор на 19 сохранённых ответах и разметка
позиций); шаг 1 (A1 против A0, D16 - основная ячейка: 5+5 при уже имеющихся двух базовых ответах на модель -> около 8;
контроль D08 x 3 - чтобы справочник не сломал `LOWER_BOUND`) - около 11-12 запросов; шаг 2 (H1/H7 на D06 и D09 x 5+5, если
владелец решит) - до 20; A2 на D04 и D13 - 12. Разумно запрашивать 25-30 запросов на шаги 1-2 и отдельное решение по A3
(каждый ход считается запросом). Мощность: при 5 повторах на ячейку различима только систематика (4 из 5 против 0 из 5:
точный тест Фишера односторонний p = 5/210, около 0,024); для спорадических дефектов (частота 1/3 - 1/2, F4, F3) нужно 20-30
повторов на ячейку (правило трёх: 0 из n даёт верхнюю границу около 3/n).

## 9. Что нужно от владельца

1. Новый учётный файл и новое явное разрешение на бюджет (число запросов, модель, регион).
2. Выбор модели для эксперимента (DS быстрее и короче; GLM показал стабильный формат, но у него слабее ревью).
3. Источник описания системы для A2: сейчас в файлах есть только текст `topology_basis` и сущность «Application profile»
   в PRD; описание «два плеча, ~20 сервисов на плечо, 2 сайдкара, БД» дано владельцем устно и в файлах не найдено. Нужны
   допустимый размер текста, кто ведёт, формат, показывать ли его на экране согласия.
4. Допустимая область данных для A3: разрешён ли только санитизованный evidence, или можно отдавать агрегаты по сырому JTL.
5. Решения рецензента: квалификация кандидатов C1, C2 (слоты 19, 20, «enable a knee detector»); вторичное ревью слотов 16-21.
6. Решение по ADR: правка правила «один запрос на совет» (ADR 0010:44-45) допустима только для эксперимента или для
   продукта.

## 10. Что не найдено / не проверено

- Температура, seed: нигде в репозитории не заданы (git grep 0 вхождений); фактические значения по умолчанию не установлены.
- Рассуждения DS-слотов (кроме единственного блока `thinking` в логах слотов 8 и 13, содержимое не разбиралось).
- `d16-capacity-ai-oracle.json`, `d04-correlation-ai-oracle.json` и `d13-resource-ai-oracle.json` не читались (по ссылкам ревью).
- Второе ревью слотов 16-21; отсюда «GLM чисто» в §2 - только по агентному ревью.
- Предыдущая приёмка 30 x 2: выполнено 0 попыток (`docs/advisory-ai-acceptance-preparation-v1.md:62`).
- Число экземпляров F3 и F11: их серьёзность указана по формулировке рецензента (`ai-root-review.md:16`, `:52`), не по отдельной шкале S1-S3.
