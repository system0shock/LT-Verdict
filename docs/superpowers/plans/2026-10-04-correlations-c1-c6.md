# Корреляции, фаза 6 (C1-C6): стадийные семьи, приращения, каталог, приёмка C5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** довести корреляционный слой до проверяемого состояния: корреляция только внутри одной стадии, предобработка приращениями до отбора, каталог шаблонов гипотез по исходам вне ядра и приёмка C5 (1 000 отчётов на сценарий, верхняя граница Уилсона доли ложных находок не выше 7 %) на настоящем JVM-коде.

**Architecture:** две независимые линии. Линия harness (Python, offline, `tools/`, без изменения продукта) строит генераторы, независимый оракул, development-исследование и драйвер приёмки; она идёт параллельно всем остальным линиям продукта. Линия ядра (Kotlin, `core/`) меняет только селектор и `evaluatePair`: стадийные семьи и приращения, с повышением модуля identity. Каталог шаблонов остаётся данными и внешним разворачивателем в обычный `correlation-plan.v1`, ядро о нём не знает. Все срезы выходят по одному PR, документация в том же PR.

**Tech Stack:** Kotlin 2 (JVM 21), JUnit 5, Python 3.14 с `numpy==2.4.2` (как `.github/workflows/runtime-quality.yml`), `unittest`, PowerShell/Gradle для JVM-прогонов. Новых зависимостей нет.

**Spec:** `docs/adr/0022-correlation-stages-increments-calibration.md` (Proposed; в плане «ADR-G»). Предыдущий метод: `docs/adr/correlation-headline-selection.md`, `docs/contracts/diagnostics/v1/correlation-headline-selection.md`. Дорожная карта фазы 6 (`C1`-`C6`) и каталог гипотез: `docs/ui-mockup/implementation-plan.md`, `docs/ui-mockup/correlation-catalog.md`; черновик спецификации калибровки Codex `docs/superpowers/plans/codex-drafts-2026-09-30/calibration-spec-adr-g-0022.md`; пилот семьи 1 `docs/ui-mockup/core-demo/family1/` — все неотслеживаемые материалы основного чекаута, поэтому ссылки даны путями без гиперссылок, нужные числа повторены в ADR.

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: фаза 6 «Корреляции»: C1 стадийное окно, C2 приращения до отбора,
  C3 каталог шаблонов и топология, C4 связи через уровень и дрейф с AR(1),
  C5 приёмка 1000 отчётов на сценарий (верхняя граница доли ложных находок
  не выше 7 %), C6 только по результату C4. Спецификация и ADR-G до кода.
REQUIRED TO ACHIEVE IT (срезы, порядок и зависимости в таблице ниже):
  ADR-G (этот PR, Proposed) и этот план; затем по одному PR на срез:
  H0-H5 harness, K1-K2 ядро, K3 каталог, C6 условно; U1 (минимум для
  демонстрации) вне C1-C6 и только после ответа владельца на вопрос 10.
NOT REQUIRED (report-only):
  - расширение исходов (p99, паузы GC) и изменение DiagnosticLoadMetric;
  - граф сервисов в ядре, автоопределение стадий, калибровка частичной
    корреляции при переменном контроле (GENUINE_PARTIAL_UNCALIBRATED);
  - тренды L1, эпизоды, сравнение двух прогонов;
  - печать и каноникализация хэша снимка (отдельный ADR; здесь только
    правило округления входов в harness);
  - UI-аналитика глубокого анализа (P5, D2), историческая динамика.
EXPECTED FILES (этот PR): docs/adr/0022-correlation-stages-increments-calibration.md,
  docs/superpowers/plans/2026-10-04-correlations-c1-c6.md. Код не меняется.
```

## Global Constraints

- ADR-G ограничивает метод: стадия равна окну `snapshot.windows`; семья равна паре «стадия, исход» (`window_id`, `load_metric`); `m <= 16` гипотез; `L <= 10` ячеек и не более 60 с; 30-240 ячеек (после приращений 30-240 разностей); `B = 999`; блоки 10 и 20; потолок 150 000 000 произведений; один Holm на семью; `alpha = 0,05`, при `F` семьях отчёта на семью `alpha / F`, `F * m <= 50`, рекомендуемый предел `F <= 3`.
- Гейт C5: верхняя граница двустороннего Уилсона 95 % (`z = 1,959963984540054`) не выше 7 % на каждом обязательном сценарии отдельно, 1 000 завершённых отчётов, то есть не более 54 из 1 000. Сравнение до округления.
- Идентичность: каждый срез, меняющий результат, поднимает версию модуля `load-resource-diagnostics` на единицу (K1: `3` в `4`, K2: `4` в `5`), обновляет golden-фикстуры и повторяет оракул. Сохранённые результаты старых версий не пересчитываются.
- Приёмка запускается на настоящем JVM-коде. Python-порт (NumPy PCG64) не заменяет измерение: продукт использует `java.util.Random` с seed из SHA-256 (`CorrelationHeadlineSelection.kt:216-229`).
- Входы harness: значения снимка с четырьмя знаками и `+ 0.0` (иначе хэш снимка расходится на `-0.0` и `5e-05`, пилот: 4 из 80 запусков); это правило harness, не продукта.
- Тяжёлые прогоны (десятки минут и больше, Gradle, bootstrap): под именованным мьютексом `Global\ltv-heavy`, приоритет ниже обычного; в CI только быстрые `unittest` (`python -m unittest discover -s tools -p "test_*.py"`).
- Язык прозы русский, идентификаторы английские. Коммиты атомарные Conventional Commits, явные файлы, Git по `AGENTS.md`. Корпус и результаты запусков не коммитятся, в git идёт код, протокол и отчёт.
- Слова «причина», «утечка», «доказано» в продуктовых текстах запрещены; формулировка результата фиксирована в срезе U1.

## Review Focus

Входы и условия, которые спецификация подразумевает, а тесты срезов могли бы не поймать. Каждая строка получает тест в срезе-владельце.

1. **План с несколькими семьями (стадии или исходы), одна из которых короче 31 ячейки или пропала.** Ожидание: эта семья `UNAVAILABLE` (`OBSERVATION_COUNT_UNSUPPORTED`), доля `alpha / F` остальных не меняется, `F` считается по плану, не по данным; два исхода в одном окне не дают `FAMILY_OUTCOME_MISMATCH` (K1).
2. **Пропуск ячейки внутри стадии.** Разность не берётся через разрыв, серия обрывается, берётся самая длинная; после приращений ряд короче 30 даёт `UNAVAILABLE`, а не молчаливое заполнение (K2).
3. **Ряд-счётчик или ряд с плато**: после разностей константа или почти все нули. Ожидание: `PAIR_NOT_EVALUABLE` / `NO_RANK_VARIATION`, а не находка и не исключение; ряд всё равно считается в размер семьи как `p = 1` (K2).
4. **Много совпадающих значений** (округление, целые мс p95): ранги со связями после разности, оракул с теми же правилами связей (H2, K2).
5. **Контроль, меняющийся внутри стадии** (ступень нагрузки внутри окна): находки нет, причина `GENUINE_PARTIAL_UNCALIBRATED`, регрессия на пилоте F6 (K1).
6. **Отключённая каталогом гипотеза** (нет метрики в снимке): в плане её нет, размер семьи равен числу реально проверяемых гипотез, план воспроизводим (K3).
7. **Хэш снимка с `-0.0` и научной записью**: вход harness проходит валидацию ядра; без округления падает с `DIAGNOSTIC_SNAPSHOT_MISMATCH`, и тест это фиксирует как известное ограничение (H4).
8. **Обзор в интерфейсе показывает неотобранную пару** (`ui/src/shell/overview.ts:150`): после U1 пара без `selected=true` в обзор не попадает (U1).

## Срезы, размеры, зависимости

Размеры: S до половины дня, M 1-2 дня, L 3-5 дней, XL выше (оценки ориентировочные, ±50 %, без вычислительного времени). «Identity» означает изменение версии модуля или схемы идентичности.

| Срез | Линия | Размер | Содержание (C-пункт) | Зависит от | Identity |
| --- | --- | --- | --- | --- | --- |
| ADR-G | docs | S | Этот PR: ADR 0022 Proposed и план | решение владельца 2026-09-29 | нет |
| H0 | harness | S | Протокол C5 v1: инвентарь сценариев, пространство seeds, подсчёт, правила заморозки (C5) | ADR-G (черновик) | нет |
| H1 | harness | M | Генераторы сценариев: AR(1) с burn-in, дрейф, общий фактор, связи с лагом без wrap, связи через уровень, пропуски, стадии (C2, C4) | H0 | нет |
| H2 | harness | M | Независимый оракул: ранги, lag-max, Holm, Уилсон, порт `java.util.Random`, нулевые векторы JDK, литеральный фикстур Holm (C2, C5) | H0 | нет |
| H3 | harness | M | Development-исследование: уровни, приращения, удаление тренда, на AR(1), дрейфе, уровневых связях; решение по C4 (C2, C4) | H1, H2 | нет |
| K1 | ядро | M | Стадийные семьи без D3: разбиение по `(window_id, load_metric)`, `alpha / F`, `HOLM_RESOLUTION_INSUFFICIENT` (C1) | ADR-G Accepted | да, `3` в `4` |
| K2 | ядро | L | Приращения до отбора в `evaluatePair`, метод `mbb-lag-max-holm.v2`, поля выдачи (C2) | ADR-G Accepted, H2, H3, K1 | да, `4` в `5` |
| K3 | каталог | M | Файл данных каталога, схема, развёртыватель в `correlation-plan.v1`, топология списком рёбер (C3) | ADR-G Accepted | нет |
| H4 | harness | L | Драйвер приёмки на JVM: корпус, внутрипроцессный раннер, скоринг по `correlation_headline_selection`, полнота (C5) | H1, H2; код под испытанием K1, K2 | нет |
| H5 | harness | XL (вычисления) | Замороженный прогон C5, отчёт (C5) | K1, K2, K3, H4, заморозка протокола | нет |
| U1 | ui | S-M | Условный срез вне C1-C6: показывать отобранные находки, формулировка и пометки (вопрос 10) | не зависит от K, но видит поля K1 и K2 | нет |
| C6 | ядро | по решению | Theil-Sen и блочная перестановка (C6) | только если C4 покажет нужду, поправка к ADR | да |

Порядок и параллельность:

```text
ADR-G ─┬─> H0 ─┬─> H1 ─┬─> H3 ──> (решение C4) ─┐
       │       └─> H2 ─┘                        │
       ├─> K1 (после Accepted) ───────────────> K2 ──> H4 ──> заморозка ──> H5
       ├─> K3 (после Accepted, параллельно) ────────────────────────────────┘
       └─> U1 (параллельно, зависит только от существующих evidence)
```

Линия harness (H0-H3) и U1 не зависят от принятия ADR для начала работы (черновик достаточен), K1-K3 ждут `Accepted`. Критический путь: решение по ADR, H0-H3, K2, H4, заморозка, H5. Оценка линии harness до H4: около 2 недель одним исполнителем; K1 и K2 около 1,5 недели; H5 зависит от вычислений (ниже).

## Что идёт в harness, что в ядре

| Работа | Где | Почему |
| --- | --- | --- |
| Генераторы сценариев и оракул | Python (`tools/`) | Offline, без Gradle; тесты в CI быстрые |
| Development-сравнение уровней и приращений | Python (NumPy-селектор) | Дёшево, потоки случайных чисел не обязаны совпадать с JVM; результат только развитие |
| Приёмка C5 | JVM (раннер `StatisticalValidationRunner` в `src/test`, внутрипроцессно) | Измеряется настоящий код и настоящий RNG |
| Стадийные семьи и приращения | Ядро (Kotlin) | Решение должно быть внутри `evaluatePair`, чтобы материальность и отбор согласовались |
| Каталог и разворачивание | Данные и внешний разворачиватель | Контракт плана не меняется, identity не трогается |
| Показ в интерфейсе | Vue (`ui/src/shell`) | Отдельный срез, только показ готовых полей |

## Срезы, которые меняют identity

K1 (`3` в `4`: многостадийные планы перестают быть `UNAVAILABLE`, меняется состав находок и добавляются поля) и K2 (`4` в `5`: метод `v2`, другой seed, разностная серия). Для каждого: обновить `AnalysisIdentityDiagnosticVersionTest`, `DiagnosticIntegrationTest`, golden-фикстуры `fixtures/`, запустить оракул и сверить, что `policy_verdict`, anomaly и comparison не изменились. H-срезы и K3 identity не меняют (K3 порождает обычный план с другим `sha256`, как любой новый план). Идентичность старых результатов не пересчитывается.

## Что выдать в минимуме для демонстрации (до приёмки C5)

Предложение по вопросу 10 (решение владельца не получено): показывать честно, без обещания причинности. Минимум состоит из среза U1 плюс заранее подготовленного синтетического входа:

- **Только отобранные.** В обзор и в таблицу «Гипотезы для проверки» попадают гипотезы с `selected=true` (находка `correlation_candidate`). Пары `CANDIDATE`, отвергнутые отбором, не показываются в обзоре (сейчас показываются: `ui/src/shell/overview.ts:150`, `ui/src` не читает `correlation_headline_selection`).
- **Фиксированная формулировка:** «Ассоциация в стадии «<id>»: <ряд> и <исход>, лаг <N> с, ранговая корреляция <rho>, скорректированная вероятность <p>, проверено гипотез: <m>. Это повод для проверки, не доказанная причина. Метод не откалиброван на реальных данных вашего стенда.»
- **Пометки метода:** версия метода (`v1` или `v2`), представление (`levels` или `first_difference`), размер семьи, число стадий; в версии `v1` дополнительно «при дрейфе ряда ненадёжно».
- **Недоступное показывается словами**, не пустотой: `GENUINE_PARTIAL_UNCALIBRATED` («нагрузка менялась внутри стадии»), `HOLM_RESOLUTION_INSUFFICIENT`, `OBSERVATION_COUNT_UNSUPPORTED` и остальные причины селектора.
- **Нет контрастов** (под против соседей, плечо A и B): их данных нет в первой версии, их нельзя имитировать.
- **Входы демонстрации:** одна стадия, умеренный AR(1)-шум, без сильного дрейфа, одна заложенная связь и 15 нулевых гипотез из каталога, сгенерированные H1; помечены «синтетические данные». Реальный прогон без дрейфа допустим, но тоже с пометкой.
- **Чего не показывать:** слова «причина», «утечка», «из-за», процент уверенности, ранжирование без пометки «по скорректированной вероятности внутри семьи».

## Вопросы владельцу (с рекомендациями)

Полный текст и последствия: раздел «Вопросы владельцу» ADR 0022. Здесь только сводка и привязка к срезам.

| № | Вопрос | Рекомендация | Блокирует |
| --- | --- | --- | --- |
| 1 | Гейт шума: только верхняя граница не выше 7 % (не более 54 из 1 000) или ещё и доля не выше 5 % (не более 50) | 7 % как гейт, 5 % как справка | H0, заморозка |
| 2 | Гейт мощности: не менее 900 из 1 000 в объявленных `P01`-`P04`, запас истинной мощности не ниже 93 % | да | H0 |
| 3 | Несколько семей (стадии и исходы): `F <= 3` с делением alpha или одна семья в первом выпуске | `F <= 3` | K1 |
| 4 | C1 без D3 (стадия равна окну снимка) | да | K1 |
| 5 | Приращения глобально в методе v2, без поля плана | да, с оговоркой про уровни | K2 |
| 6 | Расширять исходы (p99, GC) или остаться на трёх | остаться | K3 |
| 7 | Топология списком рёбер вручную | да | K3 |
| 8 | Вычислительный бюджет C5 и порядок «замер, заморозка, прогон» | да | H4, H5 |
| 9 | Запасной вариант при провале гейта на AR(1) | сначала сузить заявление, затем усложнять метод; гейт не ослабляется | H5 |
| 10 | Демонстрация до C5 с пометками | да | U1 |

## Проверка: оракулы, сценарии, вычислительный бюджет

### Оракулы

- **Литеральный фикстур** (из `CorrelationHeadlineSelectionTest.kt`): `p10 = 0,001`, `p20 = 0,012`, Holm `0,036` для заданной семьи. Проверка воспроизведения метода, не калибровка.
- **Независимые определения** в `tools/correlation_oracle.py`: усреднённые ранги со связями, lag-max с фиксированными якорями (`anchors = n - 2L`), `p = (1 + #{T* >= T}) / (B + 1)`, Holm с тем же упорядочением и порядком связей, Уилсон с `z = 1,959963984540054`. Оракул не импортирует функции продукта и не копирует их текст.
- **Порт `java.util.Random`:** линейный конгруэнтный генератор 48 бит, `nextInt(bound)` с отбраковкой и переполнением. Нулевые векторы из JDK (`jshell`) вшиваются в тест константами; способ получения записан в docstring теста. Это тест воспроизведения потока, а не калибровки.
- **Оракул для K2:** разности и ранги на малых ручных рядах (с пропуском, со связями, с константой) дают ожидаемые `source_cells`, `analysed_points`, `rho`, лаг и статус; значения посчитаны в H2 до реализации K2.

### Сценарии

Ярус C5 (по 1 000 отчётов на `scenario_id`; что идёт в гейт, указано ниже таблицы). Параметры по умолчанию: одна стадия 120 ячеек, 16 гипотез, лаг до 4 ячеек, исход p95, контроль `target_rps` постоянный; ряды ресурсов независимы между собой.

| `scenario_id` | Истина | Что проверяет |
| --- | --- | --- |
| `N01-iid` | независимые IID Gauss | базовый шум |
| `N02-ar08` | независимые AR(1), `phi = 0,8` | главный риск (v1: 7,7-13,2 %) |
| `N03-ar095` | независимые AR(1), `phi = 0,95` | сильная память |
| `N04-t3` | независимые IID Student t3 | тяжёлые хвосты |
| `N05-drift-strong-ar08` | линейный дрейф сильный (13 sd) у каждого ряда, шум AR(1) 0,8 | отсутствующее в пилоте сочетание |
| `N06-drift-weak-ar08` | дрейф слабый (1,6 sd), шум AR(1) | то же |
| `N07-randomwalk` | независимые случайные блуждания | нестационарность |
| `N08-ar08-240-l10` | `N02`, 240 ячеек, лаг 10 | худшая форма, стоимость |
| `N09-ar08-gaps` | `N02`, 5 % случайных пропусков ячеек | обрыв серии, самая длинная серия |
| `N10-stages-2`, `N10-stages-3` | `N02` в каждой из 2 и 3 стадий, независимо | объединение стадий (Д3) |
| `N11-activation` | `N02`, семья создаётся только при нарушении SLA p95 | полная цепочка активации; доля и на все запланированные отчёты, и среди активированных |
| `P01-lin-lag0`, `P02-lin-lag2`, `P03-lin-neg-lag3` | одна заложенная связь, 15 нулевых, шум AR(1) 0,8 | мощность, знак, лаг (допуск 1 ячейка); лишние находки идут под гейт шума |
| `P04-lin-drift` | как `P02` плюс сильный дрейф | устойчивость при дрейфе |
| `P05-lin-weak-{a,b,c}` | три заранее объявленных амплитуды эффекта | кривая мощности, в гейт не входит (публикуется) |
| `P06-level-threshold`, `P07-level-saturation` | связь через уровень (порог, насыщение) | C4: публикуются; идут в гейт обнаружения только после поправки к ADR, если приращения (или принятое расширение) их покрывают, иначе «не покрыто» |

Не входит в гейт, но публикуется (ярус границ): общий фактор (ресурс и исход зависят от скрытой смены смеси запросов, прямой связи нет; ассоциация настоящая, заявление «не причина»); длинные блоки пропусков; зависимость пропусков от деградации. Детерминированные граничные фикстуры (отдельно, не по 1 000): 29 и 30 якорей, `N - 1 - 2L` равно 29 и 30, 240 и 241 ячейка, `F = 4` при `m = 16` даёт `HOLM_RESOLUTION_INSUFFICIENT`, переменный контроль, 17 гипотез. Ярус разработки (H3): те же и дополнительные варианты по 200 отчётов, не входят в приёмку.

Истинность размечается генератором до запуска: «ассоциации нет» (гейт шума) или «заложена связь». **Гейт обнаружения (не менее 900 из 1 000): `P01`-`P04`**; `P05`, `P06`, `P07` публикуются без гейта. Гейт шума: все 12 `N`-конфигураций и лишние находки в `P01`-`P04`. Сценарии с неоднозначной истиной (общий фактор) в гейт не входят. Амплитуды `P01`-`P04` подбираются в H3 так, чтобы истинная мощность была не ниже 93 % (вероятность пройти порог 900 из 1 000 около 99,98 % на сценарий; при истинных 90 % всего около 53 %), доля шума целилась в 3-4 %. `N11` неполон, если доля активаций ниже объявленной в протоколе (рекомендуется не ниже 30 %).

### Вычислительный бюджет C5

Оценка, не измерение; уточняется в H4 до заморозки.

| Показатель | Значение |
| --- | --- |
| Прогон C5 | 12 `N`-конфигураций (`N01`-`N09`, `N10` с двумя и тремя стадиями, `N11`), все под гейтом шума; 4 `P`-конфигурации `P01`-`P04` под гейтом обнаружения и шума; 5 конфигураций без гейта (`P05` по трём амплитудам, `P06`, `P07`): 21 конфигурация по 1 000, 21 000 отчётов |
| Худшая форма (`N08`) | v1: 147 692 160 произведений, потолок 150 млн; v2 при 240 исходных ячейках (239 разностей, 219 якорей): 147 020 832 |
| Раньше измеренное | CLI-запуск ядра 2-3 с на отчёт (пилот семьи 1, включая запуск JVM); Python-порт 0,36 с на отчёт |
| Через CLI (верхняя оценка) | 21 000 x 2,5 с около 15 ч |
| Внутрипроцессный раннер (ожидание) | 0,3-1 с на отчёт, около 2-6 ч; замеряется в H4 |
| Разработка (H3) | 3 варианта x около 20 сценариев x 200 отчётов около 12 000 Python-прогонов, около 1,5 ч |

Правила прогона: один процесс за раз под `Global\ltv-heavy`, приоритет ниже обычного, ограничение процессоров JVM (`-XX:ActiveProcessorCount=2`) и кучи; чекпоинты по сценариям (завершённый сценарий не пересчитывается; технически прерванный отчёт повторяется с теми же входами, история попыток сохраняется); один тайм-аут на отчёт и общий бюджет с признаком `INCOMPLETE`, а не `PASS`.

### Пошаговая проверка среза

Для Kotlin-срезов: `.\gradlew.bat test --tests "io.ltverdict.core.<ИмяТеста>"` на срез, затем полный `.\gradlew.bat test` под мьютексом; для Python: `python -m unittest tools.<модуль> -v`; для документов: `markdownlint-cli2`, проверка ссылок и `git diff --check`.

## Срезы

### Task H0: Протокол C5 v1

**Files:**

- Create: `docs/statistical-validation-correlation-c5-protocol-v1.md`

**Interfaces:**

- Consumes: решения владельца по вопросам 1, 2, 8, 9.
- Produces: список `scenario_id` обязательного яруса и яруса границ, диапазон seeds нового пространства (например `100000..199999`, не пересекается с `1000..1999` пилота и с `1000..` разработки), правила подсчёта (`ложная находка`, `обнаружение`, `INCOMPLETE`), процедура заморозки; на это опираются H1, H4, H5.

- [ ] **Step 1:** Скопировать таблицу сценариев из раздела «Сценарии» и задать каждому: `N` ячеек, число гипотез, лаг, механизм, разметку истины, ожидаемое поле скоринга.
- [ ] **Step 2:** Записать правила скоринга: ложная находка равна отчёту с `selected=true` по гипотезе, размеченной «нет ассоциации»; обнаружение равно `selected=true` по заложенной гипотезе, верному знаку, ошибке лага не более 1 ячейки; `UNAVAILABLE` на положительном сценарии равно пропуск обнаружения.
- [ ] **Step 3:** Записать порядок заморозки (метод, каталог, генераторы, инвентарь, seeds, пороги, подсчёт) и запрет настройки по результату приёмки.
- [ ] **Step 4:** `markdownlint-cli2`, проверка ссылок. Commit: `docs: add C5 correlation acceptance protocol v1`.

### Task H1: Генераторы сценариев

**Files:**

- Create: `tools/correlation_scenarios.py`
- Test: `tools/test_correlation_scenarios.py`

**Interfaces:**

- Produces: `generate(scenario_id: str, seed: int) -> dict` с ключами `resources: list[list[float]]` (m рядов), `outcome: list[float]`, `target: list[float]`, `truth: dict` (`null_hypotheses: list[int]`, `planted: {"index": int, "lag_cells": int, "sign": int} | None`), `step_ms: int`, `source_cells: int`. Один исходный след генерируется один раз; преобразования (разность, удаление тренда) применяются к нему в оракуле, чтобы пары вариантов были попарными.

- [ ] **Step 1: Failing test** (свойства, а не числа):

```python
import unittest
import numpy as np
from tools import correlation_scenarios as cs

class ScenarioProperties(unittest.TestCase):
    def test_deterministic_by_seed(self):
        a = cs.generate("N02-ar08", 100001)
        b = cs.generate("N02-ar08", 100001)
        self.assertEqual(a["outcome"], b["outcome"])

    def test_ar1_stationary_acf_close_to_phi(self):
        rows = [np.array(cs.generate("N02-ar08", s)["outcome"]) for s in range(100000, 100200)]
        acf = np.mean([np.corrcoef(r[:-1], r[1:])[0, 1] for r in rows])
        self.assertAlmostEqual(acf, 0.8, delta=0.05)

    def test_planted_lag_has_no_wraparound(self):
        g = cs.generate("P02-lin-lag2", 100001)
        self.assertEqual(g["truth"]["planted"]["lag_cells"], 2)
        self.assertEqual(len(g["outcome"]), g["source_cells"])
```

- [ ] **Step 2:** `python -m unittest tools.test_correlation_scenarios -v` — FAIL (модуль отсутствует).
- [ ] **Step 3:** Реализовать генераторы: AR(1) со стационарным стартом, дрейф линейный (общее изменение `k * U(0,8; 1,2) * sd`, знак случайный; `k` равно 13 и 1,6), случайные блуждания, t3, пропуски, стадии, связь с лагом из расширенного ряда (без повторения первой точки), эффект через порог и насыщение, амплитуда задаётся коэффициентом механизма. Запретить подбор seed под желаемое `rho`.
- [ ] **Step 4:** тест зелёный; тесты быстрые (секунды), не требуют Gradle. Commit: `test: add correlation scenario generators`.

### Task H2: Независимый оракул

**Files:**

- Create: `tools/correlation_oracle.py`
- Test: `tools/test_correlation_oracle.py`

**Interfaces:**

- Produces: `ranks(values) -> list[float]`, `lag_max_abs(x, y, max_lag) -> float | None`, `holm(p_values) -> list[float]`, `wilson_upper(k, n) -> float`, `class JavaRandom(seed)` с `next_int(bound)`, `select(hypotheses, seed_material, representation) -> list[Selection]` (NumPy, для разработки).

- [ ] **Step 1: Failing tests:**

```python
class OracleVectors(unittest.TestCase):
    def test_wilson_upper_bounds(self):
        self.assertAlmostEqual(oracle.wilson_upper(54, 1000), 0.06979, places=4)
        self.assertGreater(oracle.wilson_upper(55, 1000), 0.07)
        self.assertAlmostEqual(oracle.wilson_upper(0, 1000), 0.00383, places=4)

    def test_holm_literal_fixture(self):
        # p10=0.001, p20=0.012 -> max-p 0.012; adjusted for the declared family of 3 hypotheses
        self.assertAlmostEqual(oracle.holm([0.012, 1.0, 1.0])[0], 0.036, places=12)

    def test_java_random_next_int_vectors(self):
        # vectors from JDK (jshell, 2026-10-04): new java.util.Random(42).nextInt(100) x5
        r = oracle.JavaRandom(42)
        self.assertEqual([r.next_int(100) for _ in range(5)], [30, 63, 48, 84, 70])
```

Вектор получен из `jshell` на JDK 21 (`var r = new java.util.Random(42)`, пять вызовов `r.nextInt(100)`); исполнитель H2 перепроверяет его на своём JDK **до** написания порта и добавляет векторы для границы степени двойки и отбраковки.

- [ ] **Step 2:** запуск, FAIL.
- [ ] **Step 3:** реализовать оракул без импорта продуктового кода: ранги по формуле `(start + 1 + end) / 2`, якоря фиксированы, `p = (1 + count) / (B + 1)`, Holm с порядком связей (p, затем идентификатор пары, затем идентификатор окна), `JavaRandom` по спецификации (LCG 48 бит, отбраковка, степень двойки).
- [ ] **Step 4:** тесты на ручные ряды для разностей: пропуск ячейки обрывает серию; константа даёт `None`; связи совпадают с вручную посчитанными значениями (значения записываются в тест до K2).
- [ ] **Step 5:** commit `test: add independent correlation oracle`.

### Task H3: Development-исследование и решение по C4

**Files:**

- Create: `tools/correlation_devstudy.py`, `tools/test_correlation_devstudy.py`, отчёт `docs/statistical-validation-correlation-devstudy-v1.md`

**Interfaces:**

- Consumes: `correlation_scenarios.generate`, `correlation_oracle.select`.
- Produces: таблица «вариант (уровни, разности, удаление линейного тренда) x сценарий» с долями ложных и обнаружений, интервалами Уилсона и числом отчётов; рекомендация для ADR (оставить приращения ли, добавить условие ли).

- [ ] **Step 1: Failing test** на скелете: `run(variant, scenario, n, seed0)` возвращает `{"reports": n, "false_reports": k}` и детерминирован.
- [ ] **Step 2:** реализовать; прогнать вручную ярус разработки (3 варианта, 200 отчётов на сценарий) под мьютексом; сначала `N02`, `N05`, `P06`, `P07`.
- [ ] **Step 3:** отчёт: что показали приращения на AR(1) с дрейфом (отсутствующее в пилоте), на уровневых связях (потеря), что выбирается для метода v2 и что записано как «не покрыто». Если приращения не проходят порог на `N02`/`N03` даже в разработке, зафиксировать и вынести владельцу вопрос 9 до K2.
- [ ] **Step 4:** commit `test: add correlation development study` и отдельным коммитом отчёт `docs: add correlation development study report`.

### Task K1: Стадийные семьи без D3 (C1)

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/DiagnosticAnalysis.kt:52-64`, `src/main/kotlin/io/ltverdict/core/CorrelationHeadlineSelection.kt` (параметр уровня и причина), `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt:93` (`"3"` в `"4"`)
- Test: `src/test/kotlin/io/ltverdict/core/CorrelationHeadlineSelectionTest.kt`, `DiagnosticAnalysisTest.kt`, `AnalysisIdentityDiagnosticVersionTest.kt`
- Docs: `docs/contracts/diagnostics/v1/correlation-headline-selection.md`, `CHANGELOG.md`

**Interfaces:**

- Consumes: `evaluatePair` результаты (`PairResult.hypothesis.windowId`, `outcomeKey`).
- Produces: `selectCorrelationHeadlines(hypotheses, seedMaterial, checkCancelled, alpha: Double = CORRELATION_HEADLINE_ALPHA)`; в `evaluateDiagnostics` гипотезы группируются по `(windowId, outcomeKey)`, вызов по группе с `alpha / F`, где `F` равно числу различных пар `(window_id, load_metric)` в парах плана; новая причина `HOLM_RESOLUTION_INSUFFICIENT`, если `alpha / (F * m) < 1 / (B + 1)`; публикуемое поле `alpha` равно фактическому уровню семьи, добавляется `family_count` (поля публикуются уже в K1, чтобы результат K1 не публиковал неверный уровень).

- [ ] **Step 1: Failing tests** (имена и ожидания):
  - `two stages produce independent families`: план с одной парой в окнах `w1`, `w2` с заложенной связью только в `w1`: `w1` даёт `SELECTED`, `w2` `NOT_SELECTED`, ни одной `MULTI_WINDOW_FAMILY_UNSUPPORTED`;
  - `three families divide alpha`: опубликованное `alpha` равно `0.05 / 3`, `family_count` равно 3 (проверка по Holm-границе на синтетическом p);
  - `two outcomes in one window are two families`: два исхода в одном окне не дают `FAMILY_OUTCOME_MISMATCH`, `alpha` равно `0.05 / 2`;
  - `four families with sixteen hypotheses are unavailable`: `HOLM_RESOLUTION_INSUFFICIENT` у всех, p-поля `null`;
  - `declared F is independent of data`: короткая стадия (20 ячеек) даёт `OBSERVATION_COUNT_UNSUPPORTED`, а у остальных `alpha / F` прежний (Review Focus 1);
  - `varying control inside stage stays unavailable`: `GENUINE_PARTIAL_UNCALIBRATED` (Review Focus 5);
  - `single stage is bit-identical to v1`: при `F = 1` значения p и Holm совпадают с v1 (проверка на сохранённом литеральном фикстуре).
- [ ] **Step 2:** `.\gradlew.bat test --tests "io.ltverdict.core.CorrelationHeadlineSelectionTest"` — FAIL.
- [ ] **Step 3:** реализовать минимально (разбиение, параметр, причина). Не менять метод и seed (метод остаётся `v1`).
- [ ] **Step 4:** тесты зелёные; обновить identity-тесты и golden-фикстуры; полный `.\gradlew.bat test` под мьютексом.
- [ ] **Step 5:** контракт и CHANGELOG (видимое изменение: многостадийные планы дают находки). Commit: `feat(diagnostics): select correlation headlines per stage family (ADR 0022, K1)`.

### Task K2: Приращения до отбора (C2)

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/DiagnosticAnalysis.kt` (`evaluatePair`: преобразование внутри самой длинной непрерывной серии до `association`, профиля лагов, материальности и гипотезы), `CorrelationHeadlineSelection.kt` (`CORRELATION_HEADLINE_METHOD = "mbb-lag-max-holm.v2"`), `AnalysisResult.kt:93` (`"4"` в `"5"`)
- Test: `DiagnosticAnalysisTest.kt`, `CorrelationHeadlineSelectionTest.kt`, `AnalysisIdentityDiagnosticVersionTest.kt`, `DiagnosticIntegrationTest.kt`
- Docs: `docs/contracts/diagnostics/v1/correlation-headline-selection.md`, `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`, пометка в `docs/adr/correlation-headline-selection.md`

**Interfaces:**

- Consumes: оракульные значения H2 (ручные ряды), решение H3.
- Produces: в `correlation_headline_selection` поля `representation = "first_difference"`, `source_cells`, `analysed_points` (`alpha` и `family_count` уже есть с K1); `correlation_pair` сохраняет форму; `min_abs_effect` и знак считаются по разностной серии, а `min_resource_delta` и `min_load_delta` остаются проверками размаха уровней до преобразования.

```kotlin
// сигнатура функции-преобразования (внутри evaluatePair), без нового публичного типа
private fun firstDifferences(points: List<PairPoint>): List<PairPoint> // length = points.size - 1; серия уже непрерывна
```

- [ ] **Step 1: Failing tests:**
  - `ranks and rho on differences match oracle` на ручном ряду с ожидаемыми H2 значениями;
  - `gap breaks the series`: пропуск ячейки, берётся самая длинная серия, `analysed_points` равно длине серии минус один; серия короче 31 даёт `OBSERVATION_COUNT_UNSUPPORTED` (Review Focus 2);
  - `constant or counter resource is not evaluable`: `PAIR_NOT_EVALUABLE`, остаётся в размере семьи как `p = 1` (Review Focus 3);
  - `ties after differencing use average ranks` (Review Focus 4);
  - `effect threshold follows differences`: пара с сильной корреляцией уровней и слабой корреляцией разностей получает `BELOW_EFFECT` или `MATERIALITY_NOT_MET`, а не `SELECTED`;
  - `range gates stay on levels`: пара с размахом уровней ниже `min_resource_delta` остаётся `BELOW_EFFECT`, даже если разности сильно коррелируют;
  - `drift does not select all hypotheses`: сильный дрейф с известным seed, ни одной находки (регрессия пилота F2);
  - `method v2 changes the seed stream`: p-значения отличаются от v1 на том же входе, версия метода `mbb-lag-max-holm.v2` в evidence;
  - `identity module is five`; `source_cells`, `analysed_points` присутствуют в evidence.
- [ ] **Step 2:** запуск, FAIL.
- [ ] **Step 3:** реализовать минимально; не добавлять поля плана; пороги материальности по правилам Д4.
- [ ] **Step 4:** зелёные тесты, обновить golden, полный `.\gradlew.bat test` под мьютексом; сверить, что `policy_verdict`, anomaly и comparison не изменились.
- [ ] **Step 5:** документация и CHANGELOG. Commit: `feat(diagnostics): difference correlation series before headline selection (ADR 0022, K2)`.

### Task K3: Каталог шаблонов и разворачиватель (C3)

**Files:**

- Create: `docs/contracts/diagnostics/v1/correlation-catalog.v1.json` (данные), `tools/correlation_catalog.py`, `tools/test_correlation_catalog.py`; при необходимости схема `docs/contracts/diagnostics/v1/correlation-catalog.schema.json` (`tools/verify_slice0.py` проверяет только перечисленные в нём файлы, регистрировать каталог там не нужно)
- Docs: `docs/user/slice-1-local-analysis.md` (раздел о каталоге)

**Interfaces:**

- Produces: `expand(catalog, snapshot, stages, outcome, topology_edges) -> tuple[dict | None, list[dict]]`: обычный `correlation-plan.v1` либо `None`, и отдельная ведомость `skipped` с причинами (в сам план она не попадает: парсер ядра отвергает неизвестные поля, `DiagnosticPlan.kt:288-299`); детерминированный; гипотеза без метрики в снимке пропускается; размер семьи равен числу оставшихся; если оставшихся нет, план `None`.

- [ ] **Step 1: Failing tests:** `expands family one for p95 into at most 16 pairs`; `drops hypothesis when metric missing from snapshot`; `includes load generator control hypothesis`; `rejects more than 16 pairs`; `worst pod is fixed aggregation not chosen by correlation`; `topology edge missing removes downstream hypothesis`; `same input gives identical plan hash`; `all hypotheses skipped returns no plan`; `family not activated returns no plan`; `skipped report is not part of the plan`.
- [ ] **Step 2:** запуск, FAIL.
- [ ] **Step 3:** данные: первые три семьи каталога (p95, ошибки, пропускная способность) с знаком, лагом (не более 4 ячеек), порогом эффекта 0,3, контролем; семья OOM остаётся событийной (комментарий в файле, без пар). p99 и GC не включаются.
- [ ] **Step 4:** тесты зелёные; `python tools/verify_slice0.py` если затронуты контракты. Commit: `feat(tools): add correlation hypothesis catalog and plan expander (ADR 0022, K3)`.

### Task H4: Драйвер приёмки на JVM

**Files:**

- Create: `tools/correlation_acceptance.py`, `tools/test_correlation_acceptance.py`
- Modify: `src/test/kotlin/io/ltverdict/core/StatisticalValidationTest.kt` (внутрипроцессный опциональный режим: корпус C5 читается из `LTV_C5_CORPUS`; выход `correlation_headline_selection` по всем парам и окнам)

**Interfaces:**

- Consumes: `generate`, `oracle.wilson_upper`.
- Produces: `freeze(inventory) -> corpus` (манифест, входы, хэши, `scenario_id`, seeds); `score(corpus, actual) -> report` со статусами `PASS`, `INCOMPLETE`, `CORRECTNESS FAIL`, `USEFULNESS FAIL`; полнота: каждый `scenario_id x seed` присутствует ровно раз, selection есть по каждой паре и окну, ключ по паре и окну, размер семьи не теряет недоступные гипотезы.

- [ ] **Step 1: Failing tests:** `score marks missing report as INCOMPLETE not pass`; `score uses wilson upper bound at 54 of 1000 pass and 55 fail`; `score rejects duplicate pair window key`; `score counts unavailable as missed detection on positive scenario`; `inputs round to four decimals and avoid negative zero` (Review Focus 7).
- [ ] **Step 2:** реализовать; запустить на малом корпусе (20 отчётов `N01`) на текущем `v1`, сверить с оракулом на ручных фикстурах.
- [ ] **Step 3:** замерить время отчёта внутри процесса и худшей формы (`N08`), записать в протокол как фактический вычислительный бюджет; решить: хватает ли `1000 x 21` в отведённое время.
- [ ] **Step 4:** commit: `test: add correlation acceptance driver and scorer`.

### Task H5: Заморозка и прогон C5

**Files:**

- Create: отчёт `docs/statistical-validation-correlation-c5-v1.md` (после прогона); корпус и результаты вне git (`build/`)

- [ ] **Step 1:** условия входа: ADR 0022 `Accepted`; K1, K2, K3 влиты; оракул зелёный; замер H4 готов; протокол H0 заморожен (метод, каталог, генераторы, инвентарь, seeds, пороги, подсчёт).
- [ ] **Step 2:** `freeze`, затем `run` под `Global\ltv-heavy`, чекпоинты по сценариям; один технический повтор разрешён только для прерванных отчётов.
- [ ] **Step 3:** независимый пересчёт итога: полнота идентификаторов, числители и знаменатели, Уилсон, сопоставление `selected` и опубликованных находок.
- [ ] **Step 4:** отчёт: все результаты, включая провалы; статус по сценарию отдельно; ограничение «синтетика, не гарантия для реальных данных». Если гейт провален: `USEFULNESS FAIL`, вопрос 9, новая версия протокола, без настройки по результату.
- [ ] **Step 5:** commit отчёта: `docs: record C5 correlation acceptance result`.

### Task U1: Минимум для демонстрации

**Files:**

- Modify: `ui/src/types.ts` (тип `CorrelationHeadlineSelectionEvidence`), `ui/src/shell/overview.ts:150` (пара попадает в обзор только при `selected`), `ui/src/shell/labels.ts` (фиксированная формулировка), `ui/src/AnalysisView.vue` (таблица гипотез с причиной недоступности словами)
- Test: `ui/e2e/overview.spec.ts` и `ui/e2e/diagnostics.spec.ts` (модульного тест-раннера в `ui/` нет, проверка идёт через Playwright)

**Interfaces:**

- Consumes: evidence `correlation_headline_selection` (v1 и v2; поля `representation`, `stage_count` необязательны).
- Produces: блок «Гипотезы для проверки» по формулировке раздела «Что выдать в минимуме для демонстрации».

- [ ] **Step 1: Failing test:** результат с двумя парами `CANDIDATE`, одна `selected=true`, другая `NOT_SELECTED` с `HOLM_NOT_REJECTED`: в обзоре одна позиция; в тексте нет слов «причина», «утечка», «из-за» (Review Focus 8).
- [ ] **Step 2:** `npx playwright test e2e/overview.spec.ts` в `ui/` (под мьютексом) — FAIL.
- [ ] **Step 3:** реализовать; метки только в `labels.ts`; доступность таблицы (`tabindex`, `scope`) по существующим правилам.
- [ ] **Step 4:** `npm run lint`, `npm run e2e` (под мьютексом), снимок экрана. Commit: `feat(ui): show only selected correlation hypotheses with honest labels`.

### Task C6: Theil-Sen и блочная перестановка (условно)

Не планируется. Входной критерий: C4 показал обнаружение связей через уровень ниже 900 из 1 000 отчётов и приращения несостоятельны, либо H5 провалил гейт шума на AR(1) и вопрос 9 решён в сторону усложнения метода. Выход: поправка к ADR 0022, новый план и новая заморозка; текущий план C6 не расписывает шаги, чтобы не создавать задачу без доказанной нужды.

## Самопроверка плана

- **Покрытие C1-C6:** C1 равно K1; C2 равно H2, H3, K2; C3 равно K3; C4 равно H1 (сценарии `P06`, `P07`), H3 (решение), ADR Д8; C5 равно H0, H4, H5; C6 условный Task.
- **Заглушки:** ожидаемые значения ручных рядов для K2 посчитывает H2 до реализации K2 и поэтому в плане они не выписаны; остальные шаги конкретны.
- **Согласованность типов:** `alpha` параметр `selectCorrelationHeadlines` (K1) используется в K2; поля выдачи `representation`, `source_cells`, `analysed_points`, `family_count`, `alpha` одинаковы в ADR Д7, K1, K2 и U1; `scenario_id` одинаковы в H0, H1, H4, H5.
- **Review Focus:** каждый пункт привязан к срезу и тесту.
- **Не проверено при составлении плана:** стоимость K2 на худшей форме, фактическая скорость внутрипроцессного раннера, прохождение гейта шума методом v2, применимость каталога по механизмам.

## Documentation impact

В этом PR: только план и ADR 0022 (Proposed); `CHANGELOG.md` не нужен, поведение продукта не меняется. В срезах: контракт `correlation-headline-selection.md`, пользовательская документация, `CHANGELOG.md` (K1, K2, U1), протокол и отчёты C5 как отдельные документы.
