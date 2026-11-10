package com.example.looma.feature.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTitleTest {

    @Test
    fun `a new name is trimmed before renaming`() {
        val edit = titleEditFor("  Weekly notes ", "Untitled note", lastCommitted = null)

        assertEquals("Weekly notes", edit.renameTo)
        assertFalse(edit.restoreFileName)
    }

    @Test
    fun `an unchanged name is not committed`() {
        val edit = titleEditFor("Untitled note", "Untitled note", lastCommitted = null)

        assertNull(edit.renameTo)
        assertFalse(edit.restoreFileName)
    }

    @Test
    fun `a blank draft falls back to the file name`() {
        val edit = titleEditFor("   ", "Untitled note", lastCommitted = null)

        assertNull(edit.renameTo)
        assertTrue(edit.restoreFileName)
    }

    @Test
    fun `the same draft is not committed twice`() {
        val edit = titleEditFor("Weekly notes", "Untitled note", lastCommitted = "Weekly notes")

        assertNull(edit.renameTo)
        assertFalse(edit.restoreFileName)
    }
}
