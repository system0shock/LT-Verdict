- Исправлен запуск advisory AI с реальным gigacode CLI: бюджет
  `--max-tool-calls=1` позволяет единственный разрешённый `structured_output`
  вместо `FatalBudgetExceededError` при бюджете `0`. Исключения
  `read_file,edit,notebook_edit,run_shell_command` сохранены; один вызов
  неисключённого инструмента технически возможен, поэтому сохраняются
  `--bare --safe-mode` (кроме явного отключения `--bare` оператором) и
  `--approval-mode=default`: headless не может подтвердить вызов инструмента.
