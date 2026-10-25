# TS Police 1.6 km (1600m) Tracker & ETA Coach

A custom native Android tracker (`com.tspolice.runtracker`) purpose-built for the **Telangana State Police (TSLPRB) 1,600m Physical Efficiency Test (PET)** — qualifying benchmark: **1.60 km under 7 min 15 sec (`435.0s` / `4:31.9 /km` / `1:48.75` per 400m lap)**.

## Direct App Download Link (Always Latest APK)

- **Download Latest Android APK**:
  **[ts-police-1600m-tracker.apk](https://github.com/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases/latest/download/ts-police-1600m-tracker.apk)**
- **Releases Page**:
  [github.com/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases](https://github.com/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases)

---

## Key Features

1. **Zero-Tap Auto-Detection (No Manual Sport Picker)**
   - Fuses **GPS ground speed (`m/s`)**, **hardware step detector (`TYPE_STEP_DETECTOR`)**, and **3-axis dynamic acceleration RMS (`m/s²` + peak step fallback)** to classify movement automatically in real time:
     - **Standing (Auto-Pause)**: `< 0.50 m/s` & `< 42 spm` — automatically pauses active timer and resumes when you move
     - **Walking**: `0.50–1.85 m/s` (`~1.8–6.7 km/h`)
     - **Jogging**: `1.85–3.05 m/s` (`5:28–9:00 /km`, `~135–162 spm`)
     - **Running**: `3.05–3.67 m/s` (`4:32–5:28 /km`, `163+ spm`)
     - **TS Police Target Pace**: `>= 3.68 m/s` (`<= 4:31.9 /km` — on pace for `< 7:15`)
   - **Hands-Free Auto-Start Mode**: Arm at the starting line and slip your phone into your pocket — the timer starts automatically after 3 seconds of sustained jogging or running.
   - **Indoor / Pre-GPS Step-Stride Fallback**: Continues tracking cadence, gait classification, and stride-estimated distance even before sky GPS lock completes.

2. **Live 1.6 km PET Trial & 400m Split Voice Coach**
   - Circular 270° gauge with `400m`, `800m`, `1200m`, and `1600m` lap ticks, live `+/-` split delta against `7:15`, projected 1.6 km finish time, and `TextToSpeech` audio cues every 400m.
   - Auto-finishes and logs official `1,600m` trials the instant you cross `1.60 km`.

3. **Daily Progress & Reach-Target ETA Predictor**
   - Normalizes non-1600m endurance runs (`>= 600m`) to a 1.6 km equivalent using Riegel's endurance scaling ($T_{1600} = T \cdot (1600 / D)^{1.06}$) while keeping exact 1600m trials weighted at full priority.
   - Fits a recency-weighted capability trend (`sec/day` and `sec/week` shaved) with diminishing-returns tapering to estimate **how many days, sessions, and which calendar date** you will break `7:15`.
   - Includes **+ Log Track Time** (for manual wristwatch stopwatch runs on a 400m ground) and 1-tap **Sample Data** preview.

4. **Built-In GitHub OTA Auto-Updater**
   - Automatically checks `releases/latest/download/version.json` on app startup (and manually from **PET & SENSORS -> Check for App Updates Now**).
   - Streams the new APK with a live progress dialog, verifies the **SHA-256** digest, and launches Android's `PackageInstaller` via `FileProvider` while keeping your SQLite daily run history intact.

---

## How to Push Future App Updates (1 Command)

Whenever you make code changes and want every installed copy of the app to auto-update:

```powershell
.\scripts\publish-update.ps1 -Notes "Describe what changed in this update"
```

This script automatically:
1. Increments `versionCode` and `versionName` in `app/build.gradle.kts`
2. Runs unit tests and signs the release APK with your persistent `keystore/ts-police-ota.jks` certificate
3. Updates `version.json` with the new SHA-256 digest
4. Commits, tags, pushes to GitHub, and publishes the new GitHub Release (`ts-police-1600m-tracker.apk` + `version.json`)

## Build & Test Locally

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```
