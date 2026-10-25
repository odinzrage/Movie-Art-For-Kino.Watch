# 🎬 Movie Art for Kino.Watch

Когда фокус стоит на карточке фильма или сериала из Kino.Watch (например, в строке «Я смотрю»),
фоном лаунчера становится горизонтальный арт именно этого тайтла.

Любительский бесплатный проект сообщества, не связан с Kino.Watch и Projectivy.

## ✨ Как это работает

- Kino.Watch публикует карточки в системные каналы Android TV (`PreviewProgram` / `WatchNext`)
  со ссылкой на вертикальный постер, например `.../poster/item/big/126307.jpg`.
- Projectivy при фокусе на карточке передаёт эту ссылку плагину.
- Плагин пробует широкий вариант того же постера (`.../poster/item/wide/126307.jpg`),
  подгоняет его под 16:9 и кэширует.
- Если широкого варианта нет, вертикальный постер ставится по центру на чёрном фоне 16:9.
- Для обычных приложений (не карточек фильмов) используется запасной фон: динамический градиент
  в цветах приложения (яркость настраивается) или своя картинка/видео.

Никакого поиска по названию и сторонних баз (TMDB) — только арт, который выбрал сам Kino.Watch.

## 📦 Установка

1. Скачайте `movie-art-kinowatch.apk` из [Releases](../../releases) и установите на приставку
   (например, через файловый менеджер или «Send Files to TV»).
2. В Projectivy: **Настройки → Внешний вид → Обои → Movie Art for Kino.Watch**.
3. Откройте строку «Я смотрю» и переключайтесь между тайтлами — фон должен меняться.

Настройки плагина (запасной фон, яркость градиента, своя картинка) открываются из Projectivy или
иконкой плагина в списке приложений.

## 🛠 Сборка

Нужны JDK 21 и Android SDK (проще всего — Android Studio).

```sh
./gradlew assembleDebug     # movie-art-plugin/build/outputs/apk/debug/
./gradlew assembleRelease   # movie-art-plugin/build/outputs/apk/release/
```

Без ключа release подписывается debug-ключом. Чтобы подписать своим keystore, задайте переменные
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

GitHub Actions собирает APK на каждый push и PR (артефакт `apk`), а при пуше тега `v*`
создаёт релиз.

## 🚀 Выпуск версии

```sh
git tag v0.2.0
git push origin v0.2.0
```

Версия берётся из тега (`x.y.z`, можно с суффиксом, например `v0.2.0-beta.1`).
`versionCode = major·10000 + minor·100 + patch`, поэтому каждый следующий релиз ставится поверх
предыдущего; `minor` и `patch` — от 0 до 99. Локальная сборка без переменной `APP_VERSION`
получает версию `0.0.0-dev`. Для постоянной подписи релизов добавьте в секреты репозитория
`RELEASE_KEYSTORE_BASE64` (keystore в base64) и три секрета с паролями/алиасом.

## 📁 Структура

- `movie-art-plugin/` — сам плагин
- `api/` — AIDL-интерфейс плагинов Projectivy
- `docs/fork-notes.md` — заметки по форку и отладке

## 🙏 Благодарности и лицензия

Форк [Movie Art for Projectivy](https://play.google.com/store/apps/details?id=com.danielkorgel.projectivy.plugin.cinemaglow)
от Daniel Korgel, который основан на
[шаблоне плагина Projectivy](https://github.com/spocky/projectivy-plugin-wallpaper-provider).
Распространяется по лицензии MIT, см. [LICENSE](LICENSE).
