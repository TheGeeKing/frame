package com.frame.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MediaKind(val extension: String, val mimeType: String) {
    Photo("jpg", "image/jpeg"),
    Video("mp4", "video/mp4"),
}

data class CapturedMedia(val uri: Uri, val kind: MediaKind)

class CameraEngine(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onCaptured: (CapturedMedia) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val executor = ContextCompat.getMainExecutor(context)
    private val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
    private val photo = ImageCapture.Builder().build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(
            QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
            ),
        )
        .build()
    private val video = VideoCapture.Builder(recorder)
        .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        .build()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var recording: Recording? = null
    private var front = false

    fun start() {
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                provider = future.get()
                bind()
            }, executor)
        }
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            owner,
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            photo,
            video,
        )
    }

    fun switchCamera() {
        front = !front
        runCatching(::bind).onFailure { onError(it.message ?: "Camera switch failed") }
    }

    fun setZoom(linearZoom: Float) {
        camera?.cameraControl?.setLinearZoom(linearZoom)
    }

    fun currentLinearZoom(): Float = camera?.cameraInfo?.zoomState?.value?.linearZoom ?: 0f

    fun takePhoto() {
        val values = mediaValues(MediaKind.Photo).apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val output = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        photo.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                result.savedUri?.let { onCaptured(CapturedMedia(it, MediaKind.Photo)) } ?: onError("Photo URI missing")
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "Photo capture failed")
            }
        })
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun startRecording() {
        if (recording != null) return
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(mediaValues(MediaKind.Video).apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }).build()
        var pending = recorder.prepareRecording(context, options).asPersistentRecording()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                if (event.hasError()) onError(event.cause?.message ?: "Recording failed")
                else onCaptured(CapturedMedia(event.outputResults.outputUri, MediaKind.Video))
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun close() {
        recording?.stop()
        provider?.unbindAll()
    }

    private fun mediaValues(kind: MediaKind) = ContentValues().apply {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        put(MediaStore.MediaColumns.DISPLAY_NAME, "FRAME_$stamp.${kind.extension}")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Frame")
        put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
    }
}

fun publish(context: Context, media: CapturedMedia) {
    context.contentResolver.update(
        media.uri,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null,
    )
}

fun discard(context: Context, media: CapturedMedia) {
    context.contentResolver.delete(media.uri, null, null)
}
