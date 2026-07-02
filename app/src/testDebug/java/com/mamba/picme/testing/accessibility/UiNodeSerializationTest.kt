package com.mamba.picme.testing.accessibility

import com.mamba.picme.testing.accessibility.model.Bounds
import com.mamba.picme.testing.accessibility.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNodeSerializationTest {

    @Test
    fun serializeUiNodeToJson() {
        val node = UiNode(
            id = "0",
            packageName = "com.mamba.picme",
            className = "android.widget.Button",
            text = "相册",
            contentDescription = null,
            hint = null,
            bounds = Bounds(0, 100, 200, 300),
            clickable = true,
            longClickable = false,
            scrollable = false,
            enabled = true,
            checked = false,
            selected = false,
            focused = false,
            children = emptyList()
        )

        val json = node.toJson()
        assertEquals("0", json.getString("id"))
        assertEquals("com.mamba.picme", json.getString("packageName"))
        assertEquals("android.widget.Button", json.getString("className"))
        assertEquals("相册", json.getString("text"))
        assertTrue(json.getBoolean("clickable"))
        assertEquals(0, json.getJSONObject("bounds").getInt("left"))
        assertEquals(100, json.getJSONObject("bounds").getInt("top"))
    }
}
