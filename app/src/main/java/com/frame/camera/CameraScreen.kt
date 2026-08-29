package com.frame.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
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
    var lockedZoom by remember { mutableStateOf(0f) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    var showSettings by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("frame", android.content.Context.MODE_PRIVATE) }
    var zoomSensitivity by remember { mutableStateOf(preferences.getFloat("zoomSensitivity", 1f)) }
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
            onCopy = { copyMedia(context, media) },
            onShare = { shareMedia(context, media) },
            onSave = { publish(context, media); captured = null },
            onDiscard = { discard(context, media); captured = null },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(engine) { detectTapGestures(onDoubleTap = { engine.switchCamera() }) }
                .pointerInput(engine, locked, zoomSensitivity) {
                    if (locked) {
                        detectTransformGestures { _, _, zoom, _ ->
                            lockedZoom = (lockedZoom + (zoom - 1f) * .25f * zoomSensitivity).coerceIn(0f, 1f)
                            engine.setZoom(lockedZoom)
                        }
                    }
                },
        )
        if (recording) {
            RecordingIndicator(elapsedSeconds, locked, Modifier.align(Alignment.TopCenter).offset(y = 28.dp))
        } else {
            Button(
                onClick = { showSettings = !showSettings },
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = 24.dp),
            ) { Text("⚙") }
            if (showSettings) {
                ZoomSettings(
                    zoomSensitivity,
                    onChange = { zoomSensitivity = it },
                    onFinished = { preferences.edit().putFloat("zoomSensitivity", zoomSensitivity).apply() },
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = 88.dp),
                )
            }
        }
        LockTarget(recording, locked, Modifier.align(Alignment.BottomCenter).offset(x = 110.dp, y = (-62).dp))
        AnimatedVisibility(
            visible = recording && !locked,
            modifier = Modifier.align(Alignment.BottomCenter).offset(x = 64.dp, y = (-88).dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.width(44.dp).height(4.dp).background(Color.White.copy(alpha = .65f), CircleShape))
        }
        CaptureButton(
            controller,
            engine,
            recording,
            locked,
            zoomSensitivity,
            onRecordingChange = { recording = it; if (!it) locked = false },
            onLocked = {
                locked = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onZoomChange = { lockedZoom = it },
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
    zoomSensitivity: Float,
    onRecordingChange: (Boolean) -> Unit,
    onLocked: () -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier,
) {
    val lockSideDistance = with(LocalDensity.current) { 82.dp.toPx() }
    val lockVerticalTolerance = with(LocalDensity.current) { 64.dp.toPx() }
    val zoomDistance = with(LocalDensity.current) { 600.dp.toPx() } / zoomSensitivity
    val ringColor by animateColorAsState(if (recording) Color.Red else Color.White, label = "capture ring")
    Box(
        modifier
            .size(84.dp)
            .background(Color.White.copy(alpha = .35f), CircleShape)
            .pointerInput(controller, engine) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pressed = controller.press(down.position.y, SystemClock.uptimeMillis())
                    perform(pressed, engine, onRecordingChange, onLocked, onZoomChange)
                    if (pressed == CaptureAction.StopRecording) return@awaitEachGesture

                    val quickUp = withTimeoutOrNull(CaptureController.HOLD_MILLIS) {
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) event = awaitPointerEvent()
                    }
                    if (quickUp != null) {
                        perform(controller.release(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked, onZoomChange)
                        return@awaitEachGesture
                    }

                    perform(controller.tick(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked, onZoomChange)
                    var gestureLocked = false
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (
                            !gestureLocked &&
                            change.position.x - down.position.x >= lockSideDistance &&
                            abs(down.position.y - change.position.y) <= lockVerticalTolerance
                        ) {
                            perform(controller.lock(), engine, onRecordingChange, onLocked, onZoomChange)
                            gestureLocked = true
                        } else if (!gestureLocked) {
                            perform(controller.drag(change.position.y, zoomDistance), engine, onRecordingChange, onLocked, onZoomChange)
                        }
                        if (!change.pressed) {
                            perform(controller.release(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked, onZoomChange)
                            break
                        }
                    }
                }
            }
            .border(4.dp, ringColor, CircleShape),
    ) {
        if (locked) {
            Box(Modifier.align(Alignment.Center).size(28.dp).background(Color.Red, RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
private fun ZoomSettings(
    sensitivity: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.width(220.dp).background(Color.Black.copy(alpha = .8f), RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text("Zoom sensitivity %.2f×".format(sensitivity), color = Color.White)
        Slider(
            value = sensitivity,
            onValueChange = onChange,
            valueRange = .5f..2f,
            steps = 5,
            onValueChangeFinished = onFinished,
        )
    }
}

@Composable
private fun LockTarget(visible: Boolean, locked: Boolean, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = .65f),
        exit = fadeOut() + scaleOut(targetScale = .65f),
    ) {
        val color by animateColorAsState(if (locked) Color.Red else Color.Black.copy(alpha = .65f), label = "lock target")
        Box(
            Modifier.size(58.dp).background(color, CircleShape).border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (locked) "✓" else "🔒", color = Color.White)
        }
    }
}

private fun perform(
    action: CaptureAction,
    engine: CameraEngine,
    onRecordingChange: (Boolean) -> Unit,
    onLocked: () -> Unit,
    onZoomChange: (Float) -> Unit,
) = when (action) {
    CaptureAction.TakePhoto -> engine.takePhoto()
    CaptureAction.StartRecording -> { engine.startRecording(); onRecordingChange(true) }
    CaptureAction.StopRecording -> { engine.stopRecording(); onRecordingChange(false) }
    is CaptureAction.SetZoom -> { engine.setZoom(action.linearZoom); onZoomChange(action.linearZoom) }
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
private fun ReviewScreen(
    media: CapturedMedia,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
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
        Column(Modifier.align(Alignment.BottomCenter).offset(y = (-32).dp)) {
            Row {
                Button(onClick = onCopy) { Text("Copy") }
                Button(onClick = onShare) { Text("Share") }
            }
            Row {
                Button(onClick = onDiscard) { Text("Discard") }
                Button(onClick = onSave) { Text("Save") }
            }
        }
    }
}

private fun copyMedia(context: android.content.Context, media: CapturedMedia) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Frame capture", media.uri))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareMedia(context: android.content.Context, media: CapturedMedia) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = media.kind.mimeType
        putExtra(Intent.EXTRA_STREAM, media.uri)
        clipData = ClipData.newUri(context.contentResolver, "Frame capture", media.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Share capture"))
}
