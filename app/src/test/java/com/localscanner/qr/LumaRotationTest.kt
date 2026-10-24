package com.localscanner.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LumaRotationTest {
    private val image = byteArrayOf(1, 2, 3, 4, 5, 6)

    @Test fun noRotationKeepsTheImage() {
        val r = LumaRotation.rotateClockwise(image, 3, 2, 0)
        assertEquals(3, r.width)
        assertEquals(2, r.height)
        assertArrayEquals(image, r.data)
    }

    @Test fun rotate90SwapsDimensions() {
        val r = LumaRotation.rotateClockwise(image, 3, 2, 90)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), r.data)
    }

    @Test fun rotate180() {
        val r = LumaRotation.rotateClockwise(image, 3, 2, 180)
        assertEquals(3, r.width)
        assertEquals(2, r.height)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), r.data)
    }

    @Test fun rotate270() {
        val r = LumaRotation.rotateClockwise(image, 3, 2, 270)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), r.data)
    }

    @Test fun fourQuarterTurnsAreIdentity() {
        var r = LumaRotation.Rotated(image, 3, 2)
        repeat(4) { r = LumaRotation.rotateClockwise(r.data, r.width, r.height, 90) }
        assertEquals(3, r.width)
        assertArrayEquals(image, r.data)
    }

    @Test fun negativeAndLargeAnglesAreNormalised() {
        val a = LumaRotation.rotateClockwise(image, 3, 2, -90)
        val b = LumaRotation.rotateClockwise(image, 3, 2, 270)
        assertArrayEquals(b.data, a.data)
        val c = LumaRotation.rotateClockwise(image, 3, 2, 450)
        assertArrayEquals(LumaRotation.rotateClockwise(image, 3, 2, 90).data, c.data)
    }
}
