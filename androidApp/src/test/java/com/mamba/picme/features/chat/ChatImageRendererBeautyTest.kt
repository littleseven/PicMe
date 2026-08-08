package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.features.editor.EditRecipe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [QA] ChatImageRenderer 美型接线测试（US-3 AC3.3）
 *
 * 验证 chat 一键优化链路不丢失美型参数：[ChatImageRenderer.aiOptimize] 调用
 * [AiOptimizeUseCase.optimize] 得到的 recipe（含 slimFace）被原样传入公开渲染入口
 * [ChatImageRenderer.renderRecipe]，而 renderRecipe 内部会把 recipe 交给
 * `RecipeApplier.applyGpuEffects(cropped, recipe, faceData)`。
 *
 * JVM 边界：`decodeBitmap` 依赖 `BitmapFactory`（Android 静态）且 `applyGpuEffects`
 * 需要 EGL/GPU，无法在纯 JVM 单测中真正执行到 `applyGpuEffects`。因此本测试在
 * [ChatImageRenderer.renderRecipe] 这个**通往 applyGpuEffects 的唯一公开接缝**处
 * 截获 recipe，断言 slimFace 值完好。完整的「slimFace 实际进入 applyGpuEffects」
 * 验证需在设备上以 androidTest（`./gradlew :androidApp:connectedAndroidTest`）覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatImageRendererBeautyTest {

    private val imageUri = "file:///test.jpg"

    @Test
    fun `aiOptimize forwards slimFace from optimize recipe into render pipeline`() = runTest {
        val slimFaceValue = 30f
        // optimize() 返回的 recipe 携带非零 slimFace（US-3 关心的美型字段）
        val optimizeRecipe = EditRecipe(
            sourceUri = imageUri,
            beauty = BeautySettings(enabled = true, slimFace = slimFaceValue)
        )
        val optimizeUseCase: AiOptimizeUseCase = mockk()
        coEvery { optimizeUseCase.optimize(any()) } returns AiOptimizeUseCase.Result(
            scene = Scene.SELFIE,
            confidence = 1.0f,
            editRecipe = optimizeRecipe,
            explanation = "检测到自拍，已适度磨皮美白并提亮肤色",
            processingTimeMs = 1L
        )

        val renderer = ChatImageRenderer(
            context = mockk<Context>(relaxed = true),
            photoProcessor = mockk<PhotoProcessor>(relaxed = true),
            mattingEngine = mockk<MattingEngine>(relaxed = true),
            optimizeUseCase = optimizeUseCase,
            chatImageStore = mockk<ChatImageStore>(relaxed = true),
            faceDetector = mockk<FaceDetector>(relaxed = true),
            userSettingsRepository = null,
            dispatcher = Dispatchers.Unconfined
        )
        // 截获 renderRecipe（通往 applyGpuEffects 的公开接缝），避免触发 decodeBitmap/EGL
        val spy = spyk(renderer)
        val recipeSlot = slot<EditRecipe>()
        coEvery { spy.renderRecipe(any(), capture(recipeSlot), any()) } returns "file://rendered"

        val outcome = spy.aiOptimize(imageUri, "session-id")

        // slimFace 从 optimize recipe 原样抵达渲染管线入口，未被丢弃或改写
        assertEquals("file://rendered", outcome.imageUri)
        assertEquals(slimFaceValue, recipeSlot.captured.beauty.slimFace, 0.0001f)
    }
}
