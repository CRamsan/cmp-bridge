package com.cramsan.cmpbridge.driver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Launches a desktop app subprocess with the UI bridge armed, using an isolated `user.home` so it
 * doesn't touch real session state on the host machine. Pair with [DesktopBridgeDriver.connect] to
 * drive it, or [ManagedBridgeDriver] for single-call teardown.
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
        private val READY_TIMEOUT: Duration = 30.seconds
        private val READY_POLL_INTERVAL: Duration = 250.milliseconds
        private const val DESTROY_TIMEOUT_SECONDS = 5L

        /**
         * [mainClass] is the app's own desktop launcher entry point, e.g.
         * `"com.example.myapp.desktop.MainKt"`.
         */
        suspend fun launch(mainClass: String): DesktopAppProcess {
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

        private suspend fun waitUntilReady(port: Int, process: Process) {
            try {
                withTimeout(READY_TIMEOUT) {
                    while (true) {
                        if (!process.isAlive) {
                            error(
                                "App process exited before the bridge became ready " +
                                    "(exit code ${process.exitValue()})",
                            )
                        }
                        // IOException only — a blanket Exception catch around a suspending call
                        // would also swallow this withTimeout's own TimeoutCancellationException.
                        try {
                            withContext(Dispatchers.IO) { Socket("127.0.0.1", port).close() }
                            return@withTimeout
                        } catch (_: IOException) {
                            delay(READY_POLL_INTERVAL)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                error("Bridge on port $port did not become ready within $READY_TIMEOUT")
            }
        }
    }
}
