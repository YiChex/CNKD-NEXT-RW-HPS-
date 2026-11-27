# Сервер CNKD NEXT

[简体中文](README.md) · [English (US)](README.en-US.md) · [English (UK)](README.en-GB.md) · [Français](README.fr.md) · **Русский**

Независимый сервер Rusted Warfare с одной игровой комнатой на базе RW-HPS.

[Главная страница проекта](../docs/i18n/README.ru.md) · [Скачать сервер](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/latest) · [Установка (на китайском)](../docs/DEPLOYMENT-GUIDE-CN.md) · [Каталог плагинов (на китайском)](../docs/PLUGINS-CN.md)

Публичный выпуск `2.3.2` включает сервер `1.1.24` и SDK плагинов `1.3.0`. При ручном запуске матча по умолчанию начинается пятисекундный отсчёт. Игроки могут менять место, переходить в команду наблюдателей S и возвращаться из неё, если это разрешают настройки комнаты.

## Примеры и официальные плагины

| Плагин | Назначение | Код и загрузка |
| --- | --- | --- |
| Минимальный пример | Одна команда `.hello` и шаблоны для Java и Kotlin. | [Код](https://github.com/YiChex/cnkd-next-plugin-examples) · [Скачать](https://github.com/YiChex/cnkd-next-plugin-examples/releases/latest) |
| EIRS | Передача сведений об игроках и синхронизация блокировок. В стандартный пакет входит современный адаптер. | [Код](https://github.com/YiChex/cnkd-plugin-identity-reporter) · [Скачать](https://github.com/YiChex/cnkd-plugin-identity-reporter/releases/latest) |
| Послематчевая статистика | Сбор данных о матчах и отдельный сервис отчётов. | [Код](https://github.com/YiChex/cnkd-match-analytics) · [Скачать](https://github.com/YiChex/cnkd-match-analytics/releases/latest) |
| Титулы и цвета | Псевдонимы, титулы и цвета в лобби. Используйте сервер из выпуска плагина. | [Код](https://github.com/YiChex/cnkd-next-single-room-titles) · [Скачать](https://github.com/YiChex/cnkd-next-single-room-titles/releases/latest) |

Стандартный выпуск поддерживает послематчевую статистику через `read-statistics`. Плагину титулов нужен `player-presentation`, поэтому к нему прилагается отдельный сервер. Проверяйте нужную возможность и версию SDK. При установке или замене плагина заново сформируйте файл одобрения для всего набора установленных плагинов. [Каталог плагинов (на китайском)](../docs/PLUGINS-CN.md)

## Сборка и запуск

Скачайте ZIP из раздела Releases и распакуйте его в отдельный каталог экземпляра сервера. Используйте Java 17. В Linux запустите `sh ./start.sh`, в Windows — `start-windows.bat`. Для проверки настроек выполните в консоли `startmode bootcheck` и `config check`.

Используйте JDK 17. Поместите совместимые зависимости игрового движка, на использование которых у вас есть разрешение, в `Server-Core/libs/`. Запустите Gradle Wrapper из каталога `server/`. Для полного набора тестов ядра также нужен соответствующий кеш классов в `runtime-data/data/gameData/`. [Сборка (на китайском)](BUILDING-CN.md)

```sh
sh ./gradlew :Server-Plugin-API:jar :Plugin-Tool:jar :Server-All:jar
```

## Лицензия

Исходный код сервера CNKD NEXT распространяется по **GNU Affero General Public License v3 (AGPL v3)**, как и исходный проект RW-HPS. Сохраняйте уведомления об авторских правах и лицензии и соблюдайте требования к предоставлению соответствующего исходного кода, когда они применимы. Для сторонних зависимостей и игровых ресурсов действуют их собственные лицензии. [AGPL v3](LICENSE) · [RW-HPS](https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE)

[Полное руководство по серверу (на китайском)](README.md) · [Внести вклад](../CONTRIBUTING.md) · [Вступить в группу QQ 910085695](https://qm.qq.com/q/YHtlMJwzUm)
