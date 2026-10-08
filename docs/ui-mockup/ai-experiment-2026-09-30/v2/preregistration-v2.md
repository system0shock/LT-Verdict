# Предрегистрация v2: пилот дефектов ИИ-разбора через headless Qwen Code (2026-10-01)

Статус: зафиксировано ДО первого живого запроса раунда v2 (включая смоук). Хэш этого файла записан в `PREREG_V2_SHA256.txt` и в сообщении коммита.
Предрегистрация v1 (`../preregistration.md`, sha256 `3f7ccea18a60e7e998105a9b4ea0a26bce69cce3e016bb821dde37076511e22c`) остаётся в силе для общих частей; ниже только отличия.

## 0. Решение владельца и порядок работ

Владелец (по сообщению координатора, 2026-10-01): заменить прямые API-запросы харнесса v1 на вызов headless Qwen Code; формат ответа обеспечивается его JSON-режимом.
Сделано до этого файла, без обращений к провайдеру: изучен продуктовый путь (`tools/advisory_ai_runtime.ps1`, `advisory_ai_runtime_qwen.sh`, `advisory_ai_runtime_relay.mjs`, `QwenCode0211.kt`),
найдена закреплённая версия, собран счётный relay, снят реальный запрос Qwen Code на локальный fake-endpoint (захват), 43 unit-теста (в т.ч. сухие прогоны через fake-провайдера: ledger, автостоп, 429, ключ не попадает в файлы).
Смоук (до 4 запросов) выполняется после фиксации и не входит в анализ; правки после смоука допустимы только для парсинга/формата и записываются в `v2/CHANGELOG-after-smoke-v2.md`.

## 1. Результат захвата реального запроса Qwen Code (0 запросов к провайдеру)

Файлы: `v2/capture/<ступень>-<кейс>/incoming-request.json` (то, что Qwen Code шлёт relay) и `forwarded-request.json` (то, что relay шлёт провайдеру). Отличия от тела запроса харнесса v1:

| Параметр | v1 (прямой API) | Qwen Code 0.21.1 через relay (продукт) |
|---|---|---|
| temperature | не задан (по умолчанию провайдера) | `0` (в forwarded остаётся) |
| max_tokens | не задан | `64000` в запросе Qwen; relay удаляет перед отправкой провайдеру |
| stream | `false` | `true`, `stream_options.include_usage=true` |
| tool_choice | не задан | не задан |
| описание tool | «Return the final structured output matching the required JSON schema.» | длинное: «Submit your final answer as structured JSON ... CRITICAL: ... the ONLY way to deliver the final result; the first call with valid arguments ends the session ... MUST validate against the tool's parameter schema. If validation fails you will receive the error and may retry» |
| schema в parameters | с `$schema` и `$id` | без `$schema` и `$id`, остальное совпадает |
| system | system-prompt.md | то же, байт в байт (1752 B для S0) |
| user | evidence JSON | три блока `<system-reminder>` (нет skills; контекст Qwen Code: дата, ОС, cwd и список каталога; повтор даты), затем evidence JSON |
| n, parallel_tool_calls | n=1, false | добавляет relay: n=1, false |

Следствия: (1) продукт работает при `temperature=0`; в анализе v1 написано, что temperature нигде не задаётся (это верно для репозитория, но Qwen Code задаёт 0 сам); (2) Qwen Code проверяет аргументы tool по схеме
локально и при ошибке просит модель повторить, то есть делает второй запрос, который relay блокирует (409); обёртка вида `{"output": {...}}` нарушает `additionalProperties:false` и превращается в сбой запуска, а не в ответ;
(3) короткое «нейтральное» описание tool в v1 и отсутствие temperature=0 - правдоподобные причины 16 обёрток из 72, но это гипотеза: объяснение проверяется данными раунда v2 (критерий C6). Захват не доказывает причинность.

## 2. Конструкция раунда

- Qwen Code: закреплённая 0.21.1 (`package.json` version 0.21.1, sha256 `cli-entry.js` = `CLI_ENTRY_SHA256` из `QwenCode0211.kt:17`: `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38`, совпадает);
  найдена в `.worktrees/local-baseline-comparison/build/ai-runner/qwen-code-0.21.1`, скопирована в `_qwen/` воркстри (не коммитится). Хостовая 0.24.6 НЕ используется. Node v24.14.0 на Windows.
- Флаги и окружение как в `QwenCode0211.invocation`: `--bare --safe-mode --auth-type=openai --model=<модель> --openai-base-url=<relay> --system-prompt=<текст> --input-format=text --output-format=json --json-schema=@<schema>`
  `--exclude-tools=read_file,edit,notebook_edit,run_shell_command --max-tool-calls=0 --max-wall-time=600s --approval-mode=default --chat-recording=false --openai-logging=false --telemetry=false`;
  stdin = evidence JSON (для S2 + блок производных фактов); `OPENAI_API_KEY=relay-placeholder`; HOME, QWEN_HOME, QWEN_RUNTIME_DIR, TEMP во временном каталоге. Реальный ключ Qwen Code не получает.
- Результат: stdout Qwen Code (JSON), advice берётся как в `Save-QwenAdvice` (элемент со `schema_version=ai-advice-output.v1` либо его `result`). Запуск успешен, только если relay переслал ровно один запрос
  со статусом `FORWARDED_STRUCTURED_OUTPUT`, `request_count=1`, Qwen завершился кодом 0 и advice найден; иначе он считается сбоем (B).
- Relay: копия продуктового `tools/advisory_ai_runtime_relay.mjs` (sha256 оригинала `b15731419b89fe2a02c9c58371034992199de1866fddc1d1c712550bfb41c9b8`, оригинал не менялся) в `tools/ai-experiment/relay2.mjs`: модель из параметра, ключ читается в память из `%USERPROFILE%\.qwen\settings.json`
  (`env.BAILIAN_TOKEN_PLAN_API_KEY`), запись в `ledger.jsonl` КАЖДОГО запроса к провайдеру до вызова, лимиты 100 (раунд) и 300 (всего), файл `STOP`, ровно один запрос провайдеру на запуск (второй запрос Qwen блокируется и не считается),
  сохранение входящего и пересылаемого тела (без заголовков) и SSE-ответа.
- Отличия от продукта (заявлены заранее): нет Docker, сетевой и файловой изоляции; ОС `win32` в контексте Qwen вместо Linux, дата 2026-10-01, cwd - пустой временный каталог; relay запущен на хосте;
  модель параметризована (в продукте `deepseek-v4-flash-0731` зашита). Сравнение с v1 конфаундировано (temperature 0, stream, описание tool, контекст), поэтому v1 и v2 не сводятся в одну выборку.

## 3. Бюджет

Раунд v2: не более 100 запросов к провайдеру (считает relay, а не запуски). Уже потрачено в общем лимите 74 (v1). План: смоук 2 (лимит 4) + пилот 72; ожидаемый итог 148 из 300.
Сбои: ответ HTTP не 200 или ошибка бюджета останавливают процесс (коды 4 и 3) без повтора; сетевая ошибка: один повтор запуска (считается). Блокированные вторые запросы Qwen провайдеру не уходят и не считаются.
Ответ HTTP 200 без валидного потока `structured_output` (текст вместо tool call, кривой поток, чужое имя модели в кадре) - сбой формата `BAD_STREAM` (B), прогон продолжается; остановка только при HTTP не 200.
Защита от систематического брака: после 3 подряд запусков не в статусе OK прогон останавливается (код 6) и сообщается. Расширять проверку имени модели в relay нельзя (это граница продукта).
Вызовы рецензентов Codex к бюджету провайдера не относятся.

## 4. Кейсы и ступени

Те же 12 кейсов и evidence (sha256 в `../cases/manifest.json`, хэш манифеста ниже), порядок: 12 x 2 x 3 = 72 запуска, перемешан `random.Random(20261001)`, `v2/plan-v2.json`.
S0 и S1 те же, что в v1 (system prompt S0 1752 B, S1 3335 B, байт в байт). S2 ПЕРЕСОБРАНА (`aiexp/prompts2.py`, `s2v2_addendum.txt`): система = S1 + абзац о блоке (теперь «lookup and addition», «lists quantities separately; does not assert any relation»),
блок производных фактов после evidence JSON без строки «EQUALS/DIFFERS» про суммы transaction-scope и overall и без вывода «N samples lie outside this window»; суммы transaction-scope остаются отдельной строкой, счётчики внутри окна помечены «count inside the window».
System prompt S2: 3880 B (лимит 16384).

## 5. Оракул и критерий

Оракул (`oracle.py`), `analyze.py` и критерий C0-C4 не меняются (хэши v1 ниже); известные ложные срабатывания v1 (отрицательные формулировки, совет «задать ступени», цитирование инъекции) осознанно не исправляются.
Критерий (единица - кейс, F_s, R_s, B_s, как в v1):
- C0 F_S0 >= 4; C1 F_X <= floor(F_S0/2); C2 регрессий не более 1; C3 R_X <= R_S0 + 1; C4 B_X <= B_S0 + 1.
- C5 (изменено): число кейсов с hard_defect по рецензенту на X не больше, чем на S0, ДЛЯ ОБОИХ рецензентов (A и B).
- C6 (новое, формат; общий для раунда): число запусков с любым статусом, кроме OK, из 72 не более 3 (около 4 %). Обёртки ответа в одном ключе в OK-результатах невозможны (Qwen Code проверяет схему), поэтому проверяются сбои запуска;
  причины сбоев (повторный запрос Qwen после невалидных аргументов, невалидный вывод, таймаут) раскладываются в отчёте. Если C6 не выполнен, формат считается не исправленным, а решения по ступеням помечаются как условные.
- Выбор: если проходят S1 и S2, S2 только при F_S2 <= F_S1 - 2, иначе S1. Правила разворота обёрток не нужны и не применяются.
- Метрика W (прямая проверка гипотезы об обёртках, не критерий): из сохранённого SSE-потока провайдера собираются аргументы первого tool call каждого запуска и классифицируются логикой `lenient._unwrap` на direct / wrapped / unparseable / none;
  сравнение с v1 (16 из 72 wrapped, 1 unparseable). Различие v1 и v2 смешивает четыре изменения сразу (temperature 0, описание tool, stream, обёртка user-сообщения) и покажет только, исправлен ли формат, но не какое изменение сработало.
Всё вне этого (по предикатам, по типам дефектов, по кейсам, латентность, токены, полезность, согласие рецензентов) - разведочное. Значимость не условие; доли с интервалами Клоппера-Пирсона; пары - Мак-Немар.

## 6. Рецензенты и согласие

Два независимых слепых рецензента, одна рубрика (`reviewer_rubric_v2.txt` = рубрика v1 + файл `derived.txt` с блоком производных фактов, одинаковым для ВСЕХ ответов кейса):
A: Codex `gpt-6-sol`, `model_reasoning_effort=xhigh`; B: Codex `gpt-5.6-terra`, `model_reasoning_effort=high`; оба `-s read-only`. Каждому свои идентификаторы R001... и своё случайное перемешивание, ключи в `v2/review_key_a.json`, `v2/review_key_b.json` (вне воркстри).
Один вызов на кейс на рецензента. Согласие: каппа Коэна по hard_defect (основная) вместе с сырым согласием, PABAK = 2 * согласие - 1 и распространённостью hard_defect у каждого рецензента (при распространённости около 70 % каппа занижается, парадокс каппы; низкая каппа при высоком согласии не считается провалом), по required_facts_preserved, согласие по полезности (точное и в пределах 1 балла); оракул против каждого рецензента.
Рецензенты видят evidence без блока S2, но с `derived.txt`; ответы S2 могут выдавать ступень цитатами блока.

## 7. Ожидания и ограничения

Ожидание: исчезновение обёрток при температуре 0 и продуктовом описании tool; S1 снижает knee-дефекты (T1) как в v1. Оговорки: малое n, один провайдер/модель, повторы не независимы, оракул эвристичен, рецензенты - модели,
host-запуск без Docker, дата и ОС в контексте отличаются от продукта, температура 0 уменьшает разброс между повторами, поэтому повторы ещё менее независимы.

## 8. Замороженные артефакты (sha256)

| Артефакт | sha256 |
|---|---|
| `tools/ai-experiment/aiexp/common.py` | `db692cc56d5bfbd5b02588e34ff6864043a7ccf01e334cf66bef2e2cee4e3e8a` |
| `tools/ai-experiment/aiexp/oracle.py` | `497c9573679bb8dfc467a95115ff7afd8d0c77b5c28a0c00300573cd3f441959` |
| `tools/ai-experiment/aiexp/analyze.py` | `17b9962ac90974621132f2fa03560c909af8a34630057adc6047500365d17bc7` |
| `tools/ai-experiment/aiexp/stats.py` | `e82549268ece0fa0d652304ef1f1243c11114a3115294d713959d132e2d2fc2c` |
| `tools/ai-experiment/aiexp/prompts.py` | `c695c57961d741432dd6dc5a29175f52a178dbab87245e6886ca6ae5812d0569` |
| `tools/ai-experiment/aiexp/cases.py` | `c5173f492d0371d8aa2403f3cd8013132401571208820b03a7e9d4218a9a25af` |
| `tools/ai-experiment/aiexp/prompts/s1_invariants.txt` | `947e79dadbd05121ed65b8d01d64ac5547d6294befd829602b0a6bc8666e828a` |
| `tools/ai-experiment/aiexp/prompts/s2_addendum.txt` | `9ce8f2002056c3073aaa6719f12f9a9107fc524b6aacb5b3be01e7458ee298bf` |
| `tools/ai-experiment/aiexp/prompts/reviewer_rubric.txt` | `a30db6fb56a0297bf75b3800cb8dbaf752bb0dc1a4e32857a4cebd7ebde49105` |
| `tools/ai-experiment/relay2.mjs` | `6839ac25fb0223d10967fb1cbf418e9f05b4fb26df8b107e2df7389b74736cb8` |
| `tools/ai-experiment/aiexp/qwenrun.py` | `426c0720557281cebc6dbc87682379a831945222c6270ea9247bc38ee8280547` |
| `tools/ai-experiment/aiexp/runner2.py` | `4eedbba0c713d65d308c80c683eec205ab3d504a56b79e71b6c18f4cab583c3f` |
| `tools/ai-experiment/run_v2.py` | `6b46d10b0b41080b1fb2496e305d7e11cc35abb4f0feee6ed09b11424ca65e3e` |
| `tools/ai-experiment/aiexp/prompts2.py` | `ee3420c9200c09db6762077a11d58a9aafe83c405e788ff03f61aa50c32c2267` |
| `tools/ai-experiment/aiexp/prompts/s2v2_addendum.txt` | `5a1620cad95cd991dff0dc0f09a5ebb1a2cec8a92fcc9e850c4ccbcb686f3c5d` |
| `tools/ai-experiment/aiexp/prompts/reviewer_rubric_v2.txt` | `b97803656201a939549a4445ab67568d77e9952fef66d34892540b052d935e34` |
| `tools/ai-experiment/aiexp/analysis2.py` | `d0654c55bc1a7247efaa929f0b04d790572fdf3e6cda07d61000ecd731f3b890` |
| `tools/ai-experiment/aiexp/blind2.py` | `6d72ad4011404c6e529b5c2dce9ee85755a60450efa4412f63cc1bc224dd5d5f` |
| `tools/ai-experiment/aiexp/budget.py` | `b81842d091b15cdddbba0772215807be47b0fb7b561acef6fd1745939ad14b2f` |
| `tools/ai-experiment/aiexp/runner.py` | `d59b345b6048cabe7b4dfdbef175a6f526ac74ca8604f7d74ce3dd54cb811391` |
| `docs/contracts/advice/v1/system-prompt.md` | `cec9ec6d7377a3a87ecbb051a0ee7002fcedc69d1ff5f85050d0e3817867d29b` |
| `docs/contracts/advice/v1/ai-advice-output.schema.json` | `79c9243d012372ce57bbf33bab0eee3602fa90eb96ea1e15d8ace4843ad15145` |
| system prompt S0 (v2, собранный) | `1904ce46b28c751d98c002391e0505759bd69779c6cc8184e54d2a8528613c40` |
| system prompt S1 (v2, собранный) | `407d34dba1b89907c84c41ac298e26dabf6af6dff0ae93557b6f30cb3275a6c6` |
| system prompt S2 (v2, собранный) | `ff3708cee2059a8bb179b930f10df80155888283dacc3509b224b39692beb95e` |
| `cases/manifest.json` | `9ba7dec41da0f8b603dcf46d7684112eb4c4f13352a3bc1ddac03f1f6267e649` |
| `v2/plan-v2.json` | `5f128bc977f4f242933555959f8b0734efb51183d561d1f6a42d45c0277ad5ec` |
| `_qwen/node_modules/@qwen-code/qwen-code/cli-entry.js` (0.21.1) | `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38` |
| база кода | `origin/main` = 5b5e51c, ветка `test/ai-experiment-harness`, коммиты v1 `072c1b2`, `dc42efa` |
