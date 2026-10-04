- Онлайн-источники по mTLS: в профиль HTTP-источника (`source-connections.v1`-`v3`)
  добавлен необязательный объект `tls` (`ca_file`, `client_keystore_file`,
  `client_keystore_password_env`). Клиентский сертификат (PKCS12) и доверенные CA
  (PEM) задаются на профиль, у каждого профиля с `tls` свои `SSLContext` и
  соединения, проверка имени узла не отключается, пароль берётся только из
  переменной окружения. Работает и для `grafana_proxy` (в том числе рендер
  панели). Новые причины отказа источника: `SOURCE_TLS_CONFIG_INVALID`,
  `SOURCE_TLS_CLIENT_CERT_EXPIRED`, `SOURCE_TLS_HANDSHAKE_FAILED`. Профили без
  `tls`, API, схемы результата и ядро не менялись; ротация сертификатов требует
  перезапуска backend. Решение: ADR 0025 (`docs/adr/0025-mtls-on-source-connector.md`),
  инструкция: раздел «mTLS» в `docs/user/online-sources.md`.
