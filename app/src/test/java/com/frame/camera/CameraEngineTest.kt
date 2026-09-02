package com.frame.camera

import android.util.Rational
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.ViewPort
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class CameraEngineTest {
    @Test
    fun `capture use cases share the preview field of view`() {
        val preview = Preview.Builder().build()
        val photo = ImageCapture.Builder().build()
        val video = VideoCapture.Builder(Recorder.Builder().build()).build()
        val viewPort = ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0).build()

        val group = captureUseCaseGroup(preview, photo, video, viewPort)

        assertSame(viewPort, group.viewPort)
        assertEquals(listOf(preview, photo, video), group.useCases)
    }

    @Test
    fun `default zoom is optical 1x when the camera can do it`() {
        assertEquals(1f, defaultZoomRatio(min = .6f, max = 10f))
    }

    @Test
    fun `default zoom stays inside the camera zoom range`() {
        assertEquals(1.2f, defaultZoomRatio(min = 1.2f, max = 5f))
        assertEquals(.8f, defaultZoomRatio(min = .5f, max = .8f))
    }

    @Test
    fun `flash mode fires only when capture flash is enabled`() {
        assertEquals(ImageCapture.FLASH_MODE_OFF, captureFlashMode(enabled = false))
        assertEquals(ImageCapture.FLASH_MODE_ON, captureFlashMode(enabled = true))
    }
}
