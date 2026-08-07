package com.mamba.picme.features.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    @Test
    fun `undo redo works`() {
        val history = EditHistory(maxSize = 5)
        val first = EditRecipe(sourceUri = "uri://1")
        val second = first.copy(crop = CropRecipe(rotation = 90))
        val third = second.copy(adjustments = AdjustmentRecipe(brightness = 20f))

        history.push(first)
        history.push(second)
        history.push(third)

        assertTrue(history.canUndo)
        assertEquals(second, history.undo())
        assertEquals(first, history.undo())
        assertFalse(history.canUndo)

        assertTrue(history.canRedo)
        assertEquals(second, history.redo())
        assertTrue(history.canRedo)
    }

    @Test
    fun `push after undo truncates future`() {
        val history = EditHistory()
        val first = EditRecipe(sourceUri = "uri://1")
        val second = first.copy(crop = CropRecipe(rotation = 90))
        val third = second.copy(crop = CropRecipe(rotation = 180))

        history.push(first)
        history.push(second)
        history.push(third)
        history.undo()
        history.undo()

        val branch = first.copy(adjustments = AdjustmentRecipe(contrast = 80f))
        history.push(branch)

        assertFalse(history.canRedo)
        assertEquals(branch, history.current())
    }
}
