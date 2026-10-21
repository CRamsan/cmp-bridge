package com.cramsan.cmpbridge.driver

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Starts an already-installed Android app's activity on a device/emulator with the bridge armed
 * (the `cmpBridge.enabled`/`cmpBridge.port` intent extras `AndroidBridgeServer.startIfEnabled`
 * reads), and force-stops the app on [close]. Pair with [AndroidBridgeDriver.connect] to drive
 * it, or [ManagedBridgeDriver] for single-call teardown.
 *
 * Doesn't build anything — only the caller knows how its APK gets produced (a Gradle task, a CI
 * artifact, ...), the same stance as `WasmDevServerProcess` — but [install] is offered for the
 * already-built file, since the `adb install` step is the same for everyone.
 */
class AndroidAppProcess private constructor(
    private val adb: Adb,
    private val packageName: String,
    val port: Int,
    val serial: String?,
) : AutoCloseable {
    override fun close() {
        runCatching { runBlocking { adb.run("shell", "am", "force-stop", packageName) } }
    }

    companion object {
        /**
         * Whether at least one device/emulator is attached and online, so a caller can skip
         * Android work (rather than fail it) on a device-less machine or CI runner.
         */
        suspend fun anyDeviceAttached(): Boolean = runCatching { Adb(serial = null).run("devices") }
            .getOrNull()
            ?.lines()
            ?.drop(1)
            ?.any { it.trim().endsWith("device") }
            ?: false

        /** Installs (or replaces, keeping data) an already-built [apk] on the device. */
        suspend fun install(apk: File, serial: String? = null) {
            Adb(serial).run("install", "-r", apk.absolutePath)
        }

        /**
         * [component] is the activity to start, in `am start -n` form: `"com.example.app/.MainActivity"`
         * or fully qualified. [port] is the device-side port the bridge should listen on.
         */
        suspend fun launch(component: String, port: Int = 8901, serial: String? = null): AndroidAppProcess {
            val adb = Adb(serial)
            val packageName = component.substringBefore('/')
            // -S force-stops any running instance first so the opt-in extras are read by a fresh
            // onCreate. Deliberately not -W: waiting on the activity manager's launch-complete
            // signal has been seen to block for many minutes on an emulator, and readiness is
            // established by AndroidBridgeDriver.connect's real round-trip probe anyway.
            val output = adb.run(
                "shell", "am", "start", "-S", "-n", component,
                "--ez", "cmpBridge.enabled", "true",
                "--ei", "cmpBridge.port", port.toString(),
            )
            if ("Error" in output) throw BridgeConnectionException("am start failed for $component:\n$output")
            return AndroidAppProcess(adb, packageName, port, serial)
        }
    }
}
