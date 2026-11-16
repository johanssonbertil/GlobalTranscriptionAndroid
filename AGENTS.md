# Kotlin & Android Development & Verification Rules

## 1. Automated Compilation Verification
- Before completing any task or declaring code modifications finished:
  - You MUST run `.\gradlew compileDebugKotlin` (or `.\gradlew.bat compileDebugKotlin` on Windows) to verify that the project compiles cleanly with 0 errors.
  - If compilation fails, inspect the error log, fix all unresolved references, syntax, or type errors, and re-run compilation until it succeeds.
  - Never finish a turn leaving broken compilation or missing references.

## 2. Manifest & Android Component Registration
- Whenever creating or modifying Android components (`Activity`, `Service`, `BroadcastReceiver`):
  - Verify that the class is properly declared in `app/src/main/AndroidManifest.xml`.
  - For Android 12+ (API 31+), ensure any component with an `<intent-filter>` has an explicit `android:exported="true"` or `android:exported="false"`.
  - Ensure any required permissions (e.g. `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `WAKE_LOCK`) are present in `AndroidManifest.xml`.

## 3. Kotlin Syntax, Types & Jetpack Compose
- Verify that all referenced classes, extensions, functions, and properties exist and are correctly imported.
- Composable functions must only be called from within a `@Composable` context.
- Respect type inference and parameter types; do not pass incompatible receivers or types.
- Ensure any referenced drawables, strings, or layouts exist in `app/src/main/res/`.

## 4. Live Device Testing & Debugging (Wireless ADB)
- A physical test device (OnePlus 9) is connected via wireless ADB (`adb devices`).
- **Path to adb**: `adb` is on PATH (or at `C:\Users\berti\AppData\Local\Android\Sdk\platform-tools\adb.exe`).
- **Install & Deploy**:
  - You can install built APKs directly to the test device with:
    `adb install -r app/build/outputs/apk/debug/app-debug.apk`
    (or run `.\gradlew installDebug`).
- **Runtime Log & Crash Inspection**:
  - When investigating runtime issues, crashes, or unhandled exceptions, inspect device logs with:
    `adb logcat -d -t 100 *:E` (or filter by tag/process).
  - Clear logcat before test runs if needed: `adb logcat -c`.
