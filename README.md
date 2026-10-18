# MP3 Player (Android)

Simple music player: scans your phone's music, search, play/pause, next/prev,
seek bar, shuffle, repeat, background playback with notification controls.

## Get the app on your phone (no Android Studio needed)
1. Upload all these files to a new GitHub repository.
2. Open the repo's **Actions** tab -> **Build APK** -> wait for the green check (~5 min).
3. Open that run, download **mp3-player-apk** (under Artifacts), unzip it.
4. Send `app-debug.apk` to your phone and install it (allow "install unknown apps" when asked).

If the build fails, open the failed run and copy the error text.

Built with Kotlin, Jetpack Compose and Media3 (ExoPlayer).
