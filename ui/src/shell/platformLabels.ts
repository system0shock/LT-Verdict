// Строки карты «сервис x плечо» (платформа, срез P4b). Общего заголовка «все плечи» нет: плечи оцениваются независимо.
import type { MapCellState } from '../platformTypes'
import { pluralRu } from './labels'

export const PLATFORM_MAP_LABELS = {
  title: 'Где проблема',
  lead: 'Состояние каждого сервиса на каждом плече по платформенным проверкам политики. Плечи оцениваются независимо, общего вердикта нет.',
  limit: 'Карта показывает сервисы, у которых на плече есть платформенная проверка. Сервис из каталога политики без единой проверки она показать не может: его находит ядро (причина «ряд ресурса не найден»).',
  loading: 'Загружаю результаты плеч…',
  loadFailed: (arm: string, message: string) => `Результат анализа плеча ${arm} не загружен: ${message}`,
  empty: 'Ни на одном плече нет платформенных проверок: карту строить не из чего.',
  onlyProblems: 'Только проблемные',
  summary: (problems: number, total: number) =>
    `${problems} из ${total} ${pluralRu(total, 'сервиса', 'сервисов', 'сервисов')} требуют внимания (нарушение или нет вердикта), остальные в норме или не проверялись.`,
  noProblems: 'Проблемных сервисов нет.',
  region: 'Карта сервисов по плечам',
  service: 'Сервис',
  arm: (arm: string) => `Плечо ${arm}`,
  noAnalysis: 'нет анализа',
  duplicates: (arm: string, count: number) => `У плеча ${arm} анализов: ${count}; показан выбранный или первый в списке.`,
  failedRules: (ids: string[]) => `нарушены: ${ids.join(', ')}`,
  states: {
    FAIL: { mark: '✕', text: 'Нарушение' },
    NO_VERDICT: { mark: '?', text: 'Нет вердикта' },
    PASS: { mark: '✓', text: 'В норме' },
    NOT_CHECKED: { mark: '–', text: 'Не проверялось' },
  } satisfies Record<MapCellState, { mark: string; text: string }>,
}
