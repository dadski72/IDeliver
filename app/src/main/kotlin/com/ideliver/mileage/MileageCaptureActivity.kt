package com.ideliver.mileage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ideliver.data.MileageCaptureContract
import com.ideliver.data.MileageKind
import com.ideliver.data.MileageRepository
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen camera flow for one odometer reading. Launched with a [MileageKind].
 * Captures a photo, runs on-device OCR to pre-fill the number, then requires the
 * driver to confirm/edit it before saving — OCR is a convenience, never trusted
 * blindly for a value that feeds the mileage log.
 */
class MileageCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = runCatching {
            MileageKind.valueOf(
                intent.getStringExtra(MileageCaptureContract.EXTRA_KIND) ?: MileageKind.START.name,
            )
        }.getOrDefault(MileageKind.START)

        setContent {
            MaterialTheme {
                MileageCaptureScreen(
                    kind = kind,
                    onSaved = { finish() },
                    onCancel = { finish() },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context, kind: MileageKind): Intent =
            Intent(context, MileageCaptureActivity::class.java)
                .putExtra(MileageCaptureContract.EXTRA_KIND, kind.name)
    }
}

private data class Pending(val ocrNumber: String, val rawText: String?, val photoPath: String)

@Composable
private fun MileageCaptureScreen(
    kind: MileageKind,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }

    if (!hasCamera) {
        PermissionPrompt(
            onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onCancel = onCancel,
        )
        return
    }

    var processing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            imageCapture = imageCapture,
            lifecycleOwner = lifecycleOwner,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Frame the odometer, then capture the ${kind.label} reading.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    processing = true
                    captureAndRead(context, imageCapture, kind) { number, raw, path ->
                        processing = false
                        if (path != null) {
                            pending = Pending(number.orEmpty(), raw, path)
                        }
                    }
                },
                enabled = !processing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (processing) "Reading…" else "Capture ${kind.label} mileage")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    pending?.let { p ->
        ConfirmDialog(
            kind = kind,
            initial = p.ocrNumber,
            onSave = { miles ->
                val repo = MileageRepository(context)
                (context as ComponentActivity).lifecycleScope.launch {
                    repo.add(kind, miles, p.photoPath, p.rawText)
                    onSaved()
                }
            },
            onRetake = { pending = null },
        )
    }
}

@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

@Composable
private fun ConfirmDialog(
    kind: MileageKind,
    initial: String,
    onSave: (Double) -> Unit,
    onRetake: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val miles = text.trim().replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onRetake,
        title = { Text("Confirm ${kind.label} mileage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Check the number against the dashboard and fix it if OCR misread.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Odometer (miles)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { miles?.let(onSave) }, enabled = miles != null) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onRetake) { Text("Retake") }
        },
    )
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera access is needed to photograph the odometer.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onGrant, modifier = Modifier.padding(top = 16.dp)) {
            Text("Grant camera access")
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

private val MileageKind.label: String
    get() = if (this == MileageKind.START) "start" else "end"

/** Takes a photo to internal storage, then runs OCR and returns the best number. */
private fun captureAndRead(
    context: Context,
    imageCapture: ImageCapture,
    kind: MileageKind,
    onResult: (number: String?, rawText: String?, photoPath: String?) -> Unit,
) {
    val dir = File(context.filesDir, "mileage-photos").apply { mkdirs() }
    val file = File(dir, "${System.currentTimeMillis()}-${kind.name}.jpg")
    val output = ImageCapture.OutputFileOptions.Builder(file).build()

    imageCapture.takePicture(
        output,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener { result ->
                        onResult(extractOdometer(result.text), result.text, file.absolutePath)
                    }
                    .addOnFailureListener {
                        onResult(null, null, file.absolutePath)
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(null, null, null)
            }
        },
    )
}

/** Picks the longest digit run as the likely odometer value. */
internal fun extractOdometer(text: String): String? =
    Regex("""\d[\d,]*(?:\.\d+)?""")
        .findAll(text)
        .map { it.value.replace(",", "") }
        .maxByOrNull { it.replace(".", "").length }
