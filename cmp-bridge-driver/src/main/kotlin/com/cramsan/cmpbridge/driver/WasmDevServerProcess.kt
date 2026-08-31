package com.cramsan.cmpbridge.driver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Owns an arbitrary dev-server subprocess — nothing more. cmp-bridge-driver has no opinion on how
 * a wasmJs dev server gets started (a Gradle task, an npm script, a Docker container, ...); the
 * caller supplies the exact [launch] command and working directory to run, the same as they'd
 * type at a terminal, since only the caller knows its own repo layout.
 *
 * Pair with [WebBridgeDriver.connect] to actually drive the app it serves — directly, or through
 * [ManagedBridgeDriver] for single-call teardown.
 */
class WasmDevServerProcess private constructor(
    private val process: Process,
    private val logFile: File,
    val port: Int,
) : AutoCloseable {
    /** The dev server's own URL, ready to hand to [WebBridgeDriver.connect]. */
    val url: String get() = "http://$DEV_SERVER_HOST:$port/"

    override fun close() {
        process.destroy()
        if (!process.waitFor(DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        logFile.delete()
    }

    companion object {
        private const val DEV_SERVER_HOST = "127.0.0.1"
        private val DEV_SERVER_TIMEOUT: Duration = 180.seconds
        private val POLL_INTERVAL: Duration = 500.milliseconds
        private const val DESTROY_TIMEOUT_SECONDS = 5L
        private const val DEFAULT_PORT = 8080

        /**
         * Runs [command] in [workingDir] — e.g.
         * `listOf(File(repoRoot, "gradlew").absolutePath, ":app:wasmJsBrowserDevelopmentRun", "--console=plain")`
         * — and waits for something to start listening on [port], the standard Kotlin/JS
         * webpack-dev-server port; override only if an app's `webpack.config.d` pins a different
         * one. `cmp-bridge-sample`'s own test code is a complete, working example of building
         * this command from a Gradle-supplied repo root.
         */
        // Deliberate catch-all: any readiness failure gets wrapped with the dev server's own log
        // path attached below, rather than surfacing a bare exception with nowhere to look. Real
        // coroutine cancellation is rethrown unwrapped, not swallowed by this — see below.
        @Suppress("TooGenericExceptionCaught")
        suspend fun launch(command: List<String>, workingDir: File, port: Int = DEFAULT_PORT): WasmDevServerProcess {
            val logFile = File.createTempFile("cmp-bridge-web-e2e", ".log").apply { deleteOnExit() }
            val process =
                ProcessBuilder(command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()

            try {
                waitUntilReady(process, port, logFile)
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                throw IllegalStateException("${e.message}\nDev server log: ${logFile.absolutePath}", e)
            }
            return WasmDevServerProcess(process, logFile, port)
        }

        private suspend fun waitUntilReady(process: Process, port: Int, log: File) {
            try {
                withTimeout(DEV_SERVER_TIMEOUT) {
                    while (true) {
                        if (!process.isAlive) {
                            error("Dev server process exited before it became ready (exit code ${process.exitValue()})")
                        }
                        // IOException only — a blanket Exception catch around a suspending call
                        // would also swallow this withTimeout's own TimeoutCancellationException.
                        try {
                            withContext(Dispatchers.IO) { Socket(DEV_SERVER_HOST, port).close() }
                            return@withTimeout
                        } catch (_: IOException) {
                            delay(POLL_INTERVAL)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                error("Dev server did not become ready within $DEV_SERVER_TIMEOUT\nLog: ${log.absolutePath}")
            }
        }
    }
}
