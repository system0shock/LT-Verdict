// Строки вкладки «История» (срез R7 истории релизов, ADR 0019): список релизов протокола, сохранение анализа как релиза
// и действия строки. Коды причин и ошибок, которых нет в таблицах ниже, выводятся как есть.
import { BASELINE_ERROR_LABELS } from './labels'

export const HISTORY_LABELS = {
  title: 'История релизов',
  intro: 'Диагностическая запись о релизах протокола. Метрики берутся из сохранённых анализов: в записи релиза их нет, поэтому у части релизов чисел может не быть.',
  seriesLabel: 'Показанный протокол',
  loading: 'Загрузка истории…',
  requestFailed: 'Не удалось выполнить запрос истории.',
  noReleases: 'Релизов этого протокола нет.',
  noSeries: 'Релизов пока нет. Откройте анализ и сохраните его как релиз ниже.',
  corrupt: (count: number) => `История показана не полностью: повреждённых записей релизов: ${count}.`,
  tableCaption: (series: string) => `Релизы протокола «${series}», новые сверху`,
  regionLabel: 'Таблица релизов протокола',
  heads: {
    release: 'Релиз',
    started: 'Тест начат',
    verdict: 'Вердикт',
    profile: 'Профиль',
    p95: 'p95 отклика, мс',
    errors: 'Доля ошибок',
    rps: 'Пропускная способность, зпр/с',
    actions: 'Действия',
  },
  noProfile: 'не заявлен',
  noValue: '—',
  // Почему у релиза нет чисел динамики.
  noNumbersTruncated: 'Нет чисел: превышен предел сканирования истории',
  noNumbersOldRules: 'Нет чисел: анализ создан по другим правилам',
  noNumbersMissing: 'Нет чисел: анализ не найден',
  noNumbersCorrupt: 'Нет чисел: анализ повреждён',
  noNumbersFailed: 'Нет чисел: не удалось получить динамику',
  open: 'Открыть',
  makeBaseline: 'Сделать baseline',
  compare: 'Сравнить',
  showAll: 'Показать все релизы',
  showLatest: (count: number) => `Показать последние ${count}`,
  baselineBadge: 'baseline',
  baselineBadgeNote: 'Этот анализ закреплён как baseline своей серии.',
  analysisMissing: 'Анализ не найден',
  // Метки релизов могут совпадать: короткий идентификатор делает имена кнопок различимыми.
  actionAria: (action: string, label: string, arm: string | null, shortId?: string) =>
    `${action}: релиз ${label}${arm === null ? '' : `, плечо ${arm}`}${shortId === undefined ? '' : ` (${shortId})`}`,
  // Причины, по которым анализ релиза не может стать baseline (поле ineligible_reasons ответа сервера).
  reasons: {
    BASELINE_CANDIDATE_INVALID: 'Прогон разобран не полностью',
    BASELINE_CANDIDATE_INCOMPLETE: 'Анализ неполный',
    BASELINE_CANDIDATE_NOT_PASS: 'Вердикт не PASS',
    ANALYSIS_MISSING: 'Анализ не найден',
    ANALYSIS_CORRUPT: 'Анализ повреждён',
  } as Record<string, string>,
  saveTitle: 'Сохранить как релиз',
  saveHint: 'Откройте анализ (список слева или вкладка «Новый анализ»), чтобы сохранить его как релиз.',
  saveTarget: (analysisId: string) => `Будет сохранён открытый анализ ${analysisId.slice(0, 12)}.`,
  formSeries: 'Протокол (серия)',
  formSeriesHint: 'Название сценария и стенда, до 128 байт UTF-8. Релизы одной серии сравниваются между собой.',
  formLabel: 'Метка релиза',
  formLabelHint: 'Например, версия приложения, до 128 байт UTF-8. Метка попадает в экспорт динамики.',
  profileLegend: 'Профиль условий теста (необязательно)',
  profileHint: 'Что считается теми же условиями теста. Без заявленного профиля релизы нельзя назвать сопоставимыми по условиям.',
  profileFields: {
    scenario_mix: 'Сценарии и состав запросов',
    environment_dataset: 'Стенд и набор данных',
    load_model: 'Модель нагрузки',
    targets_stages: 'Цели и ступени',
    pacing: 'Паузы между запросами (pacing)',
    generator_limits: 'Ограничения генератора',
  },
  formNotes: 'Заметка',
  formNotesHint: 'Остаётся только в локальном хранилище: не экспортируется и не передаётся в ИИ-разбор. До 1024 байт.',
  tooLong: 'Одно из полей длиннее допустимого: метка, серия и поля профиля до 128 байт UTF-8, заметка до 1024 байт.',
  save: 'Сохранить как релиз',
  saving: 'Сохранение…',
  saved: (label: string) => `Релиз «${label}» сохранён.`,
  errors: {
    RELEASE_LIMIT_REACHED: (limit: number | null) => `Достигнут предел числа релизов${limit === null ? '' : `: ${limit}`}. Удалите ненужные релизы вне интерфейса.`,
    RELEASE_ANALYSIS_ALREADY_REGISTERED: () => 'Этот анализ уже входит в релиз. Один анализ входит ровно в один релиз.',
    RELEASE_TOO_LARGE: () => 'Запись релиза слишком большая: сократите метку, профиль или заметку.',
    RELEASE_ARM_CONFLICT: () => 'Анализы релиза относятся к одному плечу стенда: в релиз можно включить один анализ на плечо.',
    RELEASE_RESULT_TOO_LARGE: () => 'Результат анализа слишком большой, чтобы сохранить его как релиз.',
    RELEASE_ANALYSIS_NO_RUN_METADATA: () => 'У анализа нет сведений о времени теста: релиз сохранить нельзя.',
    RELEASE_FACTS_INVALID: () => 'Сведения анализа не подходят для записи релиза.',
    CORRUPT_RELEASE_REGISTRY: () => 'Каталог релизов повреждён: проверьте каталог данных и файлы в нём.',
  } as Record<string, (limit: number | null) => string>,
} as const

// Фраза словаря для кода ошибки API или null: тогда показывается сообщение сервера (английское).
export function historyErrorText(code: string, limit: number | null): string | null {
  return HISTORY_LABELS.errors[code]?.(limit) ?? BASELINE_ERROR_LABELS[code]?.(limit) ?? null
}
