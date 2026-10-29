package com.cramsan.cmpbridge.driver

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a real Android app instance whose `AndroidBridgeServer` is listening on the device.
 * Connection-only — never installs or launches anything (see [AndroidAppProcess] for that).
 *
 * The device-side server speaks the same line protocol as desktop's, so this is a thin layer
 * over [DesktopBridgeDriver]: [connect] sets up `adb forward tcp:<free local port> tcp:[port]`,
 * and [close] removes that forward again. Everything else is delegated.
 */
class AndroidBridgeDriver private constructor(
    private val adb: Adb,
    private val localPort: Int,
    private val inner: DesktopBridgeDriver,
) : BridgeDriver by inner {
    /** Removes this driver's `adb forward`; the app and its server keep running. */
    override fun close() {
        inner.close()
        // runBlocking: BridgeDriver.close() is a plain AutoCloseable.close(), and a leaked forward
        // is harmless but accumulates across runs, so it's worth the short wait to remove it.
        runCatching { runBlocking { adb.run("forward", "--remove", "tcp:$localPort") } }
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = 15.seconds
        private val POLL_INTERVAL: Duration = 250.milliseconds

        /**
         * Attaches to the bridge an app on the device has armed on its loopback [port] (see
         * `AndroidBridgeServer.startIfEnabled` for how an app arms it). [serial] picks the device
         * when more than one is attached. [defaultTimeout]/[pollInterval] become this driver's
         * [BridgeDriver.defaultTimeout]/[BridgeDriver.pollInterval].
         */
        suspend fun connect(
            port: Int = 8901,
            serial: String? = null,
            defaultTimeout: Duration = BridgeDriver.DEFAULT_TIMEOUT,
            pollInterval: Duration = BridgeDriver.DEFAULT_POLL_INTERVAL,
        ): AndroidBridgeDriver {
            val adb = Adb(serial)
            // tcp:0 lets adb pick a free host port and print it, so parallel drivers never collide.
            val localPort = adb.run("forward", "tcp:0", "tcp:$port").lines().last().trim().toIntOrNull()
                ?: throw BridgeConnectionException("adb forward did not report a local port")
            try {
                val inner = DesktopBridgeDriver.connect("127.0.0.1", localPort, defaultTimeout, pollInterval)
                waitUntilServing(inner, port)
                return AndroidBridgeDriver(adb, localPort, inner)
            } catch (e: BridgeConnectionException) {
                runCatching { adb.run("forward", "--remove", "tcp:$localPort") }
                throw e
            }
        }

        /**
         * adb accepts the host-side connection itself, even when nothing listens on the device
         * port yet — the socket is only reset once the device refuses. So a connectable port
         * proves nothing here; a real round trip does. The server can also be up before Compose
         * has attached its first root (a freshly launched activity), which it reports as a
         * failure response ("No semantics owner available yet") — equally "not ready yet".
         */
        private suspend fun waitUntilServing(driver: DesktopBridgeDriver, devicePort: Int) {
            try {
                withTimeout(CONNECT_TIMEOUT) {
                    while (true) {
                        try {
                            driver.getHierarchy()
                            return@withTimeout
                        } catch (_: BridgeConnectionException) {
                            delay(POLL_INTERVAL)
                        } catch (_: IllegalStateException) {
                            delay(POLL_INTERVAL)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw BridgeConnectionException(
                    "No AndroidBridgeServer answered on the device's 127.0.0.1:$devicePort within $CONNECT_TIMEOUT — " +
                        "was the activity started with `--ez cmpBridge.enabled true`, and does the app's manifest " +
                        "grant INTERNET (cmp-bridge's own manifest requests it)?",
                )
            }
        }
    }
}
