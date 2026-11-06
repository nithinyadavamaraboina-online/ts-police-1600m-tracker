# TS Police 1.6 km (1600m) Tracker & Coach Brain

A native Android tracker (`com.tspolice.runtracker`) purpose-built for the **Telangana State Police (TSLPRB) 1,600m Physical Efficiency Test (PET)** — qualifying benchmark: **1.60 km in `<= 7:15` (`435.0s` / `4:31.9 /km` / `1:48.75` per 400m lap)**.

## Direct App Download Link (Always Latest APK)

- **Download Latest Android APK**:
  **[ts-police-1600m-tracker.apk](https://github.com/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases/latest/download/ts-police-1600m-tracker.apk)**
- **Releases Page**:
  [github.com/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases](https://github.com/nithinyadavamaraboina-online/ts-police-1600m-tracker/releases)

---

## Key Features

1. **Zero-Tap Gait & Anti-Scooter Sensor Fusion**
   - Fuses **single-best GPS ground speed (`m/s` with `<=12m` track-curve compensation)**, **hardware step detector (`TYPE_STEP_DETECTOR` / `TYPE_STEP_COUNTER`)**, and **dt-normalized 3-axis acceleration (`m/s²` + flight-phase trough detection)** to classify movement in real time:
     - **Standing**: `< 0.48 m/s` & `< 42 spm`
     - **Walking**: `0.48–2.08 m/s` (`< 138 spm`)
     - **Jogging**: `2.08–2.95 m/s` (`138–158 spm` or spring-mass flight bounce)
     - **Running**: `2.95–3.67 m/s` (`158+ spm`)
     - **TS Police Target Pace**: `>= 3.68 m/s` (`<= 4:31.9 /km` — on pace for `< 7:15`)
     - **Vehicle / Bike Guard**: Rejects scooter/car vibration in 24/7 open mode and unlocks on-foot tracking after parking + verified footsteps.
   - **Hands-Free 1.6K Trial Launch (`~1.3s`)**: Arm at the starting line and slip your phone into your pocket — standing and walking to the chalk line are ignored, and the official trial clock launches automatically once jogging/running is confirmed.
   - **Official Non-Pausing RFID Clock vs. Practice Auto-Pause**: `1.6K Trial` (`TIMED_1600M`) runs a continuous `elapsedRealtime()` gun clock just like the TSLPRB RFID mat, whereas `Open Practice` and `400m Coach` intervals track pure active running time separately from rest breaks.

2. **Live 1.6 km PET Trial, `±30m` Lap Snap & `400m` Interval Coach**
   - Circular 270° gauge with `400m`, `800m`, `1,200m`, and `1,600m` lap ticks, `1,400m` final-kick audio bell, `+ Snap 400m Lap` (`±30m` chalk-line GPS calibration), and linear boundary split interpolation at `1,600.0m`.
   - Dedicated **400m Interval Coach** (`4×`, `5×`, `6×`, `8× 400m` with custom Coach Brain lap-time handoff and `90s` recovery countdowns).
   - **Share Run Poster**: Background-rendered `1080×1350` AMOLED result card + plain-text WhatsApp/Instagram caption.

3. **Expert Running Coach Brain & Goal-Date Roadmap**
   - Analyzes all non-demo sessions, 14-day step/fatigue load, intra-hour pre-run walking fatigue, leg-feel check-ins (`Fresh`, `Tired`, `Sore Shins`), and **5 clock windows** (`Early Morning`, `Morning`, `5:00 PM`, `6:00 PM`, `Late Evening`) to recommend **1 vs. 2 daily sessions**, plain-English **What To Do / What NOT To Do** checklists, and pre/post-run Indian fueling & hydration targets.
   - **Reach-Target ETA & Merit Gradation**: Riegel endurance normalization (`>= 600m` non-interval runs) + recency-weighted capability trend and TSLPRB merit points (`0–100 pts`).

4. **Samsung Health Connect Calibration & On-Device Privacy**
   - Side-by-side **Yesterday & Today** step/distance/active-time comparison with personal stride auto-calibration and 14-day run backfill via Android Health Connect.
   - **On-Device Privacy Default**: Health and run history stay locally on your phone (`schemaVersion = 2` full JSON backup/restore included). Optional developer cloud diagnostics (`Coach & Lab -> Developer Remote Diagnostics`) is **OFF by default**.

5. **Verified GitHub OTA Auto-Updater**
   - Checks `releases/latest/download/version.json` (`Coach & Lab -> Check for App Updates`), enforces an `HTTPS` GitHub host allowlist + mandatory **SHA-256** verification before launching Android's `PackageInstaller`, and preserves your SQLite training history across updates.

---

## How to Push Future App Updates (1 Command)

Configure `keystore.properties` in the repo root pointing to your persistent release keystore (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), then run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\scripts\publish-update.ps1 -Notes "Describe what changed in this update"
```

This script automatically:
1. Increments `versionCode` and `versionName` in `app/build.gradle.kts`
2. Runs unit tests and signs the release APK (`-PrequireReleaseKeystore=true`)
3. Updates `version.json` with the new SHA-256 digest
4. Commits tracked project files, tags, pushes to GitHub, and publishes the GitHub Release (`ts-police-1600m-tracker.apk` + `version.json`)

## Build & Test Locally

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```
