# A1 Family Media Player

A1 Family Media is an Android music and video player focused on media stored on your device. The project is built with Kotlin, AndroidX, and AndroidX Media3.

## Current capabilities

- Browse music and video available to the app through Android media storage.
- Play audio using a Media3 media session service so playback can continue outside the main screen.
- Browse the music library and search within the Library.
- Use supported playback controls, including queue playback, shuffle/repeat, and seeking.
- Play videos in the app, including fullscreen playback.
- Open compatible audio and video files from Android Files or other apps using Android's **Open with** flow.
- Recover pending playback requests across activity/service transitions.
- Apply supported audio effects and a sleep timer where the device and Android audio implementation allow them.

The exact behaviour can vary by Android version, device, file format, and available media permissions. A successful CI build does not replace testing on physical devices.

## Advertising and network access

The current upgrade branch removes the Start.io SDK and Adsterra advertising placement. It does not intentionally display ads. The Android manifest still declares the INTERNET permission; do not interpret the absence of an advertising SDK as proof that every part of the application is incapable of network access. Review dependencies and runtime behaviour before making broader privacy claims.

## Android compatibility

- Minimum Android version: Android 7.0 (API 24).
- Target SDK: Android 15 (API 35).
- Build SDK: Android 16 (API 36).

The app requests access to audio/video media as needed. Android 13 and later use granular media permissions; older supported Android versions use the legacy external-storage permission. Notification permission may be requested for playback notifications.

## Build and release

GitHub Actions builds the release APK and Android App Bundle (AAB), then checks their signatures before uploading separate workflow artifacts.

1. Open the [GitHub Actions workflow](https://github.com/tapiwanashemanyonga-gif/Media-player/actions/workflows/build-kivora.yml).
2. Open a successful run on the intended branch.
3. Download the **A1-Family-Release-APK** artifact to install directly, or **A1-Family-Release-AAB** for a store workflow.
4. Verify the package ID and signing certificate against the version already installed before distributing an update.

A signed artifact is not automatically proof that it can update every previously distributed build. The package ID and signing certificate must match the existing app.

The release workflow expects these GitHub Actions secrets: A1_KEYSTORE_BASE64, A1_KEYSTORE_PASSWORD, and A1_KEY_PASSWORD. The keystore is restored to temporary runner storage during the workflow; do not commit keystores, passwords, or signing files to the repository.

## Reliability checklist before publishing

- [ ] Install the APK on a physical Android device.
- [ ] Confirm local music and video scanning with granted and denied permissions.
- [ ] Play, pause, seek, skip, shuffle, and repeat audio.
- [ ] Confirm audio continues when the screen is off and after navigating away from the app.
- [ ] Test notification/media-session controls and returning to the app.
- [ ] Open an audio file and a video file from Android Files using **Open with**.
- [ ] Test fullscreen video, back navigation, and playback after rotating or backgrounding the app.
- [ ] Test empty libraries, unsupported/corrupt media, and large libraries.
- [ ] Confirm installation and update behaviour against the intended public release.
- [ ] Review the app's permissions, privacy disclosures, store listing, and screenshots.

## Development safeguards

The GitHub Actions workflow runs git diff --check against the latest commit before building, then builds the release APK/AAB and verifies their signatures. Keep changes small and review the diff before merging. Do not remove playback recovery or file-opening intent filters without a tested replacement.
