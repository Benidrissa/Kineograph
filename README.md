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

Download an APK from [Releases](https://github.com/Benidrissa/Kineograph/releases), or the `debug-apk` artifact from the latest
[Actions run](https://github.com/Benidrissa/Kineograph/actions). The `universal` APK works on every phone.

## Build

You need JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug                        # app/build/outputs/apk/debug/
./gradlew :core:model:test testDebugUnitTest   # unit and UI tests on the JVM
./gradlew connectedDebugAndroidTest            # on a device or emulator
./gradlew ktlintCheck                          # code style
```

## Learn more

- [Architecture](docs/architecture.md)
- [Project file format](docs/file-format.md)
- [Decisions](docs/decisions.md)
- [Contributing](CONTRIBUTING.md)

## License

[GPL-3.0-or-later](LICENSE). Components are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
