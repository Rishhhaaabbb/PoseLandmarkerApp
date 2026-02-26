package com.example.poselandmarker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.poselandmarker.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var backgroundExecutor: ExecutorService
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null

    private var currentDelegate = PoseLandmarkerHelper.DELEGATE_GPU
    private var isFrontCamera = true // Start with front camera
    private var cameraProvider: ProcessCameraProvider? = null

    // FPS tracking
    private var frameCount = 0
    private var lastFpsTimestamp = SystemClock.uptimeMillis()
    private var currentFps = 0

    companion object {
        private const val TAG = "PoseLandmarker"
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        setupDelegateSpinner()
        setupCameraFlip()

        if (hasCameraPermission()) {
            startAll()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.execute { poseLandmarkerHelper?.close() }
        backgroundExecutor.shutdown()
    }

    // ── Permissions ──────────────────────────────────────────────────────
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startAll()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    // ── Delegate spinner ─────────────────────────────────────────────────
    private fun setupDelegateSpinner() {
        val spinner: Spinner = binding.spinnerDelegate
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.delegate_options,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(currentDelegate)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (position != currentDelegate) {
                    currentDelegate = position
                    // Reinitialize on the background thread
                    backgroundExecutor.execute {
                        poseLandmarkerHelper?.clearPoseLandmarker()
                        poseLandmarkerHelper?.currentDelegate = currentDelegate
                        poseLandmarkerHelper?.setupPoseLandmarker()
                    }
                    binding.overlayView.clear()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Camera flip ───────────────────────────────────────────────────────
    private fun setupCameraFlip() {
        binding.btnFlipCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            // Reset FPS counter on switch
            frameCount = 0
            lastFpsTimestamp = SystemClock.uptimeMillis()
            currentFps = 0
            binding.overlayView.clear()
            startCamera()
        }
    }

    // ── Start helper + camera ────────────────────────────────────────────
    private fun startAll() {
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = this,
                currentDelegate = currentDelegate,
                listener = this
            )
        }

        binding.previewView.post { startCamera() }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            // Use full sensor resolution — no aspect ratio constraint
            val previewBuilder = Preview.Builder()

            // Uncap frame rate — let the device run as fast as possible
            val previewInterop = Camera2Interop.Extender(previewBuilder)
            previewInterop.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(15, 300)
            )

            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

            // Uncap frame rate on analysis too
            val analysisInterop = Camera2Interop.Extender(analysisBuilder)
            analysisInterop.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(15, 300)
            )

            val cameraSelector = if (isFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = analysisBuilder.build()
                .also { analysis ->
                    analysis.setAnalyzer(backgroundExecutor) { imageProxy ->
                        poseLandmarkerHelper?.detectLiveStream(
                            imageProxy = imageProxy,
                            isFrontCamera = isFrontCamera
                        )
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── FPS counter ──────────────────────────────────────────────────────
    private fun updateFps() {
        frameCount++
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastFpsTimestamp
        if (elapsed >= 1000L) {
            currentFps = (frameCount * 1000L / elapsed).toInt()
            frameCount = 0
            lastFpsTimestamp = now
        }
    }

    // ── LandmarkerListener callbacks ─────────────────────────────────────
    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        updateFps()
        val delegateLabel = if (currentDelegate == PoseLandmarkerHelper.DELEGATE_GPU) "GPU" else "CPU"

        runOnUiThread {
            binding.overlayView.setResults(
                resultBundle.result,
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                currentFps,
                resultBundle.inferenceTime,
                delegateLabel
            )
        }
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            if (errorCode == PoseLandmarkerHelper.GPU_ERROR) {
                // Auto-fallback to CPU
                currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                binding.spinnerDelegate.setSelection(PoseLandmarkerHelper.DELEGATE_CPU)
                backgroundExecutor.execute {
                    poseLandmarkerHelper?.clearPoseLandmarker()
                    poseLandmarkerHelper?.currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                    poseLandmarkerHelper?.setupPoseLandmarker()
                }
            }
        }
    }
}
