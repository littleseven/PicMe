package com.mamba.picme.data

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.repository.AccessState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBridge(
    private val items: List<IosMediaItem> = listOf(
        IosMediaItem("ABC-1", "PHOTO", 1000L),
        IosMediaItem("ABC-2", "VIDEO", 2000L, 5000L)
    ),
    private val access: AccessState = AccessState.Full
) : IosMediaRepositoryBridge {
    var deleted: List<String> = emptyList()
        private set

    override fun currentAccessState(): AccessState = access
    override fun fetchAllMedia(): List<IosMediaItem> = items
    override fun requestReadWriteAuthorization() = Unit
    override fun addChangeListener(listener: () -> Unit) = Unit
    override fun deleteMedia(localIdentifiers: List<String>): Boolean {
        deleted = localIdentifiers
        return true
    }
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
}
