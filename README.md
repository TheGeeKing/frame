# Frame

Minimal native Android proof of concept for fast photo and video capture.

## Gestures

- Tap the shutter for a photo.
- Hold for 225 ms to start video; release to stop.
- Drag vertically while holding to zoom.
- Drag up and left to lock recording; tap the shutter to stop.
- Double-tap the preview to switch cameras, including during a persistent recording.
- Save publishes the pending capture to `DCIM/Frame`; Discard deletes it.

## Build and test

Use Android Studio's bundled JDK 17 and an Android SDK with API 36 installed:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Required device spike

Camera behavior depends on OEM hardware. Before UI polish, install the APK on the target Android 10+ phone and verify:

1. Take a photo, Save it, and confirm it appears in Gallery under Frame.
2. Record video, drag vertically to both zoom limits, release, and Save.
3. Lock a recording, lift your finger, and stop it with the shutter.
4. During one recording, double-tap rear → front → rear; confirm one playable MP4 is produced and the front segment is mirrored.
5. Discard one photo and one video; confirm neither appears in Gallery.

No emulator or physical device was attached during the automated build, so this OEM-specific spike remains manual.

