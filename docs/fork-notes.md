# Movie Art for Projectivy — форк под Kinowatch (без TMDB)

> Исторические заметки по первой версии форка. Часть описания устарела: сейчас плагин не
> пересылает `iconUri` как есть в режиме BLUR, а сам скачивает широкий (`wide`) вариант постера и
> собирает картинку 16:9 (см. `buildLocalLandscapeWallpaper` в `WallpaperProviderService.kt`).
> Актуальное описание — в [README](../README.md).

## Что изменено относительно оригинала

1. **`WallpaperProviderService.kt`** — полностью переписана обработка `Event.ProgramCardFocused`:
   - Убран вызов `tmdbApi.searchArtInfo(title)` (поиск по названию в TMDB).
   - Теперь используется **напрямую** `event.iconUri` — то есть URI постера/фона, который
     Projectivy получил из системного Android TV `PreviewProgram`/`WatchNext` (тот самый URI,
     который сам Kinowatch туда записал через `SyncProgramsJobService`).
   - URI передаётся обратно в Projectivy как есть, `displayMode = WallpaperDisplayMode.BLUR` —
     это штатный режим самого API Projectivy, который красиво растягивает/размывает даже
     портретный постер на весь экран. Никакого скачивания, копирования файлов или
     `FileProvider` для этого пути **не требуется** — Projectivy сам уже прочитал этот URI при
     формировании события, и просто снова открывает его в своём процессе.
   - Если `iconUri` пуст — используется тот же fallback (динамический градиент/кастомный фон),
     что и раньше. Заведомо неправильный постер никогда не показывается.

2. **`PreferencesManager.kt`** — из `FallbackBackground` убран вариант `PopularMoviesAndShows`
   (он использовал TMDB для случайных фонов). Остались `DynamicColors` и `CustomBackground`.
   Добавлена защита от краша при обновлении со старой версии, где в настройках мог быть
   сохранён `PopularMoviesAndShows` — теперь это тихо мигрирует на `DynamicColors`.

3. **`SettingsFragment.kt`** — переключатель Fallback Background теперь просто переключает
   между двумя оставшимися вариантами.

4. **Удалены** `helpers/TMDbApi.kt` и `helpers/ApiResponseCache.kt` — больше не нужны.

5. **`build.gradle.kts`** — убран блок чтения `apikeys.properties`/`TMDB_API_KEY`
   (`BuildConfig` поле удалено), убран плагин и зависимость `kotlinx-serialization-json`
   (использовались только в `ApiResponseCache`). **Никакой API-ключ вводить больше не нужно.**

6. `helpers/FileCache.kt` — убрана мёртвая ссылка на `tmdb_api_cache.json` в очистке кэша.

Файлы `helpers/ImageProcessor.kt`, `helpers/downloadBitmap`, `helpers/saveBitmapToFile`
оставлены как есть (сейчас не используются, но не мешают сборке) — пригодятся, если позже
захотите добавить локальную обработку (например, блюр-подложку под портретный постер вручную,
если штатного `WallpaperDisplayMode.BLUR` окажется недостаточно).

## Как это работает теперь, коротко

```
Kinowatch (SyncProgramsJobService)
      → пишет постер в системный Android TV PreviewProgram/WatchNext (posterArtUri)
Projectivy
      → читает эту запись, строит событие ProgramCardFocused(iconUri = <тот самый URI>)
      → вызывает наш WallpaperProviderService.getWallpapers(event)
Movie Art (этот форк)
      → возвращает Wallpaper(uri = event.iconUri, displayMode = BLUR) без изменений
Projectivy
      → сам загружает и отображает изображение по этому URI как фон
```

Никакого HTTP-клиента к API Kinowatch (`api2/v1/backdrop/{id}` и т.п.), никакого TMDB,
никакого сопоставления по названию — используется ровно тот постер, который Kinowatch сам
выбрал для этой карточки.

## Что ещё не подтверждено (важно проверить на реальном устройстве)

Статический анализ APK Kinowatch показал, что приложение **пишет** `posterArtUri` в
`PreviewProgram`/`WatchNextProgram`, но **какого именно формата** этот URI (обычная `https://`
ссылка на картинку, или `content://` из собственного кэша Kinowatch) — без запуска кода
установить нельзя. Оба варианта этот форк обработает одинаково (мы просто пересылаем строку
дальше), но если это окажется приватный `content://` URI Kinowatch без гранта для Projectivy —
Projectivy получит чёрный экран именно на этом URI (при этом сам факт получения `iconUri` не
пустым уже будет что-то показывать в большинстве случаев, т.к. если бы Projectivy не мог его
прочитать, скорее всего он бы и в своём "родном" виде постер в списке "Я смотрю" не показывал).

## Сборка

Собрать можно только в Android Studio на вашей стороне (использованная здесь среда без доступа
в интернет не может скачать Gradle-зависимости и Android SDK, поэтому готового `.apk` тут нет).

1. Откройте папку проекта в Android Studio (Meerkat/Narwhal или новее, под Kotlin 2.3 / AGP 9.1).
2. **Про подпись (signing):** в `movie-art-plugin/build.gradle.kts` секция `signingConfigs`
   ссылается на `../cinemaglow.jks` — этого файла в архиве нет (это приватный ключ автора
   оригинала, в .gitignore). У вас два варианта:
   - Соберите **debug**-вариант (`Build > Build Bundle(s)/APK(s) > Build APK(s)`, вариант
     `debug`) — он подписывается автоматически стандартным debug-ключом Android Studio, без
     правки `build.gradle.kts`. Для установки на Homatics Box R Plus этого достаточно.
   - Либо, если хотите собрать `release`: создайте свой keystore и замените блок
     `signingConfigs { create("release") {...} }` на свои `storeFile`/пароли, либо временно
     удалите `signingConfig = signingConfigs.getByName("release")` из `buildTypes.release`
     (тогда release тоже подпишется debug-ключом).
3. Установите получившийся `.apk` на Homatics Box R Plus.

## Проверка (ваш обычный сценарий, без ADB)

1. Установить APK.
2. Открыть настройки этого плагина один раз (не обязательно менять что-то) либо просто открыть
   Projectivy и выбрать этот плагин как Wallpaper Provider в настройках Projectivy, если ещё не
   выбран.
3. Открыть строку «Я смотрю» в Projectivy.
4. Перемещать фокус между разными фильмами/сериалами — фон должен меняться на постер именно
   этого тайтла.
5. Если для какого-то тайтла фон остаётся чёрным/пустым — это будет означать, что для этой
   конкретной карточки `iconUri` либо пуст, либо недоступен для Projectivy (см. раздел выше) —
   опишите мне, для какого именно тайтла это произошло, и я скорректирую следующий шаг
   (например, добавим свою загрузку байт изображения через API Kinowatch вместо прямой
   пересылки URI — но тогда придётся всё-таки разобраться с механизмом mirror-серверов).
