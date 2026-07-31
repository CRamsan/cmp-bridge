package com.cramsan.cmpbridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun leafNode(tag: String) =
        HierarchyNode(
            testTag = tag,
            role = "button",
            text = "value",
            contentDescription = null,
            x = 1f,
            y = 2f,
            width = 3f,
            height = 4f,
            enabled = false,
            actions = setOf("OnClick"),
            children = emptyList(),
        )

    private inline fun <reified T> roundTrip(value: T) {
        val encoded = json.encodeToString(value)
        assertEquals(value, json.decodeFromString<T>(encoded))
    }

    @Test
    fun `every BridgeCommand variant round-trips through JSON`() {
        roundTrip<BridgeCommand>(BridgeCommand.GetHierarchy)
        roundTrip<BridgeCommand>(BridgeCommand.Click("tag"))
        roundTrip<BridgeCommand>(BridgeCommand.SetText("tag", "hello bridge"))
        roundTrip<BridgeCommand>(BridgeCommand.Scroll("tag", 40))
        roundTrip<BridgeCommand>(BridgeCommand.Screenshot)
    }

    @Test
    fun `every BridgeResponse variant round-trips through JSON`() {
        val nested =
            HierarchyNode(
                testTag = null,
                role = null,
                text = null,
                contentDescription = null,
                x = 0f,
                y = 0f,
                width = 100f,
                height = 200f,
                enabled = true,
                actions = emptySet(),
                children = listOf(leafNode("a"), leafNode("b")),
            )
        roundTrip<BridgeResponse>(BridgeResponse.Hierarchy(nested))
        roundTrip<BridgeResponse>(BridgeResponse.Ack)
        roundTrip<BridgeResponse>(BridgeResponse.Image("aGVsbG8="))
        roundTrip<BridgeResponse>(BridgeResponse.Failure("Unknown tag: tag"))
    }
}
