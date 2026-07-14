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

class ThermalSamplerTest {

    private class FakeClock(var now: Long = 0) : MonotonicClock {
        override fun elapsedMillis(): Long = now
    }

    private class FakeThermal(
        override var supported: Boolean = true,
        var headroom: Float = 0.2f,
        var status: Int = 1,
        var throwOnRead: Boolean = false,
    ) : ThermalSource {
        var readCount = 0
        override fun readHeadroom(forecastSeconds: Int): Float {
            readCount++
            if (throwOnRead) throw IllegalStateException("vendor HAL exploded")
            return headroom
        }

        override fun readStatus(): Int = status
    }

    @Test
    fun `unsupported source reports Unsupported and is never polled`() {
        val source = FakeThermal(supported = false)
        val sampler = ThermalSampler(source, FakeClock())

        assertEquals(ProviderResult.Unsupported, sampler.sample())
        assertEquals(0, source.readCount)
    }

    /** The framework is not polled at the 500 ms telemetry rate. */
    @Test
    fun `framework is polled at most once per minimum interval`() {
        val clock = FakeClock()
        val source = FakeThermal()
        val sampler = ThermalSampler(source, clock, minIntervalMs = 1_000L)

        // 500 ms telemetry cadence over 3 seconds -> 6 collect cycles.
        repeat(6) {
            sampler.sample()
            clock.now += 500
        }

        // 0ms, 1000ms, 2000ms, 2500ms->no. Polls at 0, 1000, 2000 = 3.
        assertEquals(3, source.readCount)
    }

    @Test
    fun `values above 1_0 are preserved`() {
        val source = FakeThermal(headroom = 1.42f, status = 5)
        val sampler = ThermalSampler(source, FakeClock())

        val result = sampler.sample()
        assertTrue(result is ProviderResult.Ok)
        assertEquals(1.42f, (result as ProviderResult.Ok).value.headroom, 0.0001f)
    }

    @Test
    fun `negative headroom is clamped up to zero`() {
        val sampler = ThermalSampler(FakeThermal(headroom = -0.3f), FakeClock())
        val result = sampler.sample() as ProviderResult.Ok
        assertEquals(0f, result.value.headroom, 0.0001f)
    }

    /** A transient NaN must not be reported as a cool device. */
    @Test
    fun `transient NaN retains the last good sample`() {
        val clock = FakeClock()
        val source = FakeThermal(headroom = 0.9f, status = 4)
        val sampler = ThermalSampler(source, clock, minIntervalMs = 1_000L, maxAgeMs = 10_000L)

        val good = sampler.sample() as ProviderResult.Ok
        assertEquals(0.9f, good.value.headroom, 0.0001f)

        source.headroom = Float.NaN
        clock.now += 1_000
        val retained = sampler.sample()

        assertTrue("NaN should retain last good, not fail immediately", retained is ProviderResult.Ok)
        assertEquals(0.9f, (retained as ProviderResult.Ok).value.headroom, 0.0001f)
        // The sample timestamp is the *original* one, so the consumer can see it is stale.
        assertEquals(0L, retained.value.sampleElapsedMs)
    }

    @Test
    fun `retained sample expires once it exceeds max age`() {
        val clock = FakeClock()
        val source = FakeThermal(headroom = 0.9f)
        val sampler = ThermalSampler(source, clock, minIntervalMs = 1_000L, maxAgeMs = 5_000L)

        sampler.sample()
        source.headroom = Float.NaN

        clock.now = 5_000
        assertTrue("still within max age", sampler.sample() is ProviderResult.Ok)

        clock.now = 5_001
        assertTrue("beyond max age must not be trusted", sampler.sample() is ProviderResult.Failed)
    }

    @Test
    fun `a throwing HAL degrades thermal only, and does not propagate`() {
        val source = FakeThermal(throwOnRead = true)
        val sampler = ThermalSampler(source, FakeClock())

        val result = sampler.sample()
        assertTrue(result is ProviderResult.Failed)
        assertTrue((result as ProviderResult.Failed).cause.contains("threw"))
    }

    @Test
    fun `recovery after NaN produces a fresh sample and timestamp`() {
        val clock = FakeClock()
        val source = FakeThermal(headroom = 0.9f)
        val sampler = ThermalSampler(source, clock, minIntervalMs = 1_000L)
        sampler.sample()

        source.headroom = Float.NaN
        clock.now = 1_000
        sampler.sample()

        source.headroom = 0.3f
        clock.now = 2_000
        val recovered = sampler.sample() as ProviderResult.Ok

        assertEquals(0.3f, recovered.value.headroom, 0.0001f)
        assertEquals(2_000L, recovered.value.sampleElapsedMs)
    }
}
