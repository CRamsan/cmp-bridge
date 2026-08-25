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

    @Test
    fun `waitForTextEquals throws BridgeTimeoutException when the text never matches`() {
        val driver = FixedTextDriver(text = "wrong")
        val error = runCatching { driver.waitForTextEquals("my_tag", "expected", timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("never showed"))
    }

    @Test
    fun `waitForTextEquals throws BridgeTimeoutException when the tag never appears`() {
        val driver = NeverAppearsDriver()
        val error = runCatching { driver.waitForTextEquals("my_tag", "expected", timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
    }

    @Test
    fun `waitForTextEquals returns once the tag's text equals the expected value`() {
        val driver = EventuallyChangesDriver(TAGGED_NODE_NO_TEXT, TAGGED_NODE_EXPECTED_TEXT, flipAfterCalls = 2)
        val node = driver.waitForTextEquals("my_tag", "expected", timeoutMs = 1_000)
        assertEquals("expected", node.text)
    }

    @Test
    fun `waitForTagGone throws BridgeTimeoutException when the tag never disappears`() {
        val driver = FixedTextDriver(text = "still here")
        val error = runCatching { driver.waitForTagGone("my_tag", timeoutMs = 200) }.exceptionOrNull()
        assertTrue(error is BridgeTimeoutException)
        assertTrue(error.message.orEmpty().contains("still present"))
    }

    @Test
    fun `waitForTagGone returns once the tag is no longer found`() {
        val driver = EventuallyChangesDriver(TAGGED_NODE_EXPECTED_TEXT, EMPTY_ROOT, flipAfterCalls = 2)
        driver.waitForTagGone("my_tag", timeoutMs = 1_000)
    }

    @Test
    fun `waitForTagGone returns immediately when the tag was never present`() {
        val driver = NeverAppearsDriver()
        driver.waitForTagGone("my_tag", timeoutMs = 200)
    }
}
