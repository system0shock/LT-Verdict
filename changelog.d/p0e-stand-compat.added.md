- Локальный стенд демо: необязательный слой совместимости с платформенными профилями
  (срез P0e): конфигурация Prometheus с relabel cAdvisor и recording rules в форме
  контракта меток (`tools/demo-stand/platform/`), проверенные `promtool`, и
  конфигурация генератора профилей для подмножества сигналов (`cpu_limit_ratio`,
  `memory_limit_ratio`, `cpu_throttling`, `oom`). Стенд по умолчанию, ядро, API и
  схемы не менялись; `restarts` и `unavailable_replicas` на стенде не воспроизводятся.
  Настоящий cAdvisor на стенде не проверен.
