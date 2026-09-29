# Onboarding skill: минимальная подготовка

Основание: delta design §21. Поставка: repository-local skill и stdlib wrapper для allowlisted view, read-only audit, reviewable manifest proposal и отдельного подтверждённого apply. Ни модель, ни код репозитория wrapper не запускает. Автоматический AI audit подключается только через runtime с доказанной изоляцией; без него wrapper остаётся deterministic preparation.

REQUESTED: заранее подготовить skill адаптации тестов.
REQUIRED TO ACHIEVE IT: обнаружение JMeter/Gatling/build/Jenkins/artifacts, L0–L3 checklist, deny/ignore/secret filtering, hashed view, manifest-only proposal и exact-hash confirmed apply с неизменными test files.
NOT REQUIRED: переписывать JMeter/Gatling, менять load semantics, устанавливать skill глобально или запускать внешнюю модель.
EXPECTED FILES TO CHANGE: skills/lt-verdict-onboard-test/SKILL.md, tools/onboard_test.py, tools/test_onboard_test.py, docs/user/test-onboarding.md.

Без language-aware Gatling parser изменения кода запрещены, а не обрабатываются regex. Этот bounded этап не заявляет поддержку автоматической модификации произвольного тестового кода. Внешний JSON-compatible ltv-run.yaml — первый разрешённый вариант; существующий manifest не перезаписывается автоматически. Proposal не является каноническим run.v1 и содержит только сообщённые пользователем metadata, без выдуманных результатов.

Проверки: secret/ignored/symlink исключение, неизменность source, отказ stale base/hash/confirmation, отдельный output вне repository, применение только нового manifest. Python unittest, без внешних запросов.
