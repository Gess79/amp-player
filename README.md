# AMP Player

Offline music player for Android. Plays the music files on your phone — and its shuffle keeps its word.

**Package:** `io.github.gess79.amp` · **License:** [GPL-3.0](LICENSE) · **Min. Android:** 8 (API 26)

Privacy: [English](https://gess79.github.io/amp-player/privacy.html) · [Deutsch](https://gess79.github.io/amp-player/datenschutz.html)  
Contact: [gess79.apps@gmail.com](mailto:gess79.apps@gmail.com)

## Why AMP

- **Each source keeps its own shuffle.** Every playlist, folder and your favorites has its own shuffle round. Switch away and back: same song, same position, same round.
- **Every song once per round.** A round only ends when every track has played. Progress survives app restarts, Bluetooth dropouts and turning off the car.
- **Built for listening in the car.** Shortly before you drive, press Play on the widget or in the app (Android does not let the app start playback on connect by itself). Once it is playing, the sound stays on the car. Pause stays paused; auto-resume only after a Bluetooth dropout.
- **Your playlists next to your music.** Reads m3u/xspf from folders you choose (including subfolders), picks up file changes, favorites and in-app playlists.
- **Optional loudness leveling** (ReplayGain-style, files never modified).
- **Private by design:** no internet permission, no account, no ads, no tracking. Free and open source.

English and German UI (system / DE / EN).

## Status

Closed testing on Google Play is underway. This repository is the source of truth for the open-source build. Signed release APKs for sideloading may appear under [Releases](https://github.com/Gess79/amp-player/releases) when published.

## Build

Requirements: JDK 17, Android SDK (API 36).

```bash
./gradlew :app:assembleDebug
```

Release builds need a local `keystore.properties` and keystore (not in this repo). Without them, release tasks fail on purpose instead of producing an unsigned APK.

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

Debug installs as `io.github.gess79.amp.debug` (“AMP Player Debug”) and can sit next to the release app.

## Contributing

Bug reports and ideas: open an issue or email [gess79.apps@gmail.com](mailto:gess79.apps@gmail.com). Pull requests are welcome; please keep the change focused and match existing style.

## Donations

AMP is free and has no ads. Voluntary donations (e.g. Liberapay, Ko-fi, GitHub Sponsors, Bitcoin/Lightning) may be linked here later — never inside the Play Store app build.

## Name and logo

The **source code** is licensed under GPL-3.0. The name **“AMP Player”** and the app **icon/logo** are not licensed under the GPL. You may fork and modify the code under GPL-3.0, but please do not use the AMP Player name or logo in a way that suggests your fork is the official project (rename forks that you distribute publicly).

## License

[GNU General Public License v3.0](LICENSE)
