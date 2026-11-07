# Kineograph

A free, open-source, ad-free frame-by-frame animation app for Android. Named after the 1868 word for the flipbook.

**Free forever.** No ads, no subscriptions, no watermark, no limits on layers or frames, and no account.
**Private.** Your work stays on your device. The app does not even have permission to use the internet.
**Small and fast.** Built for inexpensive phones with 2–3 GB of RAM, starting from Android 8.

> **Status: Phase 1 of 3.** Drawing, frames, layers, onion skin, playback, and GIF and MP4 export all work. Audio,
> 20+ brushes, the Studio timeline and more are planned. See [CHANGELOG.md](CHANGELOG.md).

## What it does today

- Low-latency drawing with pressure support, plus simulated pressure for fingers
- Pen, Pencil and Marker brushes, an eraser, and stroke smoothing
- Holding a stylus's button erases, even if pressed mid-stroke, and so does its eraser end, without changing the selected tool
- Fill with gap closing, so small breaks in an outline do not leak
- Lasso to move, scale and rotate parts of a drawing
- Frames: add, insert, duplicate, delete, reorder, copy, paste
- Unlimited layers with opacity and merge down
- SVG import, still or animated, as a new layer, on the current layer or as the background
- Onion skin with tinting and fall-off
- Playback with a live frame-rate change
- At least 100 steps of undo
- Autosave that survives crashes
- Export to MP4 and GIF, with no watermark, ever

## Install

No signed version has been released yet, so [Releases](https://github.com/Benidrissa/Kineograph/releases) is empty. A signed
APK appears there once a version is tagged and has passed every test. There is one to download, and it works on every phone.

Until then, you can try a test build. Sign in to GitHub, open the latest successful
[run on `main`](https://github.com/Benidrissa/Kineograph/actions/workflows/android.yml?query=branch%3Amain+is%3Asuccess), and
download the `debug-apk` artifact. It is a ZIP with one APK inside, which works on every phone. Each one is kept for 30 days.

A test build:

- Shows as **Kineograph Test**. A newer test build updates an older one and keeps your projects.
- Is a separate app from a signed release, and installs beside it. Projects cannot move between the two yet, because project
  export comes in Phase 2. They live inside each app, so uninstalling one deletes its projects.
- Is signed with a key that is public, in this repository, so only install test builds from this repository's own runs.

An older test build that shows as plain **Kineograph** was signed with a key nobody kept, so it cannot be updated. A new test
build installs beside it, and its projects stay where they are.

## Build

You need JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug                        # app/build/outputs/apk/debug/
./gradlew :core:model:test testDebugUnitTest   # unit and UI tests on the JVM
./gradlew connectedDebugAndroidTest            # on a device or emulator
./gradlew ktlintCheck                          # code style
./gradlew :app:lintDebug                       # lint, for every module
```

## Learn more

- [Architecture](docs/architecture.md)
- [Project file format](docs/file-format.md)
- [Decisions](docs/decisions.md)
- [Contributing](CONTRIBUTING.md)

## License

[GPL-3.0-or-later](LICENSE). Components are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
