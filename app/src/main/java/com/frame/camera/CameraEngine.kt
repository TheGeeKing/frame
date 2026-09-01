package com.frame.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.nio.ByteBuffer
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.FocusMeteringAction
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
import java.util.concurrent.TimeUnit

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
    private val preview = Preview.Builder().build()
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
    private var active = false

    fun start() {
        active = true
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val existing = provider
        if (existing != null) {
            bind()
            return
        }
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                provider = future.get()
                if (active) bind()
            }, executor)
        }
    }

    private fun bind() {
        if (!active) return
        val cameraProvider = provider ?: return
        runCatching {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                photo,
                video,
            )
        }.onFailure { onError(it.message ?: "Camera bind failed") }
    }

    fun switchCamera() {
        front = !front
        runCatching(::bind).onFailure { onError(it.message ?: "Camera switch failed") }
    }

    fun setZoom(linearZoom: Float) {
        camera?.cameraControl?.setLinearZoom(linearZoom)
    }

    fun currentLinearZoom(): Float = camera?.cameraInfo?.zoomState?.value?.linearZoom ?: 0f

    fun focus(x: Float, y: Float) {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build(),
        )
    }

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
        if (recording != null || camera == null) return
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

    fun pauseRecording() = recording?.pause()

    fun resumeRecording() = recording?.resume()

    fun close() {
        active = false
        recording?.stop()
        recording = null
        preview.setSurfaceProvider(null)
        provider?.unbindAll()
        camera = null
    }

    private fun mediaValues(kind: MediaKind) = captureMediaValues(kind)
}

fun captureMediaValues(kind: MediaKind) = ContentValues().apply {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    put(MediaStore.MediaColumns.DISPLAY_NAME, "FRAME_$stamp.${kind.extension}")
    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Frame")
    put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
}

fun videoTrackIndex(mimeTypes: List<String?>): Int? =
    mimeTypes.indexOfFirst { it?.startsWith("video/") == true }.takeIf { it >= 0 }

fun muxerBufferFlags(sampleFlags: Int): Int {
    var flags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    return flags
}

fun stripAudio(context: Context, media: CapturedMedia): CapturedMedia {
    if (media.kind != MediaKind.Video) return media
    val values = captureMediaValues(MediaKind.Video).apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Silent video URI missing")
    runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, media.uri, null)
            val track = videoTrackIndex((0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) })
                ?: error("Video track missing")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            context.contentResolver.openFileDescriptor(uri, "w")!!.use { pfd ->
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val muxerTrack = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                }
                muxer.start()
                val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    1 shl 20
                }
                val buffer = ByteBuffer.allocate(capacity)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = muxerBufferFlags(extractor.sampleFlags)
                    muxer.writeSampleData(muxerTrack, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }.onFailure {
        context.contentResolver.delete(uri, null, null)
        throw it
    }
    return CapturedMedia(uri, MediaKind.Video)
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
