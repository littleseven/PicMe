package com.mamba.picme.features.chat

import com.mamba.picme.features.editor.EditRecipe

/**
 * 维护每个 Chat 会话当前的编辑 Recipe，用于多轮 delta 调整。
 *
 * 生命周期：应用进程内有效；切换会话、清空对话或发送新图片时重置。
 */
class ChatEditStateHolder {

    private val states = mutableMapOf<String, EditRecipe>()

    fun get(sessionId: String): EditRecipe {
        return states[sessionId] ?: EditRecipe(sourceUri = "")
    }

    fun update(sessionId: String, recipe: EditRecipe) {
        states[sessionId] = recipe
    }

    fun reset(sessionId: String) {
        states.remove(sessionId)
    }

    fun resetAll() {
        states.clear()
    }
}
