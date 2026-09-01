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

    @Test
    fun `maps extractor sample flags to muxer buffer flags`() {
        assertEquals(0, muxerBufferFlags(0))
        assertEquals(android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME, muxerBufferFlags(android.media.MediaExtractor.SAMPLE_FLAG_SYNC))
        assertEquals(
            android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            muxerBufferFlags(android.media.MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME),
        )
        assertEquals(0, muxerBufferFlags(android.media.MediaExtractor.SAMPLE_FLAG_ENCRYPTED))
    }
}
