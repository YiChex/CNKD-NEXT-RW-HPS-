# Serveur CNKD NEXT

[简体中文](README.md) · [English (US)](README.en-US.md) · [English (UK)](README.en-GB.md) · **Français** · [Русский](README.ru.md)

Un serveur indépendant à salle unique pour Rusted Warfare, issu de RW-HPS.

[Accueil du projet](../docs/i18n/README.fr.md) · [Télécharger le serveur](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/latest) · [Guide d’installation (chinois)](../docs/DEPLOYMENT-GUIDE-CN.md) · [Catalogue des extensions (chinois)](../docs/PLUGINS-CN.md)

La version publique `2.3.2` contient le serveur `1.1.24` et le SDK d’extensions `1.3.0`. Un lancement manuel déclenche par défaut un compte à rebours de cinq secondes. Les joueurs peuvent changer de place et rejoindre ou quitter l’équipe S des spectateurs lorsque les verrous du salon le permettent.

## Exemples et extensions officielles

| Extension | Usage | Sources et téléchargement |
| --- | --- | --- |
| Exemple minimal | Une commande `.hello` et des modèles Java et Kotlin. | [Sources](https://github.com/YiChex/cnkd-next-plugin-examples) · [Télécharger](https://github.com/YiChex/cnkd-next-plugin-examples/releases/latest) |
| EIRS | Collecte d’identifiants des joueurs et synchronisation des bannissements. L’adaptateur moderne est inclus dans le paquet standard. | [Sources](https://github.com/YiChex/cnkd-plugin-identity-reporter) · [Télécharger](https://github.com/YiChex/cnkd-plugin-identity-reporter/releases/latest) |
| Statistiques de fin de partie | Collecte des données de match et service de rapports séparé. | [Sources](https://github.com/YiChex/cnkd-match-analytics) · [Télécharger](https://github.com/YiChex/cnkd-match-analytics/releases/latest) |
| Titres et couleurs | Pseudonymes, titres et couleurs dans le salon. Utilisez le serveur fourni avec cette extension. | [Sources](https://github.com/YiChex/cnkd-next-single-room-titles) · [Télécharger](https://github.com/YiChex/cnkd-next-single-room-titles/releases/latest) |

La version standard prend en charge les statistiques via `read-statistics`. L’extension de titres exige `player-presentation` et fournit un serveur distinct. Vérifiez la capacité requise en plus de la version du SDK. Après l’installation ou le remplacement d’une extension, régénérez le fichier d’approbation pour l’ensemble des extensions installées. [Catalogue des extensions (chinois)](../docs/PLUGINS-CN.md)

## Compiler et démarrer

Téléchargez le ZIP sans dossier englobant depuis Releases, extrayez-le dans un répertoire réservé à l’instance et utilisez Java 17. Exécutez `sh ./start.sh` sous Linux ou `start-windows.bat` sous Windows. Dans la console, vérifiez la configuration avec `startmode bootcheck` et `config check`.

Utilisez le JDK 17. Placez les dépendances de compilation du moteur, dans leur version correspondante et dont vous êtes autorisé à disposer, dans `Server-Core/libs/`. Exécutez le wrapper Gradle depuis le dossier `server/`. Les tests complets du cœur nécessitent aussi le cache de classes correspondant dans `runtime-data/data/gameData/`. [Guide de compilation (chinois)](BUILDING-CN.md)

```sh
sh ./gradlew :Server-Plugin-API:jar :Plugin-Tool:jar :Server-All:jar
```

## Licence

Le code source du serveur CNKD NEXT est distribué sous la **GNU Affero General Public License v3 (AGPL v3)**, comme le projet d’origine RW-HPS. Conservez les mentions de droit d’auteur et de licence, et respectez les dispositions relatives au code source correspondant lorsqu’elles s’appliquent. Les dépendances tierces et les ressources du jeu gardent leurs propres licences. [AGPL v3](LICENSE) · [RW-HPS](https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE)

[Guide complet du serveur (chinois)](README.md) · [Contribuer](../CONTRIBUTING.md) · [Rejoindre le groupe QQ 910085695](https://qm.qq.com/q/YHtlMJwzUm)
