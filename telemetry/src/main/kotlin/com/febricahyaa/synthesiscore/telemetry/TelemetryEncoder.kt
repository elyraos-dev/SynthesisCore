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

import java.util.Locale

/**
 * Serialises a [TelemetrySnapshot] to the v2 wire format.
 *
 * Every number is formatted with [Locale.ROOT]. This is not a style preference:
 * the consumer is a C++ daemon calling `strtof`/`sscanf("%f")` in the C locale, and
 * on a device set to Indonesian, German, French, Russian (and many others) the
 * platform default locale formats `0.84` as `0,84`. `strtof("0,84")` stops at the
 * comma and yields `0.0` — which, under the corrected thermal semantics, reads as
 * "completely cool" and would suppress every thermal downgrade on those devices.
 */
object TelemetryEncoder {

    /** Two decimals is the useful precision of the underlying HAL; more is noise. */
    private const val FLOAT_FORMAT = "%.2f"

    /**
     * Encode [snapshot] into the canonical v2 text form.
     *
     * Keys are emitted in [TelemetryContract.KEY_ORDER]. Lines are `key value`,
     * separated by a single space and terminated by `\n`.
     */
    fun encode(snapshot: TelemetrySnapshot): String {
        val fields = LinkedHashMap<String, String>(TelemetryContract.KEY_ORDER.size)

        fields[TelemetryContract.KEY_SCHEMA_VERSION] = snapshot.schemaVersion.toString()
        fields[TelemetryContract.KEY_SEQUENCE] = snapshot.sequence.toString()
        fields[TelemetryContract.KEY_UPDATED_ELAPSED_MS] = snapshot.updatedElapsedMs.toString()
        fields[TelemetryContract.KEY_DAEMON_PID] = snapshot.daemonPid.toString()

        val fg = snapshot.foreground.valueOrNull()
        fields[TelemetryContract.KEY_FOREGROUND_AVAILABLE] = bool(snapshot.foreground.available)
        fields[TelemetryContract.KEY_FOCUSED_PACKAGE] = fg?.packageName ?: TelemetryContract.PACKAGE_NONE
        fields[TelemetryContract.KEY_FOCUSED_PID] = (fg?.pid ?: 0).toString()
        fields[TelemetryContract.KEY_FOCUSED_UID] = (fg?.uid ?: 0).toString()

        fields[TelemetryContract.KEY_SCREEN_AVAILABLE] = bool(snapshot.screenAwake.available)
        fields[TelemetryContract.KEY_SCREEN_AWAKE] = bool(snapshot.screenAwake.valueOrNull() == true)

        fields[TelemetryContract.KEY_POWER_AVAILABLE] = bool(snapshot.batterySaver.available)
        fields[TelemetryContract.KEY_BATTERY_SAVER] = bool(snapshot.batterySaver.valueOrNull() == true)

        fields[TelemetryContract.KEY_CHARGING_AVAILABLE] = bool(snapshot.chargingState.available)
        fields[TelemetryContract.KEY_CHARGING_STATE] = bool(snapshot.chargingState.valueOrNull() == true)

        encodeThermal(snapshot, fields)

        fields[TelemetryContract.KEY_AUDIO_AVAILABLE] = bool(snapshot.audioActive.available)
        fields[TelemetryContract.KEY_AUDIO_ACTIVE] = bool(snapshot.audioActive.valueOrNull() == true)

        fields[TelemetryContract.KEY_ZEN_AVAILABLE] = bool(snapshot.zenMode.available)
        // Zen mode is an enum (0=off, 1=priority, 2=total silence, 3=alarms only), not a flag.
        // Flattening it to a boolean is what made Flux restore the wrong mode on game exit.
        fields[TelemetryContract.KEY_ZEN_MODE] = (snapshot.zenMode.valueOrNull() ?: 0).toString()

        fields[TelemetryContract.KEY_KERNEL_IS_GKI] = bool(snapshot.kernelIsGki)

        return buildString(256) {
            for (key in TelemetryContract.KEY_ORDER) {
                val value = fields[key]
                    ?: error("Encoder produced no value for contract key '$key'")
                append(key).append(' ').append(value).append('\n')
            }
        }
    }

    private fun encodeThermal(snapshot: TelemetrySnapshot, fields: MutableMap<String, String>) {
        val thermal = snapshot.thermal
        val reading = thermal.valueOrNull()

        fields[TelemetryContract.KEY_THERMAL_AVAILABLE] = bool(thermal.available)
        fields[TelemetryContract.KEY_THERMAL_VALID] = bool(thermal.valid)

        if (reading == null) {
            // No usable reading. Emit a value the consumer cannot mistake for a real
            // measurement: 0.0 would mean "perfectly cool" and -1.0 was the v1 sentinel
            // that Flux then compared numerically against a threshold. thermal_valid=0
            // is the only thing a consumer may key off here.
            fields[TelemetryContract.KEY_THERMAL_HEADROOM] = formatFloat(Float.NaN)
            fields[TelemetryContract.KEY_THERMAL_STATUS] = TelemetryContract.THERMAL_STATUS_UNKNOWN.toString()
            fields[TelemetryContract.KEY_THERMAL_SAMPLE_ELAPSED_MS] = "0"
            fields[TelemetryContract.KEY_THERMAL_AGE_MS] = "0"
            return
        }

        val ageMs = (snapshot.updatedElapsedMs - reading.sampleElapsedMs).coerceAtLeast(0L)
        fields[TelemetryContract.KEY_THERMAL_HEADROOM] = formatFloat(reading.headroom)
        fields[TelemetryContract.KEY_THERMAL_STATUS] = reading.status.toString()
        fields[TelemetryContract.KEY_THERMAL_SAMPLE_ELAPSED_MS] = reading.sampleElapsedMs.toString()
        fields[TelemetryContract.KEY_THERMAL_AGE_MS] = ageMs.toString()
    }

    /**
     * Format a float for the wire, always in [Locale.ROOT].
     *
     * NaN is emitted literally as `nan`, which `strtof` parses back to NaN and which
     * no consumer can mistake for a number. It is only ever emitted alongside
     * `thermal_valid 0`.
     */
    fun formatFloat(value: Float): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        return String.format(Locale.ROOT, FLOAT_FORMAT, value)
    }

    private fun bool(value: Boolean): String = if (value) "1" else "0"
}
