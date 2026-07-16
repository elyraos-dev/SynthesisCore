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
 * Outcome of sampling a single telemetry provider.
 *
 * Availability and validity are distinct, and both are distinct from the value:
 * a device that predates an API reports [Unsupported] forever, while a device
 * that supports the API but whose HAL returned garbage on this cycle reports
 * [Failed]. Collapsing either into a sentinel value (`0`, `-1`) is what let the
 * v1 contract confuse "cool device" with "no thermal data".
 */
sealed interface ProviderResult<out T> {

    /** The provider produced a value this cycle. */
    data class Ok<out T>(val value: T) : ProviderResult<T>

    /** The provider does not exist on this device / API level. Permanent. */
    data object Unsupported : ProviderResult<Nothing>

    /** The provider exists but this sample failed or is not currently valid. Transient. */
    data class Failed(val cause: String) : ProviderResult<Nothing>

    /** True when the provider exists on this device, regardless of this cycle's outcome. */
    val available: Boolean get() = this !is Unsupported

    /** True only when this cycle produced a usable value. */
    val valid: Boolean get() = this is Ok

    /** The value, or `null` when unavailable or invalid. */
    fun valueOrNull(): T? = (this as? Ok)?.value
}

/** Resolved foreground application. */
data class ForegroundApp(
    val packageName: String,
    val pid: Int,
    val uid: Int,
)

/**
 * A thermal sample using Android's real `PowerManager.getThermalHeadroom()` semantics.
 *
 * @property headroom Normalised thermal headroom. **0.0 means no thermal pressure and
 *   1.0 means the device has reached the severe-throttling threshold.** Values above
 *   1.0 are legal and meaningful — they indicate the device is past that threshold —
 *   and are deliberately *not* clamped away. Only the lower bound is clamped, since a
 *   negative headroom has no defined meaning.
 * @property status `PowerManager.getCurrentThermalStatus()`, i.e. THERMAL_STATUS_NONE(0)
 *   through THERMAL_STATUS_SHUTDOWN(6), or [TelemetryContract.THERMAL_STATUS_UNKNOWN].
 * @property sampleElapsedMs Monotonic timestamp at which this headroom was actually read
 *   from the framework. It is *not* the snapshot timestamp: thermal is sampled on a slower
 *   cadence than the rest of the telemetry, so consumers need the real sample age to decide
 *   whether the reading is still trustworthy.
 */
data class ThermalReading(
    val headroom: Float,
    val status: Int,
    val sampleElapsedMs: Long,
)

/**
 * A complete, immutable telemetry snapshot.
 *
 * Snapshots are published whole. A consumer either sees a fully-formed snapshot or
 * the previous one — never a half-updated mixture of the two.
 */
data class TelemetrySnapshot(
    val sequence: Long,
    val updatedElapsedMs: Long,
    val daemonPid: Int,
    val foreground: ProviderResult<ForegroundApp>,
    val screenAwake: ProviderResult<Boolean>,
    val batterySaver: ProviderResult<Boolean>,
    val chargingState: ProviderResult<Boolean>,
    val thermal: ProviderResult<ThermalReading>,
    val audioActive: ProviderResult<Boolean>,
    val zenMode: ProviderResult<Int>,
    val kernelIsGki: Boolean,
    val schemaVersion: Int = TelemetryContract.SCHEMA_VERSION,
)
