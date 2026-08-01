package com.cramsan.cmpbridge.driver

/**
 * Pairs a [BridgeDriver] with the resource it was connected against (e.g. a launched
 * [DesktopAppProcess]), closing the driver first and then the resource on [close].
 */
class ManagedBridgeDriver(private val resource: AutoCloseable, private val driver: BridgeDriver) :
    BridgeDriver by driver {
    override fun close() {
        driver.close()
        resource.close()
    }
}
