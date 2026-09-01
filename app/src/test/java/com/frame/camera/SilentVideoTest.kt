package com.frame.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SilentVideoTest {
    @Test
    fun `selects the first video track`() {
        assertEquals(1, videoTrackIndex(listOf("audio/mp4a-latm", "video/avc")))
        assertEquals(0, videoTrackIndex(listOf("video/hevc", "audio/mp4a-latm")))
        assertNull(videoTrackIndex(listOf("audio/mp4a-latm")))
    }
}
