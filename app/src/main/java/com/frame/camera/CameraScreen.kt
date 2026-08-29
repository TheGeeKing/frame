package com.frame.camera

import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

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
    var recording by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    val engine = remember { CameraEngine(context, owner, previewView, { captured = it }, { error = it }) }
    val controller = remember { CaptureController() }

    DisposableEffect(engine) {
        engine.start()
        onDispose(engine::close)
    }
    LaunchedEffect(recording) {
        elapsedSeconds = 0
        while (recording) {
            delay(1_000)
            elapsedSeconds++
        }
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
        if (recording) {
            RecordingIndicator(elapsedSeconds, locked, Modifier.align(Alignment.TopCenter).offset(y = 28.dp))
            Text(
                if (locked) "🔒 Locked" else "↖ Drag here to lock",
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).offset(x = (-92).dp, y = (-172).dp),
            )
        }
        CaptureButton(
            controller,
            engine,
            recording,
            locked,
            onRecordingChange = { recording = it; if (!it) locked = false },
            onLocked = { locked = true },
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-48).dp),
        )
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).offset(y = 24.dp)) }
    }
}

@Composable
private fun CaptureButton(
    controller: CaptureController,
    engine: CameraEngine,
    recording: Boolean,
    locked: Boolean,
    onRecordingChange: (Boolean) -> Unit,
    onLocked: () -> Unit,
    modifier: Modifier,
) {
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
                    perform(pressed, engine, onRecordingChange, onLocked)
                    if (pressed == CaptureAction.StopRecording) return@awaitEachGesture

                    val quickUp = withTimeoutOrNull(CaptureController.HOLD_MILLIS) {
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) event = awaitPointerEvent()
                    }
                    if (quickUp != null) {
                        perform(controller.release(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked)
                        return@awaitEachGesture
                    }

                    perform(controller.tick(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked)
                    var gestureLocked = false
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (
                            !gestureLocked &&
                            down.position.y - change.position.y >= lockDistance &&
                            down.position.x - change.position.x >= lockSideDistance
                        ) {
                            perform(controller.lock(), engine, onRecordingChange, onLocked)
                            gestureLocked = true
                        } else if (!gestureLocked) {
                            perform(controller.drag(change.position.y, zoomDistance), engine, onRecordingChange, onLocked)
                        }
                        if (!change.pressed) {
                            perform(controller.release(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked)
                            break
                        }
                    }
                }
            }
            .border(4.dp, if (recording) Color.Red else Color.White, CircleShape),
    ) {
        if (locked) {
            Box(Modifier.align(Alignment.Center).size(28.dp).background(Color.Red, RoundedCornerShape(5.dp)))
        }
    }
}

private fun perform(
    action: CaptureAction,
    engine: CameraEngine,
    onRecordingChange: (Boolean) -> Unit,
    onLocked: () -> Unit,
) = when (action) {
    CaptureAction.TakePhoto -> engine.takePhoto()
    CaptureAction.StartRecording -> { engine.startRecording(); onRecordingChange(true) }
    CaptureAction.StopRecording -> { engine.stopRecording(); onRecordingChange(false) }
    is CaptureAction.SetZoom -> engine.setZoom(action.linearZoom)
    CaptureAction.LockRecording -> onLocked()
    CaptureAction.None -> Unit
}

@Composable
private fun RecordingIndicator(seconds: Long, locked: Boolean, modifier: Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = .6f), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).background(Color.Red, CircleShape))
        Text(
            "%02d:%02d%s".format(seconds / 60, seconds % 60, if (locked) "  🔒" else ""),
            color = Color.White,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
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
