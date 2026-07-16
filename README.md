# Synthesis Core

<p align="center">
  <img src="diagram.svg" alt="SynthesisCore Architecture" width="100%"/>
</p>

<p align="center">
  <b>Fast, native Android system monitor — a modern replacement for <code>dumpsys</code></b><br/>
  Built with Kotlin · Runs via <code>app_process</code> · Zero external dependencies
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-API%2028%2B-3DDC84?logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue"/>
  <img src="https://img.shields.io/badge/Used%20By-Flux%20Tweaks-6C63FF"/>
</p>

---

## What is SynthesisCore?

SynthesisCore is a lightweight background daemon that monitors critical Android system state in real-time and exposes it as a simple plain-text file. It is designed to be polled or watched via `inotify` by other native daemons — most notably [Flux Tweaks](https://github.com/febricahyaa/Flux).

It runs as a standalone process using `app_process`, which gives it access to the full Android Java framework stack (including hidden/internal APIs) without needing to be installed as a regular app.

### Why not `dumpsys`?

| | SynthesisCore | `dumpsys` |
|---|---|---|
| **Speed** | Polls in ~500 ms, sub-50 ms PID retry | Limited to ~1 s intervals |
| **Precision** | Returns exactly the fields you need | Dumps everything, requires custom parsing |
| **Overhead** | Minimal — single persistent process | Spawns a new process on every call |
| **Integration** | `inotify`-friendly file output | Requires shell piping |

---

## Output Format

SynthesisCore writes a plain-text key-value file, updated only when any value changes (`fd.sync()` guaranteed):

```text
focused_app com.rhmsoft.edit.pro 4720 10292
screen_awake 1
battery_saver 0
zen_mode 0
charging_state 1
thermal_status 0.85
audio_active 1
```

### Field Reference

| Field | Type | Description |
|---|---|---|
| `focused_app` | `string int int` | Foreground package name, PID, UID |
| `screen_awake` | `0\|1` | Whether the display is interactive |
| `battery_saver` | `0\|1` | Power Save Mode active |
| `zen_mode` | `0–3` | DND level: 0=off, 1=priority, 2=silence, 3=alarms |
| `charging_state` | `0\|1` | `1` = device is charging (AC / USB / wireless) |
| `thermal_status` | `0.00–1.00` | Thermal headroom — `1.0` = cool, `0.0` = throttling. `-1.00` on API < 31 |
| `audio_active` | `0\|1` | `1` = music/game audio stream is active |

---

## Usage

```shell
app_process -Djava.class.path=/sdcard/app-release.apk / \
  --nice-name=FluxSysMon \
  com.febricahyaa.synthesiscore.MainKt \
  /path/to/output/file \
  [/path/to/lock/file]
```

The optional second argument is a lock-file path. If provided, SynthesisCore will acquire an exclusive `FileLock` on startup — any attempt to run a second instance against the same lock will exit immediately with an error.

---

## Commit Roadmap

Development is structured as discrete, reviewable commits. Each commit introduces exactly one new output field and its corresponding Android API integration.

### ✅ v1.0.0 — Initial Release
Core infrastructure: `focused_app`, `screen_awake`, `battery_saver`, `zen_mode`.

### 🔜 Planned — Field Expansion

**Commit 1 — `feat: add charging_state field`**
- Integrates `BatteryManager.isCharging()`
- Enables Flux to allow more aggressive performance profiles while plugged in
- No version gate required (API 23+, within our minSdk 28)

**Commit 2 — `feat: add thermal_status field`**
- Integrates `PowerManager.getThermalHeadroom(forecast=1s)`
- Returns normalised float `[0.0–1.0]`; gracefully falls back to `-1.00` on API < 31
- Enables thermal-aware profile tiering in Flux (Performance → PerformanceLite → Balance)

**Commit 3 — `feat: add audio_active field`**
- Integrates `AudioManager.isMusicActive()`
- Detects in-game audio to help Flux avoid disruptive profile switches mid-session
- No version gate required

**Commit 4 — `fix: graceful HiddenApiBypass fallback`**
- Wraps `HiddenApiBypass.addHiddenApiExemptions("")` in try/catch
- Logs a warning instead of crashing if bypass fails on future Android versions
- Degrades gracefully: features that depend on private APIs return sentinel values

---

## Architecture

The diagram at the top of this page shows the full data flow. In short:

1. `MainKt` bootstraps an Android system context via `app_process`
2. A monitor loop runs every 500 ms, reading from five Android system services
3. `buildStatus()` assembles all fields into a key-value string
4. `writeStatus()` compares against the last-written snapshot — writes only on change, then `fsync`s
5. An upstream daemon (e.g. Flux) watches the output file with `inotify` and reacts within milliseconds

---

## Building

```shell
# Standard Gradle release build
./gradlew assembleRelease
```

The APK is self-contained and intended to be run via `app_process`, not installed normally. The GitHub Actions CI workflow builds and signs the APK automatically on every push to `master`.

**Requirements:** JDK 21 · Android Gradle Plugin 9.x · Gradle 9.5.x · Kotlin 2.x · `compileSdk 36`

---

## License

```
Copyright 2026 FebriCahyaa

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
