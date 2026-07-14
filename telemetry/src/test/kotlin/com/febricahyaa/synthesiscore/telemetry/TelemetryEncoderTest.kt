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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TelemetryEncoderTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun snapshot(
        thermal: ProviderResult<ThermalReading> = ProviderResult.Ok(
            ThermalReading(headroom = 0.84f, status = 3, sampleElapsedMs = 81_723_000L)
        ),
        zen: ProviderResult<Int> = ProviderResult.Ok(0),
        foreground: ProviderResult<ForegroundApp> = ProviderResult.Ok(
            ForegroundApp("com.example.game", 9821, 10384)
        ),
        updatedElapsedMs: Long = 81_723_451L,
        sequence: Long = 125L,
    ) = TelemetrySnapshot(
        sequence = sequence,
        updatedElapsedMs = updatedElapsedMs,
        daemonPid = 2841,
        foreground = foreground,
        screenAwake = ProviderResult.Ok(true),
        batterySaver = ProviderResult.Ok(false),
        chargingState = ProviderResult.Ok(true),
        thermal = thermal,
        audioActive = ProviderResult.Ok(true),
        zenMode = zen,
        kernelIsGki = true,
    )

    private fun fields(encoded: String): Map<String, String> =
        encoded.trim().lines().associate { line ->
            val idx = line.indexOf(' ')
            line.substring(0, idx) to line.substring(idx + 1)
        }

    @Test
    fun `emits schema version 2`() {
        val f = fields(TelemetryEncoder.encode(snapshot()))
        assertEquals("2", f[TelemetryContract.KEY_SCHEMA_VERSION])
    }

    @Test
    fun `emits every contract key exactly once in canonical order`() {
        val keys = TelemetryEncoder.encode(snapshot()).trim().lines().map { it.substringBefore(' ') }
        assertEquals(TelemetryContract.KEY_ORDER, keys)
    }

    /**
     * The regression that motivated the whole v2 contract: on an Indonesian, German or
     * French device the platform default locale renders 0.84 as "0,84", which the C++
     * consumer's strtof() truncates to 0.0 — i.e. "no thermal pressure at all".
     */
    @Test
    fun `float output is identical under a comma-decimal locale`() {
        Locale.setDefault(Locale.ROOT)
        val root = TelemetryEncoder.encode(snapshot())

        for (locale in listOf(Locale.forLanguageTag("id-ID"), Locale.GERMANY, Locale.FRANCE)) {
            Locale.setDefault(locale)
            val localized = TelemetryEncoder.encode(snapshot())
            assertEquals("locale $locale changed the encoding", root, localized)
            assertTrue(
                "locale $locale emitted a comma decimal separator",
                !fields(localized)[TelemetryContract.KEY_THERMAL_HEADROOM]!!.contains(',')
            )
        }
    }

    @Test
    fun `headroom above the severe threshold is preserved not clamped`() {
        val f = fields(
            TelemetryEncoder.encode(
                snapshot(thermal = ProviderResult.Ok(ThermalReading(1.73f, 5, 1_000L)))
            )
        )
        assertEquals("1.73", f[TelemetryContract.KEY_THERMAL_HEADROOM])
        assertEquals("1", f[TelemetryContract.KEY_THERMAL_VALID])
    }

    @Test
    fun `unsupported thermal reports unavailable and invalid, never a numeric sentinel`() {
        val f = fields(TelemetryEncoder.encode(snapshot(thermal = ProviderResult.Unsupported)))
        assertEquals("0", f[TelemetryContract.KEY_THERMAL_AVAILABLE])
        assertEquals("0", f[TelemetryContract.KEY_THERMAL_VALID])
        assertEquals("nan", f[TelemetryContract.KEY_THERMAL_HEADROOM])
        assertEquals("-1", f[TelemetryContract.KEY_THERMAL_STATUS])
    }

    @Test
    fun `failed thermal is available but invalid`() {
        val f = fields(
            TelemetryEncoder.encode(snapshot(thermal = ProviderResult.Failed("NaN")))
        )
        assertEquals("1", f[TelemetryContract.KEY_THERMAL_AVAILABLE])
        assertEquals("0", f[TelemetryContract.KEY_THERMAL_VALID])
    }

    @Test
    fun `thermal age is derived from the sample timestamp not the snapshot timestamp`() {
        val f = fields(
            TelemetryEncoder.encode(
                snapshot(
                    thermal = ProviderResult.Ok(ThermalReading(0.5f, 2, 80_000L)),
                    updatedElapsedMs = 82_500L,
                )
            )
        )
        assertEquals("80000", f[TelemetryContract.KEY_THERMAL_SAMPLE_ELAPSED_MS])
        assertEquals("2500", f[TelemetryContract.KEY_THERMAL_AGE_MS])
    }

    /** Zen is an enum; flattening it to a bool is what made Flux restore the wrong mode. */
    @Test
    fun `full zen mode value survives encoding`() {
        for (mode in 0..3) {
            val f = fields(TelemetryEncoder.encode(snapshot(zen = ProviderResult.Ok(mode))))
            assertEquals(mode.toString(), f[TelemetryContract.KEY_ZEN_MODE])
            assertEquals("1", f[TelemetryContract.KEY_ZEN_AVAILABLE])
        }
    }

    @Test
    fun `absent foreground app is flagged unavailable rather than reported as pid zero`() {
        val f = fields(
            TelemetryEncoder.encode(snapshot(foreground = ProviderResult.Failed("no window")))
        )
        assertEquals("1", f[TelemetryContract.KEY_FOREGROUND_AVAILABLE])
        assertEquals(TelemetryContract.PACKAGE_NONE, f[TelemetryContract.KEY_FOCUSED_PACKAGE])
        assertEquals("0", f[TelemetryContract.KEY_FOCUSED_PID])
    }

    @Test
    fun `capability flags are independent of values`() {
        val f = fields(TelemetryEncoder.encode(snapshot()))
        assertEquals("1", f[TelemetryContract.KEY_SCREEN_AVAILABLE])
        assertEquals("1", f[TelemetryContract.KEY_POWER_AVAILABLE])
        assertEquals("1", f[TelemetryContract.KEY_CHARGING_AVAILABLE])
        assertEquals("1", f[TelemetryContract.KEY_AUDIO_AVAILABLE])
        // battery_saver is false, but the power provider is still available.
        assertEquals("0", f[TelemetryContract.KEY_BATTERY_SAVER])
    }
}
