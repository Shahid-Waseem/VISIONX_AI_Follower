package com.visionx.follower

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var modeButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val aiEnabled = AtomicBoolean(false)
    private val analyzing = AtomicBoolean(false)
    private var lastTargetAt = 0L
    private var lastCommand = "S"
    private var lastCommandAt = 0L
    private var lastDistanceCheck = 0L
    private var obstacleDistanceCm: Int? = null
    private var detector: com.google.mlkit.vision.objects.ObjectDetector? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showStatus("Camera permission required")
    }

    private val distancePoll = object : Runnable {
        override fun run() {
            if (aiEnabled.get()) fetchDistance()
            handler.postDelayed(this, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .build()
        detector = ObjectDetection.getClient(options)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }


    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))

        statusText = TextView(this).apply {
            text = "Connect phone to VISION_X_ROVER Wi-Fi"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(12, 12, 12, 12)
        }
        root.addView(statusText)

        modeButton = Button(this).apply {
            text = "START AI FOLLOW"
            setOnClickListener { toggleAi() }
        }
        root.addView(modeButton, LinearLayout.LayoutParams(-1, -2))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = controlButton("◀ LEFT") { sendCommand("L", true) }
        val stop = controlButton("STOP") { sendCommand("S", true) }
        val right = controlButton("RIGHT ▶") { sendCommand("R", true) }
        row1.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val forward = controlButton("▲ FORWARD") { sendCommand("F", true) }
        val reverse = controlButton("▼ REVERSE") { sendCommand("B", true) }
        row2.addView(forward, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(reverse, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row2)
        setContentView(root)
    }


    private fun controlButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }


    private fun toggleAi() {
        if (aiEnabled.get()) {
            aiEnabled.set(false)
            modeButton.text = "START AI FOLLOW"
            handler.removeCallbacks(distancePoll)
            sendCommand("S", true)
            showStatus("AI OFF — manual controls ready")
        } else {
            aiEnabled.set(true)
            modeButton.text = "STOP AI FOLLOW"
            lastTargetAt = System.currentTimeMillis()
            lastCommand = "S"
            sendCommand("S", true)
            handler.post(distancePoll)
            showStatus("AI ON — aim camera at a target")
        }
    }


    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null || !aiEnabled.get() || !analyzing.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector?.process(image)
                    ?.addOnSuccessListener { objects ->
                        if (aiEnabled.get()) processObjects(objects, imageProxy.width, imageProxy.height)
                    }
                    ?.addOnFailureListener { showStatus("AI detection error — stopping") ; sendCommand("S") }
                    ?.addOnCompleteListener {
                        analyzing.set(false)
                        imageProxy.close()
                    } ?: run {
                        analyzing.set(false)
                        imageProxy.close()
                    }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                showStatus("Camera start failed: ${e.localizedMessage ?: "unknown error"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun processObjects(objects: List<com.google.mlkit.vision.objects.DetectedObject>, imageWidth: Int, imageHeight: Int) {
        if (objects.isEmpty()) {
            if (System.currentTimeMillis() - lastTargetAt > 700) {
                sendCommand("S")
                showStatus("Target lost — STOP")
            }
            return
        }
        // Prototype behavior: follow the largest detected bounding box (not a person-specific identity).
        val target = objects.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        lastTargetAt = System.currentTimeMillis()
        val box = target.boundingBox
        val centerX = box.centerX().toFloat() / max(1, imageWidth)
        val areaRatio = (box.width().toFloat() * box.height().toFloat()) / (imageWidth.toFloat() * imageHeight.toFloat())
        val label = target.labels.firstOrNull()?.text ?: "object"
        val command = when {
            areaRatio >= 0.30f -> "S" // close target: stop
            centerX < 0.38f -> "L"
            centerX > 0.62f -> "R"
            else -> "F"
        }
        val distance = obstacleDistanceCm
        val safeCommand = if (command == "F" && distance != null && distance <= 20) "S" else command
        showStatus("AI: $label | ${commandName(safeCommand)} | distance: ${distance?.let { "$it cm" } ?: "--"}")
        sendCommand(safeCommand)
    }


    private fun fetchDistance() {
        val now = System.currentTimeMillis()
        if (now - lastDistanceCheck < 350) return
        lastDistanceCheck = now
        networkExecutor.execute {
            try {
                val connection = (URL("http://192.168.4.1/distance").openConnection() as HttpURLConnection)
                connection.connectTimeout = 600
                connection.readTimeout = 600
                val body = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                connection.disconnect()
                val parsed = body.toIntOrNull()
                if (parsed != null && parsed > 0) obstacleDistanceCm = parsed else obstacleDistanceCm = null
                if (parsed != null && parsed in 1..20 && aiEnabled.get()) sendCommand("S")
            } catch (_: Exception) {
                obstacleDistanceCm = null
            }
        }
    }


    private fun sendCommand(command: String, force: Boolean = false) {
        if (!force && !aiEnabled.get()) return
        val now = System.currentTimeMillis()
        if (!force && command == lastCommand && now - lastCommandAt < 250) return
        lastCommand = command
        lastCommandAt = now
        networkExecutor.execute {
            try {
                val connection = (URL("http://192.168.4.1/cmd?d=$command").openConnection() as HttpURLConnection)
                connection.connectTimeout = 700
                connection.readTimeout = 700
                connection.requestMethod = "GET"
                connection.inputStream.close()
                connection.disconnect()
            } catch (_: Exception) {
                if (aiEnabled.get()) showStatus("Wi-Fi command failed — STOP and check rover connection")
            }
        }
    }


    private fun commandName(c: String) = when (c) {
        "F" -> "FORWARD"
        "B" -> "BACK"
        "L" -> "LEFT"
        "R" -> "RIGHT"
        else -> "STOP"
    }


    private fun showStatus(text: String) = runOnUiThread { statusText.text = text }


    override fun onDestroy() {
        aiEnabled.set(false)
        handler.removeCallbacks(distancePoll)
        sendCommand("S", true)
        detector?.close()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
