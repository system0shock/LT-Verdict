- При пустом dashboard uid панель Grafana показывает подсказку «Укажите dashboard
  uid в поле Dashboard UID» и не позволяет запросить ссылку или PNG; заполненное
  значение по-прежнему задаётся в форме, отдельно от `datasource_uid` профиля.
  API и схемы не изменены: внутренняя валидация backend отклоняет пустой uid с кодом
  `GRAFANA_REQUEST_INVALID`.
