package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class MattingRouterTest {

    @Test
    fun `portrait with face routes to MODNet`() {
        assertEquals(MaskSource.MODNET, MattingRouter.choose(hasFace = true))
    }

    @Test
    fun `image without face routes to U2Net`() {
        assertEquals(MaskSource.U2NETP, MattingRouter.choose(hasFace = false))
    }
}
