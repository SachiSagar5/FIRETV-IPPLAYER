# StreamShelf for Fire TV

## App UI preview

![StreamShelf home screen UI preview](docs/streamshelf-ui-preview.png)


A native Android TV IPTV player for Fire TV. It accepts multiple M3U playlist URLs, keeps their names and URLs encrypted with Android Keystore on the device, parses standard `#EXTINF` items and common `group-title` / `tvg-logo` attributes, and provides Live, Movies, and Series shelves with ExoPlayer playback. Artwork loads off the UI thread into memory and disk caches, and shelves add more items in small batches so large playlists appear faster.

Series are grouped by show: the playlist's one-entry-per-episode listings collapse into a single card per show ("36 episodes"), and opening that card lists the episodes in season/episode order. Playback progress is saved as you watch, so reopening a movie or episode resumes where it stopped, and a show's card remembers the episode you were on ("RESUME S01E05").

## Compatibility

- Fire OS 7 / Android 9 and newer (minSdk 24; tested target device family: Fire TV)
- Android Studio is not needed on your Mac to build this project: GitHub Actions can build it on an Ubuntu runner.
- XMLTV EPG, subtitle tracks beyond the built-in menu, and Play Store distribution are not included yet.

Classification uses group and item text: groups or titles mentioning movie/VOD/film go to Movies; series/season/episode/shows go to Series; other entries go to Live. M3U metadata is inconsistent, so some entries may need better classification rules for a particular provider. `tvg-logo` is parsed but the first UI version uses text tiles.

## Fire TV remote

The home screen uses a full-screen dashboard with a left navigation rail, featured title, and horizontal Movies, Series, and Live TV shelves. Use the D-pad to navigate, **Select** to open a title (a series card opens its episode list), **Menu** to add or remove the focused title — or every episode of the focused show — from My List, **Search** to search the active playlist, and **Back** to leave playback or close the episode/See All grid. Saved titles show a small heart on the card on every shelf and tab. While a movie or episode is playing, **Menu** also saves the stream that is playing, and **Left/Right** skips back or forward 10 seconds (the on-screen Rewind/Forward buttons and the remote's rewind/fast-forward keys do the same). The player handles remote play/pause keys, shows buffering and retry feedback, and pauses when StreamShelf goes into the background. The app includes an adaptive launcher icon and a dedicated Leanback banner for Fire TV. Entries without a valid `tvg-logo` keep a branded placeholder.

## Build APK remotely with GitHub Actions

1. The app starts with the supplied MyList M3U URL on a fresh install. The playlist contents are fetched at runtime and are not copied into this repository. Existing installs with a saved playlist keep their current source.
2. Open **Actions → Build StreamShelf APK → Run workflow** (or push to `main`).
3. Download the `streamshelf-debug-apk` artifact from the completed workflow run. It contains `app-debug.apk`.
4. Sideload the APK using your preferred Fire TV method. The app is not signed for Play Store distribution; the debug APK is appropriate for personal sideloading.

The workflow installs Java 17, Gradle 8.9, Android SDK platform 35, and builds the APK on GitHub's runner. No Android SDK, Gradle, Java, or Android Studio installation is needed locally.

## Add playlists

On a fresh install, StreamShelf loads the supplied MyList M3U URL by default. Open Sources to add more playlist URLs; use the source label above the shelves to switch playlists. Playlist URLs may contain account credentials; they are AES-GCM encrypted with a key held by Android Keystore. User-added URLs are not uploaded by the app. The built-in default is only a URL; playlist contents are fetched at runtime.

## Project structure

- `app/src/main/java/tv/sachin/streamshelf/MainActivity.java`: UI, playlist parser, encrypted source storage, playback
- `app/src/main/AndroidManifest.xml`: Fire TV launcher entry and internet permission
- `app/src/main/res/`: adaptive icon, Leanback banner, and artwork placeholders
- `.github/workflows/build.yml`: remote APK build

## Notes

This player only displays and plays streams provided by the user. It does not include channels or content. Make sure you have permission to access each playlist and stream.
