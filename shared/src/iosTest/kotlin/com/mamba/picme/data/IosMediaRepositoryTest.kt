package com.mamba.picme.data

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.repository.AccessState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBridge(
    private val items: List<IosMediaItem> = listOf(
        IosMediaItem("ABC-1", "PHOTO", 1000L, fileName = "IMG_0001.jpg"),
        IosMediaItem("ABC-2", "VIDEO", 2000L, 5000L, fileName = "VID_0002.mp4")
    ),
    private val access: AccessState = AccessState.Full
) : IosMediaRepositoryBridge {
    var deleted: List<String> = emptyList()
        private set
    var listenerRemoved: Boolean = false
        private set

    override fun currentAccessState(): AccessState = access
    override fun fetchAllMedia(): List<IosMediaItem> = items
    override fun requestReadWriteAuthorization() = Unit
    override fun addChangeListener(listener: () -> Unit) = Unit
    override fun removeChangeListener() {
        listenerRemoved = true
    }
    override fun deleteMedia(localIdentifiers: List<String>): Boolean {
        deleted = localIdentifiers
        return true
    }
    override fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean = true
}

class IosMediaRepositoryTest {

    @Test
    fun allMediaMapsDtoToDomain() = runTest {
        val repo = IosMediaRepository(FakeBridge())
        val list = repo.allMedia.first()
        assertEquals(2, list.size)
        assertEquals("ABC-1", list[0].uri)
        assertEquals(MediaType.PHOTO, list[0].type)
        assertEquals(MediaType.VIDEO, list[1].type)
        assertEquals(5000L, list[1].duration)
    }

    @Test
    fun idIsDerivedFromLocalIdentifier() = runTest {
        val repo = IosMediaRepository(FakeBridge())
        val list = repo.allMedia.first()
        assertEquals("ABC-1".hashCode().toLong(), list[0].id)
    }

    @Test
    fun accessStateEmitsSnapshot() = runTest {
        val repo = IosMediaRepository(FakeBridge(access = AccessState.Limited))
        assertEquals(AccessState.Limited, repo.accessState.first())
    }

    @Test
    fun getMediaByIdFindsByDerivedId() = runTest {
        val repo = IosMediaRepository(FakeBridge())
        val id = "ABC-2".hashCode().toLong()
        assertEquals("ABC-2", repo.getMediaById(id)?.uri)
        assertNull(repo.getMediaById(-1L))
    }

    @Test
    fun deleteMediaByIdsResolvesIdentifiersFromFetch() = runTest {
        val bridge = FakeBridge()
        val repo = IosMediaRepository(bridge)
        repo.deleteMediaByIds(listOf("ABC-1".hashCode().toLong(), "ABC-2".hashCode().toLong()))
        assertEquals(listOf("ABC-1", "ABC-2"), bridge.deleted)
    }

    @Test
    fun androidPendingDeleteApisAreNoOps() = runTest {
        val repo = IosMediaRepository(FakeBridge())
        assertTrue(repo.getPendingDeleteUris().isEmpty())
        repo.executePendingDeletes() // 不抛即过
    }

    @Test
    fun fileNameComesFromDtoOriginalFilename() = runTest {
        val repo = IosMediaRepository(FakeBridge())
        val list = repo.allMedia.first()
        assertEquals("IMG_0001.jpg", list[0].fileName)
        assertEquals("VID_0002.mp4", list[1].fileName)
    }

    @Test
    fun flowCloseRemovesChangeListener() = runTest {
        val bridge = FakeBridge()
        val repo = IosMediaRepository(bridge)
        val job = launch { repo.allMedia.collect { } }
        runCurrent()  // StandardTestDispatcher 需显式驱动，让 flow 块先进去注册 listener
        job.cancel()
        job.join()
        assertTrue(bridge.listenerRemoved, "awaitClose 应注销 changeListener（防泄漏）")
    }
}
