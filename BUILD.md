# Building Sil-Q Android

## Toolchain

| Component | Version |
| --- | --- |
| JDK | 17 or compatible Android Studio JBR |
| Gradle | 8.11.1 (wrapper) |
| Android Gradle Plugin | 8.9.2 |
| Compile / target SDK | 36 |
| Minimum SDK | 24 |
| NDK | 28.0.13004108 |
| CMake | 3.22.1 |

Native targets: `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

## SDL2 dependency

The build requires SDL 2.30.12 at revision
`8236e01a9f758d15927624925c6043f84d8a261f`. From the repository root:

```sh
git clone https://github.com/libsdl-org/SDL.git android/vendor/SDL2
git -C android/vendor/SDL2 checkout --detach 8236e01a9f758d15927624925c6043f84d8a261f
```

CMake compiles SDL directly; Gradle includes its Android Java sources and
license. `android/vendor` is untracked and must be populated before syncing.

## Project configuration

Open `android/` as the Android Studio project. Configure the Gradle JDK and
SDK location through the IDE or use `JAVA_HOME` and `ANDROID_HOME` for CLI
builds. The SDK path can also be supplied through `android/local.properties`.

## Build and validation

Run from `android/`:

```sh
bash gradlew :app:lintDebug :app:assembleDebug
bash gradlew :app:lintRelease :app:assembleRelease
```

On Windows, substitute `./gradlew.bat` for `bash gradlew`.

Release signing is not configured in the repository. Supply signing
credentials through your local Android Studio or release workflow.
`assembleRelease` produces an unsigned APK unless signing is configured.

## Build structure

- `android/app/src/main/jni/CMakeLists.txt` builds the engine and SDL bridge.
- `src/core-sources.cmake` defines the shared engine source list.
- Gradle's `stageGameAssets` task packages game data, graphics, the tutorial,
  and license notices before `preBuild`.
- The build has no dependency on Python or the local `tools/` directory.
