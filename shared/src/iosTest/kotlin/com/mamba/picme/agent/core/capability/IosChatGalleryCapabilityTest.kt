package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.data.IosMediaItem
import com.mamba.picme.data.IosMediaRepository
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.domain.repository.AccessState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeBridge(
    private val items: List<IosMediaItem>
) : IosMediaRepositoryBridge {
    val favoriteCalls = mutableListOf<Pair<String, Boolean>>()
    var deleted: List<String> = emptyList()
        private set
    var favoriteResult: Boolean = true
    var deleteResult: Boolean = true

    override fun currentAccessState(): AccessState = AccessState.Full
    override fun fetchAllMedia(): List<IosMediaItem> = items
    override fun requestReadWriteAuthorization() = Unit
    override fun addChangeListener(listener: () -> Unit) = Unit
    override fun removeChangeListener() = Unit
    override fun deleteMedia(localIdentifiers: List<String>): Boolean {
        deleted = localIdentifiers
        return deleteResult
    }
    override fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean {
        favoriteCalls += localIdentifier to favorite
        return favoriteResult
    }
}

private val ITEMS = listOf(
    IosMediaItem("L-1", "PHOTO", 1000L, fileName = "cat.jpg"),
    IosMediaItem("L-2", "PHOTO", 2000L, fileName = "dog.png"),
    IosMediaItem("L-3", "VIDEO", 3000L, 8000L, fileName = "IMG_0001.HEIC")
)

private fun idOf(localIdentifier: String): Long = localIdentifier.hashCode().toLong()

class IosChatGalleryCapabilityTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    private fun newCapability(bridge: FakeBridge = FakeBridge(ITEMS)) =
        IosChatGalleryCapability(IosMediaRepository(bridge), bridge) to bridge

    // ── 盘点 ──────────────────────────────────────────────────────────────

    @Test
    fun summaryCountsPhotosAndVideos() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.GetGallerySummary(), context, null).getOrThrow()
        val reply = assertIs<AgentAction.TextReply>(action)
        assertTrue(reply.message.contains("3 个媒体"), "应含总数：${reply.message}")
        assertTrue(reply.message.contains("2 张照片"), "应含照片数：${reply.message}")
        assertTrue(reply.message.contains("1 个视频"), "应含视频数：${reply.message}")
    }

    // ── 搜索 / 细化 ─────────────────────────────────────────────────────────

    @Test
    fun searchMatchesFileNameIgnoreCase() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.SearchMedia(query = "CAT"), context, null).getOrThrow()
        val results = assertIs<AgentAction.MediaResults>(action)
        assertEquals(listOf(idOf("L-1")), results.mediaIds)
        assertEquals(1, results.totalCount)
        assertEquals(false, results.isRefinement)
    }

    @Test
    fun searchNoMatchReturnsZeroResults() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.SearchMedia(query = "zzz"), context, null).getOrThrow()
        val results = assertIs<AgentAction.MediaResults>(action)
        assertEquals(0, results.totalCount)
        assertTrue(results.mediaIds.isEmpty())
    }

    @Test
    fun searchWithTimeRangeFiltersCaptureDate() = runTest {
        val (cap, _) = newCapability()
        val command = AgentCommand.SearchMedia(
            query = "",
            intent = SearchIntent(query = "", timeRange = TimeRange(1500L, 2500L))
        )
        val action = cap.execute(command, context, null).getOrThrow()
        val results = assertIs<AgentAction.MediaResults>(action)
        assertEquals(listOf(idOf("L-2")), results.mediaIds)
    }

    @Test
    fun refineNarrowsWithinPreviousResults() = runTest {
        val (cap, _) = newCapability()
        // 空关键词搜索 = 「看看最近的」，种子为全部 3 条
        cap.execute(AgentCommand.SearchMedia(query = ""), context, null).getOrThrow()
        val action = cap.execute(AgentCommand.RefineMediaSearch(constraint = "cat"), context, null).getOrThrow()
        val results = assertIs<AgentAction.MediaResults>(action)
        assertEquals(listOf(idOf("L-1")), results.mediaIds)
        assertEquals(true, results.isRefinement)
    }

    @Test
    fun refineWithoutPreviousSearchFallsBackToGlobal() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.RefineMediaSearch(constraint = "dog"), context, null).getOrThrow()
        val results = assertIs<AgentAction.MediaResults>(action)
        assertEquals(listOf(idOf("L-2")), results.mediaIds)
        assertEquals(false, results.isRefinement)
    }

    // ── 写命令 ──────────────────────────────────────────────────────────────

    @Test
    fun favoriteCallsBridgeWithResolvedIdentifier() = runTest {
        val (cap, bridge) = newCapability()
        val command = AgentCommand.FavoriteMedia(mediaId = idOf("L-1").toString(), favorite = true)
        val action = cap.execute(command, context, null).getOrThrow()
        val reply = assertIs<AgentAction.TextReply>(action)
        assertEquals("已收藏", reply.message)
        assertEquals(listOf("L-1" to true), bridge.favoriteCalls)
    }

    @Test
    fun favoriteUnknownMediaReturnsNotFoundReply() = runTest {
        val (cap, bridge) = newCapability()
        val command = AgentCommand.FavoriteMedia(mediaId = "999999", favorite = true)
        val action = cap.execute(command, context, null).getOrThrow()
        assertIs<AgentAction.TextReply>(action)
        assertTrue(bridge.favoriteCalls.isEmpty(), "未命中媒体不应调用桥")
    }

    @Test
    fun deleteResolvesIdentifiersAndClearsLastSearch() = runTest {
        val (cap, bridge) = newCapability()
        // 先搜索种子，删除后 refine 应退化为全局（lastSearchAssets 已清）
        cap.execute(AgentCommand.SearchMedia(query = ""), context, null).getOrThrow()
        val command = AgentCommand.DeleteMedia(mediaIds = listOf(idOf("L-1").toString()))
        val action = cap.execute(command, context, null).getOrThrow()
        val reply = assertIs<AgentAction.TextReply>(action)
        assertTrue(reply.message.contains("1 个媒体"), "应含删除数：${reply.message}")
        assertEquals(listOf("L-1"), bridge.deleted)

        val refine = cap.execute(AgentCommand.RefineMediaSearch(constraint = "dog"), context, null).getOrThrow()
        assertEquals(false, assertIs<AgentAction.MediaResults>(refine).isRefinement)
    }

    @Test
    fun deleteEmptyIdsReturnsInvalidParams() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.DeleteMedia(), context, null).getOrThrow()
        val error = assertIs<AgentAction.Error>(action)
        assertEquals(AgentErrorCode.INVALID_PARAMS, error.errorCode)
    }

    // ── UI 直通命令 ─────────────────────────────────────────────────────────

    @Test
    fun viewSelectSharePassThroughAsSuccess() = runTest {
        val (cap, _) = newCapability()
        val view = AgentCommand.ViewMedia(mediaId = idOf("L-1").toString())
        val viewAction = cap.execute(view, context, null).getOrThrow()
        assertEquals(view, assertIs<AgentAction.Success>(viewAction).command)

        val select = AgentCommand.SelectMedia(mediaId = idOf("L-2").toString(), selected = true)
        val selectAction = cap.execute(select, context, null).getOrThrow()
        assertEquals(select, assertIs<AgentAction.Success>(selectAction).command)

        val share = AgentCommand.ShareMedia(mediaIds = listOf(idOf("L-3").toString()))
        val shareAction = cap.execute(share, context, null).getOrThrow()
        assertEquals(share, assertIs<AgentAction.Success>(shareAction).command)
    }

    @Test
    fun viewWithoutMediaIdReturnsInvalidParams() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.ViewMedia(), context, null).getOrThrow()
        val error = assertIs<AgentAction.Error>(action)
        assertEquals(AgentErrorCode.INVALID_PARAMS, error.errorCode)
    }

    @Test
    fun unsupportedCommandReturnsMethodNotFound() = runTest {
        val (cap, _) = newCapability()
        val action = cap.execute(AgentCommand.StartTagScan(action = "query"), context, null).getOrThrow()
        val error = assertIs<AgentAction.Error>(action)
        assertEquals(AgentErrorCode.METHOD_NOT_FOUND, error.errorCode)
    }

    @Test
    fun supportedCommandsCoverEightChatGalleryTools() {
        val (cap, _) = newCapability()
        assertEquals(
            listOf(
                "get_gallery_summary", "search_media", "refine_media_search",
                "view_media", "select_media", "favorite_media", "delete_media", "share_media"
            ),
            cap.supportedCommands()
        )
    }
}
