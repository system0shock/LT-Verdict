# Методы статистического анализа: выбранное и отложенное

Решение пользователя после анализа Astra (reasoning high), 2026-09-05.
Цель — полезные выводы для человека и агента, а не коллекция алгоритмов.

## Ближайший срез

- Median/MAD относительно explicit reference того же режима, абсолютный порог
  и длительность; соседние отклонения объединяются в эпизод. MAD=0 не заменяется
  epsilon. SLA и статистическая необычность — разные утверждения.
- Spearman внутри заданных окон; partial rank association только с явными
  controls. Achieved RPS не является автоматически экзогенным контролем.
- Дельты двух прогонов по явно сопоставленным окнам: load и resources,
  абсолютные/относительные изменения и заранее выбранная материальность.
- Bounded lag profile — диагностическая деталь; gaps и неизвестные часы
  ограничивают интерпретацию. Uncertainty: `NOT_ESTIMATED`, без p-values.

## Кандидаты следующих слайсов

| Метод | Полезный вопрос / условие включения | Ограничения и шум |
| --- | --- | --- |
| Pearson | Нужна оценка именно линейной зависимости/модели в исходных единицах | Не дополнительный голос за Spearman; чувствительность к выбросам и общему тренду |
| Kendall tau-b | Нужна проверка ранговой согласованности, особенно при ties | Добавлять при конкретном отличии пользы; не включать три коэффициента в сводку |
| PELT / change-point segmentation | Ручных ступеней недостаточно; нужны границы режимов | Penalty/minimum segment length, автокорреляция и null-калибровка; худший случай O(n²) |
| Кусочная load-response regression | Где начинается knee, как меняется отклик на заданную нагрузку | Диагностический knee не заменяет verified SLA capacity |
| Block bootstrap effect intervals | Нужна временная неопределённость эффекта в сохранённых рядах | Не оценивает межпрогонную изменчивость по двум runs; нужны block-size sensitivity и calibration |
| Block permutation + Holm | Нужны обоснованные inferential claims для выбранных pairs/lags | Fitted residuals и nonstationarity; Holm не исправляет неправильный null; учитывать весь поиск лагов |
| TTS | Нужен тест stationary unconditional dependence | Гарантии нельзя переносить на conditional fitted residuals автоматически |
| Mutual information / distance correlation | Есть практически важные немонотонные связи, пропускаемые ranks | Estimator bias, sample size, autocorrelation, multiple testing; сначала доказать пользу на fixtures |
| Granger / VAR | Нужна дополнительная прогнозная информация при достаточной длине рядов | Не causal proof; режимы, стационарность, лаги и omitted variables |
| Historical load-conditioned models | Есть повторные независимые runs и устойчивая метаинформация | Внутрипрогонные точки не заменяют независимые повторения |

Не добавлять ансамбли/единый confidence score без отдельного доказательства
полезности. Transaction-specific и resource/resource edges расширяют область
анализа, но не требуют другого коэффициента ради самого коэффициента.

## Проверка полезности

Измерять ложные главные находки на уровне целого synthetic report, включая
разрешённое число метрик/пар/лагов. AR(1), тяжёлые хвосты, общий target ramp,
смены режима, gaps, редкие ошибки, разные смеси ступеней. Отдельные seeds для
настройки и приёмки; вместе с null rate измерять обнаружение внедрённых эффектов.
Для будущего inferential gate кандидат: 1000 reports на сценарий, false-headline
rate <=5%, верхняя 95% binomial bound <=7%. Это инженерная цель для указанного
набора сценариев, не гарантия для любого стенда; пока не заявлена достигнутой.

Human summary объединяет эпизоды. Machine evidence сохраняет observations,
settings, source refs, ограничения, tested/evaluable/suppressed counts.
`NOT_EVALUATED`, `INSUFFICIENT_DATA`, `DESCRIPTIVE`, `NO_MATERIAL_CHANGE` и
`CANDIDATE` различаются. Отсутствие finding не означает здоровье.

Источники: [NIST modified Z](https://www.itl.nist.gov/div898/handbook/eda/section3/eda35h.htm),
[Spearman](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.spearmanr.html),
[Gatling workload models](https://docs.gatling.io/testing-concepts/workload-models/),
[Kalibera/Jones effect intervals](https://www.cs.kent.ac.uk/pubs/2012/3233/),
[TTS](https://journals.plos.org/plosbiology/article?id=10.1371/journal.pbio.3002758),
[PELT](https://arxiv.org/abs/1101.1438).
