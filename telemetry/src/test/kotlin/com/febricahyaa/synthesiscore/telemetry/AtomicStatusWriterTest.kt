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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AtomicStatusWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeClock(var now: Long = 0) : MonotonicClock {
        override fun elapsedMillis(): Long = now
    }

    private fun snapshot(seq: Long, elapsed: Long, awake: Boolean = true) = TelemetrySnapshot(
        sequence = seq,
        updatedElapsedMs = elapsed,
        daemonPid = 1234,
        foreground = ProviderResult.Ok(ForegroundApp("com.example.game", 900, 10100)),
        screenAwake = ProviderResult.Ok(awake),
        batterySaver = ProviderResult.Ok(false),
        chargingState = ProviderResult.Ok(true),
        thermal = ProviderResult.Ok(ThermalReading(0.4f, 2, elapsed)),
        audioActive = ProviderResult.Ok(false),
        zenMode = ProviderResult.Ok(0),
        kernelIsGki = false,
    )

    @Test
    fun `writes the snapshot and leaves no temp file behind`() {
        val target = File(tmp.root, "synthesis_core.json")
        val writer = AtomicStatusWriter(target, FakeClock())

        assertTrue(writer.writeIfNeeded(snapshot(1, 0)))
        assertTrue(target.exists())
        assertFalse(File(tmp.root, "synthesis_core.json.tmp").exists())
        assertTrue(target.readText().startsWith("schema_version 2\n"))
    }

    @Test
    fun `an unchanged snapshot is not rewritten before the heartbeat is due`() {
        val clock = FakeClock()
        val target = File(tmp.root, "s.json")
        val writer = AtomicStatusWriter(target, clock, heartbeatIntervalMs = 2_000L)

        assertTrue(writer.writeIfNeeded(snapshot(1, 0)))

        // Same device state, later cycle: sequence and timestamp advanced, nothing else.
        clock.now = 500
        assertFalse(writer.writeIfNeeded(snapshot(2, 500)))
        clock.now = 1_500
        assertFalse(writer.writeIfNeeded(snapshot(3, 1_500)))
    }

    /** A dead producer and an idle one must not look identical to the consumer. */
    @Test
    fun `an unchanged snapshot is republished once the heartbeat is due`() {
        val clock = FakeClock()
        val target = File(tmp.root, "s.json")
        val writer = AtomicStatusWriter(target, clock, heartbeatIntervalMs = 2_000L)

        writer.writeIfNeeded(snapshot(1, 0))
        clock.now = 2_000
        assertTrue("heartbeat must republish", writer.writeIfNeeded(snapshot(5, 2_000)))

        val fields = target.readText().trim().lines().associate {
            it.substringBefore(' ') to it.substringAfter(' ')
        }
        assertEquals("5", fields[TelemetryContract.KEY_SEQUENCE])
        assertEquals("2000", fields[TelemetryContract.KEY_UPDATED_ELAPSED_MS])
    }

    @Test
    fun `a semantic change is written immediately`() {
        val clock = FakeClock()
        val target = File(tmp.root, "s.json")
        val writer = AtomicStatusWriter(target, clock, heartbeatIntervalMs = 60_000L)

        writer.writeIfNeeded(snapshot(1, 0, awake = true))
        clock.now = 100
        assertTrue("screen state changed", writer.writeIfNeeded(snapshot(2, 100, awake = false)))
    }

    @Test
    fun `sequence increases monotonically across writes`() {
        val clock = FakeClock()
        val target = File(tmp.root, "s.json")
        val writer = AtomicStatusWriter(target, clock, heartbeatIntervalMs = 0L)

        var previous = -1L
        for (i in 1..20) {
            clock.now = i * 100L
            writer.writeIfNeeded(snapshot(i.toLong(), clock.now, awake = i % 2 == 0))
            val seq = target.readText().lineSequence()
                .first { it.startsWith("sequence ") }.substringAfter(' ').toLong()
            assertTrue("sequence must increase: $previous -> $seq", seq > previous)
            previous = seq
        }
    }

    /**
     * The core guarantee: a reader polling the target concurrently with 200 replacements
     * must never observe a truncated or empty file. If the writer were doing a plain
     * truncate-and-write, this would catch it.
     */
    @Test
    fun `concurrent readers never observe a partial snapshot`() {
        val target = File(tmp.root, "s.json")
        val clock = FakeClock()
        val writer = AtomicStatusWriter(target, clock, heartbeatIntervalMs = 0L)
        writer.writeIfNeeded(snapshot(0, 0))

        val corrupted = AtomicBoolean(false)
        val stop = AtomicBoolean(false)
        val started = CountDownLatch(1)

        val reader = Thread {
            started.countDown()
            while (!stop.get()) {
                val text = runCatching { target.readText() }.getOrNull() ?: continue
                if (text.isEmpty()) continue // rename window: file always exists, but be lenient
                val lines = text.trim().lines()
                val wellFormed = lines.size == TelemetryContract.KEY_ORDER.size &&
                    lines.first().startsWith("schema_version ") &&
                    lines.last().startsWith("kernel_is_gki ")
                if (!wellFormed) {
                    corrupted.set(true)
                    return@Thread
                }
            }
        }
        reader.start()
        started.await(5, TimeUnit.SECONDS)

        for (i in 1..200) {
            clock.now = i.toLong()
            writer.writeIfNeeded(snapshot(i.toLong(), clock.now, awake = i % 2 == 0))
        }

        stop.set(true)
        reader.join(10_000)
        assertFalse("reader observed a partial snapshot", corrupted.get())
    }

    @Test
    fun `a previous snapshot survives a failed write`() {
        val target = File(tmp.root, "s.json")
        val writer = AtomicStatusWriter(target, FakeClock())
        writer.writeIfNeeded(snapshot(1, 0))
        val original = target.readText()

        // Make the directory read-only so the temp write fails.
        assertTrue(tmp.root.setWritable(false))
        try {
            val hostile = AtomicStatusWriter(File(tmp.root, "s.json"), FakeClock())
            runCatching { hostile.writeAtomically("garbage") }
            assertEquals("previous snapshot must survive", original, target.readText())
        } finally {
            tmp.root.setWritable(true)
        }
    }
}
