package com.lava.floorislava.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lava.floorislava.processing.SampleSketch
import com.lava.floorislava.ui.SketchLegend
import com.lava.floorislava.ui.theme.InkBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Step 1 of the flow: get a photo of a drawing.
 *
 * CameraX for the live viewfinder, the Photo Picker for the gallery, and a built-in
 * sample sketch so the app is usable on an emulator with no camera.
 */
@Composable
fun CameraScreen(
    onSketchSelected: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            val bitmap = ImageUtils.loadFromUri(context, uri)
            isBusy = false
            if (bitmap == null) {
                errorMessage = "That image could not be opened. Try another one."
            } else {
                onSketchSelected(bitmap)
            }
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    // Bind/unbind the camera use cases to this composable's lifetime.
    DisposableEffect(hasCameraPermission, lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        val job = scope.launch {
            if (!hasCameraPermission) return@launch
            runCatching {
                val cameraProvider = context.awaitCameraProvider()
                provider = cameraProvider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            }.onFailure {
                errorMessage = "Could not start the camera (${it.javaClass.simpleName}). " +
                    "You can still pick a photo from the gallery."
            }
        }
        onDispose {
            job.cancel()
            provider?.unbindAll()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(InkBlack)) {
        if (hasCameraPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            FramingGuide(modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Draw it. Photograph it. Play it.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                SketchLegend(modifier = Modifier.fillMaxWidth())
            }

            if (!hasCameraPermission) {
                PermissionCard(onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            }

            errorMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(message, modifier = Modifier.padding(12.dp))
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Surface(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Pick from gallery")
                        }
                    }

                    ShutterButton(
                        enabled = hasCameraPermission && !isBusy,
                        onClick = {
                            isBusy = true
                            errorMessage = null
                            imageCapture.takePhoto(
                                context = context,
                                onBitmap = { bitmap ->
                                    isBusy = false
                                    onSketchSelected(bitmap)
                                },
                                onFailure = { message ->
                                    isBusy = false
                                    errorMessage = message
                                },
                                scopeLaunch = { block -> scope.launch { block() } },
                            )
                        },
                    )

                    Surface(
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier.size(56.dp),
                    ) {}
                }

                TextButton(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val sample = withContext(Dispatchers.Default) { SampleSketch.create() }
                            isBusy = false
                            onSketchSelected(sample)
                        }
                    },
                ) {
                    Text("No paper handy? Try the sample sketch", color = Color.White)
                }
            }
        }

        if (isBusy) {
            Box(
                modifier = Modifier.fillMaxSize().background(InkBlack.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.White,
        modifier = Modifier
            .size(80.dp)
            .semantics { contentDescription = "Take a photo of the sketch" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** A dashed-ish frame hinting at where the paper should sit. */
@Composable
private fun FramingGuide(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f).padding(vertical = 140.dp),
            color = Color.Transparent,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.55f)),
        ) {
            Spacer(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Camera access", style = MaterialTheme.typography.titleMedium)
            Text(
                "Allow the camera to photograph your drawing, or pick an existing photo " +
                    "from the gallery instead.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onGrant) { Text("Allow camera") }
        }
    }
}

// ---------------------------------------------------------------- CameraX plumbing

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** `ProcessCameraProvider.getInstance` returns a `ListenableFuture`; make it suspend. */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

/**
 * Takes a picture and decodes it off the main thread.
 *
 * [scopeLaunch] keeps the coroutine tied to the composable's scope, so navigating away
 * mid-capture cannot leak the decode or call back into a dead screen.
 */
private fun ImageCapture.takePhoto(
    context: Context,
    onBitmap: (Bitmap) -> Unit,
    onFailure: (String) -> Unit,
    scopeLaunch: (suspend () -> Unit) -> Unit,
) {
    takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                scopeLaunch {
                    val bitmap = try {
                        withContext(Dispatchers.Default) { ImageUtils.fromImageProxy(image) }
                    } finally {
                        image.close()
                    }
                    if (bitmap == null) {
                        onFailure("Could not read that photo.")
                    } else {
                        onBitmap(bitmap)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onFailure("Capture failed: ${exception.message ?: exception.imageCaptureError}")
            }
        },
    )
}
