# Changelog

Все значимые пользовательские изменения LT Verdict фиксируются в этом файле.
Формат основан на Keep a Changelog, версии следуют Semantic Versioning.

## [Unreleased]

### Added

- Открытие сохранённых analyses после reload UI, графики RPS/errors/P95 с
  сохранением gaps и пагинацией normalized data.
- Экспорт сохранённого результата в canonical JSON и автономный HTML через UI
  и `ltv report`, без повторного анализа и изменения вердикта.
- Принят local-first baseline PRC/PRD v0.6 и план MVP по Slices 0–10.
- Добавлен минимальный Slice 0: два контракта, два fixtures, offline verifier и
  CI gate.
- Зафиксированы ADR публичных контрактов и отображение Slice 0 на Stage 0.
- Регламент разработки и правила работы AI-агентов.
- Добавлен local-only Slice 1: Web UI и CLI для потокового анализа JMeter JTL
  CSV/XML и Gatling logs, deterministic metrics/verdict, strict `policy.v1`,
  immutable RunBundle, light/dark themes и offline/runtime quality gates.

### Fixed

- Устранено переполнение памяти при завершении анализа больших JTL со
  множеством sparse one-second buckets.
