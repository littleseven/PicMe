package com.mamba.picme.features.common.avatar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class AvatarCaptureControllerTest {

    @After
    fun tearDown() {
        AvatarCaptureController.clear()
    }

    @Test
    fun beginSetsPendingAndClearResets() {
        assertNull(AvatarCaptureController.pending.value)

        AvatarCaptureController.begin(AvatarCaptureTarget.Person(7L), AvatarCaptureOrigin.PEOPLE_PAGE)

        val pending = AvatarCaptureController.pending.value
        assertEquals(AvatarCaptureTarget.Person(7L), pending?.target)
        assertEquals(AvatarCaptureOrigin.PEOPLE_PAGE, pending?.origin)

        AvatarCaptureController.clear()
        assertNull(AvatarCaptureController.pending.value)
    }

    @Test
    fun beginOverwritesPreviousPending() {
        AvatarCaptureController.begin(AvatarCaptureTarget.Person(1L), AvatarCaptureOrigin.GALLERY_PAGE)
        AvatarCaptureController.begin(AvatarCaptureTarget.Self, AvatarCaptureOrigin.SETTINGS_PAGE)

        val pending = AvatarCaptureController.pending.value
        assertEquals(AvatarCaptureTarget.Self, pending?.target)
        assertEquals(AvatarCaptureOrigin.SETTINGS_PAGE, pending?.origin)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AvatarCaptureFinisherTest {

    private fun newFinisher(
        latestMediaId: Long?,
        selfPersonId: Long? = null,
        updateCoverCalls: MutableList<Pair<Long, Long>> = mutableListOf()
    ) = AvatarCaptureFinisher(
        ioDispatcher = UnconfinedTestDispatcher(),
        findLatestCapturedMediaId = { latestMediaId },
        getSelfPersonId = { selfPersonId },
        updateCover = { personId, mediaId -> updateCoverCalls.add(personId to mediaId) },
        pollIntervalMs = 0L,
        delayMs = { }
    )

    @Test
    fun personTargetSetsCoverFromLatestCapturedMedia() = runTest {
        val updateCoverCalls = mutableListOf<Pair<Long, Long>>()
        val finisher = newFinisher(latestMediaId = 42L, updateCoverCalls = updateCoverCalls)

        val result = finisher.finish(AvatarCaptureTarget.Person(7L), success = true, captureStartMs = 1000L)

        assertTrue(result)
        assertEquals(listOf(7L to 42L), updateCoverCalls)
    }

    @Test
    fun selfTargetUsesSelfPersonId() = runTest {
        val updateCoverCalls = mutableListOf<Pair<Long, Long>>()
        val finisher = newFinisher(latestMediaId = 42L, selfPersonId = 3L, updateCoverCalls = updateCoverCalls)

        val result = finisher.finish(AvatarCaptureTarget.Self, success = true, captureStartMs = 1000L)

        assertTrue(result)
        assertEquals(listOf(3L to 42L), updateCoverCalls)
    }

    @Test
    fun selfTargetWithoutSelfMarkFailsSilently() = runTest {
        val updateCoverCalls = mutableListOf<Pair<Long, Long>>()
        val finisher = newFinisher(latestMediaId = 42L, selfPersonId = null, updateCoverCalls = updateCoverCalls)

        val result = finisher.finish(AvatarCaptureTarget.Self, success = true, captureStartMs = 1000L)

        assertFalse(result)
        assertTrue(updateCoverCalls.isEmpty())
    }

    @Test
    fun captureFailureSkipsCoverUpdate() = runTest {
        val updateCoverCalls = mutableListOf<Pair<Long, Long>>()
        val finisher = newFinisher(latestMediaId = 42L, updateCoverCalls = updateCoverCalls)

        val result = finisher.finish(AvatarCaptureTarget.Person(7L), success = false, captureStartMs = 1000L)

        assertFalse(result)
        assertTrue(updateCoverCalls.isEmpty())
    }

    @Test
    fun missingNewPhotoSkipsCoverUpdate() = runTest {
        val updateCoverCalls = mutableListOf<Pair<Long, Long>>()
        val finisher = newFinisher(latestMediaId = null, updateCoverCalls = updateCoverCalls)

        val result = finisher.finish(AvatarCaptureTarget.Person(7L), success = true, captureStartMs = 1000L)

        assertFalse(result)
        assertTrue(updateCoverCalls.isEmpty())
    }

    @Test
    fun pollsUntilPhotoRowAppears() = runTest {
        val updateCoverCalls = mutableListOf<Pair<Long, Long>>()
        var queryCount = 0
        val finisher = AvatarCaptureFinisher(
            ioDispatcher = UnconfinedTestDispatcher(),
            findLatestCapturedMediaId = {
                queryCount += 1
                // 前两次查询模拟 insertMedia 异步写入尚未完成
                if (queryCount >= 3) 42L else null
            },
            getSelfPersonId = { null },
            updateCover = { personId, mediaId -> updateCoverCalls.add(personId to mediaId) },
            pollIntervalMs = 0L,
            delayMs = { }
        )

        val result = finisher.finish(AvatarCaptureTarget.Person(7L), success = true, captureStartMs = 1000L)

        assertTrue(result)
        assertEquals(3, queryCount)
        assertEquals(listOf(7L to 42L), updateCoverCalls)
    }
}
