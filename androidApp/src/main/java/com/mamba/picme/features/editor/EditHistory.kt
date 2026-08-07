package com.mamba.picme.features.editor

class EditHistory(private val maxSize: Int = 30) {
    private val stack = mutableListOf<EditRecipe>()
    private var index = -1

    val canUndo: Boolean
        get() = index > 0

    val canRedo: Boolean
        get() = index < stack.lastIndex

    fun current(): EditRecipe? = if (index in stack.indices) stack[index] else null

    fun push(recipe: EditRecipe) {
        if (index < stack.lastIndex) {
            stack.subList(index + 1, stack.size).clear()
        }
        stack.add(recipe)
        if (stack.size > maxSize) {
            stack.removeAt(0)
            if (index > 0) index--
        }
        index = stack.lastIndex
    }

    fun undo(): EditRecipe? {
        if (!canUndo) return null
        return stack[--index]
    }

    fun redo(): EditRecipe? {
        if (!canRedo) return null
        return stack[++index]
    }

    fun reset(recipe: EditRecipe) {
        stack.clear()
        index = -1
        push(recipe)
    }
}
