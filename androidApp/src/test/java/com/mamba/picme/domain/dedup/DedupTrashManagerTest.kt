package com.mamba.picme.domain.dedup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DedupTrashManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `queryExisting returns empty for unknown uris`() {
        val mgr = DedupTrashManager(context)
        assertTrue(mgr.queryExisting(listOf("content://media/external/images/media/999999")).isEmpty())
    }
}
