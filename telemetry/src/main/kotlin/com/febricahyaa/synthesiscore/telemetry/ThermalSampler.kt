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

/** A monotonic millisecond clock. Never runs backwards, unaffected by wall-clock changes. */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

/**
 * The platform thermal API, narrowed to what SynthesisCore needs.
 *
 * Kept as an interface so the sampling policy below — which is where the interesting
 * behaviour lives — can be exercised on a host JVM without an Android framework.
 */
interface ThermalSource {

    /** True when `PowerManager.getThermalHeadroom()` exists on this device (API 31+). */
    val supported: Boolean

    /**
     * Read the current headroom forecast.
     *
     * Returns NaN when the platform declines to answer — which it legitimately does when
     * the thermal HAL has no valid reading yet, and also when it is polled faster than it
     * allows. Implementations must return NaN rather than throwing.
     */
    fun readHeadroom(forecastSeconds: Int): Float

    /** Current discrete thermal status, or [TelemetryContract.THERMAL_STATUS_UNKNOWN]. */
    fun readStatus(): Int
}

/**
 * Samples [ThermalSource] on its own cadence and applies last-known-good retention.
 *
 * Two rules drive this class:
 *
 *  1. **Do not poll the framework at the telemetry rate.** `getThermalHeadroom()` reaches
 *     into the thermal HAL; Android documents a minimum polling interval and returns NaN
 *     (or a cached value) when called faster. The rest of the telemetry samples every
 *     ~500 ms, so thermal is decoupled onto [minIntervalMs].
 *
 *  2. **A transient NaN is not a cool device.** The HAL routinely returns NaN for a cycle
 *     or two. Dropping straight to "invalid" would make Flux oscillate into its safe
 *     profile, so the last good sample is retained — but only up to [maxAgeMs], after which
 *     the reading is reported [ProviderResult.Failed] rather than being trusted forever.
 */
class ThermalSampler(
    private val source: ThermalSource,
    private val clock: MonotonicClock,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val forecastSeconds: Int = DEFAULT_FORECAST_SECONDS,
) {

    private var lastGood: ThermalReading? = null
    private var lastAttemptMs: Long = Long.MIN_VALUE

    /**
     * Return the current thermal reading, polling the framework at most once per
     * [minIntervalMs].
     */
    fun sample(): ProviderResult<ThermalReading> {
        if (!source.supported) return ProviderResult.Unsupported

        val now = clock.elapsedMillis()
        val dueForPoll = lastAttemptMs == Long.MIN_VALUE || (now - lastAttemptMs) >= minIntervalMs

        if (dueForPoll) {
            lastAttemptMs = now
            val raw = try {
                source.readHeadroom(forecastSeconds)
            } catch (e: Exception) {
                // A misbehaving vendor HAL must degrade this one provider, not the daemon.
                return retainOrFail(now, "getThermalHeadroom threw: ${e.message}")
            }

            if (!raw.isNaN()) {
                val status = try {
                    source.readStatus()
                } catch (_: Exception) {
                    TelemetryContract.THERMAL_STATUS_UNKNOWN
                }
                // Clamp the lower bound only. Headroom above 1.0 means the device is past
                // the severe-throttling threshold and is exactly the signal Flux must act
                // on — clamping it to 1.0 would erase the worst case.
                val reading = ThermalReading(
                    headroom = raw.coerceAtLeast(0f),
                    status = status,
                    sampleElapsedMs = now,
                )
                lastGood = reading
                return ProviderResult.Ok(reading)
            }

            return retainOrFail(now, "getThermalHeadroom returned NaN")
        }

        // Not due for a fresh poll: reuse the last good sample if it is still young enough.
        return retainOrFail(now, "no fresh sample yet")
    }

    /** Reuse [lastGood] while it is younger than [maxAgeMs]; otherwise report failure. */
    private fun retainOrFail(now: Long, cause: String): ProviderResult<ThermalReading> {
        val retained = lastGood
        if (retained != null && (now - retained.sampleElapsedMs) <= maxAgeMs) {
            return ProviderResult.Ok(retained)
        }
        if (retained != null) lastGood = null // too old to keep pretending
        return ProviderResult.Failed(cause)
    }

    companion object {
        /**
         * Android enforces a minimum interval between `getThermalHeadroom()` calls and
         * returns a cached or NaN result when polled faster. 1 s is the documented floor.
         */
        const val DEFAULT_MIN_INTERVAL_MS = 1_000L

        /** Beyond this, a retained sample stops being evidence about the device's state. */
        const val DEFAULT_MAX_AGE_MS = 10_000L

        /** Forecast window passed to `getThermalHeadroom(int)`, in seconds. */
        const val DEFAULT_FORECAST_SECONDS = 1
    }
}
