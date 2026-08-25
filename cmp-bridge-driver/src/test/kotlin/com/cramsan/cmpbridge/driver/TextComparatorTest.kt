package com.cramsan.cmpbridge.driver

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true }

class TextComparatorTest {
    @Test
    fun `Present matches any text`() {
        assertTrue(TextComparator.Present.matches(""))
        assertTrue(TextComparator.Present.matches("anything"))
    }

    @Test
    fun `Empty matches only an empty string`() {
        assertTrue(TextComparator.Empty.matches(""))
        assertFalse(TextComparator.Empty.matches("not empty"))
    }

    @Test
    fun `Equals matches only the exact value`() {
        val comparator = TextComparator.Equals("expected")
        assertTrue(comparator.matches("expected"))
        assertFalse(comparator.matches("unexpected"))
        assertFalse(comparator.matches(""))
    }

    @Test
    fun `StartsWith matches only text with that prefix`() {
        val comparator = TextComparator.StartsWith("Hel")
        assertTrue(comparator.matches("Hello"))
        assertFalse(comparator.matches("Bye"))
    }

    @Test
    fun `Present round-trips through JSON`() {
        val encoded = json.encodeToString(TextComparator.serializer(), TextComparator.Present)
        assertEquals(TextComparator.Present, json.decodeFromString(TextComparator.serializer(), encoded))
    }

    @Test
    fun `Empty round-trips through JSON`() {
        val encoded = json.encodeToString(TextComparator.serializer(), TextComparator.Empty)
        assertEquals(TextComparator.Empty, json.decodeFromString(TextComparator.serializer(), encoded))
    }

    @Test
    fun `Equals round-trips through JSON`() {
        val original = TextComparator.Equals("hello")
        val encoded = json.encodeToString(TextComparator.serializer(), original)
        assertEquals(original, json.decodeFromString(TextComparator.serializer(), encoded))
    }

    @Test
    fun `StartsWith round-trips through JSON`() {
        val original = TextComparator.StartsWith("He")
        val encoded = json.encodeToString(TextComparator.serializer(), original)
        assertEquals(original, json.decodeFromString(TextComparator.serializer(), encoded))
    }

    @Test
    fun `wire format uses the short SerialName discriminator, not the qualified class name`() {
        val encoded = json.encodeToString(TextComparator.serializer(), TextComparator.Equals("hello"))
        assertTrue(encoded.contains("\"type\":\"equals\""))
        assertFalse(encoded.contains("TextComparator"))
    }
}
