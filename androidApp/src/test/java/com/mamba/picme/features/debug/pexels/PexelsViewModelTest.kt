package com.mamba.picme.features.debug.pexels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

@OptIn(ExperimentalCoroutinesApi::class)
class PexelsViewModelTest {

    private val api: PexelsApi = mockk()
    private val keyStore: PexelsKeyStore = mockk(relaxUnitFun = true)
    private val imageSaver: PexelsImageSaver = mockk()
    private lateinit var scope: TestScope

    private fun photo(id: Long) = PexelsPhoto(
        id = id,
        src = PexelsSrc(large2x = "https://img/$id/large2x.jpg", medium = "https://img/$id/medium.jpg")
    )

    private fun response(ids: List<Long>, nextPage: String? = "next") =
        PexelsSearchResponse(photos = ids.map(::photo), page = 1, nextPage = nextPage)

    @Before
    fun setUp() {
        scope = TestScope(UnconfinedTestDispatcher())
    }

    private fun newViewModel(): PexelsViewModel =
        PexelsViewModel(api, keyStore, imageSaver, scope)

    @Test
    fun `no key stored starts at NoKey`() {
        every { keyStore.getKey() } returns null
        val vm = newViewModel()
        assertEquals(PexelsUiState.NoKey(), vm.uiState.value)
    }

    @Test
    fun `key stored auto loads curated into Ready`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        val vm = newViewModel()
        val state = vm.uiState.value as PexelsUiState.Ready
        assertEquals(listOf(1L, 2L), state.photos.map { it.id })
        assertFalse(state.endReached)
    }

    @Test
    fun `saveKey stores key and loads curated`() {
        every { keyStore.getKey() } returns null andThen "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        val vm = newViewModel()
        vm.saveKey("  key  ")
        verify { keyStore.saveKey("  key  ") }
        assertTrue(vm.uiState.value is PexelsUiState.Ready)
    }

    @Test
    fun `search replaces photos and uses query endpoint`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        coEvery { api.search("key", "雪山", 1) } returns response(listOf(9L))
        val vm = newViewModel()
        vm.search("雪山")
        val state = vm.uiState.value as PexelsUiState.Ready
        assertEquals(listOf(9L), state.photos.map { it.id })
    }

    @Test
    fun `blank search falls back to curated`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        val vm = newViewModel()
        vm.search("   ")
        coVerify(exactly = 2) { api.curated("key", 1) }
    }

    @Test
    fun `loadMore appends next page`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        coEvery { api.curated("key", 2) } returns response(listOf(2L), nextPage = null)
        val vm = newViewModel()
        vm.loadMore()
        val state = vm.uiState.value as PexelsUiState.Ready
        assertEquals(listOf(1L, 2L), state.photos.map { it.id })
        assertTrue(state.endReached)
    }

    @Test
    fun `loadMore is no-op when endReached`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L), nextPage = null)
        val vm = newViewModel()
        vm.loadMore()
        coVerify(exactly = 1) { api.curated("key", 1) }
    }

    @Test
    fun `toggleSelect adds then removes`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        val vm = newViewModel()
        vm.toggleSelect(1L)
        assertEquals(setOf(1L), (vm.uiState.value as PexelsUiState.Ready).selectedIds)
        vm.toggleSelect(1L)
        assertTrue((vm.uiState.value as PexelsUiState.Ready).selectedIds.isEmpty())
    }

    @Test
    fun `downloadSelected saves each selected photo and clears selection`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        coEvery { imageSaver.save(any(), any()) } returns true
        val vm = newViewModel()
        vm.toggleSelect(2L)
        vm.downloadSelected()
        coVerify(exactly = 1) { imageSaver.save(2L, "https://img/2/large2x.jpg") }
        val state = vm.uiState.value as PexelsUiState.Ready
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.downloading)
    }

    @Test
    fun `downloadBatch paginates until enough photos`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        coEvery { api.curated("key", 2) } returns response(listOf(2L), nextPage = null)
        coEvery { imageSaver.save(any(), any()) } returns true
        val vm = newViewModel()
        vm.downloadBatch(2)
        coVerify(exactly = 1) { imageSaver.save(1L, any()) }
        coVerify(exactly = 1) { imageSaver.save(2L, any()) }
    }

    @Test
    fun `401 clears key and falls back to NoKey with invalidPrevious`() {
        every { keyStore.getKey() } returns "bad"
        coEvery { api.curated("bad", 1) } throws mockk<HttpException> {
            every { code() } returns 401
        }
        val vm = newViewModel()
        verify { keyStore.clear() }
        assertEquals(PexelsUiState.NoKey(invalidPrevious = true), vm.uiState.value)
    }

    @Test
    fun `429 maps to RATE_LIMITED error`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } throws mockk<HttpException> {
            every { code() } returns 429
        }
        val vm = newViewModel()
        assertEquals(PexelsUiState.Error(PexelsErrorKind.RATE_LIMITED), vm.uiState.value)
    }

    @Test
    fun `network exception maps to NETWORK error`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } throws java.io.IOException("timeout")
        val vm = newViewModel()
        assertEquals(PexelsUiState.Error(PexelsErrorKind.NETWORK), vm.uiState.value)
    }

    @Test
    fun `download completion emits DownloadCompleted event`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        coEvery { imageSaver.save(any(), any()) } returns true
        val vm = newViewModel()
        val received = mutableListOf<PexelsEvent>()
        scope.launch { vm.events.toList(received) }
        vm.toggleSelect(1L)
        vm.toggleSelect(2L)
        vm.downloadSelected()
        assertEquals(listOf(PexelsEvent.DownloadCompleted(2, 2)), received)
    }

    @Test
    fun `partial download failure reports accurate success count`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        coEvery { imageSaver.save(1L, any()) } returns true
        coEvery { imageSaver.save(2L, any()) } returns false
        val vm = newViewModel()
        val received = mutableListOf<PexelsEvent>()
        scope.launch { vm.events.toList(received) }
        vm.toggleSelect(1L)
        vm.toggleSelect(2L)
        vm.downloadSelected()
        assertEquals(listOf(PexelsEvent.DownloadCompleted(1, 2)), received)
    }
}
