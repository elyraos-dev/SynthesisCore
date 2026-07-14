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
 * One telemetry provider.
 *
 * Implementations may throw; [TelemetryCollector] contains the blast radius. A provider
 * that is structurally absent on this device should return [ProviderResult.Unsupported]
 * rather than throwing on every cycle.
 */
fun interface Provider<T> {
    fun sample(): ProviderResult<T>
}

/**
 * Assembles a [TelemetrySnapshot] from independent providers.
 *
 * Each provider is sampled inside its own guard. A vendor ROM whose `INotificationManager`
 * lacks `getZenMode`, or whose thermal HAL throws, degrades exactly one field to
 * [ProviderResult.Failed] — the remaining providers still report, and the daemon still
 * emits a snapshot. Previously a single throwing service call aborted the whole write and
 * Flux simply stopped receiving telemetry.
 *
 * @param errorSink receives a *deduplicated* description of each provider failure. Sampling
 *   runs twice a second; logging every failure would flood the log within minutes, so the
 *   collector only reports a given provider's failure when its cause changes.
 */
class TelemetryCollector(
    private val clock: MonotonicClock,
    private val daemonPid: Int,
    private val kernelIsGki: Boolean,
    private val foreground: Provider<ForegroundApp>,
    private val screenAwake: Provider<Boolean>,
    private val batterySaver: Provider<Boolean>,
    private val chargingState: Provider<Boolean>,
    private val thermal: Provider<ThermalReading>,
    private val audioActive: Provider<Boolean>,
    private val zenMode: Provider<Int>,
    private val errorSink: (String) -> Unit = {},
) {

    private var sequence: Long = 0
    private val reportedFailures = HashMap<String, String>()

    /** Sample every provider once and return a complete snapshot. */
    fun collect(): TelemetrySnapshot {
        val snapshot = TelemetrySnapshot(
            sequence = ++sequence,
            updatedElapsedMs = clock.elapsedMillis(),
            daemonPid = daemonPid,
            foreground = guard("foreground", foreground),
            screenAwake = guard("screen", screenAwake),
            batterySaver = guard("battery_saver", batterySaver),
            chargingState = guard("charging", chargingState),
            thermal = guard("thermal", thermal),
            audioActive = guard("audio", audioActive),
            zenMode = guard("zen", zenMode),
            kernelIsGki = kernelIsGki,
        )
        return snapshot
    }

    /**
     * Sample one provider, converting any escape into [ProviderResult.Failed].
     *
     * Throwable rather than Exception: a reflective call into a hidden framework API can
     * surface an Error (NoSuchMethodError, NoClassDefFoundError) on an unexpected ROM, and
     * letting that unwind would take the whole daemon down with it.
     */
    private fun <T> guard(name: String, provider: Provider<T>): ProviderResult<T> {
        val result = try {
            provider.sample()
        } catch (t: Throwable) {
            ProviderResult.Failed("${t.javaClass.simpleName}: ${t.message}")
        }

        when (result) {
            is ProviderResult.Failed -> reportOnce(name, result.cause)
            else -> reportedFailures.remove(name) // recovered; allow the next failure to log
        }
        return result
    }

    private fun reportOnce(name: String, cause: String) {
        if (reportedFailures[name] == cause) return
        reportedFailures[name] = cause
        errorSink("provider '$name' failed: $cause")
    }
}
