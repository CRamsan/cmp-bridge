package com.cramsan.cmpbridge.driver

/**
 * Pairs a [BridgeDriver] with whatever resource it was connected against (a launched
 * [DesktopAppProcess], a launched [WasmDevServerProcess], ...), closing both together in the
 * right order on [close]: the driver first (its own sockets/browser), then the resource (kills
 * whatever process was launched for it).
 *
 * Delegates every other [BridgeDriver] method straight to [driver]. Exists purely to make "launch
 * something, then connect a driver to it" composable with single-call teardown, without teaching
 * either the driver or the resource anything about each other — a test that doesn't need a
 * launched resource can just use [DesktopBridgeDriver.connect]/[WebBridgeDriver.connect] directly.
 */
class ManagedBridgeDriver(private val resource: AutoCloseable, private val driver: BridgeDriver) :
    BridgeDriver by driver {
    override fun close() {
        driver.close()
        resource.close()
    }
}
