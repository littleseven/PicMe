package com.mamba.picme.features.chat

import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatEditStateHolderTest {

    @Test
    fun `returns default recipe for unknown session`() {
        val holder = ChatEditStateHolder()
        val recipe = holder.get("session-a")
        assertEquals(EditRecipe(sourceUri = ""), recipe)
    }

    @Test
    fun `updates and retrieves recipe per session`() {
        val holder = ChatEditStateHolder()
        val recipeA = EditRecipe(
            sourceUri = "uri-a",
            adjustments = AdjustmentRecipe(brightness = 10f)
        )
        holder.update("session-a", recipeA)
        assertEquals(10f, holder.get("session-a").adjustments.brightness, 0.001f)

        val recipeB = EditRecipe(
            sourceUri = "uri-b",
            adjustments = AdjustmentRecipe(brightness = 20f)
        )
        holder.update("session-b", recipeB)
        assertEquals(20f, holder.get("session-b").adjustments.brightness, 0.001f)
        assertEquals(10f, holder.get("session-a").adjustments.brightness, 0.001f)
    }

    @Test
    fun `reset clears session recipe`() {
        val holder = ChatEditStateHolder()
        holder.update(
            "session-a",
            EditRecipe(sourceUri = "uri-a", adjustments = AdjustmentRecipe(brightness = 10f))
        )
        holder.reset("session-a")
        assertEquals(0f, holder.get("session-a").adjustments.brightness, 0.001f)
    }
}
