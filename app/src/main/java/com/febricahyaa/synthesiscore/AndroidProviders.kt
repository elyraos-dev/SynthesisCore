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
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

import com.febricahyaa.synthesiscore.telemetry.ForegroundApp
import com.febricahyaa.synthesiscore.telemetry.MonotonicClock
import com.febricahyaa.synthesiscore.telemetry.Provider
import com.febricahyaa.synthesiscore.telemetry.ProviderResult
import com.febricahyaa.synthesiscore.telemetry.TelemetryContract
import com.febricahyaa.synthesiscore.telemetry.ThermalSource

import org.lsposed.hiddenapibypass.HiddenApiBypass

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Android implementations of the pure telemetry provider interfaces.
 *
 * Everything in this file touches the framework. Everything that decides what the
 * telemetry *means* lives in the `:telemetry` module, which is why that half is host
 * testable and this half is not.
 */

/** `SystemClock.elapsedRealtime()`: monotonic, and unlike `uptimeMillis()` it counts deep sleep. */
object AndroidClock : MonotonicClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

/**
 * `PowerManager.getThermalHeadroom()`, resolved reflectively once.
 *
 * The API landed in 31. On older devices [supported] is false forever and the sampler
 * never calls in, rather than throwing on every cycle.
 */
@SuppressLint("PrivateApi")
class AndroidThermalSource(private val powerManager: PowerManager?) : ThermalSource {

    private val headroomMethod: Method? = resolveHeadroomMethod()
    private val statusMethod: Method? = resolveStatusMethod()

    override val supported: Boolean = powerManager != null && headroomMethod != null

    private fun resolveHeadroomMethod(): Method? {
        if (Build.VERSION.SDK_INT < THERMAL_API_MIN_SDK) return null
        return try {
            PowerManager::class.java.getMethod("getThermalHeadroom", Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            System.err.println("WARN: getThermalHeadroom() absent on this build: ${e.message}")
            null
        }
    }

    private fun resolveStatusMethod(): Method? {
        if (Build.VERSION.SDK_INT < THERMAL_STATUS_MIN_SDK) return null
        return try {
            PowerManager::class.java.getMethod("getCurrentThermalStatus")
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    /**
     * Returns the raw headroom, or NaN when the platform declines to answer.
     *
     * NaN is a legitimate, expected answer — the thermal HAL returns it when it has no
     * valid reading yet, and when polled faster than its minimum interval. It is emphatically
     * *not* "the device is cool"; the caller retains the last good sample instead.
     */
    override fun readHeadroom(forecastSeconds: Int): Float {
        val method = headroomMethod ?: return Float.NaN
        return try {
            method.invoke(powerManager, forecastSeconds) as? Float ?: Float.NaN
        } catch (_: Exception) {
            Float.NaN
        }
    }

    override fun readStatus(): Int {
        val method = statusMethod ?: return TelemetryContract.THERMAL_STATUS_UNKNOWN
        return try {
            method.invoke(powerManager) as? Int ?: TelemetryContract.THERMAL_STATUS_UNKNOWN
        } catch (_: Exception) {
            TelemetryContract.THERMAL_STATUS_UNKNOWN
        }
    }

    private companion object {
        /** `getThermalHeadroom(int)` requires API 31. */
        const val THERMAL_API_MIN_SDK = 31

        /** `getCurrentThermalStatus()` requires API 29. */
        const val THERMAL_STATUS_MIN_SDK = 29
    }
}

/** Screen interactivity. Present on every supported API level. */
class ScreenProvider(private val powerManager: PowerManager?) : Provider<Boolean> {
    override fun sample(): ProviderResult<Boolean> {
        val pm = powerManager ?: return ProviderResult.Unsupported
        return ProviderResult.Ok(pm.isInteractive)
    }
}

/** Battery saver. Present on every supported API level. */
class BatterySaverProvider(private val powerManager: PowerManager?) : Provider<Boolean> {
    override fun sample(): ProviderResult<Boolean> {
        val pm = powerManager ?: return ProviderResult.Unsupported
        return ProviderResult.Ok(pm.isPowerSaveMode)
    }
}

/** Charging state via `BatteryManager.isCharging` (API 23+). */
class ChargingProvider(private val batteryManager: BatteryManager?) : Provider<Boolean> {
    override fun sample(): ProviderResult<Boolean> {
        val bm = batteryManager ?: return ProviderResult.Unsupported
        return ProviderResult.Ok(bm.isCharging)
    }
}

/**
 * Audio activity via `AudioManager.isMusicActive`.
 *
 * Covers MediaPlayer/ExoPlayer/AudioTrack, which is what mobile games use. It is a hint
 * for avoiding cosmetic profile churn during playback — never a reason to defer a thermal
 * downgrade.
 */
class AudioProvider(private val audioManager: AudioManager?) : Provider<Boolean> {
    override fun sample(): ProviderResult<Boolean> {
        val am = audioManager ?: return ProviderResult.Unsupported
        return ProviderResult.Ok(am.isMusicActive)
    }
}

/**
 * Zen mode via the hidden `INotificationManager.getZenMode()`.
 *
 * Reports the full enum (0 off, 1 priority, 2 total silence, 3 alarms only). Collapsing it
 * to a boolean is what previously made Flux restore "priority" as plain "on" after a game
 * exited, silently changing the user's setting.
 */
class ZenProvider(
    private val notificationManager: Any?,
    private val getZenModeMethod: Method?,
) : Provider<Int> {
    override fun sample(): ProviderResult<Int> {
        val nm = notificationManager ?: return ProviderResult.Unsupported
        val method = getZenModeMethod ?: return ProviderResult.Unsupported
        val value = method.invoke(nm) as? Int
            ?: return ProviderResult.Failed("getZenMode returned a non-Int")
        return ProviderResult.Ok(value)
    }
}

/**
 * Foreground application, resolved through `IActivityTaskManager`.
 *
 * The reflection here is deliberately forgiving: OEM ROMs rename and reshape these hidden
 * APIs constantly, so several method names and several field names are tried before giving
 * up. This logic is carried over unchanged from the previous implementation — it is known
 * to work across a wide range of ROMs and is not what this rework set out to fix.
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class ForegroundAppProvider(
    private val activityTaskManager: Any?,
    private val foregroundMethod: Method?,
    private val activityManager: ActivityManager?,
) : Provider<ForegroundApp> {

    private var bruteForceCandidates: List<Method>? = null

    override fun sample(): ProviderResult<ForegroundApp> {
        if (activityTaskManager == null) return ProviderResult.Unsupported

        val result = invokeForegroundMethod()
            ?: return ProviderResult.Failed("no foreground method produced a result")

        val pkg = when (result) {
            is List<*> -> packageFromList(result)
            else -> packageFromObject(result)
        } ?: return ProviderResult.Failed("could not resolve a package name")

        if (pkg == TelemetryContract.PACKAGE_NONE) {
            // A genuinely empty task list — e.g. the launcher is not yet up. The provider
            // works; there is simply nothing focused.
            return ProviderResult.Failed("no focused task")
        }

        val (pid, uid) = resolvePidUid(pkg)
        return ProviderResult.Ok(ForegroundApp(pkg, pid, uid))
    }

    /**
     * Resolve the PID/UID of [pkg].
     *
     * A freshly-launched app is briefly absent from `getRunningAppProcesses()`. The previous
     * implementation blocked the whole monitor loop for up to 500 ms retrying. It no longer
     * does: a PID of 0 is reported honestly for one cycle and the next 500 ms cycle picks it
     * up. Flux keys game sessions off the package name and does its own PID lookup, so a
     * one-cycle 0 costs nothing — whereas stalling the loop delayed *every other* provider,
     * thermal included.
     */
    private fun resolvePidUid(pkg: String): Pair<Int, Int> {
        return try {
            activityManager?.runningAppProcesses
                ?.find { it.processName == pkg || it.pkgList?.contains(pkg) == true }
                ?.let { it.pid to it.uid }
                ?: (0 to 0)
        } catch (_: Exception) {
            0 to 0
        }
    }

    private fun packageFromList(list: List<*>): String? {
        if (list.isEmpty()) return TelemetryContract.PACKAGE_NONE
        list.forEach { element ->
            extractComponentName(element)?.let { return it.packageName }
        }
        return list[0]?.let { packageFromObject(it) }
    }

    private fun packageFromObject(obj: Any): String? {
        extractComponentName(obj)?.let { return it.packageName }
        return findPackageLikeString(obj)
    }

    private fun invokeForegroundMethod(): Any? {
        val method = foregroundMethod ?: return bruteForceForegroundMethod()
        return tryInvokeForegroundMethod(method) ?: bruteForceForegroundMethod()
    }

    private fun tryInvokeForegroundMethod(method: Method): Any? {
        val target = activityTaskManager ?: return null
        return try {
            when {
                method.name == "getTasks" || method.name == "getRunningTasks" ->
                    tryInvokeWithArgs(method, target, arrayOf(1), arrayOf(1, 0), arrayOf(1, false, false))

                method.parameterTypes.isEmpty() -> method.invoke(target)
                else -> tryInvokeWithArgs(method, target, arrayOf(0))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryInvokeWithArgs(method: Method, target: Any, vararg argSets: Array<Any>): Any? {
        for (args in argSets) {
            try {
                return method.invoke(target, *args)
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun bruteForceForegroundMethod(): Any? {
        val target = activityTaskManager ?: return null
        return try {
            val candidates = bruteForceCandidates
                ?: ReflectionSupport.declaredMethods(target.javaClass)
                    .filter {
                        val name = it.name.lowercase()
                        name.contains("focus") || name.contains("top") || name.contains("task")
                    }
                    .onEach { it.isAccessible = true }
                    .also { bruteForceCandidates = it }

            candidates.firstNotNullOfOrNull { method ->
                when {
                    method.parameterTypes.isEmpty() ->
                        runCatching { method.invoke(target) }.getOrNull()

                    method.parameterTypes.size == 1 && method.parameterTypes[0] == Int::class.java ->
                        runCatching { method.invoke(target, 1) }.getOrNull()

                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractComponentName(obj: Any?): ComponentName? {
        if (obj == null) return null
        if (obj is ComponentName) return obj

        COMPONENT_NAME_FIELDS.forEach { fieldName ->
            try {
                val field = obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
                (field.get(obj) as? ComponentName)?.let { return it }
            } catch (_: Exception) {
                // Field absent on this ROM's shape of the object; try the next candidate.
            }
        }

        return scanHierarchyForComponentName(obj)
    }

    private fun scanHierarchyForComponentName(obj: Any): ComponentName? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            ReflectionSupport.instanceFields(cls).forEach { field ->
                try {
                    field.isAccessible = true
                    (field.get(obj) as? ComponentName)?.let { return it }
                } catch (_: Exception) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findPackageLikeString(obj: Any?): String? {
        if (obj == null) return null
        extractPackageName(obj.toString())?.let { return it }

        ReflectionSupport.instanceFields(obj.javaClass).forEach { field ->
            if (field.type == String::class.java) {
                try {
                    field.isAccessible = true
                    (field.get(obj) as? String)?.let { str ->
                        extractPackageName(str)?.let { return it }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun extractPackageName(input: String?): String? {
        if (input == null || input.indexOf('.') <= 0) return null
        val normalized = input.lowercase().replace(Regex("[^a-z0-9._-]"), " ")
        return normalized.split(Regex("\\s+")).find {
            it.contains(".") && it.matches(Regex("[a-z0-9]+(\\.[a-z0-9]+)+"))
        }
    }

    companion object {
        val FOREGROUND_METHOD_CANDIDATES = listOf(
            "getFocusedRootTaskInfo",
            "getFocusedRootTask",
            "getFocusedTaskInfo",
            "getFocusedStackInfo",
            "getTopActivity",
            "getTasks",
            "getRunningTasks",
        )

        val COMPONENT_NAME_FIELDS = listOf(
            "topActivity",
            "topActivityComponent",
            "realActivity",
            "baseActivity",
            "origActivity",
            "activity",
        )

        /** Pick the first candidate the ROM actually exposes with a callable signature. */
        fun resolveForegroundMethod(atm: Any): Method? {
            val methods = ReflectionSupport.declaredMethods(atm.javaClass).associateBy { it.name }
            return FOREGROUND_METHOD_CANDIDATES
                .mapNotNull { methods[it] }
                .find { method ->
                    method.parameterTypes.isEmpty() ||
                        (method.parameterTypes.size == 1 && method.parameterTypes[0] == Int::class.java) ||
                        method.name == "getTasks" || method.name == "getRunningTasks"
                }
                ?.apply { isAccessible = true }
        }
    }
}

/** Hidden-API reflection helpers, funnelled through HiddenApiBypass. */
object ReflectionSupport {
    fun declaredMethods(cls: Class<*>): List<Method> =
        HiddenApiBypass.getDeclaredMethods(cls).filterIsInstance<Method>()

    fun instanceFields(cls: Class<*>): List<Field> =
        HiddenApiBypass.getInstanceFields(cls).filterIsInstance<Field>()

    fun systemService(name: String): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        return serviceManager.getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder
    }

    fun bindInterface(stubClassName: String, binder: IBinder): Any =
        Class.forName(stubClassName)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: error("asInterface returned null for $stubClassName")

    fun atmServiceName(): String =
        if (Build.VERSION.SDK_INT >= 29) "activity_task" else Context.ACTIVITY_SERVICE

    fun atmInterfaceName(): String =
        if (Build.VERSION.SDK_INT >= 29) "android.app.IActivityTaskManager" else "android.app.IActivityManager"

    /**
     * True on a GKI (Generic Kernel Image) kernel, identified by the `-androidXX-` segment
     * in `uname -r` (e.g. `5.15.123-android13-8-...`). Vendor kernels carry device-specific
     * suffixes instead and return false.
     */
    fun isGkiKernel(): Boolean = try {
        (System.getProperty("os.version") ?: "").contains(Regex("-android\\d+-"))
    } catch (_: Exception) {
        false
    }
}
