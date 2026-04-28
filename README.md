# Dog Run Detector

Small experimental Android app with a C++ detection core. It lets you choose a video, samples up to the first 10 seconds at 5 fps, and reports frames that look like a dog running.

## How It Works

- `MainActivity.java` opens a video with Android's document picker.
- `MediaMetadataRetriever` extracts frames from the first 10 seconds.
- Frames are scaled to `160 x 90` and passed into native C++ through JNI.
- `native-lib.cpp` computes:
  - frame-to-frame motion energy,
  - a simple dog-like color mask for tan, dark, and white fur,
  - moving dog-like foreground overlap,
  - apparent movement speed across sampled frames.

This is intentionally a lightweight heuristic. It is useful for experimenting with the phone app flow and native frame analysis, but it is not a production dog detector.

## Build

Open this folder in Android Studio, let it sync Gradle, then run the `app` configuration on an Android device or emulator.

From PowerShell, you can also run:

```powershell
.\scripts\build-debug.ps1
```

Or, after opening a new terminal, use the normal Gradle commands from this or any other project:

```powershell
gradle --version
java -version
adb version
```

To start the emulator and force its window back onto the visible screen:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-emulator.ps1
```

In VS Code, use `Terminal > Run Task...` and pick:

- `Android: Start Emulator`
- `Android: Build Install Launch`

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The project uses:

- Android Gradle Plugin `8.5.2`
- CMake `3.22.1`
- Java Activity UI
- C++17 native library

## Key Files

- `app/src/main/java/com/example/dogrundetector/MainActivity.java`
- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/cpp/CMakeLists.txt`

## Improving Detection

For a real version, keep the same JNI shape and replace `isDogLikePixel` with a proper model pipeline:

1. Run a small TFLite object detector to confirm that a dog is present.
2. Track the dog bounding box over time.
3. Classify running using box velocity, leg pose, or a short temporal action model.

That upgrade can still keep the motion scoring in C++ as a cheap pre-filter.
