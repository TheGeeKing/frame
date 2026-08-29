package com.frame.camera

sealed interface CaptureAction {
    data object None : CaptureAction
    data object TakePhoto : CaptureAction
    data object StartRecording : CaptureAction
    data object StopRecording : CaptureAction
    data object LockRecording : CaptureAction
    data class SetZoom(val linearZoom: Float) : CaptureAction
}

class CaptureController(private val holdMillis: Long = HOLD_MILLIS) {
    companion object {
        const val HOLD_MILLIS = 225L
    }

    private enum class State { Idle, Pressing, RecordingHeld, RecordingLocked }

    private var state = State.Idle
    private var pressedAt = 0L
    private var pressedY = 0f
    private var pressedZoom = 0f

    fun press(y: Float, atMillis: Long, linearZoom: Float = 0f): CaptureAction {
        if (state == State.RecordingLocked) {
            state = State.Idle
            return CaptureAction.StopRecording
        }
        pressedAt = atMillis
        pressedY = y
        pressedZoom = linearZoom
        state = State.Pressing
        return CaptureAction.None
    }

    fun tick(atMillis: Long): CaptureAction =
        if (state == State.Pressing && atMillis - pressedAt >= holdMillis) {
            state = State.RecordingHeld
            CaptureAction.StartRecording
        } else {
            CaptureAction.None
        }

    fun drag(y: Float, height: Float): CaptureAction =
        if (state == State.RecordingHeld || state == State.RecordingLocked) {
            CaptureAction.SetZoom((pressedZoom + (pressedY - y) / height).coerceIn(0f, 1f))
        } else {
            CaptureAction.None
        }

    fun lock(): CaptureAction =
        if (state == State.RecordingHeld) {
            state = State.RecordingLocked
            CaptureAction.LockRecording
        } else {
            CaptureAction.None
        }

    fun release(atMillis: Long): CaptureAction = when (state) {
        State.Pressing -> {
            state = State.Idle
            if (atMillis - pressedAt < holdMillis) CaptureAction.TakePhoto else CaptureAction.None
        }
        State.RecordingHeld -> {
            state = State.Idle
            CaptureAction.StopRecording
        }
        State.RecordingLocked -> CaptureAction.None
        State.Idle -> CaptureAction.None
    }
}
