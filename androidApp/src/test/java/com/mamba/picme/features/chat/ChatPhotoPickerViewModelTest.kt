package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPhotoPickerViewModelTest {

    private fun asset(id: Long) = MediaAsset(
        id = id,
        uri = "content://media/external/images/media/$id",
        type = MediaType.PHOTO,
        captureDate = 0L,
        fileName = "img$id.jpg"
    )

    @Test
    fun `blank query never searches and keeps results empty`() = runTest {
        val calls = mutableListOf<String>()
        val vm = ChatPhotoPickerViewModel(
            search = { q -> calls.add(q); emptyList() },
            searchAvailable = true,
            coroutineScope = backgroundScope
        )
        vm.setQuery("")
        advanceTimeBy(300); advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertTrue(vm.results.value.isEmpty())
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun `non-blank query searches after debounce and populates results`() = runTest {
        val fake = listOf(asset(1), asset(2))
        val vm = ChatPhotoPickerViewModel(
            search = { q -> if (q == "cat") fake else emptyList() },
            searchAvailable = true,
            coroutineScope = backgroundScope
        )
        vm.setQuery("cat")
        advanceTimeBy(300); advanceUntilIdle()
        assertEquals(fake, vm.results.value)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun `search unavailable never invokes search`() = runTest {
        val calls = mutableListOf<String>()
        val vm = ChatPhotoPickerViewModel(
            search = { q -> calls.add(q); emptyList() },
            searchAvailable = false,
            coroutineScope = backgroundScope
        )
        vm.setQuery("cat")
        advanceTimeBy(300); advanceUntilIdle()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `clearing query after a search empties results`() = runTest {
        val fake = listOf(asset(1))
        val vm = ChatPhotoPickerViewModel(
            search = { fake },
            searchAvailable = true,
            coroutineScope = backgroundScope
        )
        vm.setQuery("cat"); advanceTimeBy(300); advanceUntilIdle()
        assertEquals(fake, vm.results.value)
        vm.setQuery(""); advanceTimeBy(300); advanceUntilIdle()
        assertTrue(vm.results.value.isEmpty())
    }
}
