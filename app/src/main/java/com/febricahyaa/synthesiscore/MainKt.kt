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

package com.febricahyaa.synthesiscore

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process

import com.febricahyaa.synthesiscore.telemetry.AtomicStatusWriter
import com.febricahyaa.synthesiscore.telemetry.Provider
import com.febricahyaa.synthesiscore.telemetry.ProviderResult
import com.febricahyaa.synthesiscore.telemetry.TelemetryCollector
import com.febricahyaa.synthesiscore.telemetry.ThermalSampler

import org.lsposed.hiddenapibypass.HiddenApiBypass

import java.io.File
import java.lang.reflect.Method
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SynthesisCore: the Android-side telemetry producer for Flux.
 *
 * Runs headless via `app_process`, not as an Activity — hence @SuppressLint("StaticFieldLeak"):
 * there is no Activity lifecycle here and so no Context leak to speak of.
 *
 * Its entire job is to sample framework state and publish a versioned snapshot
 * (see `TelemetryContract`) to a file that Flux watches. It owns no tuning policy, applies
 * no profiles, and makes no decisions about performance.
 */
@SuppressLint("StaticFieldLeak", "DiscouragedPrivateApi", "PrivateApi")
object MainKt {

    /** Cadence for the cheap providers. Thermal runs slower, on its own interval. */
    private const val POLL_INTERVAL_MS = 500L

    private var systemContext: Context? = null
    private val shuttingDown = AtomicBoolean(false)

    private var lockChannel: FileChannel? = null
    private var lockHandle: FileLock? = null

    @JvmStatic
    fun main(args: Array<String>) {
        // Usage: app_process / com.febricahyaa.synthesiscore.MainKt <output_path> [lock_file_path]
        if (args.isEmpty()) {
            System.err.println("Usage: <output_path> [lock_file_path]")
            System.err.println("ERROR: output path is required.")
            return
        }
        val outputPath = args[0]
        val lockFilePath = args.getOrNull(1)

        bypassHiddenApiRestrictions()

        systemContext = createSystemContext()
        val ctx = systemContext
        if (ctx == null) {
            System.err.println("ERROR: System context is null.")
            return
        }

        if (lockFilePath != null && !acquireSingletonLock(lockFilePath)) {
            // acquireSingletonLock has already explained why.
            return
        }

        val collector = try {
            buildCollector(ctx)
        } catch (t: Throwable) {
            System.err.println("ERROR: Failed to initialise providers: ${t.message}")
            t.printStackTrace()
            releaseLock()
            return
        }

        val writer = AtomicStatusWriter(File(outputPath), AndroidClock)
        installShutdownHook()

        runMonitorLoop(collector, writer)

        releaseLock()
    }

    /**
     * Wire the Android providers into the pure collector.
     *
     * A service that fails to resolve yields a `null` here, which its provider reports as
     * [ProviderResult.Unsupported] rather than taking down initialisation. The old code
     * cast every service non-null and returned false from `initializeServices()` on the
     * first failure, so one uncooperative ROM service meant *no telemetry at all*.
     */
    private fun buildCollector(ctx: Context): TelemetryCollector {
        val powerManager = ctx.systemServiceOrNull<PowerManager>(Context.POWER_SERVICE)
        val activityManager = ctx.systemServiceOrNull<ActivityManager>(Context.ACTIVITY_SERVICE)
        val audioManager = ctx.systemServiceOrNull<AudioManager>(Context.AUDIO_SERVICE)
        val batteryManager = ctx.systemServiceOrNull<BatteryManager>(Context.BATTERY_SERVICE)

        val (atm, foregroundMethod) = initActivityTaskManager()
        val (notificationManager, zenMethod) = initNotificationManager()

        val thermalSampler = ThermalSampler(
            source = AndroidThermalSource(powerManager),
            clock = AndroidClock,
        )

        return TelemetryCollector(
            clock = AndroidClock,
            daemonPid = Process.myPid(),
            kernelIsGki = ReflectionSupport.isGkiKernel(),
            foreground = ForegroundAppProvider(atm, foregroundMethod, activityManager),
            screenAwake = ScreenProvider(powerManager),
            batterySaver = BatterySaverProvider(powerManager),
            chargingState = ChargingProvider(batteryManager),
            thermal = Provider { thermalSampler.sample() },
            audioActive = AudioProvider(audioManager),
            zenMode = ZenProvider(notificationManager, zenMethod),
            errorSink = { message -> System.err.println("WARN: $message") },
        )
    }

    /**
     * The monitor loop.
     *
     * Every provider failure is already contained by [TelemetryCollector], so the only
     * failure that reaches here is the write itself. A write failure is logged once and
     * retried on the next cycle — a full disk or a momentarily unwritable config directory
     * is not a reason to exit and hand Flux a permanently missing producer.
     */
    private fun runMonitorLoop(collector: TelemetryCollector, writer: AtomicStatusWriter) {
        var lastWriteError: String? = null

        while (!shuttingDown.get() && !Thread.currentThread().isInterrupted) {
            try {
                val snapshot = collector.collect()
                writer.writeIfNeeded(snapshot)
                lastWriteError = null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                val message = "${t.javaClass.simpleName}: ${t.message}"
                if (message != lastWriteError) {
                    System.err.println("ERROR: failed to publish telemetry: $message")
                    lastWriteError = message
                }
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        System.err.println("INFO: SynthesisCore monitor loop exited.")
    }

    /**
     * Release the lock and stop the loop on SIGTERM/SIGINT.
     *
     * The JVM runs shutdown hooks for both, and for a normal `System.exit`. Releasing the
     * lock deterministically is what lets Flux's supervisor tell "SynthesisCore exited"
     * from "SynthesisCore is wedged".
     */
    private fun installShutdownHook() {
        val monitorThread = Thread.currentThread()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                shuttingDown.set(true)
                monitorThread.interrupt()
                releaseLock()
            }
        )
    }

    /**
     * Take the singleton lock, or refuse to start.
     *
     * @return false when another instance already holds it, or the lock could not be taken.
     */
    private fun acquireSingletonLock(path: String): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()

            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )

            val lock = channel.tryLock()
            if (lock == null) {
                System.err.println("ERROR: Another instance holds the lock at '$path'.")
                channel.close()
                false
            } else {
                lockChannel = channel
                lockHandle = lock
                true
            }
        } catch (e: Exception) {
            System.err.println("ERROR: Failed to acquire lock at '$path': ${e.message}")
            false
        }
    }

    private fun releaseLock() {
        runCatching { lockHandle?.release() }
        runCatching { lockChannel?.close() }
        lockHandle = null
        lockChannel = null
    }

    private fun createSystemContext(): Context? {
        return try {
            val looperClass = Class.forName("android.os.Looper")
            if (looperClass.getMethod("getMainLooper").invoke(null) == null) {
                looperClass.getMethod("prepareMainLooper").invoke(null)
            }

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass.getMethod("systemMain").invoke(null)
                ?: activityThreadClass.getMethod("currentActivityThread").invoke(null)
                ?: error("Both systemMain() and currentActivityThread() returned null")

            activityThreadClass.getMethod("getSystemContext").invoke(thread) as? Context
                ?: error("getSystemContext() returned null")
        } catch (e: Exception) {
            System.err.println("ERROR: Failed to set up system context:")
            e.printStackTrace()
            null
        }
    }

    private fun bypassHiddenApiRestrictions() {
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (e: Exception) {
            // Zen mode and ATM foreground detection will report Unsupported/Failed rather
            // than silently reporting wrong values.
            System.err.println("WARN: HiddenApiBypass failed, some providers will be unavailable: ${e.message}")
        }
    }

    /** @return the ATM binder proxy and its foreground method, or (null, null) if unavailable. */
    private fun initActivityTaskManager(): Pair<Any?, Method?> {
        return try {
            val serviceName = ReflectionSupport.atmServiceName()
            val binder = ReflectionSupport.systemService(serviceName)
                ?: error("ServiceManager returned null binder for '$serviceName'")
            val atm = ReflectionSupport.bindInterface(
                "${ReflectionSupport.atmInterfaceName()}\$Stub",
                binder,
            )
            atm to ForegroundAppProvider.resolveForegroundMethod(atm)
        } catch (t: Throwable) {
            System.err.println("WARN: ActivityTaskManager unavailable: ${t.message}")
            null to null
        }
    }

    /** @return the INotificationManager proxy and its getZenMode method, or (null, null). */
    private fun initNotificationManager(): Pair<Any?, Method?> {
        return try {
            val binder = ReflectionSupport.systemService(Context.NOTIFICATION_SERVICE)
                ?: error("ServiceManager returned null binder for notification service")
            val manager = ReflectionSupport.bindInterface("android.app.INotificationManager\$Stub", binder)
            val method = ReflectionSupport.declaredMethods(manager.javaClass)
                .firstOrNull { it.name == "getZenMode" && it.parameterTypes.isEmpty() }
            manager to method
        } catch (t: Throwable) {
            System.err.println("WARN: NotificationManager unavailable: ${t.message}")
            null to null
        }
    }

    /** getSystemService without the unchecked cast blowing up initialisation on odd ROMs. */
    private inline fun <reified T> Context.systemServiceOrNull(name: String): T? = try {
        getSystemService(name) as? T
    } catch (t: Throwable) {
        System.err.println("WARN: system service '$name' unavailable: ${t.message}")
        null
    }
}
