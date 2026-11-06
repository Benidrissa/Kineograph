# Contributing

Thank you for helping. Please keep these commitments in mind, because they are not negotiable:

- **Free forever.** No ads, paywalls, "premium" badges or upsells.
- **No artificial limits.** Only the device may limit layers, frames or export size. Warn rather than block.
- **No watermark.**
- **Private.** No analytics, no crash reporting services, no Firebase, no Google Play Services, and **no `INTERNET`
  permission**. The build fails if the merged manifest contains one.
- **Every dependency is free software** and works offline.
- **Every bundled asset** is original or openly licensed, and listed in `THIRD_PARTY_LICENSES.md`.

## Working on the code

1. Read [docs/architecture.md](docs/architecture.md).
2. Before sending a change, run:
   ```sh
   ./gradlew ktlintCheck checkNoBangBangInEngine :core:model:test testDebugUnitTest
   ```
3. Add tests with your change. Pixel behaviour can be tested on the JVM: see `DrawingSessionTest`, which uses real Skia
   through Robolectric's native graphics mode.
4. Touch and rendering performance need a real device, ideally a cheap one.
5. Record non-obvious choices in [docs/decisions.md](docs/decisions.md).

## Rules for the engine

- No `!!` in `:core:engine`. The build checks.
- Nothing there may touch the UI.
- Public methods of `DrawingSession` queue work on the engine thread and return at once.

## Translations

Strings live in each module's `res/values/strings.xml`. To add a language, add a `res/values-xx/strings.xml` to every module
that has one, and add the language to `app/src/main/res/xml/locales_config.xml`.

By contributing, you agree that your contribution is licensed under GPL-3.0-or-later.
