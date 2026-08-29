# Frame

Minimal native Android proof of concept for fast photo and video capture.

## Gestures

- Tap the shutter for a photo.
- Hold for 225 ms to start video; release to stop.
- Drag vertically while holding to zoom.
- Drag right into the animated lock target; release, then tap the shutter to stop.
- Pinch anywhere on the preview to zoom while recording is locked.
- Double-tap the preview to switch cameras, including during a persistent recording.
- Save publishes the pending capture to `DCIM/Frame`; Discard deletes it.

## Build and test

Use Android Studio's bundled JDK 17 and an Android SDK with API 36 installed:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

### Release builds

Build minimized APKs for each supported CPU architecture and a Play Store bundle:

```powershell
.\gradlew.bat assembleLocalRelease -PabiSplits=true
.\gradlew.bat assembleRelease -PabiSplits=true
.\gradlew.bat bundleRelease
```

Outputs:

- Installable local APKs: `app/build/outputs/apk/localRelease/app-*-localRelease.apk`
- `app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk`
- `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`
- `app/build/outputs/apk/release/app-x86_64-release-unsigned.apk`
- `app/build/outputs/bundle/release/app-release.aab`

One build supports every Android version from Android 10 (`minSdk 29`) onward; the APK split only selects the phone's CPU architecture. Use `localRelease` for direct installation: it is optimized and non-debuggable but signed with Android's local debug key. Production `release` APKs remain unsigned until signed with your private keystore. Google Play signs and splits the AAB automatically.

## Required device spike

Camera behavior depends on OEM hardware. Before UI polish, install the APK on the target Android 10+ phone and verify:

1. Take a photo, Save it, and confirm it appears in Gallery under Frame.
2. Record video, drag vertically to both zoom limits, release, and Save.
3. Lock a recording, lift your finger, and stop it with the shutter.
4. During one recording, double-tap rear → front → rear; confirm one playable MP4 is produced and the front segment is mirrored.
5. Discard one photo and one video; confirm neither appears in Gallery.

No emulator or physical device was attached during the automated build, so this OEM-specific spike remains manual.

Audio is recorded when microphone permission is granted; denying it deliberately falls back to silent video. CameraX mirrors front-camera video to match the preview. Front photos keep the camera's unmirrored output because mirroring them would require an image-processing pass outside this PoC.
