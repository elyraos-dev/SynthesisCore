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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCollectorTest {

    private class FakeClock(var now: Long = 0) : MonotonicClock {
        override fun elapsedMillis(): Long = now
    }

    private fun collector(
        clock: MonotonicClock = FakeClock(),
        thermal: Provider<ThermalReading> = Provider { ProviderResult.Unsupported },
        zen: Provider<Int> = Provider { ProviderResult.Ok(0) },
        foreground: Provider<ForegroundApp> = Provider {
            ProviderResult.Ok(ForegroundApp("com.example.app", 100, 10001))
        },
        errors: MutableList<String> = mutableListOf(),
    ) = TelemetryCollector(
        clock = clock,
        daemonPid = 4242,
        kernelIsGki = true,
        foreground = foreground,
        screenAwake = { ProviderResult.Ok(true) },
        batterySaver = { ProviderResult.Ok(false) },
        chargingState = { ProviderResult.Ok(false) },
        thermal = thermal,
        audioActive = { ProviderResult.Ok(false) },
        zenMode = zen,
        errorSink = { errors.add(it) },
    )

    @Test
    fun `sequence starts at one and increments per collect`() {
        val c = collector()
        assertEquals(1L, c.collect().sequence)
        assertEquals(2L, c.collect().sequence)
        assertEquals(3L, c.collect().sequence)
    }

    @Test
    fun `timestamps are taken from the monotonic clock`() {
        val clock = FakeClock(9_000)
        val c = collector(clock = clock)
        assertEquals(9_000L, c.collect().updatedElapsedMs)
        clock.now = 9_500
        assertEquals(9_500L, c.collect().updatedElapsedMs)
    }

    /** A single broken provider must not take the snapshot — or the daemon — down. */
    @Test
    fun `a throwing provider is isolated and the rest of the snapshot survives`() {
        val c = collector(zen = Provider { throw NoSuchMethodError("getZenMode") })
        val snap = c.collect()

        assertTrue(snap.zenMode is ProviderResult.Failed)
        assertTrue("other providers must still report", snap.screenAwake is ProviderResult.Ok)
        assertEquals("com.example.app", snap.foreground.valueOrNull()?.packageName)
    }

    @Test
    fun `an Error from a hidden API reflection call is caught, not just Exception`() {
        val c = collector(foreground = Provider { throw NoClassDefFoundError("IActivityTaskManager") })
        val snap = c.collect()
        assertTrue(snap.foreground is ProviderResult.Failed)
        assertTrue((snap.foreground as ProviderResult.Failed).cause.contains("NoClassDefFoundError"))
    }

    /** Sampling runs twice a second; an unchanged failure must not be logged 120 times a minute. */
    @Test
    fun `a repeated identical failure is only reported once`() {
        val errors = mutableListOf<String>()
        val c = collector(zen = Provider { throw IllegalStateException("boom") }, errors = errors)

        repeat(50) { c.collect() }
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("zen"))
    }

    @Test
    fun `a failure is reported again after the provider recovers`() {
        val errors = mutableListOf<String>()
        var broken = true
        val c = collector(
            zen = Provider {
                if (broken) throw IllegalStateException("boom") else ProviderResult.Ok(2)
            },
            errors = errors,
        )

        repeat(5) { c.collect() }
        assertEquals(1, errors.size)

        broken = false
        assertEquals(ProviderResult.Ok(2), c.collect().zenMode)

        broken = true
        repeat(5) { c.collect() }
        assertEquals("failure after recovery must log again", 2, errors.size)
    }

    @Test
    fun `unsupported providers are distinguished from failed ones`() {
        val snap = collector(thermal = Provider { ProviderResult.Unsupported }).collect()
        assertEquals(false, snap.thermal.available)
        assertEquals(false, snap.thermal.valid)

        val failed = collector(thermal = Provider { ProviderResult.Failed("HAL timeout") }).collect()
        assertEquals(true, failed.thermal.available)
        assertEquals(false, failed.thermal.valid)
    }
}
