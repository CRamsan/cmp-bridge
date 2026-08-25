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

private val TAGGED_NODE_EXPECTED_TEXT =
    EMPTY_ROOT.copy(
        children =
        listOf(
            EMPTY_ROOT.copy(testTag = "my_tag", text = "expected", width = 10f, height = 10f),
        ),
    )

private val TAGGED_NODE_EMPTY_TEXT =
    EMPTY_ROOT.copy(
        children =
        listOf(
            EMPTY_ROOT.copy(testTag = "my_tag", text = "", width = 10f, height = 10f),
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

/** A fake whose tag is present throughout with a fixed [text] that never changes. */
private class FixedTextDriver(private val text: String?) : BridgeDriver {
    override fun getHierarchy(): HierarchyNode =
        EMPTY_ROOT.copy(children = listOf(EMPTY_ROOT.copy(testTag = "my_tag", text = text, width = 10f, height = 10f)))
    override fun click(tag: String) = Unit
    override fun setText(tag: String, text: String) = Unit
    override fun scroll(anchorTag: String, deltaY: Int) = Unit
    override fun screenshot(): ByteArray = byteArrayOf()
    override fun close() = Unit
}

/**
 * A fake whose hierarchy changes on the [flipAfterCalls]-th (and later) [getHierarchy] call —
 * simulates a tag's text eventually matching, or a tag eventually disappearing, without depending
 * on real elapsed time.
 */
private class EventuallyChangesDriver(
    private val before: HierarchyNode,
    private val after: HierarchyNode,
    private val flipAfterCalls: Int,
) : BridgeDriver {
    private var calls = 0
    override fun getHierarchy(): HierarchyNode {
        calls++
        return if (calls >= flipAfterCalls) after else before
    }
    override fun click(tag: String) = Unit
    override fun setText(tag: String, text: String) = Unit
    override fun scroll(anchorTag: String, deltaY: Int) = Unit
    override fun screenshot(): ByteArray = byteArrayOf()
    override fun close() = Unit
}

class BridgeDriverDefaultsTest {
    @Test
    fun `waitForTagVisibility with VISIBLE throws BridgeTimeoutException when the tag never appears`() {
        val driver = NeverAppearsDriver()
        val error =
            runCatching {
                driver.waitForTagVisibility("my_tag", TagVisibility.VISIBLE, timeoutMs = 200)
            }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("never reached visibility"))
    }

    @Test
    fun `waitForText with Present throws BridgeTimeoutException when the tag never appears`() {
        val driver = NeverAppearsDriver()
        val error =
            runCatching { driver.waitForText("my_tag", TextComparator.Present, timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("never satisfied"))
    }

    @Test
    fun `waitForText with Present throws BridgeTimeoutException when the tag's text never settles`() {
        val driver = TextNeverSettlesDriver()
        val error =
            runCatching { driver.waitForText("my_tag", TextComparator.Present, timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("never satisfied"))
    }

    @Test
    fun `waitForText with Present returns once the tag's text settles`() {
        val driver = EventuallyChangesDriver(TAGGED_NODE_NO_TEXT, TAGGED_NODE_EXPECTED_TEXT, flipAfterCalls = 2)
        val node = driver.waitForText("my_tag", TextComparator.Present, timeoutMs = 1_000)
        assertEquals("expected", node.text)
    }

    @Test
    fun `waitForTagVisibility with VISIBLE returns once the tag appears`() {
        val driver = TextNeverSettlesDriver()
        val node = driver.waitForTagVisibility("my_tag", TagVisibility.VISIBLE, timeoutMs = 200)
        assertEquals("my_tag", node?.testTag)
    }

    @Test
    fun `waitForText with Equals throws BridgeTimeoutException when the text never matches`() {
        val driver = FixedTextDriver(text = "wrong")
        val error =
            runCatching {
                driver.waitForText("my_tag", TextComparator.Equals("expected"), timeoutMs = 200)
            }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("never satisfied"))
    }

    @Test
    fun `waitForText with Equals throws BridgeTimeoutException when the tag never appears`() {
        val driver = NeverAppearsDriver()
        val error =
            runCatching {
                driver.waitForText("my_tag", TextComparator.Equals("expected"), timeoutMs = 200)
            }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
    }

    @Test
    fun `waitForText with Equals returns once the tag's text equals the expected value`() {
        val driver = EventuallyChangesDriver(TAGGED_NODE_NO_TEXT, TAGGED_NODE_EXPECTED_TEXT, flipAfterCalls = 2)
        val node = driver.waitForText("my_tag", TextComparator.Equals("expected"), timeoutMs = 1_000)
        assertEquals("expected", node.text)
    }

    @Test
    fun `waitForText with Empty returns once the tag's text becomes empty`() {
        val driver = EventuallyChangesDriver(TAGGED_NODE_EXPECTED_TEXT, TAGGED_NODE_EMPTY_TEXT, flipAfterCalls = 2)
        val node = driver.waitForText("my_tag", TextComparator.Empty, timeoutMs = 1_000)
        assertEquals("", node.text)
    }

    @Test
    fun `waitForText with StartsWith returns once the tag's text starts with the prefix`() {
        val driver = EventuallyChangesDriver(TAGGED_NODE_NO_TEXT, TAGGED_NODE_EXPECTED_TEXT, flipAfterCalls = 2)
        val node = driver.waitForText("my_tag", TextComparator.StartsWith("exp"), timeoutMs = 1_000)
        assertEquals("expected", node.text)
    }

    @Test
    fun `waitForTagVisibility with GONE throws BridgeTimeoutException when the tag never disappears`() {
        val driver = FixedTextDriver(text = "still here")
        val error =
            runCatching {
                driver.waitForTagVisibility("my_tag", TagVisibility.GONE, timeoutMs = 200)
            }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("never reached visibility"))
    }

    @Test
    fun `waitForTagVisibility with GONE returns null once the tag is no longer found`() {
        val driver = EventuallyChangesDriver(TAGGED_NODE_EXPECTED_TEXT, EMPTY_ROOT, flipAfterCalls = 2)
        val node = driver.waitForTagVisibility("my_tag", TagVisibility.GONE, timeoutMs = 1_000)
        assertEquals(null, node)
    }

    @Test
    fun `waitForTagVisibility with GONE returns null immediately when the tag was never present`() {
        val driver = NeverAppearsDriver()
        val node = driver.waitForTagVisibility("my_tag", TagVisibility.GONE, timeoutMs = 200)
        assertEquals(null, node)
    }
}
