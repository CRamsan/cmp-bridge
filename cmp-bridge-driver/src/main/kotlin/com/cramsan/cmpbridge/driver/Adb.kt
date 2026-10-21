package com.cramsan.cmpbridge.driver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal `adb` runner shared by [AndroidBridgeDriver] and [AndroidAppProcess]. Locates the
 * binary once per process: an explicit `CMP_BRIDGE_ADB` path, then `ANDROID_HOME`/
 * `ANDROID_SDK_ROOT`'s `platform-tools/adb`, then the default SDK locations per OS, then plain
 * `adb` on `PATH`. [serial] scopes every command to one device (`adb -s`), as needed whenever
 * more than one is attached.
 */
internal class Adb(private val serial: String?, private val executable: String = locate()) {
    /**
     * Runs `adb [-s serial] args...` and returns its trimmed stdout, throwing
     * [BridgeConnectionException] (with adb's own combined output) on a non-zero exit.
     */
    suspend fun run(vararg args: String): String = withContext(Dispatchers.IO) {
        val command = buildList {
            add(executable)
            serial?.let { addAll(listOf("-s", it)) }
            addAll(args)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw BridgeConnectionException("adb timed out: ${command.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw BridgeConnectionException(
                "adb failed (exit ${process.exitValue()}): ${command.joinToString(" ")}\n$output",
            )
        }
        output
    }

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 60L

        internal fun locate(): String {
            val explicit = System.getenv("CMP_BRIDGE_ADB")?.takeIf { it.isNotBlank() }
            val home = System.getProperty("user.home")
            val sdkRoots = listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                "$home/Library/Android/sdk",
                "$home/Android/Sdk",
                System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
            )
            val binary = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
            val fromSdk = sdkRoots.map { File(it, "platform-tools/$binary") }.firstOrNull { it.canExecute() }
            return explicit ?: fromSdk?.absolutePath ?: binary
        }
    }
}
