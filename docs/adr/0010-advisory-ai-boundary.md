# ADR 0010: граница advisory AI

Дата: 2026-09-06.

Статус: accepted.

## Контекст

Advisory AI запускается только явно и после сохранённого deterministic analysis.
Он не может менять verdict или immutable analysis bundle. Safe flags Qwen Code
не заменяют filesystem/network isolation. Пользователь разрешил upstream Qwen
Code 0.21.1 как замену GigaCode с GigaCode naming на уровне wrapper.

## Решение

1. Backend передаёт только allowlisted поля проверенного
   `analysis-result.json`; credentials, profiles, raw SQL/log bodies и файлы
   repository исключены.
2. Model output проходит closed validation и проверку каждой evidence reference
   по фактически переданному набору.
3. Advice публикуется вне `analyses/<analysis_id>` через существующий
   data-directory operation lock, owned-path checks и atomic move. Запись
   привязана к SHA-256 проверенного analysis manifest.
4. Первый контракт хранит одно immutable advice на analysis. Повторный generate
   читает его без model request; regeneration не входит в первую поставку.
5. Единственный runner — `gigacode-qwen-code/0.21.1`; provider registry и новая
   dependency не вводятся. Exact model — `deepseek-v4-flash-0731`, endpoint —
   `https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions`.
6. Qwen запускается только во внешней OS sandbox с ephemeral state, очищенным
   environment, read-only evidence, без repository/user-home/Docker-socket
   mounts и с endpoint-only egress. До фактического probe состояние —
   `UNAVAILABLE`; конфигурационный флаг не может объявить boundary проверенной.
   Ordinary tools одновременно удаляются через exact `--exclude-tools` и
   блокируются `--max-tool-calls=0`; одного budget guard недостаточно.
7. Timeout, process failure, output overflow, invalid contract и неизвестные
   references возвращаются как bounded fail-soft outcome. Raw stdout/stderr и
   secret values не сохраняются.

## Последствия

- Deterministic analysis bytes/hash не меняются при любом AI outcome.
- Fake runner проверяет contract/storage/failure handling, но не production
  isolation и не live model capability.
- Для live ModelStudio используется узкий relay с фиксированным destination,
  model и одним upstream request; универсальный proxy framework не создаётся.
- Documentation impact: добавлены contract, prompt, design decision и plan;
  user/API integration выполняется корневым треком.
- Согласие на отправку и выбор модели (ADR
  [0023](0023-advisory-ai-consent-removal-and-model-config.md), Accepted,
  2026-10-05): текст этого ADR не меняется. Владелец решил убрать согласие
  полностью и всегда и принял риск отправки evidence на endpoint из
  конфигурации; п. 5 (единственный runner, exact model и endpoint) дополняется:
  модель и endpoint задаются файлом конфигурации (по умолчанию прежние), runner
  остаётся один. П. 4 (один immutable advice на analysis) остаётся в силе и для
  выбора модели.

## Поправка production readiness от 2026-09-22

Разрешённая production topology закрепляет уже проверенный локальный image
`mcr.microsoft.com/playwright/mcp@sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2`.
Qwen работает только во внутренней Docker network, а credential получает только
отдельный relay через env-file. Qwen, argv, advice и provenance credential не
содержат. Runtime не загружает image или Qwen package автоматически и не
повторяет model request, кроме одного повтора при ошибке схемы (ADR 0021, Д2). Точная установка и fail-soft состояния описаны в
`docs/user/advisory-ai.md`.
