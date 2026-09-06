package com.frame.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.ButtonDefaults
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = androidx.compose.material3.lightColorScheme(
                    primary = Colors.Ink,
                    onPrimary = Color.White,
                    background = Colors.Paper,
                    onBackground = Colors.Ink,
                    surface = Color.White,
                    outline = Colors.Hairline,
                ),
            ) { PermissionGate() }
        }
    }
}

@Composable
private fun PermissionGate() {
    val activity = LocalActivity.current ?: return
    var cameraGranted by remember { mutableStateOf(activity.hasPermission(Manifest.permission.CAMERA)) }
    var microphoneGranted by remember { mutableStateOf(activity.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    var microphoneSkipped by remember { mutableStateOf(false) }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (!granted) microphoneSkipped = true
    }

    when {
        cameraGranted && (microphoneGranted || microphoneSkipped) -> CameraScreen()
        cameraGranted -> PermissionStep(
            eyebrow = "2 of 2",
            title = "Microphone",
            body = "Sound is only used when you record video. Skip this to shoot silent clips.",
            allowLabel = "Allow microphone",
            skipLabel = "Not now",
            onAllow = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
            onSkip = { microphoneSkipped = true },
        )
        else -> PermissionStep(
            eyebrow = "1 of 2",
            title = "Camera",
            body = "Frame needs the camera to take photos and record video. Without it, there is nothing to shoot.",
            allowLabel = "Allow camera",
            onAllow = { requestCamera.launch(Manifest.permission.CAMERA) },
        )
    }
}

@Composable
private fun PermissionStep(
    eyebrow: String,
    title: String,
    body: String,
    allowLabel: String,
    onAllow: () -> Unit,
    skipLabel: String? = null,
    onSkip: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().background(Colors.Paper).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(eyebrow, color = Colors.Muted, style = MaterialTheme.typography.labelLarge)
        Text(
            title,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            body,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            color = Colors.Muted,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onAllow,
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Colors.Ink, contentColor = Color.White),
        ) { Text(allowLabel) }
        if (skipLabel != null && onSkip != null) {
            TextButton(onClick = onSkip) {
                Text(skipLabel, color = Colors.Muted)
            }
        }
    }
}

private fun Activity.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
