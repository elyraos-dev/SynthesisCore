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

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Replaces the status file atomically.
 *
 * ## The inotify contract this creates
 *
 * The write sequence is: create `<status>.tmp`, write, `fsync`, close, then
 * `rename(<status>.tmp -> <status>)`. On a watch registered against the *parent
 * directory*, the kernel therefore delivers:
 *
 *   - `IN_CREATE`      name=`<status>.tmp`
 *   - `IN_MODIFY`      name=`<status>.tmp`
 *   - `IN_CLOSE_WRITE` name=`<status>.tmp`   ← note the name: the temp file, never the target
 *   - `IN_MOVED_FROM`  name=`<status>.tmp`
 *   - **`IN_MOVED_TO`  name=`<status>`**     ← the only event that announces a new snapshot
 *
 * **The target file never receives `IN_CLOSE_WRITE`.** Nothing ever opens it for writing;
 * it only ever appears as the destination of a rename. A consumer that watches for
 * `IN_CLOSE_WRITE` on the target name will therefore see the file update exactly zero
 * times — which is precisely the bug this contract exists to make impossible to
 * re-introduce silently. Consumers must watch for `IN_MOVED_TO`.
 *
 * A consumer holding an open fd or an inode watch (`inotify_add_watch` on the *file*)
 * also observes nothing, because rename swaps the inode. The watch must be on the directory.
 */
class AtomicStatusWriter(
    private val target: File,
    private val clock: MonotonicClock,
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
) {

    private val tmp = File(target.parentFile, target.name + TMP_SUFFIX)

    /** Payload of the last successful write, excluding the volatile envelope fields. */
    private var lastSemanticPayload: String? = null
    private var lastWriteMs: Long = Long.MIN_VALUE

    /**
     * Write [snapshot] if anything meaningful changed, or if the heartbeat is due.
     *
     * Rewriting an identical snapshot 120 times a minute is pure I/O for no information.
     * But never rewriting an unchanged snapshot is worse: it makes a *dead* producer
     * indistinguishable from an *idle* one, since the consumer's only freshness signal is
     * the file. So an unchanged snapshot is still republished every [heartbeatIntervalMs],
     * which keeps `updated_elapsed_ms` a usable liveness signal.
     *
     * @return true if bytes were written to disk this call.
     * @throws IOException if the snapshot could not be durably replaced. The previous
     *   snapshot is left intact in that case.
     */
    fun writeIfNeeded(snapshot: TelemetrySnapshot): Boolean {
        val encoded = TelemetryEncoder.encode(snapshot)
        val semantic = stripVolatileFields(encoded)
        val now = clock.elapsedMillis()

        val changed = semantic != lastSemanticPayload
        val heartbeatDue = lastWriteMs == Long.MIN_VALUE || (now - lastWriteMs) >= heartbeatIntervalMs
        if (!changed && !heartbeatDue) return false

        writeAtomically(encoded)

        lastSemanticPayload = semantic
        lastWriteMs = now
        return true
    }

    /** Unconditionally replace the status file. */
    fun writeAtomically(encoded: String) {
        target.parentFile?.mkdirs()

        try {
            FileOutputStream(tmp).use { out ->
                out.write(encoded.toByteArray(Charsets.UTF_8))
                out.flush()
                // Durability before the rename: a rename that beats its own data to disk
                // would leave a valid-looking but empty snapshot after a hard reset.
                out.fd.sync()
            }

            runCatching { Files.setPosixFilePermissions(tmp.toPath(), FILE_PERMISSIONS) }

            // ATOMIC_MOVE gives the POSIX rename() guarantee: the consumer sees either the
            // old snapshot or the new one, never a partial file, and never a missing file.
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )

            syncParentDirectory()
        } catch (e: IOException) {
            // Leave the previous snapshot in place and clear the debris.
            runCatching { if (tmp.exists()) tmp.delete() }
            throw e
        }
    }

    /**
     * fsync the parent directory so the rename itself is durable.
     *
     * Best-effort: opening a directory as a channel is a Linux affordance, not a Java
     * guarantee, and some filesystems reject the force(). Failing to sync the directory
     * costs durability across a hard reset, not correctness at runtime, so it is not
     * escalated.
     */
    private fun syncParentDirectory() {
        val parent = target.parentFile?.toPath() ?: return
        runCatching {
            FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /**
     * Remove the fields that change on every single snapshot, leaving only the fields
     * that carry actual information about device state.
     */
    private fun stripVolatileFields(encoded: String): String =
        encoded.lineSequence()
            .filterNot { line ->
                VOLATILE_KEYS.any { key -> line.startsWith("$key ") }
            }
            .joinToString("\n")

    companion object {
        const val TMP_SUFFIX = ".tmp"

        /** Republish an unchanged snapshot at least this often, to prove liveness. */
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 2_000L

        /**
         * Fields excluded from change detection.
         *
         * These are timestamps and counters, not device state: they advance on their own
         * schedule, so including them would mark every snapshot "changed" and defeat the
         * check entirely. `thermal_sample_elapsed_ms` belongs here too — it ticks forward
         * on each thermal poll even when the measured headroom is unchanged, and a device
         * sitting at a steady 0.40 headroom is not new information. Liveness and freshness
         * are carried by the heartbeat republish and by `updated_elapsed_ms`, not by these.
         */
        private val VOLATILE_KEYS = listOf(
            TelemetryContract.KEY_SEQUENCE,
            TelemetryContract.KEY_UPDATED_ELAPSED_MS,
            TelemetryContract.KEY_THERMAL_SAMPLE_ELAPSED_MS,
            TelemetryContract.KEY_THERMAL_AGE_MS,
        )

        /** rw-r--r--: the daemon writes as root; Flux reads as root. */
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
        )
    }
}
