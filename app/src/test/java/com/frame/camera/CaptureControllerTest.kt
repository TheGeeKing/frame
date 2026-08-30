package com.frame.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureControllerTest {
    @Test
    fun `quick release takes a photo`() {
        val capture = CaptureController()

        capture.press(y = 800f, atMillis = 0)

        assertEquals(CaptureAction.TakePhoto, capture.release(atMillis = 100))
    }

    @Test
    fun `hold records until release`() {
        val capture = CaptureController()

        capture.press(y = 800f, atMillis = 0)
        assertEquals(CaptureAction.StartRecording, capture.tick(atMillis = 250))

        assertEquals(CaptureAction.StopRecording, capture.release(atMillis = 300))
    }

    @Test
    fun `locked recording continues after release and stops on tap`() {
        val capture = CaptureController()

        capture.press(y = 800f, atMillis = 0)
        capture.tick(atMillis = 250)
        assertEquals(CaptureAction.LockRecording, capture.lock())
        assertEquals(CaptureAction.None, capture.release(atMillis = 300))

        assertEquals(CaptureAction.StopRecording, capture.press(y = 800f, atMillis = 400))
    }

    @Test
    fun `pausing locks recording after release`() {
        val capture = CaptureController()

        capture.press(y = 800f, atMillis = 0)
        capture.tick(atMillis = 250)
        assertEquals(CaptureAction.PauseRecording, capture.pause())
        assertEquals(CaptureAction.None, capture.release(atMillis = 300))

        assertEquals(CaptureAction.StopRecording, capture.press(y = 800f, atMillis = 400))
    }

    @Test
    fun `vertical drag changes zoom within camera range`() {
        val capture = CaptureController()

        capture.press(y = 800f, atMillis = 0)
        capture.tick(atMillis = 250)

        assertEquals(CaptureAction.SetZoom(1f), capture.drag(y = -200f, height = 1000f))
        assertEquals(CaptureAction.SetZoom(0f), capture.drag(y = 1800f, height = 1000f))
    }

    @Test
    fun `recording starts from the camera current zoom`() {
        val capture = CaptureController()

        capture.press(y = 800f, atMillis = 0, linearZoom = .4f)
        capture.tick(atMillis = 250)

        assertEquals(CaptureAction.SetZoom(.4f), capture.drag(y = 800f, height = 1000f))
    }
}
