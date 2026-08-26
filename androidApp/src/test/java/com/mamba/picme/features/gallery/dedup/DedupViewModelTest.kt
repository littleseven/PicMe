package com.mamba.picme.features.gallery.dedup

import android.content.IntentSender
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupMember
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanController
import com.mamba.picme.domain.dedup.DedupScanEvent
import com.mamba.picme.domain.dedup.DedupScanner
import com.mamba.picme.domain.dedup.DedupTrashManager
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.VersionRole
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DedupViewModelTest {

    private fun member(
        uri: String,
        sizeBytes: Long = 1_000L,
        captureDate: Long = 0L,
        modifiedAt: Long = 0L,
        pixelArea: Int = 100,
    ) = DedupMember(
        uri = uri,
        sizeBytes = sizeBytes,
        mime = "image/jpeg",
        captureDate = captureDate,
        modifiedAt = modifiedAt,
        pixelArea = pixelArea,
        aestheticScore = null,
        role = VersionRole.ORIGINAL,
        md5 = null,
        phash = null,
    )

    private fun group(id: String, level: DedupLevel, members: List<DedupMember>) =
        DedupGroup(id = id, level = level, members = members, keepUri = members.first().uri)

    /** 假扫描器：按脚本流出事件，可选挂起（模拟扫描进行中）。 */
    private class FakeScanner(
        private val events: List<DedupScanEvent>,
        private val hang: Boolean = false,
    ) : DedupScanController {
        override var pauseRequested: Boolean = false
        var resumeCount: Int = 0
            private set
        var lastConfig: DedupScanConfig? = null
            private set

        override fun resume() {
            pauseRequested = false
            resumeCount++
        }

        override fun scan(
            items: List<DedupScanner.ScanItem>,
            config: DedupScanConfig,
        ): Flow<DedupScanEvent> {
            lastConfig = config
            return flow {
                for (event in events) emit(event)
                if (hang) awaitCancellation()
            }
        }
    }

    private fun fakeTrashManager(supported: Boolean = true): DedupTrashManager = mockk {
        every { isSupported } returns supported
        every { buildTrashIntent(any()) } returns mockk<IntentSender>()
        every { buildRestoreIntent(any()) } returns mockk<IntentSender>()
        every { queryExisting(any()) } returns emptyList()
    }

    /** 排空协程：本环境 advanceUntilIdle 不驱动 backgroundScope，先推进 1ms（同 ChatPhotoPickerViewModelTest 范式）。 */
    private fun TestScope.settle() {
        advanceTimeBy(1)
        advanceUntilIdle()
    }

    private fun viewModel(
        scanner: DedupScanController,
        trashManager: DedupTrashManager = fakeTrashManager(),
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ) = DedupViewModel(
        mediaSource = DedupMediaSource { emptyList() },
        scanner = scanner,
        trashManager = trashManager,
        coroutineScope = scope,
        ioDispatcher = ioDispatcher,
    )

    @Test
    fun `GroupFound events appear in Scanning state progressively`() = runTest {
        val g1 = group("g1", DedupLevel.EXACT, listOf(member("a"), member("b")))
        val g2 = group("g2", DedupLevel.EXACT, listOf(member("c"), member("d")))
        val scanner = FakeScanner(
            events = listOf(
                DedupScanEvent.PhaseChanged(DedupLevel.EXACT, 1, 2),
                DedupScanEvent.Progress(DedupLevel.EXACT, scanned = 10, total = 100),
                DedupScanEvent.GroupFound(g1),
                DedupScanEvent.GroupFound(g2),
            ),
            hang = true,
        )
        val vm = viewModel(scanner, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()

        val state = vm.uiState.value as DedupUiState.Scanning
        assertEquals(DedupLevel.EXACT, state.phase)
        assertEquals(1, state.phaseIndex)
        assertEquals(2, state.phaseCount)
        assertEquals(10, state.scanned)
        assertEquals(100, state.total)
        // 最新发现排前
        assertEquals(listOf(g2, g1), state.foundGroups)
        assertFalse(state.paused)
    }

    @Test
    fun `setKeep marks userOverride and changes deleteUris`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a"), member("b", sizeBytes = 2_000L)))
        val scanner = FakeScanner(events = listOf(DedupScanEvent.Done(listOf(g))))
        val vm = viewModel(scanner, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()
        val results = vm.uiState.value as DedupUiState.Results
        assertEquals("a", results.groups.single().keepUri)
        assertEquals(listOf("b"), results.groups.single().deleteUris)

        vm.setKeep("g1", "b")

        val updated = (vm.uiState.value as DedupUiState.Results).groups.single()
        assertEquals("b", updated.keepUri)
        assertTrue(updated.userOverride)
        assertEquals(listOf("a"), updated.deleteUris)
        assertEquals(1_000L, updated.reclaimBytes)
    }

    @Test
    fun `applyPolicy skips userOverride groups`() = runTest {
        val g1 = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", modifiedAt = 100L), member("b", modifiedAt = 200L)),
        )
        val g2 = group(
            "g2", DedupLevel.VISUAL,
            listOf(member("c", modifiedAt = 300L), member("d", modifiedAt = 400L)),
        )
        val scanner = FakeScanner(events = listOf(DedupScanEvent.Done(listOf(g1, g2))))
        val vm = viewModel(scanner, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()
        vm.setKeep("g1", "b") // 用户手动保留 b

        vm.applyPolicy(KeepPolicy.LATEST)

        val state = vm.uiState.value as DedupUiState.Results
        assertEquals(KeepPolicy.LATEST, state.policy)
        val updatedG1 = state.groups.first { grp -> grp.id == "g1" }
        // userOverride 组不动
        assertEquals("b", updatedG1.keepUri)
        assertTrue(updatedG1.userOverride)
        assertEquals(listOf("a", "b"), updatedG1.members.map { m -> m.uri })
        val updatedG2 = state.groups.first { grp -> grp.id == "g2" }
        // 未覆盖组按 LATEST 重排：modifiedAt 最新的 d 排第一并成为 keep
        assertEquals("d", updatedG2.keepUri)
        assertEquals(listOf("d", "c"), updatedG2.members.map { m -> m.uri })
    }

    @Test
    fun `smartSelectAll does not touch SCENE groups`() = runTest {
        val exact = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", captureDate = 1L), member("b", captureDate = 2L)),
        )
        val scene = group(
            "g2", DedupLevel.SCENE,
            listOf(member("c", captureDate = 3L), member("d", captureDate = 4L)),
        )
        val scanner = FakeScanner(events = listOf(DedupScanEvent.Done(listOf(exact, scene))))
        val vm = viewModel(scanner, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()
        vm.setKeep("g1", "a")
        vm.setKeep("g2", "d")

        vm.smartSelectAll()

        val state = vm.uiState.value as DedupUiState.Results
        val exactGroup = state.groups.first { grp -> grp.id == "g1" }
        // EXACT 组清 override 并按当前 policy（BEST_QUALITY：captureDate 最新在前）重算
        assertFalse(exactGroup.userOverride)
        assertEquals("b", exactGroup.keepUri)
        val sceneGroup = state.groups.first { grp -> grp.id == "g2" }
        // SCENE 组保留用户手动选择
        assertTrue(sceneGroup.userOverride)
        assertEquals("d", sceneGroup.keepUri)
    }

    @Test
    fun `pause sets paused true and resume clears it`() = runTest {
        val scanner = FakeScanner(
            events = listOf(DedupScanEvent.Progress(DedupLevel.EXACT, 5, 100)),
            hang = true,
        )
        val vm = viewModel(scanner, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()

        vm.pauseScan()
        val paused = vm.uiState.value as DedupUiState.Scanning
        assertTrue(paused.paused)
        assertTrue(scanner.pauseRequested)

        vm.resumeScan()
        val resumed = vm.uiState.value as DedupUiState.Scanning
        assertFalse(resumed.paused)
        assertFalse(scanner.pauseRequested)
        assertEquals(1, scanner.resumeCount)
    }

    @Test
    fun `cancelScan returns to Config and releases scanner pause`() = runTest {
        val scanner = FakeScanner(events = emptyList(), hang = true)
        val vm = viewModel(scanner, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()
        assertTrue(vm.uiState.value is DedupUiState.Scanning)

        vm.cancelScan()
        settle()

        assertTrue(vm.uiState.value is DedupUiState.Config)
        assertEquals(1, scanner.resumeCount)
    }

    @Test
    fun `deleteSelected on API30 path emits PendingTrash and refusal returns to Results with groups intact`() =
        runTest {
            val g = group("g1", DedupLevel.EXACT, listOf(member("a"), member("b", sizeBytes = 2_000L)))
            val scanner = FakeScanner(events = listOf(DedupScanEvent.Done(listOf(g))))
            val trash = fakeTrashManager(supported = true)
            val vm = viewModel(scanner, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

            vm.startScan(DedupScanConfig())
            settle()

            vm.deleteSelected()
            val pending = vm.pendingTrash.value
            assertNotNull(pending)
            assertEquals(listOf("b"), pending?.uris)
            // 授权在途时状态仍是 Results
            assertTrue(vm.uiState.value is DedupUiState.Results)

            vm.onTrashResult(ok = false)

            assertNull(vm.pendingTrash.value)
            val state = vm.uiState.value as DedupUiState.Results
            assertEquals(listOf(g), state.groups)
        }

    @Test
    fun `deleteSelected confirmed moves to Cleaned with reclaimed bytes`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a"), member("b", sizeBytes = 2_000L)))
        val scanner = FakeScanner(events = listOf(DedupScanEvent.Done(listOf(g))))
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(scanner, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()
        vm.deleteSelected()
        vm.onTrashResult(ok = true)
        settle()

        val cleaned = vm.uiState.value as DedupUiState.Cleaned
        assertEquals(1, cleaned.deletedCount)
        assertEquals(2_000L, cleaned.reclaimedBytes)
        assertEquals(listOf("b"), cleaned.trashedUris)
        assertNull(vm.pendingTrash.value)
    }

    @Test
    fun `deleteSelected with no deletable uris is a no-op`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a")))
        val scanner = FakeScanner(events = listOf(DedupScanEvent.Done(listOf(g))))
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(scanner, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        settle()
        vm.deleteSelected()

        assertNull(vm.pendingTrash.value)
        assertTrue(vm.uiState.value is DedupUiState.Results)
    }

    @Test
    fun `startScan is not re-entrant while Scanning`() = runTest {
        val scanner = FakeScanner(events = emptyList(), hang = true)
        val vm = viewModel(scanner, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig(visualThreshold = 5))
        settle()
        vm.startScan(DedupScanConfig(visualThreshold = 9))
        settle()

        // 第二次启动被忽略：scanner 只见过第一份配置
        assertEquals(5, scanner.lastConfig?.visualThreshold)
    }
}
