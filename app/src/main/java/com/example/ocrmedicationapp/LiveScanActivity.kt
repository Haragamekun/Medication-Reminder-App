package com.example.ocrmedicationapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ocrmedicationapp.databinding.ActivityLiveScanBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLiveScanBinding
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var ocrWrapper: MlKitOcrWrapper
    private var lastResult = ""

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for live scan", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHighRefreshRate()
        binding = ActivityLiveScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ocrWrapper = MlKitOcrWrapper()

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnDone.setOnClickListener {
            val intent = Intent()
            intent.putExtra("detected_text", lastResult)
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentDisplay = this.display
            if (currentDisplay != null) {
                val modes = currentDisplay.supportedModes
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = 120f
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("LiveScan", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        imageProxy.close()
        if (bitmap != null) {
            lifecycleScope.launch {
                val text = ocrWrapper.recognizeText(bitmap)
                if (text.isNotBlank()) {
                    lastResult = text
                    binding.tvLiveResult.text = "Detected: ${text.take(30)}..."
                    binding.btnDone.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun toggleFlash() {
        camera?.let {
            val isFlashAvailable = it.cameraInfo.hasFlashUnit()
            if (isFlashAvailable) {
                val newState = it.cameraInfo.torchState.value == TorchState.OFF
                it.cameraControl.enableTorch(newState)
                binding.btnFlash.alpha = if (newState) 1.0f else 0.5f
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ocrWrapper.release()
    }
}
