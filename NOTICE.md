# NOTICE

SynthesisCore
Copyright (C) 2026 FebriCahyaa

This product includes software developed as part of the Flux project. This
NOTICE file is provided in accordance with Section 4(d) of the Apache License,
Version 2.0.

--------------------------------------------------------------------------------

## 1. Provenance

SynthesisCore is original Flux-owned work. It is the Android-side telemetry
producer consumed by Flux Tweaks, and its repository history begins with its own
initial commit ("Initial: SynthesisCore"); it is **not** derived from Encore
Tweaks. This is the counterpart of the statement in the Flux `NOTICE.md` §4,
which classifies SynthesisCore and the Flux-side SynthesisCore integration as
original work.

SynthesisCore's responsibility is intentionally narrow: platform observation,
provider isolation, telemetry normalization, versioned snapshot generation, and
lifecycle health. It chooses no performance profiles, writes no CPU/GPU tuning
values, and contains no Flux policy or WebUI logic. The telemetry wire contract
it emits is documented in
`telemetry/src/main/kotlin/com/febricahyaa/synthesiscore/telemetry/TelemetryContract.kt`.

--------------------------------------------------------------------------------

## 2. Bundled third-party components

### HiddenApiBypass
Copyright (C) 2021 LSPosed Developers.
Licensed under the Apache License, Version 2.0.
Consumed as a Gradle dependency (`org.lsposed.hiddenapibypass:hiddenapibypass`,
version pinned in `gradle/libs.versions.toml`) and bundled into the release APK.
https://github.com/LSPosed/AndroidHiddenApiBypass

Used to reach the hidden framework APIs (`IActivityTaskManager` foreground
resolution, `INotificationManager.getZenMode()`) that SynthesisCore observes.

--------------------------------------------------------------------------------

## 3. Build/test dependencies (not distributed)

### JUnit 4
Copyright (C) the JUnit contributors.
Licensed under the Eclipse Public License 1.0.
Used only by the host-side test suite (`:telemetry:test`); not shipped in the
release APK.

--------------------------------------------------------------------------------

A full software bill of materials (SBOM) for release artifacts is produced as
part of the release pipeline.
