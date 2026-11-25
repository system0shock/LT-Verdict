- Добавлен локальный stdio MCP-прокси `ltv mcp` (`--url http://127.0.0.1:<port>`
  или `--data-dir <path>` с опциональным `--connections`) с инструментами
  `analyze`, `get_result`, `list_incidents`, `list_transactions`,
  `validate_policy`, `compare`, `get_resource_series`, `probe_source` и
  `save_advice`, а также skill `skills/lt-verdict-mcp-analysis/SKILL.md` и
  ветка `provenance_kind: "self-reported"` в `ai-advice.v1` /
  `PUT /api/runs/{runId}/analyses/{analysisId}/advice` без изменения
  детерминированного вердикта и с сохранением заморозки
  `ModelStudioAdvisoryRunner`.
- Добавлена передача release provenance (`--jenkins-build` и `--commit` в
  `ltv analyze`, поля `jenkins_build` и `commit` в `POST /api/jobs` и MCP
  `analyze`) с сохранением в необязательных полях `identity.json` и выводом в
  `GET .../analytics` и экспорты без изменения ключа сопоставимости и
  байтовой совместимости с `v0.2.0`.
