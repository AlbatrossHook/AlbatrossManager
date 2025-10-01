# AlbatrossManager

[中文版本](README_CN.md)
----------------

Android manager app for the Albatross hook plugin ecosystem. This app manages the lifecycle of Albatross hook plugins on rooted devices. It requires the core library and plugin APKs to be installed on the device to function properly.

If you are developing a plugin, see the reference project: `https://github.com/AlbatrossHook/HideApp`.

## Overview

- Min SDK: 24
- Target SDK: 34
- Compile SDK: 36
- Language/Tooling: Java 17 (AGP 8.x), AndroidX
- Core dependency: `lib/albatross.jar` (provided as `compileOnly`)
- Root required: Yes (device must be rooted for hook to function)

## Demo

- Watch the demo: [AlbatrossManager.mp4](AlbatrossManager.mp4)

<video src="AlbatrossManager.mp4" controls preload="metadata" style="max-width: 100%; height: auto;">
  Your browser does not support the video tag. You can download it from the link above.
</video>

## Requirements

- Android device with root access
- Android Studio (latest stable) or Gradle 8.x
- Java 17 toolchain
- Plugin APKs built against the Albatross plugin spec

## Build

The project uses Gradle Version Catalogs and AGP.

1) Open in Android Studio and let it sync; or build via CLI:

```bash
./gradlew :app:assembleDebug
```

Artifacts are in `app/build/outputs/apk/`.

### Release build and signing

The `release` buildType expects keystore configuration from `local.properties` or environment variables:

Supported keys (either in `local.properties` or as env vars):
- `RELEASE_STORE_FILE` (or env `RELEASE_STORE_FILE`) – defaults to `qing.jks` at repo root
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Then build:

```bash
./gradlew :app:assembleRelease
```

## Install

1) Ensure the device is rooted.
2) Install Albatross plugins required by your scenario (see Plugin section below).
3) Install the manager APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For release builds, install the `app-release.apk` that you signed.

## Runtime permissions and queries

The app requests:
- `android.permission.QUERY_ALL_PACKAGES` – enumerate installed apps to manage plugin targets.
- `android.permission.REQUEST_INSTALL_PACKAGES` – assist installing plugin APKs.

It also declares a package query for the action `qing.albatross.PluginConfig` to discover compatible plugins.

## Root & Core

- Hook functionality requires root; without root, the manager UI may start but plugin actions will not take effect.
- The app links against the core `lib/albatross.jar` as `compileOnly`. Ensure your runtime environment includes the corresponding native/service components required by Albatross.

## Using plugins

1) Build or obtain plugin APKs that expose the intent action `qing.albatross.PluginConfig`.
2) Install the plugin(s) on the device.
3) Open AlbatrossManager and verify the plugin list and details.
4) Enable/configure plugin behavior per your needs.

For plugin development and reference implementation, see: `https://github.com/AlbatrossHook/HideApp`.


## Troubleshooting

- No plugins detected: ensure your plugin declares the discovery intent action,meta data and is installed.
- Actions not taking effect: confirm device is rooted and core services are available.
- Build fails on signing: check `local.properties` or env variables for release keystore config.

## License

This project is distributed under the license specified in `LICENSE`.
