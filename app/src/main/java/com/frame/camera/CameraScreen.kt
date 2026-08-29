package com.frame.camera

import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    var captured by remember { mutableStateOf<CapturedMedia?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { CameraEngine(context, owner, previewView, { captured = it }, { error = it }) }
    val controller = remember { CaptureController() }

    DisposableEffect(engine) {
        engine.start()
        onDispose(engine::close)
    }

    captured?.let { media ->
        ReviewScreen(
            media,
            onSave = { publish(context, media); captured = null },
            onDiscard = { discard(context, media); captured = null },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().pointerInput(engine) {
                detectTapGestures(onDoubleTap = { engine.switchCamera() })
            },
        )
        CaptureButton(controller, engine, Modifier.align(Alignment.BottomCenter).offset(y = (-48).dp))
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).offset(y = 24.dp)) }
    }
}

@Composable
private fun CaptureButton(controller: CaptureController, engine: CameraEngine, modifier: Modifier) {
    val lockDistance = with(LocalDensity.current) { 140.dp.toPx() }
    val lockSideDistance = with(LocalDensity.current) { 72.dp.toPx() }
    val zoomDistance = with(LocalDensity.current) { 600.dp.toPx() }
    Box(
        modifier
            .size(84.dp)
            .background(Color.White.copy(alpha = .35f), CircleShape)
            .pointerInput(controller, engine) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pressed = controller.press(down.position.y, SystemClock.uptimeMillis())
                    perform(pressed, engine)
                    if (pressed == CaptureAction.StopRecording) return@awaitEachGesture

                    val quickUp = withTimeoutOrNull(225) {
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) event = awaitPointerEvent()
                    }
                    if (quickUp != null) {
                        perform(controller.release(SystemClock.uptimeMillis()), engine)
                        return@awaitEachGesture
                    }

                    perform(controller.tick(SystemClock.uptimeMillis()), engine)
                    var locked = false
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (
                            !locked &&
                            down.position.y - change.position.y >= lockDistance &&
                            down.position.x - change.position.x >= lockSideDistance
                        ) {
                            perform(controller.lock(), engine)
                            locked = true
                        } else if (!locked) {
                            perform(controller.drag(change.position.y, zoomDistance), engine)
                        }
                        if (!change.pressed) {
                            perform(controller.release(SystemClock.uptimeMillis()), engine)
                            break
                        }
                    }
                }
            },
    )
}

private fun perform(action: CaptureAction, engine: CameraEngine) = when (action) {
    CaptureAction.TakePhoto -> engine.takePhoto()
    CaptureAction.StartRecording -> engine.startRecording()
    CaptureAction.StopRecording -> engine.stopRecording()
    is CaptureAction.SetZoom -> engine.setZoom(action.linearZoom)
    CaptureAction.LockRecording, CaptureAction.None -> Unit
}

@Composable
private fun ReviewScreen(media: CapturedMedia, onSave: () -> Unit, onDiscard: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (media.kind == MediaKind.Video) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(media.uri)
                        setOnPreparedListener { it.isLooping = true; start() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageURI(media.uri)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(Modifier.align(Alignment.BottomCenter).offset(y = (-32).dp)) {
            Button(onClick = onDiscard) { Text("Discard") }
            Button(onClick = onSave) { Text("Save") }
        }
    }
}
