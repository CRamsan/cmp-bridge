package com.cramsan.cmpbridge.driver

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Owns a desktop app subprocess launched with the UI interaction bridge armed (see
 * `DesktopBridgeServer`) — nothing more. Reuses this test JVM's own classpath
 * (`java.class.path`) to launch the child process, and an isolated `user.home` so it never reads
 * or writes whatever real session state is persisted on the machine actually running these
 * tests.
 *
 * Pair with [DesktopBridgeDriver.connect] to actually drive the app it starts — directly, or
 * through [ManagedBridgeDriver] for single-call teardown.
 */
class DesktopAppProcess private constructor(
    private val process: Process,
    private val logFile: File,
    val host: String,
    val port: Int,
) : AutoCloseable {
    override fun close() {
        process.destroy()
        if (!process.waitFor(DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        logFile.delete()
    }

    companion object {
        private const val READY_TIMEOUT_MS = 30_000L
        private const val READY_POLL_INTERVAL_MS = 250L
        private const val DESTROY_TIMEOUT_SECONDS = 5L

        /**
         * [mainClass] is the app's own desktop launcher entry point, e.g.
         * `"com.cramsan.edifikana.client.desktop.EdifikanaApplicationKt"`.
         */
        fun launch(mainClass: String): DesktopAppProcess {
            val classpath =
                System.getProperty("java.class.path")
                    ?: error("java.class.path not set — cannot locate the desktop app's runtime classpath")

            val port = ServerSocket(0).use { it.localPort }
            val isolatedHome =
                File.createTempFile("cmp-bridge-e2e-home", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }
            val logFile = File.createTempFile("cmp-bridge-desktop-e2e", ".log").apply { deleteOnExit() }
            val javaBin = "${System.getProperty("java.home")}/bin/java"

            val process =
                ProcessBuilder(
                    javaBin,
                    "-DcmpBridge.enabled=true",
                    "-DcmpBridge.port=$port",
                    "-Duser.home=${isolatedHome.absolutePath}",
                    "-cp",
                    classpath,
                    mainClass,
                ).redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()

            try {
                waitUntilReady(port, process)
            } catch (e: IllegalStateException) {
                throw IllegalStateException("${e.message}\nApp log: ${logFile.absolutePath}", e)
            }
            return DesktopAppProcess(process, logFile, "127.0.0.1", port)
        }

        private fun waitUntilReady(port: Int, process: Process) {
            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    error("App process exited before the bridge became ready (exit code ${process.exitValue()})")
                }
                try {
                    Socket("127.0.0.1", port).close()
                    return
                } catch (_: Exception) {
                    Thread.sleep(READY_POLL_INTERVAL_MS)
                }
            }
            error("Bridge on port $port did not become ready within ${READY_TIMEOUT_MS}ms")
        }
    }
}
