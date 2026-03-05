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
import com.example.poselandmarker.posecorrection.FeedbackGenerator
import com.example.poselandmarker.posecorrection.PoseFeatureExtractor
import com.example.poselandmarker.posecorrection.PoseGraph
import com.example.poselandmarker.posecorrection.PoseStateMachine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var backgroundExecutor: ExecutorService
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null

    private var currentDelegate = PoseLandmarkerHelper.DELEGATE_GPU
    private var isFrontCamera = true
    private var cameraProvider: ProcessCameraProvider? = null

    // FPS tracking
    private var frameCount = 0
    private var lastFpsTimestamp = SystemClock.uptimeMillis()
    private var currentFps = 0

    // Pose correction components
    private var poseGraph: PoseGraph? = null
    private var poseStateMachine: PoseStateMachine? = null
    private val featureExtractor = PoseFeatureExtractor()
    private val feedbackGenerator = FeedbackGenerator()
    private var poseCorrectionEnabled = true

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
            frameCount = 0
            lastFpsTimestamp = SystemClock.uptimeMillis()
            currentFps = 0
            binding.overlayView.clear()
            startCamera()
        }
    }

    // ── Start helper + camera ────────────────────────────────────────────
    private fun startAll() {
        // Load pose graph from assets
        try {
            poseGraph = PoseGraph.loadFromAssets(this, "tree.json")
            poseGraph?.let {
                poseStateMachine = PoseStateMachine(
                    graph = it,
                    enableAdaptive = true,
                    enablePreview = true
                )
                Log.d(TAG, "Loaded pose graph: ${it.states.size} states, " +
                        "temporal_seq=${it.metadata.temporalSequence}, " +
                        "base_tol_multiplier=${it.baseToleranceMultiplier}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pose graph: ${e.message}")
            poseCorrectionEnabled = false
        }

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

            val previewBuilder = Preview.Builder()

            // Target 30 FPS for stability (not uncapped)
            val previewInterop = Camera2Interop.Extender(previewBuilder)
            previewInterop.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(15, 30)
            )

            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

            val analysisInterop = Camera2Interop.Extender(analysisBuilder)
            analysisInterop.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(15, 30)
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

        // Process pose correction
        var stateName = ""
        var sequenceProgress = ""
        var repCount = 0
        var feedback = ""
        var isMatching = false
        var holdProgress = 0f
        var holdRemainingSeconds = 0f
        var accuracyPercent = 0
        var matchRatio = 0f
        var streak = 0
        var isInGracePeriod = false
        var isGuidanceActive = false
        var guidanceRemainingSeconds = 0f
        var nextStatePreview: com.example.poselandmarker.posecorrection.PoseState? = null
        var currentTargetKeypoints: List<Triple<Float, Float, Float>>? = null
        var perJointErrorRatios: Map<String, Float> = emptyMap()

        if (poseCorrectionEnabled && poseStateMachine != null) {
            val landmarks = resultBundle.result.landmarks()
            if (landmarks.isNotEmpty()) {
                val features = featureExtractor.extractFeatures(landmarks[0])
                if (features != null) {
                    // Create mirrored features for front-camera L/R swap
                    val mirrored = if (isFrontCamera) featureExtractor.mirrorFeatures(features) else null

                    val stateUpdate = poseStateMachine!!.update(features, mirrored)

                    stateName = stateUpdate.stateName
                    sequenceProgress = stateUpdate.sequenceProgress
                    repCount = stateUpdate.repCount
                    isMatching = stateUpdate.isMatching
                    holdProgress = stateUpdate.holdProgress
                    holdRemainingSeconds = stateUpdate.holdRemainingSeconds
                    accuracyPercent = stateUpdate.accuracyPercent
                    matchRatio = stateUpdate.matchRatio
                    streak = stateUpdate.streak
                    isInGracePeriod = stateUpdate.isInGracePeriod
                    isGuidanceActive = stateUpdate.isGuidanceActive
                    guidanceRemainingSeconds = stateUpdate.guidanceRemainingSeconds
                    nextStatePreview = stateUpdate.nextStatePreview
                    currentTargetKeypoints = stateUpdate.currentTargetKeypoints
                    perJointErrorRatios = stateUpdate.perJointErrorRatios

                    // Generate detailed feedback (debounced)
                    val currentState = poseGraph?.getState(stateUpdate.stateId)
                    feedback = if (stateUpdate.feedback.isNotEmpty()) {
                        stateUpdate.feedback
                    } else if (currentState != null) {
                        feedbackGenerator.generateFeedback(
                            stateUpdate.deviations,
                            currentState.featureTolerances,
                            stateUpdate.toleranceScale,
                            isMatching
                        )
                    } else {
                        ""
                    }
                }
            }
        }

        runOnUiThread {
            binding.overlayView.setResults(
                poseLandmarkerResult = resultBundle.result,
                imgHeight = resultBundle.inputImageHeight,
                imgWidth = resultBundle.inputImageWidth,
                currentFps = currentFps,
                inferenceTime = resultBundle.inferenceTime,
                poseStateName = stateName,
                poseSequenceProgress = sequenceProgress,
                poseRepCount = repCount,
                poseFeedback = feedback,
                poseIsMatching = isMatching,
                poseHoldProgress = holdProgress,
                poseHoldRemainingSeconds = holdRemainingSeconds,
                poseAccuracyPercent = accuracyPercent,
                poseMatchRatio = matchRatio,
                poseStreak = streak,
                poseIsInGracePeriod = isInGracePeriod,
                poseIsGuidanceActive = isGuidanceActive,
                poseGuidanceRemainingSeconds = guidanceRemainingSeconds,
                poseNextStatePreview = nextStatePreview,
                poseCurrentTargetKeypoints = currentTargetKeypoints,
                posePerJointErrorRatios = perJointErrorRatios
            )
        }
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            if (errorCode == PoseLandmarkerHelper.GPU_ERROR) {
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
