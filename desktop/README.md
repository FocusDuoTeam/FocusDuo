# FocusDuo Desktop

Kotlin/JVM + Compose Desktop для Windows. Самостоятельная сборка: backend не нужен для сборки или запуска этого каталога.

## Текущий результат: этап 1 из 10

Запускаемый каркас со светло-зелёным оформлением, системным окном, явным выбором деморежима и локальной формой имени/цели. Это первый небольшой этап, а не готовая комната: `FakeRepository`, задачи, таймер и остальные экраны запланированы в следующих этапах. Сетевых запросов сейчас нет.

Демо включается только флагом `--demo` или кнопкой на стартовом экране. В демо постоянно видна соответствующая пометка. Данные формы находятся в памяти и сбрасываются при закрытии приложения.

- [Этапы и критерии готовности](docs/ROADMAP.md)
- [Текущее состояние и передача](docs/HANDOFF.md)
- [Утверждённый светлый макет](docs/design/light-green-reference.png)
- [Исходное техническое задание](docs/reference/PROMPT_DESKTOP_KOTLIN.txt)
- [Справочная копия контракта v1](docs/reference/FOCUSDUO_CONTRACT_V1.txt)

Последнее прямое уточнение пользователя утверждает светлый макет с зелёными акцентами и круговым таймером. Оно заменяет тёмную тему в исходном ТЗ и общих `docs/design/`; общие файлы не изменяются.

## Запуск в PowerShell

Из корня репозитория:

```powershell
# Первый запуск скачивает зависимости. Скрипт ищет JDK 21 в FOCUSDUO_JAVA_HOME,
# JAVA_HOME, затем в пользовательской .jdks и по java из PATH.
powershell -ExecutionPolicy Bypass -File .\desktop\run.ps1 -Demo
```

`ExecutionPolicy Bypass` относится только к этому процессу PowerShell; скрипт не меняет системную политику или постоянные переменные среды. Без `-Demo` откроется экран явного выбора демо:

```powershell
powershell -ExecutionPolicy Bypass -File .\desktop\run.ps1
```

Если автоматический поиск JDK не сработал:

```powershell
$env:FOCUSDUO_JAVA_HOME='C:\путь\к\jdk-21'
powershell -ExecutionPolicy Bypass -File .\desktop\run.ps1 -Demo
```

Прямой запуск и тесты через wrapper:

```powershell
$env:JAVA_HOME='C:\путь\к\jdk-21'
Set-Location desktop
.\gradlew.bat test
.\gradlew.bat run --args="--demo"
.\gradlew.bat run --args="--demo --width=1000 --height=700"
```

Для проверки размера окна также доступно `run.ps1 -Demo -Width 1000 -Height 700`. Свернуть, развернуть, переместить и закрыть окно можно стандартными средствами Windows.

## Запуск в bash

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
cd desktop
bash ./gradlew test
bash ./gradlew run --args='--demo'
```

Bash-команды приведены для разработки; проверка этого этапа выполняется на Windows. Java 21 требуется для разработки. Проверенного готового установщика пока нет.

## Закреплённые версии

| Компонент | Версия |
| --- | --- |
| JDK / JVM toolchain | 21 |
| Gradle Wrapper | 8.13, с SHA-256 дистрибутива |
| Kotlin/JVM и Compose Compiler | 2.2.20 |
| Compose Multiplatform Desktop | 1.9.1 |
| kotlinx.coroutines | 1.10.2 |
| JUnit Jupiter | 5.13.3 |

Совместимость проверена по [Kotlin Gradle guide](https://kotlinlang.org/docs/gradle-configure-project.html), [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html) и [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html). Wrapper принадлежит desktop-сборке.

## Следующие этапы

Полный `FakeRepository` и комната начинаются на этапе 2; управление раундом — на этапе 3. `RealRepository`, Ktor Client/CIO, kotlinx.serialization, авторизация и восстановление соединения добавляются на этапах 5–7. Серверный адрес будет настраиваемым, по умолчанию `http://127.0.0.1:8090`; на этапе 1 он не используется. Реальная интеграция и Windows-упаковка пока не выполнены.

## Ресурсы

Manrope с кириллицей включён в ресурсы приложения отдельными начертаниями Regular, Medium, Semibold и Bold. Источник: [Manrope / Google Fonts](https://github.com/googlefonts/manrope), лицензия SIL Open Font License 1.1: `src/main/resources/fonts/OFL.txt`. Макет предоставлен пользователем; это визуальная справка, а не фон интерфейса.
