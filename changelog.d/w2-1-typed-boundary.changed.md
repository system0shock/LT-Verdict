- Внутренний рефакторинг (W2.1, первый срез): `analysis-result` и `analysis-identity`
  собираются из типизированных `@Serializable`-моделей вместо ручных `JsonObject`, а
  `AdvisoryAi` берёт допустимый набор ключей результата из модели и проверяет
  `schema_version` по набору поддерживаемых версий. Байты `analysis-result.json` и
  `identity.json`, `analysis_id`, формат CLI и HTTP, схемы и коды отказа ИИ-разбора не
  меняются; это подтверждено побайтовым сравнением со старыми построителями и с файлами,
  снятыми до рефакторинга. Добавлены `ui/src/types.generated.ts` (генерируется из Kotlin,
  проверяется тестом и `npm run test:contracts`) и фикстура-bundle текущей версии.
  Не входит: типизация findings и evidence, ответов HTTP и saved-analytics; рукописный
  `ui/src/types.ts` не менялся.
