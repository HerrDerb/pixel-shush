# Contributing to Pssst

Thanks for your interest in contributing!

## Getting started

1. Fork the repository and clone your fork
2. Open the project in a recent Android Studio that supports AGP 9.4
3. Run `./gradlew assembleDebug testDebugUnitTest` to verify the build

## Development setup

- **JDK** 17
- **Android Gradle Plugin** 9.4.1, **Gradle** 9.8.1
- **compileSdk** 36, **targetSdk** 36, **minSdk** 29

Exact versions live in `build.gradle`, `app/build.gradle` and the Gradle wrapper; Dependabot keeps them current.

## Making changes

1. Create a feature branch from `main`
2. Add or update unit tests under `app/src/test` for detection logic changes
3. Ensure build and tests pass: `./gradlew assembleDebug testDebugUnitTest`
4. Open a pull request describing what and why

## Guidelines

- Pssst mimics the Pixel feature: one switch, no settings
- No internet access, no analytics, no proprietary or Google dependencies (it must stay F-Droid compatible)
- No third-party libraries beyond AndroidX/Compose
- Test detection changes on a real device; `adb shell setprop log.tag.Pssst DEBUG` logs the sensor values

## License

By contributing, you agree that your contributions are licensed under the GNU General Public License v3.0 or later.
