package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val EMPTY_ROOT =
    HierarchyNode(
        testTag = null,
        role = null,
        text = null,
        contentDescription = null,
        x = 0f,
        y = 0f,
        width = 0f,
        height = 0f,
        enabled = true,
        actions = emptySet(),
        children = emptyList(),
    )

private val TAGGED_NODE_NO_TEXT =
    EMPTY_ROOT.copy(
        children =
        listOf(
            EMPTY_ROOT.copy(testTag = "my_tag", text = null, width = 10f, height = 10f),
        ),
    )

/** A fake whose hierarchy never contains the tag being waited on, to exercise timeout paths. */
private class NeverAppearsDriver : BridgeDriver {
    override fun getHierarchy(): HierarchyNode = EMPTY_ROOT
    override fun click(tag: String) = Unit
    override fun setText(tag: String, text: String) = Unit
    override fun scroll(anchorTag: String, deltaY: Int) = Unit
    override fun screenshot(): ByteArray = byteArrayOf()
    override fun close() = Unit
}

/** A fake whose tag appears with settled bounds but whose text never becomes non-null. */
private class TextNeverSettlesDriver : BridgeDriver {
    override fun getHierarchy(): HierarchyNode = TAGGED_NODE_NO_TEXT
    override fun click(tag: String) = Unit
    override fun setText(tag: String, text: String) = Unit
    override fun scroll(anchorTag: String, deltaY: Int) = Unit
    override fun screenshot(): ByteArray = byteArrayOf()
    override fun close() = Unit
}

class BridgeDriverDefaultsTest {
    @Test
    fun `waitForTag throws BridgeTimeoutException when the tag never appears`() {
        val driver = NeverAppearsDriver()
        val error = runCatching { driver.waitForTag("my_tag", timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("did not appear"))
    }

    @Test
    fun `waitForText throws BridgeTimeoutException when the tag never appears`() {
        val driver = NeverAppearsDriver()
        val error = runCatching { driver.waitForText("my_tag", timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
    }

    @Test
    fun `waitForText throws BridgeTimeoutException when the tag's text never settles`() {
        val driver = TextNeverSettlesDriver()
        val error = runCatching { driver.waitForText("my_tag", timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("never settled"))
    }

    @Test
    fun `waitForTag returns once the tag appears`() {
        val driver = TextNeverSettlesDriver()
        val node = driver.waitForTag("my_tag", timeoutMs = 200)
        assertEquals("my_tag", node.testTag)
    }
}
