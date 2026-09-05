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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val Ink = Color(0xFF171715)
private val Paper = Color(0xFFF7F6F2)
private val Hairline = Color(0xFFE7E5DF)
private val RecordRed = Color(0xFFC94B43)

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
    var paused by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var lockedZoom by remember { mutableStateOf(0f) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    var showSettings by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    val updateManager = remember { UpdateManager(context.applicationContext) }
    val preferences = remember { context.getSharedPreferences("frame", android.content.Context.MODE_PRIVATE) }
    var zoomSensitivity by remember { mutableStateOf(preferences.getFloat("zoomSensitivity", 1f)) }
    var autoMuteReplay by remember { mutableStateOf(preferences.getBoolean("autoMuteReplay", false)) }
    val engine = remember { CameraEngine(context, owner, previewView, { captured = it }, { error = it }) }
    val controller = remember { CaptureController() }

    DisposableEffect(engine, captured) {
        if (captured == null) {
            engine.start()
            onDispose(engine::close)
        } else {
            engine.close()
            onDispose { }
        }
    }
    LaunchedEffect(recording) {
        elapsedSeconds = 0
        while (recording) {
            delay(1_000)
            if (!paused) elapsedSeconds++
        }
    }
    LaunchedEffect(Unit) {
        update = runCatching {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            withContext(Dispatchers.IO) { updateManager.check(version) }
        }.getOrNull()
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_000)
            focusPoint = null
        }
    }

    captured?.let { media ->
        ReviewScreen(
            media,
            autoMuteReplay = autoMuteReplay,
            onCopy = { exported -> copyMedia(context, exported) },
            onShare = { exported -> shareMedia(context, exported) },
            onSave = { exported ->
                if (exported.uri != media.uri) discard(context, media)
                publish(context, exported)
                captured = null
            },
            onDiscard = { silent ->
                silent?.let { discard(context, it) }
                discard(context, media)
                captured = null
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AndroidView(
            factory = {
                (previewView.parent as? ViewGroup)?.removeView(previewView)
                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(engine) {
                    detectTapGestures(
                        onDoubleTap = { engine.switchCamera() },
                        onTap = { focusPoint = it; engine.focus(it.x, it.y) },
                    )
                }
                .pointerInput(engine, zoomSensitivity) {
                    val zoomDistance = 600.dp.toPx() / zoomSensitivity
                    detectTransformGestures { _, pan, zoom, _ ->
                        lockedZoom = (
                            engine.currentLinearZoom() - pan.y / zoomDistance + (zoom - 1f) * .25f * zoomSensitivity
                        ).coerceIn(0f, 1f)
                        engine.setZoom(lockedZoom)
                    }
                },
        )
        focusPoint?.let { point ->
            Box(
                Modifier
                    .offset { IntOffset((point.x - 32.dp.toPx()).toInt(), (point.y - 32.dp.toPx()).toInt()) }
                    .size(64.dp)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
            )
        }
        if (recording) {
            RecordingIndicator(elapsedSeconds, locked, Modifier.align(Alignment.TopCenter).offset(y = 28.dp))
        } else {
            Row(
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UtilityButton(
                    label = if (flashEnabled) "Flash on" else "Flash",
                    selected = flashEnabled,
                    onClick = {
                        flashEnabled = !flashEnabled
                        engine.setFlashEnabled(flashEnabled)
                    },
                )
                UtilityButton(
                    label = if (update == null) "Settings" else "Settings · 1",
                    selected = showSettings,
                    onClick = { showSettings = !showSettings },
                )
            }
            if (showSettings) {
                ZoomSettings(
                    zoomSensitivity,
                    onChange = { zoomSensitivity = it },
                    onFinished = { preferences.edit().putFloat("zoomSensitivity", zoomSensitivity).apply() },
                    autoMuteReplay = autoMuteReplay,
                    onAutoMuteReplayChange = {
                        autoMuteReplay = it
                        preferences.edit().putBoolean("autoMuteReplay", it).apply()
                    },
                    update = update,
                    onUpdate = { update?.let(updateManager::install) },
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 72.dp, end = 16.dp),
                )
            }
        }
        LockTarget(recording, locked, Modifier.align(Alignment.BottomCenter).offset(x = 110.dp, y = (-62).dp))
        PauseTarget(
            recording,
            paused,
            locked,
            onClick = {
                paused = !paused
                if (paused) engine.pauseRecording() else engine.resumeRecording()
            },
            modifier = Modifier.align(Alignment.BottomCenter).offset(x = (-110).dp, y = (-62).dp),
        )
        AnimatedVisibility(
            visible = recording && !locked,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-88).dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(Modifier.width(172.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(2) { Box(Modifier.width(44.dp).height(4.dp).background(Color.White.copy(alpha = .65f), CircleShape)) }
            }
        }
        CaptureButton(
            controller,
            engine,
            recording,
            locked,
            zoomSensitivity,
            onRecordingChange = { recording = it; if (!it) { locked = false; paused = false } },
            onLocked = {
                locked = true
                lockedZoom = engine.currentLinearZoom()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onPaused = { paused = true },
            onZoomChange = { lockedZoom = it },
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-48).dp),
        )
        error?.let {
            Text(
                it,
                color = Ink,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp).background(Paper, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun UtilityButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Paper else Ink.copy(alpha = .88f),
            contentColor = if (selected) Ink else Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .2.sp)
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
    onPaused: () -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier,
) {
    val lockSideDistance = with(LocalDensity.current) { 82.dp.toPx() }
    val lockVerticalTolerance = with(LocalDensity.current) { 64.dp.toPx() }
    val zoomDistance = with(LocalDensity.current) { 600.dp.toPx() } / zoomSensitivity
    val ringColor by animateColorAsState(if (recording) RecordRed else Color.White, label = "capture ring")
    Box(
        modifier
            .size(84.dp)
            .background(Ink.copy(alpha = .52f), CircleShape)
            .pointerInput(controller, engine) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pressed = controller.press(
                        down.position.y,
                        SystemClock.uptimeMillis(),
                        engine.currentLinearZoom(),
                    )
                    perform(pressed, engine, onRecordingChange, onLocked, onPaused, onZoomChange)
                    if (pressed == CaptureAction.StopRecording) return@awaitEachGesture

                    val quickUp = withTimeoutOrNull(CaptureController.HOLD_MILLIS) {
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) event = awaitPointerEvent()
                    }
                    if (quickUp != null) {
                        perform(controller.release(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked, onPaused, onZoomChange)
                        return@awaitEachGesture
                    }

                    perform(controller.tick(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked, onPaused, onZoomChange)
                    var gestureLocked = false
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (
                            !gestureLocked &&
                            change.position.x - down.position.x >= lockSideDistance &&
                            abs(down.position.y - change.position.y) <= lockVerticalTolerance
                        ) {
                            perform(controller.lock(), engine, onRecordingChange, onLocked, onPaused, onZoomChange)
                            gestureLocked = true
                        } else if (
                            !gestureLocked &&
                            down.position.x - change.position.x >= lockSideDistance &&
                            abs(down.position.y - change.position.y) <= lockVerticalTolerance
                        ) {
                            perform(controller.pause(), engine, onRecordingChange, onLocked, onPaused, onZoomChange)
                            gestureLocked = true
                        } else if (!gestureLocked) {
                            perform(controller.drag(change.position.y, zoomDistance), engine, onRecordingChange, onLocked, onPaused, onZoomChange)
                        }
                        if (!change.pressed) {
                            perform(controller.release(SystemClock.uptimeMillis()), engine, onRecordingChange, onLocked, onPaused, onZoomChange)
                            break
                        }
                    }
                }
            }
            .border(4.dp, ringColor, CircleShape),
    ) {
        if (locked) {
            Box(Modifier.align(Alignment.Center).size(28.dp).background(RecordRed, RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
private fun ZoomSettings(
    sensitivity: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    autoMuteReplay: Boolean,
    onAutoMuteReplayChange: (Boolean) -> Unit,
    update: AppUpdate?,
    onUpdate: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.width(236.dp).background(Paper, RoundedCornerShape(10.dp)).border(1.dp, Hairline, RoundedCornerShape(10.dp)).padding(18.dp)) {
        Text("Camera controls", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("Zoom sensitivity · %.2f×".format(sensitivity), color = Color(0xFF6F6D67), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Slider(
            value = sensitivity,
            onValueChange = onChange,
            valueRange = .5f..3f,
            steps = 9,
            onValueChangeFinished = onFinished,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Mute replay", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Shared and saved videos keep their sound", color = Color(0xFF6F6D67), fontSize = 11.sp, lineHeight = 15.sp)
            }
            Switch(checked = autoMuteReplay, onCheckedChange = onAutoMuteReplayChange)
        }
        update?.let {
            Button(
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
            ) { Text("Update to ${it.version}") }
        }
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
        val color by animateColorAsState(if (locked) RecordRed else Ink.copy(alpha = .84f), label = "lock target")
        Box(
            Modifier.size(58.dp).background(color, CircleShape).border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (locked) "LOCKED" else "LOCK", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
        }
    }
}

@Composable
private fun PauseTarget(visible: Boolean, paused: Boolean, locked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = .65f),
        exit = fadeOut() + scaleOut(targetScale = .65f),
    ) {
        val color by animateColorAsState(if (paused) RecordRed else Ink.copy(alpha = .84f), label = "pause target")
        Box(
            Modifier
                .size(58.dp)
                .background(color, CircleShape)
                .border(3.dp, Color.White, CircleShape)
                .clickable(enabled = locked, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (paused) "RESUME" else "PAUSE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
        }
    }
}

private fun perform(
    action: CaptureAction,
    engine: CameraEngine,
    onRecordingChange: (Boolean) -> Unit,
    onLocked: () -> Unit,
    onPaused: () -> Unit,
    onZoomChange: (Float) -> Unit,
) = when (action) {
    CaptureAction.TakePhoto -> engine.takePhoto()
    CaptureAction.StartRecording -> { engine.startRecording(); onRecordingChange(true) }
    CaptureAction.StopRecording -> { engine.stopRecording(); onRecordingChange(false) }
    is CaptureAction.SetZoom -> { engine.setZoom(action.linearZoom); onZoomChange(action.linearZoom) }
    CaptureAction.LockRecording -> onLocked()
    CaptureAction.PauseRecording -> { engine.pauseRecording(); onPaused(); onLocked() }
    CaptureAction.None -> Unit
}

@Composable
private fun RecordingIndicator(seconds: Long, locked: Boolean, modifier: Modifier) {
    Row(
        modifier.background(Ink.copy(alpha = .88f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(RecordRed, CircleShape))
        Text(
            "%02d:%02d%s".format(seconds / 60, seconds % 60, if (locked) "  · LOCKED" else ""),
            color = Color.White,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ReviewScreen(
    media: CapturedMedia,
    autoMuteReplay: Boolean,
    onCopy: (CapturedMedia) -> Unit,
    onShare: (CapturedMedia) -> Unit,
    onSave: (CapturedMedia) -> Unit,
    onDiscard: (CapturedMedia?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var muted by remember(media.uri) { mutableStateOf(autoMuteReplay && media.kind == MediaKind.Video) }
    var stripAudioOnExport by remember(media.uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var silent by remember { mutableStateOf<CapturedMedia?>(null) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    LaunchedEffect(muted, player) {
        val volume = if (muted) 0f else 1f
        player?.setVolume(volume, volume)
    }
    fun export(action: (CapturedMedia) -> Unit) {
        if (busy) return
        if (!stripAudioOnExport || media.kind != MediaKind.Video) {
            action(media)
            return
        }
        busy = true
        scope.launch {
            runCatching {
                silent ?: withContext(Dispatchers.IO) { stripAudio(context, media) }.also { silent = it }
            }.onSuccess(action).onFailure {
                Toast.makeText(context, it.message ?: "Mute failed", Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (media.kind == MediaKind.Video) {
            AndroidView(
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        setVideoURI(media.uri)
                        setOnPreparedListener { prepared ->
                            player = prepared
                            prepared.isLooping = true
                            val volume = if (muted) 0f else 1f
                            prepared.setVolume(volume, volume)
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { viewContext ->
                    ImageView(viewContext).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageURI(media.uri)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Button(
            onClick = { onDiscard(silent) },
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.copy(alpha = .88f), contentColor = Color.White),
        ) { Text("Discard") }
        if (media.kind == MediaKind.Video) {
            Button(
                onClick = {
                    muted = !muted
                    stripAudioOnExport = muted
                },
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (muted) Paper else Ink.copy(alpha = .88f), contentColor = if (muted) Ink else Color.White),
            ) {
                Text(
                    when {
                        !muted -> "Sound on"
                        stripAudioOnExport -> "Video muted"
                        else -> "Replay muted"
                    },
                )
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .background(Paper, RoundedCornerShape(10.dp))
                .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { export(onCopy) }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp)) { Text("Copy") }
            FilledTonalButton(onClick = { export(onShare) }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp)) { Text("Share") }
            Button(
                onClick = {
                    export { exported ->
                        silent?.takeIf { it.uri != exported.uri }?.let { discard(context, it) }
                        onSave(exported)
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
            ) { Text("Save") }
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
