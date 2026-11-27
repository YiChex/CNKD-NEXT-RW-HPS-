# CNKD NEXT

[简体中文](../../README.md) · [English (US)](README.en-US.md) · [English (UK)](README.en-GB.md) · **Français** · [Русский](README.ru.md)

**Un serveur indépendant à salle unique pour Rusted Warfare, issu de RW-HPS.** · [AGPL v3](../../LICENSE)

[Télécharger le serveur](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/latest) · [Guide d’installation (chinois)](../DEPLOYMENT-GUIDE-CN.md) · [Catalogue des extensions (chinois)](../PLUGINS-CN.md) · [Sources du serveur](../../server/README.fr.md)

## Lettre à la communauté de Rusted Warfare

Nos premiers remerciements vont à **Dr. (deng-rui), auteur de RW-HPS, et à toutes les personnes qui ont contribué au projet d’origine**. Les modalités de maintenance et de publication de RW-HPS ont évolué. CNKD NEXT leur doit ses fondations, et nous continuerons à les créditer dans le code et la documentation. [RW-HPS](https://github.com/RW-HPS/RW-HPS)

L’équipe CNKD poursuit ce travail sur la base de RW-HPS depuis février 2026. Après mûre réflexion, nous estimons que le serveur à salle unique est assez mûr pour être entretenu à plusieurs. La communauté a encore bien des ressources à nous faire découvrir. Maintenant que le code source du serveur est public, nous pouvons enfin l’annoncer : nous nous reconvertissons dans *Wuthering Waves*. 😄

Tout le monde peut proposer une modification par pull request. Les changements peuvent être intégrés après relecture, et les personnes qui contribuent dans la durée pourront recevoir les droits de maintenance adaptés. Si le groupe de mainteneurs s’agrandit, nous envisagerons de créer une organisation GitHub et de confier progressivement la maintenance à la communauté. Nous prévoyons aussi de publier dès que possible le code de la prochaine génération du système de parties classées, un socle serveur extensible pour Rusted Warfare fondé sur CNKD NEXT. [Contribuer](../../CONTRIBUTING.md)

Les clans de Rusted Warfare ont été le principal moteur de cette aventure. Merci à **灰太狼 (Loup gris)**, le premier soutien de l’équipe CNKD, et au **clan 七安 (Qi’an)**, la première équipe à utiliser ce serveur. Nous saluons toutes les équipes qui l’ont essayé, nous ont fait part de leurs retours ou ont aidé à l’améliorer. Merci aussi aux utilisateurs de CNKD Cloud Instances et aux équipes qui font vivre cet écosystème. Par-dessus tout, merci d’être là.

Un clin d’œil aux équipes des outils d’IA qui nous ont aidés en route 😄 [OpenAI Group PBC](https://openai.com/) · [DeepSeek (Hangzhou DeepSeek Artificial Intelligence Basic Technology Research Co., Ltd.)](https://www.deepseek.com/)

> Que la route traverse de hautes montagnes et de lointaines rivières, et que le code trouve un foyer.
>
> Quand les mises à jour feront une pause, que ces étincelles éclairent celles et ceux qui prendront la suite.

**CNKD 薯条 🍟** · [Rejoindre le groupe QQ 910085695](https://qm.qq.com/q/YHtlMJwzUm)

## Exemples et extensions officielles

| Extension | Usage | Sources et téléchargement |
| --- | --- | --- |
| Exemple minimal | Une commande `.hello` et des modèles Java et Kotlin. | [Sources](https://github.com/YiChex/cnkd-next-plugin-examples) · [Télécharger](https://github.com/YiChex/cnkd-next-plugin-examples/releases/latest) |
| EIRS | Collecte d’identifiants des joueurs et synchronisation des bannissements. L’adaptateur moderne est inclus dans le paquet standard. | [Sources](https://github.com/YiChex/cnkd-plugin-identity-reporter) · [Télécharger](https://github.com/YiChex/cnkd-plugin-identity-reporter/releases/latest) |
| Statistiques de fin de partie | Collecte des données de match et service de rapports séparé. | [Sources](https://github.com/YiChex/cnkd-match-analytics) · [Télécharger](https://github.com/YiChex/cnkd-match-analytics/releases/latest) |
| Titres et couleurs | Pseudonymes, titres et couleurs dans le salon. Utilisez le serveur fourni avec cette extension. | [Sources](https://github.com/YiChex/cnkd-next-single-room-titles) · [Télécharger](https://github.com/YiChex/cnkd-next-single-room-titles/releases/latest) |

La version standard prend en charge les statistiques via `read-statistics`. L’extension de titres exige `player-presentation` et fournit un serveur distinct. Vérifiez la capacité requise en plus de la version du SDK. Après l’installation ou le remplacement d’une extension, régénérez le fichier d’approbation pour l’ensemble des extensions installées. [Catalogue des extensions (chinois)](../PLUGINS-CN.md)

## Les sites de la communauté CNKD

- Essayez le serveur et configurez-le dans un panneau visuel. [CNKD Cloud Instances](https://cloud.cnkd.fun/)
- Retrouvez les tournois et les activités des clans de Rusted Warfare. [CNKD Esports](https://esports.cnkd.fun/)
- Parcourez les cartes et suivez les mises à jour du site. [CNKD Map Resources](https://rw.cnkd.fun/maps)

## Premiers pas

La version publique `2.3.2` contient le serveur `1.1.24` et le SDK d’extensions `1.3.0`. Un lancement manuel déclenche par défaut un compte à rebours de cinq secondes. Les joueurs peuvent changer de place et rejoindre ou quitter l’équipe S des spectateurs lorsque les verrous du salon le permettent.

Téléchargez le ZIP sans dossier englobant depuis Releases, extrayez-le dans un répertoire réservé à l’instance et utilisez Java 17. Exécutez `sh ./start.sh` sous Linux ou `start-windows.bat` sous Windows. Dans la console, vérifiez la configuration avec `startmode bootcheck` et `config check`. [Guide d’installation (chinois)](../DEPLOYMENT-GUIDE-CN.md)

## Licence

Le code source du serveur CNKD NEXT est distribué sous la **GNU Affero General Public License v3 (AGPL v3)**, comme le projet d’origine RW-HPS. Conservez les mentions de droit d’auteur et de licence, et respectez les dispositions relatives au code source correspondant lorsqu’elles s’appliquent. Les dépendances tierces et les ressources du jeu gardent leurs propres licences. [AGPL v3](../../LICENSE) · [RW-HPS](https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE)
