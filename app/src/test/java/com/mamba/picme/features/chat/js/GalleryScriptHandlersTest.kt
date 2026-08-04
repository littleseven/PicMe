package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsBridge
import com.mamba.picme.agent.core.js.JsBridgeException
import com.mamba.picme.agent.core.js.JsCallback
import com.mamba.picme.agent.core.js.JsEngine
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * registerGalleryHandlers 注册与 async 分发的单元测试（纯 JVM，FakeEngine 捕获 JsBridge）。
 */
class GalleryScriptHandlersTest {

    /** 捕获 installBridge 注入的 JsBridge 的假引擎（不执行任何 JS）。 */
    private class FakeEngine : JsEngine {
        lateinit var bridge: JsBridge
        override fun eval(script: String): JsValue = JsValue.Null
        override fun callFunction(name: String, vararg args: JsValue): JsValue = JsValue.Null
        override fun installBridge(bridge: JsBridge) {
            this.bridge = bridge
        }
    }

    private data class Fixture(
        val runtime: JsRuntime,
        val bridge: JsBridge,
        val personDao: PersonDao,
        val queryGalleryMediaUseCase: QueryGalleryMediaUseCase,
        val scanProgressHolder: ScanProgressHolder,
    )

    /** tag.scan_status 的假进度来源（测试可改写 snapshot）。 */
    private class ScanProgressHolder {
        var snapshot: TagScanSessionProgress? = null
    }

    private fun newFixture(scope: CoroutineScope): Fixture {
        val engine = FakeEngine()
        val runtime = JsRuntime(engine = engine, scope = scope)
        val personDao = mockk<PersonDao>()
        val queryGalleryMediaUseCase = mockk<QueryGalleryMediaUseCase>()
        val scanProgressHolder = ScanProgressHolder()
        registerGalleryHandlers(
            runtime = runtime,
            getGallerySummaryUseCase = mockk<GetGallerySummaryUseCase>(),
            queryGalleryMediaUseCase = queryGalleryMediaUseCase,
            personDao = personDao,
            controlledVocab = ControlledVocab(
                scene = listOf("户外"),
                objects = listOf("猫"),
            ),
            scanProgressProvider = { scanProgressHolder.snapshot },
        )
        return Fixture(runtime, engine.bridge, personDao, queryGalleryMediaUseCase, scanProgressHolder)
    }

    private suspend fun JsBridge.callAsync(name: String, args: JsValue): JsValue? {
        val deferred = CompletableDeferred<JsValue?>()
        dispatchAsync(name, args, JsCallback { _, res -> deferred.complete(res) })
        return deferred.await()
    }

    @Test
    fun `registers all 12 handlers including face_cluster and tag_audit`() = runTest {
        val f = newFixture(this)
        val expected = setOf(
            "gallery.summary", "gallery.query", "gallery.tags", "gallery.timeline",
            "gallery.intersect", "gallery.stats_by_tag", "gallery.stats_by_city",
            "media.meta", "media.batch_meta",
            "face.cluster", "tag.audit", "tag.scan_status",
        )
        assertTrue(f.runtime.handlerNames().containsAll(expected))
        f.runtime.close()
    }

    @Test
    fun `gallery handlers are async - dispatchSync throws HANDLER_NOT_ASYNC_CALLABLE`() = runTest {
        val f = newFixture(this)
        try {
            f.bridge.dispatchSync("gallery.summary", JsValue.Null)
            fail("expected JsBridgeException")
        } catch (e: JsBridgeException) {
            assertEquals(JsBridgeException.HANDLER_NOT_ASYNC_CALLABLE, e.errorCode)
        }
        f.runtime.close()
    }

    @Test
    fun `face_cluster returns cluster stats and top persons`() = runTest {
        val f = newFixture(this)
        coEvery { f.personDao.getPersonCount() } returns 4
        coEvery { f.personDao.getNamedPersonCount() } returns 2
        coEvery { f.personDao.getAllEmbeddingCount() } returns 120
        coEvery { f.personDao.getUnassignedEmbeddingCount() } returns 30
        coEvery { f.personDao.getAllPersons() } returns listOf(
            PersonEntity(personId = 1, name = "小明", coverMediaId = 42, faceCount = 50),
            PersonEntity(personId = 2, name = null, coverMediaId = null, faceCount = 30),
        )

        val result = f.bridge.callAsync("face.cluster", JsValue.Null) as JsValue.Obj
        val e = result.entries
        assertEquals(4.0, (e["clusterCount"] as JsValue.Num).value, 0.0)
        assertEquals(2.0, (e["namedCount"] as JsValue.Num).value, 0.0)
        assertEquals(120.0, (e["totalEmbeddings"] as JsValue.Num).value, 0.0)
        assertEquals(30.0, (e["unassignedEmbeddings"] as JsValue.Num).value, 0.0)
        val top = e["topPersons"] as JsValue.Arr
        assertEquals(2, top.items.size)
        val first = (top.items[0] as JsValue.Obj).entries
        assertEquals("小明", (first["name"] as JsValue.Str).value)
        assertEquals(50.0, (first["faceCount"] as JsValue.Num).value, 0.0)
        f.runtime.close()
    }

    @Test
    fun `face_cluster topN is capped at 50`() = runTest {
        val f = newFixture(this)
        val many = (1..60).map { PersonEntity(personId = it.toLong(), faceCount = 60 - it) }
        coEvery { f.personDao.getPersonCount() } returns 60
        coEvery { f.personDao.getNamedPersonCount() } returns 0
        coEvery { f.personDao.getAllEmbeddingCount() } returns 60
        coEvery { f.personDao.getUnassignedEmbeddingCount() } returns 0
        coEvery { f.personDao.getAllPersons() } returns many

        val args = JsValue.Obj(linkedMapOf("topN" to JsValue.Num(100.0)))
        val result = f.bridge.callAsync("face.cluster", args) as JsValue.Obj
        assertEquals(50, (result.entries["topPersons"] as JsValue.Arr).items.size)
        f.runtime.close()
    }

    @Test
    fun `tag_audit returns scan coverage and out-of-vocab tags`() = runTest {
        val f = newFixture(this)
        coEvery { f.queryGalleryMediaUseCase.tagScanAudit() } returns
            QueryGalleryMediaUseCase.TagScanAudit(
                totalMedia = 100,
                unlabeledCount = 40,
                neverScannedCount = 25,
                lastScanAt = 1_700_000_000_000L,
            )
        coEvery { f.queryGalleryMediaUseCase.tags(any()) } returns linkedMapOf(
            "户外" to 30,
            "非标标签甲" to 12,
            "猫" to 8,
            "非标标签乙" to 3,
        )

        val result = f.bridge.callAsync("tag.audit", JsValue.Null) as JsValue.Obj
        val e = result.entries
        assertEquals(100.0, (e["totalMedia"] as JsValue.Num).value, 0.0)
        assertEquals(40.0, (e["unlabeledCount"] as JsValue.Num).value, 0.0)
        assertEquals(25.0, (e["neverScannedCount"] as JsValue.Num).value, 0.0)
        assertEquals(1_700_000_000_000.0, (e["lastScanAt"] as JsValue.Num).value, 0.0)
        // 词表外：「户外」「猫」在 ControlledVocab(scene/objects) 内，被过滤
        val oov = (e["outOfVocabTags"] as JsValue.Obj).entries
        assertEquals(setOf("非标标签甲", "非标标签乙"), oov.keys)
        assertEquals(12.0, (oov["非标标签甲"] as JsValue.Num).value, 0.0)
        f.runtime.close()
    }

    @Test
    fun `gallery stats_by_city returns city distribution`() = runTest {
        val f = newFixture(this)
        coEvery { f.queryGalleryMediaUseCase.statsByCity(any()) } returns linkedMapOf(
            "北京" to 120,
            "上海" to 45,
        )

        val result = f.bridge.callAsync(
            "gallery.stats_by_city",
            JsValue.Obj(linkedMapOf("topN" to JsValue.Num(10.0))),
        ) as JsValue.Obj
        assertEquals(120.0, (result.entries["北京"] as JsValue.Num).value, 0.0)
        assertEquals(45.0, (result.entries["上海"] as JsValue.Num).value, 0.0)
        f.runtime.close()
    }

    @Test
    fun `tag scan_status without session returns inactive`() = runTest {
        val f = newFixture(this)
        f.scanProgressHolder.snapshot = null

        val result = f.bridge.callAsync("tag.scan_status", JsValue.Null) as JsValue.Obj
        assertEquals(false, (result.entries["active"] as JsValue.Bool).value)
        assertEquals(JsValue.Null, result.entries["state"])
        f.runtime.close()
    }

    @Test
    fun `tag scan_status with running session returns progress snapshot`() = runTest {
        val f = newFixture(this)
        f.scanProgressHolder.snapshot = TagScanSessionProgress(
            sessionId = "s-1",
            state = ScanSessionState.RUNNING,
            currentPass = TagScanPass.FACE_DETECTION,
            processed = 30,
            total = 100,
            pending = 68,
            failed = 2,
            estimatedRemainingMs = 60_000L,
        )

        val result = f.bridge.callAsync("tag.scan_status", JsValue.Null) as JsValue.Obj
        val e = result.entries
        assertEquals(true, (e["active"] as JsValue.Bool).value)
        assertEquals("RUNNING", (e["state"] as JsValue.Str).value)
        assertEquals("FACE_DETECTION", (e["currentPass"] as JsValue.Str).value)
        assertEquals(30.0, (e["processed"] as JsValue.Num).value, 0.0)
        assertEquals(100.0, (e["total"] as JsValue.Num).value, 0.0)
        assertEquals(68.0, (e["pending"] as JsValue.Num).value, 0.0)
        assertEquals(2.0, (e["failed"] as JsValue.Num).value, 0.0)
        assertEquals(60_000.0, (e["estimatedRemainingMs"] as JsValue.Num).value, 0.0)
        f.runtime.close()
    }

    @Test
    fun `tag scan_status with completed session is inactive`() = runTest {
        val f = newFixture(this)
        f.scanProgressHolder.snapshot = TagScanSessionProgress(
            sessionId = "s-2",
            state = ScanSessionState.COMPLETED,
            processed = 100,
            total = 100,
        )

        val result = f.bridge.callAsync("tag.scan_status", JsValue.Null) as JsValue.Obj
        assertEquals(false, (result.entries["active"] as JsValue.Bool).value)
        assertEquals("COMPLETED", (result.entries["state"] as JsValue.Str).value)
        f.runtime.close()
    }
}
