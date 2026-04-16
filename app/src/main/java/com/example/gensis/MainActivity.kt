package com.example.gensis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.gensis.analyzer.FaceAnalyzer
import com.example.gensis.ui.theme.GensisTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        enableEdgeToEdge()
        setContent {
            GensisTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CameraScreen(
                        modifier = Modifier.padding(innerPadding),
                        onDrowsyAlert = { level -> playAlert(level) },
                        onNormal = { resetScreenBrightness() }
                    )
                }
            }
        }
    }

    private fun playAlert(level: Int) {
        val tone = when {
            level >= 3 -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
            level >= 2 -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        toneGenerator?.startTone(tone, 500)
        
        // Maximize brightness
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 1.0f
        window.attributes = layoutParams
    }

    private fun resetScreenBrightness() {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = layoutParams
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
    }
}

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    onDrowsyAlert: (Int) -> Unit,
    onNormal: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    var faceData by remember { mutableStateOf<FaceAnalyzer.FaceData?>(null) }
    var closedEyesStartTime by remember { mutableLongStateOf(0L) }
    var alertLevel by remember { mutableStateOf(0) }

    LaunchedEffect(faceData) {
        val data = faceData
        if (data != null && data.faceDetected && data.isDrowsy) {
            if (closedEyesStartTime == 0L) {
                closedEyesStartTime = System.currentTimeMillis()
            } else {
                val duration = System.currentTimeMillis() - closedEyesStartTime
                if (duration > 2000) {
                    val newLevel = ((duration - 2000) / 2000).toInt() + 1
                    if (newLevel > alertLevel) {
                        alertLevel = newLevel
                        onDrowsyAlert(alertLevel)
                    }
                }
            }
        } else {
            if (closedEyesStartTime != 0L) {
                closedEyesStartTime = 0L
                alertLevel = 0
                onNormal()
            }
        }
    }

    if (hasCameraPermission) {
        Box(modifier = modifier.fillMaxSize()) {
            CameraPreview(onFaceData = { faceData = it })
            
            StatusOverlay(faceData, alertLevel)
        }
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Camera permission required")
        }
    }
}

@Composable
fun StatusOverlay(faceData: FaceAnalyzer.FaceData?, alertLevel: Int) {
    val backgroundColor = when {
        alertLevel >= 3 -> Color.Red.copy(alpha = 0.5f)
        alertLevel >= 1 -> Color.Yellow.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (faceData == null || !faceData.faceDetected) {
            Text("Face not detected!", color = Color.White, fontSize = 24.sp, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp))
        } else if (alertLevel > 0) {
            Text("WAKE UP!", color = Color.White, fontSize = 48.sp, modifier = Modifier.background(Color.Red).padding(16.dp))
        } else {
            Text("Monitoring...", color = Color.White, fontSize = 18.sp, modifier = Modifier.background(Color.Black.copy(alpha = 0.4f)).padding(4.dp))
        }
        
        faceData?.let {
            Text(
                text = "L: ${"%.2f".format(it.leftEyeOpenProb ?: 0f)} R: ${"%.2f".format(it.rightEyeOpenProb ?: 0f)}",
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onFaceData: (FaceAnalyzer.FaceData) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor, FaceAnalyzer(onFaceData))
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", e)
                }
            }, executor)
            previewView
        },
        modifier = modifier.fillMaxSize()
    )
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GensisTheme {
        Greeting("Android")
    }
}