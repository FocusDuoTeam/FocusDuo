# Передача backend Kotlin-разработчику

Согласованный протокол: [FOCUSDUO_CONTRACT_V1.txt](../FOCUSDUO_CONTRACT_V1.txt). JSON fixtures в [contracts/fixtures](../contracts/fixtures/) сохранены без изменений. Машиночитаемая REST-спецификация: [openapi.yaml](../contracts/openapi.yaml). Ручные запросы: [requests.http](../contracts/requests.http). Запуск и переменные окружения: [backend/README.md](../../backend/README.md).

## Адреса и авторизация

Локально REST работает по `http://127.0.0.1:8090/api/v1`, raw JSON WebSocket — по `ws://127.0.0.1:8090/ws/rooms/{roomId}`. Для работы на двух ПК используйте один сервер: задайте ему `SERVER_ADDRESS=0.0.0.0`, обоим клиентам — его LAN-адрес. Порт 8090 должен быть доступен в локальной сети. Удалённый доступ — HTTPS/WSS.

Зарегистрируйте `alice_demo` и `bob_demo` через `/auth/register`; для локального теста можно использовать пароль `focusduo-demo-password`. Входящие поля: `{username,displayName,password}`. Реальные пользователи и токены не зашиты в код. Токен из fixture является только примером.

`AuthSession` содержит `{user,accessToken,expiresAt}`. Токен непрозрачный, не JWT. Передавайте `Authorization: Bearer <accessToken>` и в REST, и в HTTP-handshake WebSocket. Kotlin HTTP/WS-клиент должен поддерживать собственные заголовки handshake. Cookie, токен в URL, STOMP и SockJS не используются. Пароль и токен клиент хранит только в памяти. После перезапуска — новый login, затем `GET /rooms/current`; logout отзывает текущую сессию, но не закрывает комнату.

## Команды, повторы и revision

Все команды изменения комнаты возвращают `200 RoomSnapshot`. Отправляйте UUID в `Idempotency-Key`, а для существующей комнаты — целочисленный `X-Room-Revision` последнего принятого snapshot. Create/join не требуют revision. GET/auth/history не требуют ни одного из этих заголовков. Не добавляйте `expectedRevision` в JSON или `If-Match`.

После неоднозначного сетевого сбоя повторяйте тот же method/path/body, тот же ключ и **исходную** revision. Успешный результат хранится в БД вместе с изменением; повтор возвращает его без второго эффекта, включая одновременный повтор. Иной запрос с этим ключом даёт `409 IDEMPOTENCY_KEY_REUSED`. Сохранённый ответ может иметь старую revision — применяйте обычные правила сравнения, не откатывайте UI.

`409 REVISION_CONFLICT` содержит свежий snapshot для участника комнаты. Примените его и предложите пользователю повторить действие. Это новое действие получает новый ключ. Остальные ошибки имеют `snapshot=null`; формат всегда `{code,message,requestId,fieldErrors,snapshot}`. Необходимость повторного входа определяется `401`, а не разбором текста `message`.

## WebSocket и восстановление

Первое сообщение после соединения — полный снимок:

```json
{"type":"ROOM_SNAPSHOT","data":{"roomId":"...","revision":1,"serverNow":"..."}}
```

Это сокращённая иллюстрация envelope; полный JSON — [room-event.json](../contracts/fixtures/room-event.json). Последующие события используют тот же envelope и полный DTO после commit. Команды отправляйте через REST, не через JSON WebSocket. Секундных событий нет; ping/pong — стандартные control frames.

Сравнивайте revision внутри одного roomId: меньшую игнорировать, равная не откатывает данные, более свежий `serverNow` при равной версии может уточнить отсчёт. Игнорируйте поздние кадры предыдущего соединения/комнаты. При reconnect дождитесь первого полного snapshot, прежде чем разрешать изменения. Несколько сокетов пользователя не создают новых членств.

Сокет истёкшей или отозванной сессии закрывается с кодом `4401`; выполните login снова. Logout/потеря сети/закрытие клиента не закрывают комнату. Команда close от любого участника освобождает обоих, сохраняет закрытый snapshot и историю, отправляет закрытый snapshot и завершает подключения. Сохранённое членство позволяет читать CLOSED-комнату, но изменения запрещены.

## Таймер и история

Владелец управляет настройками и таймером; каждый участник — только своей целью и задачами. FOCUS требует двух участников. BREAK запускается вручную после последнего `COMPLETED FOCUS`. Таймер адресуется конкретным roundId; поздняя команда старого раунда получает `ROUND_NOT_CURRENT`.

RUNNING: отсчёт из `serverNow`/`endsAt` и монотонных часов клиента. PAUSED: `endsAt=null`, `remainingMs` и `activeElapsedMs` заморожены. После resume `startedAt` сохраняется. На wake/reconnect обновите snapshot; достижение нуля на клиенте не создаёт историю.

COMPLETED/CANCELLED: `endsAt=null`, `remainingMs=0`, `endedAt` заполнен. `activeElapsedMs` не включает паузы; при досрочном finish меньше полной длительности. Автозавершение: `COMPLETED/ELAPSED`, `endedAt` равен сроку даже после позднего восстановления сервера. Ручное: `COMPLETED/MANUAL`. Close активного раунда: `CANCELLED/ROOM_CLOSED`. Отмена не считается успешным фокусом. Уже завершённый раунд при close не переписывается.

История фиксирует копии целей и задач на момент конца, неизменяемые последующими правками. В `HistoryParticipant` есть total/completed counts. `/history` возвращает максимум 50 собственных результатов по `endedAt DESC` и стабильному roundId. `/history/{roundId}` доступен только участнику этого раунда.

## Проверки и ограничения передачи

В первом этапе на Windows с Java 21 выполнен `mvnw.cmd verify`: **40 тестов прошли, 0 failures, 0 errors, 0 skipped**. Из них 27 unit/auth/fixture/timer-тестов и 13 интеграционных: `BackendIT` — 6, `DomainPersistenceIT` — 6, `RestartIT` — 1. Интеграционные тесты использовали отдельный нативный PostgreSQL 17.11 на `127.0.0.1:55434` через `FOCUSDUO_TEST_DB_*`. Maven Wrapper 3.3.4 запускает Maven 3.9.11 с закреплённым SHA-256 дистрибутива.

Проверены HTTP/WS-сценарий двух пользователей, pause/resume/finish/BREAK/close, права доступа, logout/истечение сессии, reconnect, неизменяемая история, повторы ключей и конкурентные команды. Отдельные тесты проверяют ограничения PostgreSQL, гонки pause/close/истечения, сохранение автозавершения при конфликте и восстановление просроченного/приостановленного раунда после закрытия и повторного создания Spring-контекста с той же БД. Временные сценарии используют управляемые часы. Отчёты: `backend/target/surefire-reports` и `backend/target/failsafe-reports`.

OpenAPI проверен по официальной JSON Schema OpenAPI 3.1: 20 уникальных операций, все 199 внутренних ссылок разрешаются, все 10 исходных fixtures соответствуют описанным JSON Schema DTO. YAML Compose и backend CI синтаксически разобран; это не заменяет фактический запуск Docker/CI.

Отдельно запущен собранный `focusduo-backend-0.1.0.jar` на `127.0.0.1:8090` с этой изолированной PostgreSQL. HTTP-проверка прошла: регистрация двух пользователей → create/join → FOCUS → создание и отметка задачи → pause/resume → finish → сохранённая история → close → logout. После проверки процесс остановлен. В PowerShell-запросах явно задавался `Content-Type: application/json; charset=utf-8`.

Первый PR объединён в `main` (`462b827`); [GitHub CI первого этапа](https://github.com/KDvibers/FocusDuo/actions/runs/37818507362) успешно выполнил стандартный Testcontainers-прогон. Это подтверждение первой версии backend, отдельно от проверок текущего второго этапа.

В Windows Docker-команды отсутствуют, но для второго этапа подготовлен WSL `Ubuntu`: Ubuntu 26.04, Docker 29.1.3, Compose 2.40.3, Java 21.0.12.1. Docker запускается под `root`; группы пользователя не менялись. Linux-wrapper требует `curl`/`wget` и `unzip` для закреплённого ZIP с SHA-256. Порядок запуска с существующим `.env` описан в [backend README](../../backend/README.md).

В ветке `feat/backend-stage-2` подтверждён запуск Compose-проекта `focusduo-stage2` с новым volume: PostgreSQL 17.11 прошёл healthcheck. Собранный Linux JAR и [Python HTTP smoke](../../backend/scripts/README.md) прошли все 8 этапов `PASS`. Отдельно все 8 этапов прошли из Windows с Python 3.14 через `http://127.0.0.1:8090`; WSL JAR запущен с `-Djava.net.preferIPv4Stack=true`, устраняющим timeout Windows-клиента на IPv6-mapped socket. Проверены полный сценарий двух пользователей, идемпотентные повторы, история после последующих правок, BREAK/close и отзыв сессий. Доступ с другого ПК этим прогоном не подтверждён.

После проверок тестовые серверы остановлены; временные контейнер, сеть и volume Compose-проекта `focusduo-stage2` удалены.

Полный WSL `verify` завершился `BUILD SUCCESS`: **53 теста — 27 unit + 26 integration, 0 failures, 0 errors, 0 skipped**. PostgreSQL запущен через Testcontainers. Нативный Windows `mvnw.cmd verify` с Java 21 также прошёл **все 53 теста, без failures/errors/skips**; он использовал отдельную БД `focusduo_stage2_test` на PostgreSQL 17.11 через WSL Compose, порт 5434 и `FOCUSDUO_TEST_DB_*`. Testcontainers запускался в Linux-прогоне; Windows-прогон использовал внешнюю БД. Активный WSL-вызов удерживал БД доступной на время Windows-проверки.

Mockito agent подключается при старте тестовых JVM. Исправлен обход строгой UUID-проверки при привязке Spring MVC: сокращённые UUID теперь возвращают `400 VALIDATION_ERROR`. WS-регрессия ожидает завершения отправки pong, исключая гонку самого тестового клиента. Согласованные контракт и fixtures, а также `desktop/` не изменены. CI дополнен проверкой наличия инструментов, Compose и smoke; удалённый запуск новой ветки ожидает push владельца и PR-проверок.

Kotlin-клиент и межкомпьютерная интеграция пока отдельно не проверены. Для первого совместного прогона: register двух пользователей → create → join → открыть два WS → FOCUS → изменить задачу → pause/resume → reconnect → finish → проверить одну историю обоим → BREAK → close.

Один процесс backend обслуживает WS-соединения. Горизонтальное масштабирование событий потребует общей шины доставки. Пагинация истории, отдельное presence/измерение внимания, смена партнёра, password reset и production TLS deployment не входят в v1.

Репозиторий: [KDvibers/FocusDuo](https://github.com/KDvibers/FocusDuo). Первый этап объединён. Второй готовится в `feat/backend-stage-2` → `main`; он закрывается после review и согласованных проверок отдельного PR. Push выполняет владелец проекта, затем можно открыть PR. Третий этап — совместная приёмка с Kotlin. Подробности: [ROADMAP.md](ROADMAP.md), [описание PR второго этапа](PR_STAGE_2.md). Автоматические push и merge не выполняются.
