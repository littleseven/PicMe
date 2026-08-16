package com.mamba.picme.features.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [QA] 相机状态单元测试
 *
 * CameraPanelState 面板状态机：
 * - 初始状态（全部关闭）
 * - closePrimaryPanels() / closeBeautySubPanels() / closeAllPanels() 互斥关闭
 * - toggleFacialRefinement() / toggleBodyManagement() 互斥开关
 * - openMakeupEntry() 幂等切换与入口替换
 * - 组合场景（多面板联动）
 */
class CameraStateTest {

    // ================================================================
    // §1 CameraPanelState 面板状态机
    // ================================================================

    private lateinit var panelState: CameraPanelState

    @Before
    fun setUp() {
        panelState = CameraPanelState()
    }

    // --- 初始状态 ---

    @Test
    fun `panel initial state - all panels are closed`() {
        assertFalse(panelState.showFilterSelector)
        assertFalse(panelState.showBeautySelector)
        assertFalse(panelState.showRatioSelector)
        assertFalse(panelState.showSceneSelector)
        assertFalse(panelState.showGridSelector)
        assertFalse(panelState.showFacialRefinement)
        assertFalse(panelState.showMakeupAdjustment)
        assertFalse(panelState.showBodyManagement)
    }

    // --- closePrimaryPanels() ---

    @Test
    fun `closePrimaryPanels - closes all primary panels`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true
        panelState.showRatioSelector = true
        panelState.showSceneSelector = true
        panelState.showGridSelector = true

        panelState.closePrimaryPanels()

        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
        assertFalse("showBeautySelector should be closed", panelState.showBeautySelector)
        assertFalse("showRatioSelector should be closed", panelState.showRatioSelector)
        assertFalse("showSceneSelector should be closed", panelState.showSceneSelector)
        assertFalse("showGridSelector should be closed", panelState.showGridSelector)
    }

    @Test
    fun `closePrimaryPanels - does not affect beauty sub panels`() {
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true

        panelState.closePrimaryPanels()

        assertTrue("showFacialRefinement should remain open", panelState.showFacialRefinement)
        assertTrue("showMakeupAdjustment should remain open", panelState.showMakeupAdjustment)
        assertTrue("showBodyManagement should remain open", panelState.showBodyManagement)
    }

    // --- closeBeautySubPanels() ---

    @Test
    fun `closeBeautySubPanels - closes all beauty sub panels`() {
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true

        panelState.closeBeautySubPanels()

        assertFalse("showFacialRefinement should be closed", panelState.showFacialRefinement)
        assertFalse("showMakeupAdjustment should be closed", panelState.showMakeupAdjustment)
        assertFalse("showBodyManagement should be closed", panelState.showBodyManagement)
    }

    @Test
    fun `closeBeautySubPanels - does not affect primary panels`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true

        panelState.closeBeautySubPanels()

        assertTrue("showFilterSelector should remain open", panelState.showFilterSelector)
        assertTrue("showBeautySelector should remain open", panelState.showBeautySelector)
    }

    // --- closeAllPanels() ---

    @Test
    fun `closeAllPanels - closes every panel`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true
        panelState.showRatioSelector = true
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true
        panelState.showProPanel = true

        panelState.closeAllPanels()

        assertFalse(panelState.showFilterSelector)
        assertFalse(panelState.showBeautySelector)
        assertFalse(panelState.showRatioSelector)
        assertFalse(panelState.showSceneSelector)
        assertFalse(panelState.showGridSelector)
        assertFalse(panelState.showFacialRefinement)
        assertFalse(panelState.showMakeupAdjustment)
        assertFalse(panelState.showBodyManagement)
        assertFalse("showProPanel should be closed", panelState.showProPanel)
    }

    // --- toggleFacialRefinement() ---

    @Test
    fun `toggleFacialRefinement - opens facial refinement and closes primary panels`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true

        panelState.toggleFacialRefinement()

        assertTrue("showFacialRefinement should be open", panelState.showFacialRefinement)
        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
        assertFalse("showBeautySelector should be closed", panelState.showBeautySelector)
    }

    @Test
    fun `toggleFacialRefinement - closes makeup and body when opening facial`() {
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true

        panelState.toggleFacialRefinement()

        assertTrue("showFacialRefinement should be open", panelState.showFacialRefinement)
        assertFalse("showMakeupAdjustment should be closed", panelState.showMakeupAdjustment)
        assertFalse("showBodyManagement should be closed", panelState.showBodyManagement)
    }

    @Test
    fun `toggleFacialRefinement - second toggle closes facial refinement`() {
        panelState.toggleFacialRefinement()
        assertTrue(panelState.showFacialRefinement)

        panelState.toggleFacialRefinement()
        assertFalse("Second toggle should close facial refinement", panelState.showFacialRefinement)
    }

    // --- toggleBodyManagement() ---

    @Test
    fun `toggleBodyManagement - opens body management and closes primary panels`() {
        panelState.showFilterSelector = true

        panelState.toggleBodyManagement()

        assertTrue("showBodyManagement should be open", panelState.showBodyManagement)
        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
    }

    @Test
    fun `toggleBodyManagement - closes facial and makeup panels when opening`() {
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true

        panelState.toggleBodyManagement()

        assertTrue("showBodyManagement should be open", panelState.showBodyManagement)
        assertFalse("showFacialRefinement should be closed", panelState.showFacialRefinement)
        assertFalse("showMakeupAdjustment should be closed", panelState.showMakeupAdjustment)
    }

    @Test
    fun `toggleBodyManagement - second toggle closes body management`() {
        panelState.toggleBodyManagement()
        assertTrue(panelState.showBodyManagement)

        panelState.toggleBodyManagement()
        assertFalse("Second toggle should close body management", panelState.showBodyManagement)
    }

    // --- openMakeupEntry() ---

    @Test
    fun `openMakeupEntry LIP_COLOR - opens makeup adjustment and closes primary panels`() {
        panelState.showFilterSelector = true

        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)

        assertTrue("showMakeupAdjustment should be open", panelState.showMakeupAdjustment)
        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
    }

    @Test
    fun `openMakeupEntry - closes facial refinement when opening makeup`() {
        panelState.showFacialRefinement = true

        panelState.openMakeupEntry(MakeupEntry.BLUSH)

        assertTrue("showMakeupAdjustment should be open", panelState.showMakeupAdjustment)
        assertFalse("showFacialRefinement should be closed", panelState.showFacialRefinement)
    }

    @Test
    fun `openMakeupEntry - same entry toggles off makeup panel`() {
        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        assertTrue(panelState.showMakeupAdjustment)

        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        assertFalse("Same entry should toggle off makeup panel", panelState.showMakeupAdjustment)
    }

    @Test
    fun `openMakeupEntry - different entry switches active entry without closing`() {
        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        panelState.openMakeupEntry(MakeupEntry.BLUSH)

        assertTrue("Makeup panel should remain open when switching entry", panelState.showMakeupAdjustment)
        assertTrue("Active entry should be BLUSH", panelState.activeMakeupEntry == MakeupEntry.BLUSH)
    }

    @Test
    fun `openMakeupEntry - switching through all makeup entries ends on last`() {
        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        panelState.openMakeupEntry(MakeupEntry.BLUSH)

        assertTrue(panelState.showMakeupAdjustment)
        assertTrue(panelState.activeMakeupEntry == MakeupEntry.BLUSH)
    }

    // --- 组合场景 ---

    @Test
    fun `combined - opening beauty sub panel closes all primary panels`() {
        panelState.showFilterSelector = true
        panelState.showRatioSelector = true

        panelState.toggleFacialRefinement()

        assertFalse("Filter selector should be closed", panelState.showFilterSelector)
        assertFalse("Ratio selector should be closed", panelState.showRatioSelector)
        assertTrue("Facial refinement should be open", panelState.showFacialRefinement)
    }

    @Test
    fun `combined - closeAllPanels resets entire state`() {
        panelState.showBeautySelector = true
        panelState.showFacialRefinement = true
        panelState.openMakeupEntry(MakeupEntry.BLUSH)
        panelState.toggleBodyManagement()

        panelState.closeAllPanels()

        assertFalse(panelState.showFilterSelector)
        assertFalse(panelState.showBeautySelector)
        assertFalse(panelState.showRatioSelector)
        assertFalse(panelState.showSceneSelector)
        assertFalse(panelState.showGridSelector)
        assertFalse(panelState.showFacialRefinement)
        assertFalse(panelState.showMakeupAdjustment)
        assertFalse(panelState.showBodyManagement)
    }

    @Test
    fun `combined - toggleMakeupAdjustment delegates to openMakeupEntry with active entry`() {
        panelState.openMakeupEntry(MakeupEntry.BLUSH)
        panelState.showMakeupAdjustment = false

        panelState.toggleMakeupAdjustment()

        assertTrue("toggleMakeupAdjustment should reopen with active entry", panelState.showMakeupAdjustment)
        assertTrue("Active entry should still be BLUSH", panelState.activeMakeupEntry == MakeupEntry.BLUSH)
    }

    // --- togglePrimaryPanel() 统一互斥（camera.yaml §17，2026-08-15 改版） ---

    @Test
    fun `togglePrimaryPanel - opening a panel closes all others including pro`() {
        panelState.showBeautySelector = true
        panelState.showProPanel = true

        togglePrimaryPanel(
            isCurrentlyVisible = panelState.showRatioSelector,
            closeAllPanels = panelState::closeAllPanels,
            onPanelVisibilityChanged = { isVisible -> panelState.showRatioSelector = isVisible }
        )

        assertTrue("Ratio panel should be open", panelState.showRatioSelector)
        assertFalse("Beauty panel should be closed", panelState.showBeautySelector)
        assertFalse("Pro panel should be closed", panelState.showProPanel)
    }

    @Test
    fun `togglePrimaryPanel - opening filter while ratio open leaves only filter`() {
        panelState.showRatioSelector = true

        togglePrimaryPanel(
            isCurrentlyVisible = panelState.showFilterSelector,
            closeAllPanels = panelState::closeAllPanels,
            onPanelVisibilityChanged = { isVisible -> panelState.showFilterSelector = isVisible }
        )

        assertTrue("Filter panel should be open", panelState.showFilterSelector)
        assertFalse("Ratio panel should be closed", panelState.showRatioSelector)
    }

    @Test
    fun `togglePrimaryPanel - toggling an open panel closes everything`() {
        panelState.showGridSelector = true

        togglePrimaryPanel(
            isCurrentlyVisible = panelState.showGridSelector,
            closeAllPanels = panelState::closeAllPanels,
            onPanelVisibilityChanged = { isVisible -> panelState.showGridSelector = isVisible }
        )

        assertFalse("Grid panel should be closed after second toggle", panelState.showGridSelector)
    }
}

