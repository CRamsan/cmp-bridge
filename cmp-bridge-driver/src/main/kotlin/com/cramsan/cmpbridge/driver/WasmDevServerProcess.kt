package com.cramsan.cmpbridge.driver

import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Owns a wasmJs dev-server subprocess — nothing more.
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
        private const val DEV_SERVER_TIMEOUT_MS = 180_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val DESTROY_TIMEOUT_SECONDS = 5L
        private const val DEFAULT_PORT = 8080

        /**
         * [gradleModulePath] is the app's own `launcher-web` module, e.g.
         * `":edifikana:front-end:launcher-web"` — its `wasmJsBrowserDevelopmentRun` task is run to
         * bring up the dev server. [port] defaults to the standard Kotlin/JS webpack-dev-server
         * port; override only if an app's `webpack.config.d` pins a different one.
         */
        // Deliberate catch-all: any readiness failure gets wrapped with the dev server's own log
        // path attached below, rather than surfacing a bare exception with nowhere to look.
        @Suppress("TooGenericExceptionCaught")
        fun launch(gradleModulePath: String, port: Int = DEFAULT_PORT): WasmDevServerProcess {
            val repoRoot =
                System.getProperty("e2e.repoRoot")
                    ?: error("e2e.repoRoot not set — run via the `test` Gradle task in this module")

            val logFile = File.createTempFile("cmp-bridge-web-e2e", ".log").apply { deleteOnExit() }
            val gradlew = File(repoRoot, "gradlew").absolutePath
            val process =
                ProcessBuilder(
                    gradlew,
                    "$gradleModulePath:wasmJsBrowserDevelopmentRun",
                    "--console=plain",
                ).directory(File(repoRoot))
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()

            try {
                waitUntilReady(process, port, logFile)
            } catch (e: Exception) {
                process.destroyForcibly()
                throw IllegalStateException("${e.message}\nDev server log: ${logFile.absolutePath}", e)
            }
            return WasmDevServerProcess(process, logFile, port)
        }

        private fun waitUntilReady(process: Process, port: Int, log: File) {
            val deadline = System.currentTimeMillis() + DEV_SERVER_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    error("Dev server process exited before it became ready (exit code ${process.exitValue()})")
                }
                try {
                    Socket(DEV_SERVER_HOST, port).close()
                    return
                } catch (_: Exception) {
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }
            error("Dev server did not become ready within ${DEV_SERVER_TIMEOUT_MS}ms\nLog: ${log.absolutePath}")
        }
    }
}
