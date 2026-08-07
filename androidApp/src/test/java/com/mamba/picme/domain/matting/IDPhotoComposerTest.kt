package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class IDPhotoComposerTest {

    @Test
    fun `coverCropRect on square source to portrait returns centered vertical crop`() {
        // 100x100 源 → 50x100 目标（更高）：cover 需裁掉左右，宽取 50 居中
        val rect = IDPhotoComposer.coverCropRect(srcW = 100, srcH = 100, dstW = 50, dstH = 100)
        assertEquals(25, rect.left)
        assertEquals(75, rect.right)
        assertEquals(0, rect.top)
        assertEquals(100, rect.bottom)
    }

    @Test
    fun `coverCropRect on wide source to square returns centered horizontal crop`() {
        // 200x100 源 → 100x100 目标：裁掉左右
        val rect = IDPhotoComposer.coverCropRect(srcW = 200, srcH = 100, dstW = 100, dstH = 100)
        assertEquals(50, rect.left)
        assertEquals(150, rect.right)
        assertEquals(0, rect.top)
        assertEquals(100, rect.bottom)
    }

    @Test
    fun `coverCropRect same aspect returns full source`() {
        val rect = IDPhotoComposer.coverCropRect(srcW = 200, srcH = 300, dstW = 100, dstH = 150)
        assertEquals(0, rect.left)
        assertEquals(200, rect.right)
        assertEquals(0, rect.top)
        assertEquals(300, rect.bottom)
    }

    @Test
    fun `subjectBounds returns null when alpha has no subject`() {
        val alpha = FloatArray(100) // 10x10 全背景
        assertEquals(null, IDPhotoComposer.subjectBounds(alpha, width = 10, height = 10))
    }

    @Test
    fun `subjectBounds finds top row and horizontal center of subject`() {
        // 10x10：主体占行 2..8、列 3..5 → top=2, centerX=4
        val w = 10
        val h = 10
        val alpha = FloatArray(w * h)
        for (y in 2..8) {
            for (x in 3..5) {
                alpha[y * w + x] = 1f
            }
        }
        val bounds = IDPhotoComposer.subjectBounds(alpha, width = w, height = h)
        assertEquals(2, bounds?.top)
        assertEquals(4, bounds?.centerX)
    }

    @Test
    fun `subjectAwareCropRect keeps head with headroom instead of center-cropping it off`() {
        // 1000x2000 源 → 295x413 目标：cropH = 1400，居中 top=300 会把 top=100 的头砍掉
        val framing = IDPhotoComposer.CropFraming(subject = IDPhotoComposer.SubjectBounds(top = 100, centerX = 500))
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413, framing = framing
        )
        // headroom = 1400 * 0.08 = 112 → top = 100 - 112 = -12 → clamp 到 0，头部完整保留
        assertEquals(0, rect.top)
        assertEquals(1400, rect.bottom - rect.top)
    }

    @Test
    fun `subjectAwareCropRect positions head at headroom when space allows`() {
        // 1000x2000 源 → cropH=1400；头顶在 500：top = 500 - 112 = 388（合法范围内）
        val framing = IDPhotoComposer.CropFraming(subject = IDPhotoComposer.SubjectBounds(top = 500, centerX = 500))
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413, framing = framing
        )
        assertEquals(388, rect.top)
    }

    @Test
    fun `subjectAwareCropRect falls back to centered crop without subject`() {
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413, framing = IDPhotoComposer.CropFraming()
        )
        assertEquals(300, rect.top) // 与 coverCropRect 居中结果一致
    }

    @Test
    fun `subjectAwareCropRect applies user offset and clamps to valid range`() {
        // top 自动定位 388，offsetY +0.1 → 388 + 140 = 528
        val subject = IDPhotoComposer.SubjectBounds(top = 500, centerX = 500)
        val shifted = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413,
            framing = IDPhotoComposer.CropFraming(subject = subject, offsetY = 0.1f)
        )
        assertEquals(528, shifted.top)
        // 向下拖到头：388 - 1400 → clamp 0
        val clampedTop = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413,
            framing = IDPhotoComposer.CropFraming(subject = subject, offsetY = -1f)
        )
        assertEquals(0, clampedTop.top)
        // 向上拖到底：388 + 1400 → clamp srcH - cropH = 600
        val clampedBottom = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413,
            framing = IDPhotoComposer.CropFraming(subject = subject, offsetY = 1f)
        )
        assertEquals(600, clampedBottom.top)
    }

    @Test
    fun `subjectAwareCropRect centers horizontally on subject for wide source`() {
        // 2000x1000 源 → 1000x1000 目标：cropW=1000，居中 left=500 会切到 centerX=1500 的主体
        val framing = IDPhotoComposer.CropFraming(subject = IDPhotoComposer.SubjectBounds(top = 100, centerX = 1500))
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 2000, srcH = 1000, dstW = 1000, dstH = 1000, framing = framing
        )
        assertEquals(1000, rect.left) // 1500 - 500 = 1000，主体居中
        assertEquals(2000, rect.right)
    }

    @Test
    fun `subjectAwareCropRect clamps horizontal centering when subject near edge`() {
        // centerX=200：left = 200 - 500 = -300 → clamp 0
        val framing = IDPhotoComposer.CropFraming(subject = IDPhotoComposer.SubjectBounds(top = 100, centerX = 200))
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 2000, srcH = 1000, dstW = 1000, dstH = 1000, framing = framing
        )
        assertEquals(0, rect.left)
    }

    @Test
    fun `subjectAwareCropRect zoom shrinks window and keeps head at headroom`() {
        // 1000x2000 源 zoom=2 → cropW=500, cropH=700；头顶 500：top = 500 - 700*0.08 = 444
        // centerX=600：left = 600 - 250 = 350（zoom>1 后横向也有余量，按主体居中）
        val framing = IDPhotoComposer.CropFraming(
            subject = IDPhotoComposer.SubjectBounds(top = 500, centerX = 600),
            zoom = 2f
        )
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413, framing = framing
        )
        assertEquals(500, rect.right - rect.left)
        assertEquals(700, rect.bottom - rect.top)
        assertEquals(444, rect.top)
        assertEquals(350, rect.left)
    }

    @Test
    fun `subjectAwareCropRect zoom positions both axes without subject`() {
        // 无主体 zoom=2：窗口缩小后两轴都居中 → left=(1000-500)/2=250, top=(2000-700)/2=650
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413,
            framing = IDPhotoComposer.CropFraming(zoom = 2f)
        )
        assertEquals(250, rect.left)
        assertEquals(650, rect.top)
    }

    @Test
    fun `subjectAwareCropRect zoom clamps when subject near top-left corner`() {
        // zoom=2，头顶 20、centerX 100：top = 20-56 <0 → 0；left = 100-250 <0 → 0
        val framing = IDPhotoComposer.CropFraming(
            subject = IDPhotoComposer.SubjectBounds(top = 20, centerX = 100),
            zoom = 2f
        )
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413, framing = framing
        )
        assertEquals(0, rect.top)
        assertEquals(0, rect.left)
    }

    @Test
    fun `subjectAwareCropRect applies horizontal user offset`() {
        // 2000x1000 源 → cropW=1000，centerX=1000 → autoLeft=500；offsetX +0.1 → left=600
        val subject = IDPhotoComposer.SubjectBounds(top = 100, centerX = 1000)
        val shifted = IDPhotoComposer.subjectAwareCropRect(
            srcW = 2000, srcH = 1000, dstW = 1000, dstH = 1000,
            framing = IDPhotoComposer.CropFraming(subject = subject, offsetX = 0.1f)
        )
        assertEquals(600, shifted.left)
        // 向左拖过界：500 - 2000 → clamp 0
        val clampedLeft = IDPhotoComposer.subjectAwareCropRect(
            srcW = 2000, srcH = 1000, dstW = 1000, dstH = 1000,
            framing = IDPhotoComposer.CropFraming(subject = subject, offsetX = -2f)
        )
        assertEquals(0, clampedLeft.left)
    }

    @Test
    fun `subjectAwareCropRect zoom below 1 is coerced to cover fit`() {
        // zoom=0.5 → 按 zoom=1 处理（窗口不能大于 cover 尺寸）
        val framing = IDPhotoComposer.CropFraming(zoom = 0.5f)
        val rect = IDPhotoComposer.subjectAwareCropRect(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413, framing = framing
        )
        assertEquals(1000, rect.right - rect.left)
        assertEquals(1400, rect.bottom - rect.top)
    }

    @Test
    fun `clampFraming clamps accumulated offset to valid range`() {
        // 1000x2000、subject top=500 → autoTop=388, cropH=1400
        // 合法 offsetY 区间 [-388/1400, (2000-1400-388)/1400] ≈ [-0.2771, 0.1514]
        val framing = IDPhotoComposer.CropFraming(
            subject = IDPhotoComposer.SubjectBounds(top = 500, centerX = 500)
        )
        val overDraggedDown = IDPhotoComposer.clampFraming(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413,
            framing = framing.copy(offsetY = -2f)
        )
        assertEquals(-388f / 1400f, overDraggedDown.offsetY, 0.0001f)
        val overDraggedUp = IDPhotoComposer.clampFraming(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413,
            framing = framing.copy(offsetY = 5f)
        )
        assertEquals(212f / 1400f, overDraggedUp.offsetY, 0.0001f)
    }

    @Test
    fun `clampFraming keeps in-range offset unchanged`() {
        val framing = IDPhotoComposer.CropFraming(
            subject = IDPhotoComposer.SubjectBounds(top = 500, centerX = 500),
            offsetY = 0.1f
        )
        val clamped = IDPhotoComposer.clampFraming(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413, framing = framing
        )
        assertEquals(0.1f, clamped.offsetY, 0.0001f)
    }

    @Test
    fun `clampFraming coerces zoom below 1 up to 1`() {
        val clamped = IDPhotoComposer.clampFraming(
            srcW = 1000, srcH = 2000, dstW = 295, dstH = 413,
            framing = IDPhotoComposer.CropFraming(zoom = 0.5f)
        )
        assertEquals(1f, clamped.zoom, 0.0001f)
    }

    @Test
    fun `frameToSource maps frame position into crop window`() {
        // cropRect = (100,200)-(300,600)（宽200 高400），画框 100x200：中心点应映射到 crop 中心
        val crop = IDPhotoComposer.CropRect(100, 200, 300, 600)
        val p = IDPhotoComposer.frameToSource(px = 50f, py = 100f, frameW = 100f, frameH = 200f, crop = crop)
        assertEquals(200f, p.x, 0.01f)
        assertEquals(400f, p.y, 0.01f)
    }

    @Test
    fun `frameToSource maps frame origin to crop left top`() {
        val crop = IDPhotoComposer.CropRect(100, 200, 300, 600)
        val p = IDPhotoComposer.frameToSource(px = 0f, py = 0f, frameW = 100f, frameH = 200f, crop = crop)
        assertEquals(100f, p.x, 0.01f)
        assertEquals(200f, p.y, 0.01f)
    }

    @Test
    fun `frameRadiusToSource scales by crop width over frame width`() {
        val crop = IDPhotoComposer.CropRect(0, 0, 200, 400)
        val r = IDPhotoComposer.frameRadiusToSource(radiusPx = 10f, frameW = 100f, crop = crop)
        assertEquals(20f, r, 0.01f)
    }
}
