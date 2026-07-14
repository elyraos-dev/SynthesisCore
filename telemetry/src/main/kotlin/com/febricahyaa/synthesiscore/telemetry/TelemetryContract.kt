/*
 * Copyright (C) 2026 FebriCahyaa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.febricahyaa.synthesiscore.telemetry

/**
 * The wire contract between SynthesisCore (producer) and Flux (consumer).
 *
 * The format is a line-oriented `key value` text snapshot. It is deliberately
 * not JSON: the consumer is a C++ daemon that parses this file on every update,
 * and a flat key-value format is cheaper to parse and impossible to half-parse
 * into a nested structure.
 *
 * Every field is emitted on every write, in [KEY_ORDER], so a consumer never has
 * to distinguish "absent because unchanged" from "absent because broken".
 * Availability and validity are carried explicitly instead of being inferred
 * from sentinel values.
 */
object TelemetryContract {

    /**
     * Schema version of the emitted snapshot.
     *
     * v1 (historical) was unversioned, emitted `thermal_status` as a *clamped and
     * inverted* headroom float, and carried no sequence or timestamp. It is not
     * forward compatible and consumers must not attempt to read v2 with a v1 parser.
     */
    const val SCHEMA_VERSION = 2

    // --- Envelope ---------------------------------------------------------
    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_SEQUENCE = "sequence"
    const val KEY_UPDATED_ELAPSED_MS = "updated_elapsed_ms"
    const val KEY_DAEMON_PID = "daemon_pid"

    // --- Foreground application -------------------------------------------
    const val KEY_FOREGROUND_AVAILABLE = "foreground_available"
    const val KEY_FOCUSED_PACKAGE = "focused_package"
    const val KEY_FOCUSED_PID = "focused_pid"
    const val KEY_FOCUSED_UID = "focused_uid"

    // --- Screen ------------------------------------------------------------
    const val KEY_SCREEN_AVAILABLE = "screen_available"
    const val KEY_SCREEN_AWAKE = "screen_awake"

    // --- Power -------------------------------------------------------------
    const val KEY_POWER_AVAILABLE = "power_available"
    const val KEY_BATTERY_SAVER = "battery_saver"
    const val KEY_CHARGING_AVAILABLE = "charging_available"
    const val KEY_CHARGING_STATE = "charging_state"

    // --- Thermal -----------------------------------------------------------
    const val KEY_THERMAL_AVAILABLE = "thermal_available"
    const val KEY_THERMAL_VALID = "thermal_valid"
    const val KEY_THERMAL_HEADROOM = "thermal_headroom"
    const val KEY_THERMAL_STATUS = "thermal_status"
    const val KEY_THERMAL_SAMPLE_ELAPSED_MS = "thermal_sample_elapsed_ms"
    const val KEY_THERMAL_AGE_MS = "thermal_age_ms"

    // --- Audio -------------------------------------------------------------
    const val KEY_AUDIO_AVAILABLE = "audio_available"
    const val KEY_AUDIO_ACTIVE = "audio_active"

    // --- Zen / Do Not Disturb ----------------------------------------------
    const val KEY_ZEN_AVAILABLE = "zen_available"
    const val KEY_ZEN_MODE = "zen_mode"

    // --- Kernel ------------------------------------------------------------
    const val KEY_KERNEL_IS_GKI = "kernel_is_gki"

    /**
     * Canonical emission order.
     *
     * The order is part of the contract: it keeps snapshots byte-comparable so the
     * writer can cheaply detect "nothing semantically changed" without re-parsing,
     * and it keeps diffs readable when a user pastes a snapshot into a bug report.
     */
    val KEY_ORDER: List<String> = listOf(
        KEY_SCHEMA_VERSION,
        KEY_SEQUENCE,
        KEY_UPDATED_ELAPSED_MS,
        KEY_DAEMON_PID,
        KEY_FOREGROUND_AVAILABLE,
        KEY_FOCUSED_PACKAGE,
        KEY_FOCUSED_PID,
        KEY_FOCUSED_UID,
        KEY_SCREEN_AVAILABLE,
        KEY_SCREEN_AWAKE,
        KEY_POWER_AVAILABLE,
        KEY_BATTERY_SAVER,
        KEY_CHARGING_AVAILABLE,
        KEY_CHARGING_STATE,
        KEY_THERMAL_AVAILABLE,
        KEY_THERMAL_VALID,
        KEY_THERMAL_HEADROOM,
        KEY_THERMAL_STATUS,
        KEY_THERMAL_SAMPLE_ELAPSED_MS,
        KEY_THERMAL_AGE_MS,
        KEY_AUDIO_AVAILABLE,
        KEY_AUDIO_ACTIVE,
        KEY_ZEN_AVAILABLE,
        KEY_ZEN_MODE,
        KEY_KERNEL_IS_GKI,
    )

    /** Emitted for [KEY_FOCUSED_PACKAGE] when no package could be resolved. */
    const val PACKAGE_NONE = "none"

    /**
     * Emitted for [KEY_THERMAL_STATUS] when the platform cannot report a
     * discrete thermal status. Mirrors the absence of `THERMAL_STATUS_UNKNOWN`
     * in the framework, which only defines NONE(0)..SHUTDOWN(6).
     */
    const val THERMAL_STATUS_UNKNOWN = -1
}
