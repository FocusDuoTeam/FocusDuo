# Второй PR backend

Репозиторий: [KDvibers/FocusDuo](https://github.com/KDvibers/FocusDuo). Ветка: `feat/backend-stage-2` → `main`, исходный commit `462b827`. PR открывается после push владельца; автоматический merge не выполняется.

## Заголовок

Проверить запуск backend через Docker и расширить регрессии контракта v1

## Описание

API теперь отклоняет сокращённые UUID вместо неявного дополнения недостающих разрядов при привязке Spring MVC. Например, `1-1-1-1-1` возвращает `400 VALIDATION_ERROR`. Дополнительные регрессии закрепляют границы контракта и воспроизводимый запуск собранного сервера: CI после Maven `verify` поднимает PostgreSQL через Compose, запускает JAR и проходит HTTP-сценарий двух пользователей. Ошибка готовности, протокола или результата завершает smoke ненулевым кодом; логи сервера/БД сохраняются вместе с тестовыми отчётами, временные процессы и Compose-ресурсы очищаются.

- Python smoke без сторонних пакетов проверяет create/join, FOCUS, задачи, pause/resume/finish, неизменяемую историю, BREAK/close, идемпотентные повторы с исходной revision и logout. Секреты не выводятся; случайные тестовые аккаунты и история сохраняются только в выбранной БД.
- Дополнительные PostgreSQL/HTTP/WS-регрессии проверяют лимит задач, последние 50 результатов и стабильный порядок, границы длительностей/текста, правила настроек и BREAK, строгие JSON/UUID, изоляцию данных, независимые сессии и control frames ping/pong.
- Строгий UUID editor через `@InitBinder` исключает неявный fallback Spring-конвертера. Проверка ping/pong ждёт завершения отправки control frame клиентом, чтобы последующие проверки не зависели от гонки в самом тесте.
- Mockito agent подключается при старте тестовых JVM через Maven Surefire/Failsafe: тестам не требуется динамический self-attach, который не сработал в WSL с Java 21. Windows-настройка временного каталога сохраняется.
- Инструкции включают Linux/WSL, существующий `.env`, требования `curl`/`wget` + `unzip`, Docker/Compose и отдельный запуск smoke. Закреплённая SHA-256-проверка Maven ZIP сохраняется. `desktop/`, согласованный контракт и исходные fixtures не меняются.

## Проверки

Базовая версия уже прошла [GitHub CI с Testcontainers](https://github.com/KDvibers/FocusDuo/actions/runs/37818507362). Это результат первого этапа, объединённого в `main`.

Полный `verify` в WSL завершился `BUILD SUCCESS`: **53 теста — 27 unit + 26 integration, 0 failures, 0 errors, 0 skipped**. Среда: Ubuntu 26.04, Docker 29.1.3, Compose 2.40.3, Java 21.0.12.1; integration tests использовали PostgreSQL через Testcontainers.

Нативный Windows `mvnw.cmd verify` с Java 21 также прошёл **53 теста, 0 failures, 0 errors, 0 skipped**. Его integration tests подключались через `FOCUSDUO_TEST_DB_*` к отдельной БД `focusduo_stage2_test` на PostgreSQL 17.11 в WSL Compose, порт 5434. WSL-вызов оставался активным на время проверки. Это проверка Windows JVM с внешней БД; Testcontainers подтверждён Linux-прогоном.

Compose-проект `focusduo-stage2` с новым volume успешно запустил PostgreSQL 17.11 и прошёл healthcheck. Собранный Linux JAR прошёл Python HTTP smoke: **все 8 этапов `PASS`**. Отдельно те же 8 этапов прошли из Windows с Python 3.14 по `127.0.0.1:8090`; проверенная WSL-команда использует `-Djava.net.preferIPv4Stack=true`. Проверены запуск, две сессии, комната, повтор запросов, FOCUS/задачи/pause/resume/finish, неизменность истории, BREAK/close и logout.

## Ограничения

Новый CI ветки второго этапа ещё не запускался. Kotlin-клиент и межкомпьютерная LAN-интеграция не проверены; это третий этап [ROADMAP.md](ROADMAP.md). Python smoke проверяет HTTP, а WS, управляемое время, конкурентность и восстановление проверяются Maven integration tests. Docker доступен в WSL под `root`; членство пользователя в группах не менялось. Этап принимается после отдельного PR, review и согласованных проверок; локальный успех не закрывает эту контрольную точку.
