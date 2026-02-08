# QMX server for Android

Android application (server) for interfacing a QRP Labs QMX Transceiver (or QDX) with local internet using an ENET gaming UDP networking library.

Designed to work on old phones, great way to upcycle an old phone.

## Building

### Local Build
```bash
# Initialize submodules
git submodule update --init --recursive

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

### GitHub Actions CI/CD

This project includes GitHub Actions for automated builds. Builds are triggered:
- **Manually**: Go to **Actions**  **Build Android APK**  **Run workflow**
- **On Tag Push**: Push a tag to trigger a build and release
  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```

Tags starting with `v` create a release, tags starting with `release-` create a prerelease.

## Requirements

- Android NDK 28.2.13676358
- CMake 3.22.1
- Java 17+

## Native Dependencies

This project uses git submodules for native C++ dependencies:
- `libusb` (USB library)
- `enet`
