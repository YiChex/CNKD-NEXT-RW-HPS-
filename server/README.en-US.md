# CNKD NEXT server

[简体中文](README.md) · **English (US)** · [English (UK)](README.en-GB.md) · [Français](README.fr.md) · [Русский](README.ru.md)

An independent single-room server for Rusted Warfare, built on RW-HPS.

[Project home](../docs/i18n/README.en-US.md) · [Download server](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/latest) · [Setup guide (Chinese)](../docs/DEPLOYMENT-GUIDE-CN.md) · [Plugin catalog (Chinese)](../docs/PLUGINS-CN.md)

Public release `2.3.2` contains server `1.1.24` and Plugin SDK `1.3.0`. Manual Start defaults to a five-second countdown. Ordinary players can change slots and enter or leave spectator team S when the lobby locks allow it.

## Plugin examples and official plugins

| Plugin | What it does | Source and downloads |
| --- | --- | --- |
| Minimal example | One `.hello` command, with Java and Kotlin starter projects. | [Source](https://github.com/YiChex/cnkd-next-plugin-examples) · [Download](https://github.com/YiChex/cnkd-next-plugin-examples/releases/latest) |
| EIRS | Player identity reporting and ban synchronization. The standard package includes the modern adapter. | [Source](https://github.com/YiChex/cnkd-plugin-identity-reporter) · [Download](https://github.com/YiChex/cnkd-plugin-identity-reporter/releases/latest) |
| Postgame analytics | Match data collector and separate report service. | [Source](https://github.com/YiChex/cnkd-match-analytics) · [Download](https://github.com/YiChex/cnkd-match-analytics/releases/latest) |
| Titles and colors | Lobby nicknames, titles, and colors. Use the companion host from the plugin release. | [Source](https://github.com/YiChex/cnkd-next-single-room-titles) · [Download](https://github.com/YiChex/cnkd-next-single-room-titles/releases/latest) |

The standard host supports postgame analytics through `read-statistics`. The titles plugin requires `player-presentation` and comes with a companion host. Check both the required capability and the SDK version. After you install or replace a plugin, regenerate the approval lock for the full installed set. [Plugin catalog (Chinese)](../docs/PLUGINS-CN.md)

## Build and run

Download the flat ZIP from Releases, extract it into a separate instance directory, and use Java 17. Run `sh ./start.sh` on Linux or `start-windows.bat` on Windows. Verify the configuration with `startmode bootcheck` and `config check` in the server console.

Use JDK 17 and place the matching engine build dependencies you are authorized to use in `Server-Core/libs/`. Run the Gradle wrapper from the `server/` directory. The full core test suite also needs the matching game class cache in `runtime-data/data/gameData/`. [Build guide (Chinese)](BUILDING-CN.md)

```sh
sh ./gradlew :Server-Plugin-API:jar :Plugin-Tool:jar :Server-All:jar
```

## License

CNKD NEXT server source is distributed under the **GNU Affero General Public License v3 (AGPL v3)**, as is upstream RW-HPS. Keep copyright and license notices and follow the corresponding-source terms when they apply. Third-party dependencies and game assets retain their own licenses. [AGPL v3](LICENSE) · [RW-HPS](https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE)

[Full server guide (Chinese)](README.md) · [Contribute](../CONTRIBUTING.md) · [Join QQ group 910085695](https://qm.qq.com/q/YHtlMJwzUm)
