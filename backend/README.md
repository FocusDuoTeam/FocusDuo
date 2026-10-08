# FocusDuo Java backend

Java 21, Spring Boot 3.5.16, PostgreSQL 17, Flyway и Maven 3.9.11. Maven Wrapper 3.3.4 находится в этой папке; отдельная установка Maven не требуется. Первый запуск wrapper и сборка требуют доступа к Maven Central. SHA-256 дистрибутива Maven закреплён в `.mvn/wrapper/maven-wrapper.properties`.

## Запуск в PowerShell

Из корня репозитория, с установленными Java 21 и Docker с Linux containers:

```powershell
Copy-Item .env.example .env
notepad .env
# Замените DB_PASSWORD в .env своим локальным паролем перед продолжением.
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
docker compose up -d --wait db
Set-Location backend
.\mvnw.cmd spring-boot:run
```

Повторный запуск: загрузите переменные из уже существующего `.env`, запустите БД, затем `spring-boot:run`. Не копируйте пример поверх изменённого `.env` повторно.

## Запуск в bash

```bash
cp .env.example .env
# Отредактируйте .env и замените DB_PASSWORD своим локальным паролем.
set -a
. ./.env
set +a
docker compose up -d --wait db
cd backend
bash ./mvnw spring-boot:run
```

`.env` автоматически читает Docker Compose, но не Java-процесс: поэтому команды выше экспортируют переменные в текущую оболочку. Формат примера использует простые `KEY=value`; если меняете пароль, избегайте синтаксиса оболочки либо задавайте переменные непосредственно в оболочке.

Первый запуск применяет Flyway-миграции. Hibernate проверяет схему, а не создаёт её. Compose публикует только `127.0.0.1:5434`, создаёт базу `focusduo` и собственный volume `focusduo_focusduo_data`. Прочие локальные базы не используются. Для остановки: `docker compose stop db` из корня; данные сохраняются. Изменение пароля в `.env` не изменяет пароль в уже инициализированной базе.

## Переменные окружения

| Переменная | Значение по умолчанию | Назначение |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5434/focusduo` | JDBC PostgreSQL |
| `DB_USERNAME` | `focusduo` | Пользователь БД |
| `DB_PASSWORD` | Обязательна | Пароль БД; не хранить в Git |
| `SERVER_ADDRESS` | `127.0.0.1` | Адрес привязки HTTP/WS |
| `SERVER_PORT` | `8090` | Порт HTTP/WS |
| `FOCUSDUO_SESSION_TTL` | `PT24H` | TTL bearer-сессии, ISO 8601 duration |

Для уже установленного PostgreSQL 17 можно создать **отдельную** базу FocusDuo и роль, а затем задать `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Docker в таком случае нужен только для стандартного Testcontainers-прогона, не для работы приложения.

## Подключение клиента

- REST: `http://127.0.0.1:8090/api/v1`.
- WebSocket: `ws://127.0.0.1:8090/ws/rooms/{roomId}`.
- Для обоих транспортов: `Authorization: Bearer <accessToken>`.
- Команды комнаты: `Idempotency-Key: UUID`; кроме create/join также `X-Room-Revision: число`.
- Вход: `POST /api/v1/auth/register` или `/auth/login`; после входа `GET /api/v1/rooms/current`.

Тестовые пользователи не создаются автоматически. Для локального сценария зарегистрируйте `alice_demo` и `bob_demo`, например с displayName `Alice Demo` / `Bob Demo` и учебным паролем `focusduo-demo-password` (не используйте его вне локального теста). [requests.http](../docs/contracts/requests.http) содержит полный ручной сценарий. Токен из исходных fixtures искусственный и не даёт доступа к серверу.

Для двух компьютеров запустите сервер с `SERVER_ADDRESS=0.0.0.0` (PowerShell: `$env:SERVER_ADDRESS='0.0.0.0'`, bash: `export SERVER_ADDRESS=0.0.0.0`). Оба клиента используют LAN-адрес компьютера с Java, например `http://192.168.1.20:8090`; разрешите входящий TCP 8090 в локальной сети. БД оставьте на loopback. Для удалённого соединения настройте HTTPS/WSS с reverse proxy, поддерживающим WebSocket Upgrade. Сервер продолжает требовать bearer-авторизацию.

## Сборка и проверки

Из `backend/`:

```powershell
.\mvnw.cmd test             # unit-тесты и контрактные fixtures; Docker не нужен
.\mvnw.cmd verify           # также PostgreSQL Testcontainers integration tests; нужен Docker
.\mvnw.cmd package          # JAR с запуском unit-тестов
java -jar target/focusduo-backend-0.1.0.jar
```

В bash замените `.\mvnw.cmd` на `bash ./mvnw`. Переменные подключения к БД должны быть доступны процессу `java -jar`, как и при `spring-boot:run`.

На этой Windows-машине JDK не смог открыть selector, когда путь временной папки содержал короткое имя вида `THUNDE~1`. Windows-профиль Maven задаёт `jdk.net.unixdomain.tmpdir` для тестов и `spring-boot:run` автоматически. Если тот же сбой появляется при запуске JAR, укажите существующую папку полным путём без коротких имён:

```powershell
java -Djdk.net.unixdomain.tmpdir=C:/Daniyar/MyProjects/FocusDuo/backend/target -jar target/focusduo-backend-0.1.0.jar
```

Путь должен соответствовать вашей рабочей копии; это настройка временной папки JDK, авторизация приложения от неё не меняется.

`verify` запускает `*IT` через Maven Failsafe. По умолчанию тесты поднимают настоящий PostgreSQL 17.11 через Testcontainers. Отсутствие Docker является ошибкой такого прогона, а не тихим пропуском тестов. CI на GitHub исполняет именно `verify` на Ubuntu с Docker. H2 не используется.

Дополнительный локальный режим допускает выделенный PostgreSQL 17 без Docker:

```powershell
$env:FOCUSDUO_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55434/focusduo_test'
$env:FOCUSDUO_TEST_DB_USERNAME='focusduo_test'
$env:FOCUSDUO_TEST_DB_PASSWORD='<пароль отдельной тестовой БД>'
.\mvnw.cmd verify
```

В bash задайте те же три переменные через `export`. Этот режим предназначен только для отдельной тестовой БД: тесты применяют миграции и изменяют данные с управляемыми часами. Не указывайте здесь рабочую БД. Удалите эти переменные перед проверкой Testcontainers. Такой прогон проверяет настоящую PostgreSQL-схему и транзакции, но не доказывает работу Docker/Testcontainers.

Результаты текущего выполнения и ограничения фиксируются в [HANDOFF.md](../docs/backend/HANDOFF.md). Отчёты Maven находятся в `target/surefire-reports` и `target/failsafe-reports`.

## Устройство и границы MVP

PostgreSQL хранит пользователей, хеши токенов, комнаты и членства, задачи/цели, временные опоры раундов, историю и успешные результаты идемпотентных запросов. REST отдаёт DTO, не JPA-сущности. Один внедряемый `Clock` позволяет проверять время без ожидания реальных минут. После восстановления процесса просроченные раунды завершаются через общий механизм актуализации.

WebSocket передаёт полные снимки после commit, без STOMP/SockJS и без секундных JSON-тиков. HTTP выполняет команды; WebSocket служит для серверных событий. Подробнее о revision, reconnect, terminal-состояниях и неизменяемой истории — в [HANDOFF.md](../docs/backend/HANDOFF.md).

MVP рассчитан на один процесс приложения. Для нескольких экземпляров потребуется межпроцессная доставка событий WebSocket; БД остаётся источником истины, но локальный реестр соединений не является распределённой шиной. История выдаёт последние 50 элементов без пагинации. Нет смены партнёра/leave, восстановления пароля, очистки старых сессий или гарантии доставки события клиенту после сетевого сбоя; reconnect получает актуальный полный снимок.

Версии и инструменты проверены по [системным требованиям Spring Boot](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [документации Maven Wrapper](https://maven.apache.org/tools/wrapper/), [release notes PostgreSQL 17.11](https://www.postgresql.org/docs/17/release-17-11.html) и [официальным Docker tags PostgreSQL](https://github.com/docker-library/official-images/blob/master/library/postgres). Зафиксированные зависимости находятся в `pom.xml`, wrapper properties и `compose.yaml`.
