package com.example.poselandmarker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    var minPoseDetectionConfidence: Float = DEFAULT_POSE_DETECTION_CONFIDENCE,
    var minPoseTrackingConfidence: Float = DEFAULT_POSE_TRACKING_CONFIDENCE,
    var minPosePresenceConfidence: Float = DEFAULT_POSE_PRESENCE_CONFIDENCE,
    var currentDelegate: Int = DELEGATE_GPU,
    val context: Context,
    val listener: LandmarkerListener? = null
) {
    private var poseLandmarker: PoseLandmarker? = null

    init {
        setupPoseLandmarker()
    }

    fun clearPoseLandmarker() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    fun isClose(): Boolean = poseLandmarker == null

    fun setupPoseLandmarker() {
        val baseOptionBuilder = BaseOptions.builder()

        when (currentDelegate) {
            DELEGATE_CPU -> baseOptionBuilder.setDelegate(Delegate.CPU)
            DELEGATE_GPU -> baseOptionBuilder.setDelegate(Delegate.GPU)
        }

        baseOptionBuilder.setModelAssetPath("pose_landmarker_full.task")

        try {
            val baseOptions = baseOptionBuilder.build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                .setMinTrackingConfidence(minPoseTrackingConfidence)
                .setMinPosePresenceConfidence(minPosePresenceConfidence)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setResultListener(this::returnLivestreamResult)
                .setErrorListener(this::returnLivestreamError)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            listener?.onError("Pose Landmarker failed to initialize: ${e.message}")
            Log.e(TAG, "MediaPipe init error", e)
        } catch (e: RuntimeException) {
            listener?.onError(
                "GPU delegate failed. Falling back to CPU.",
                GPU_ERROR
            )
            Log.e(TAG, "GPU delegate error", e)
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val frameTime = SystemClock.uptimeMillis()

        // Save metadata before closing the proxy
        val imgWidth = imageProxy.width
        val imgHeight = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Copy RGBA pixels from the frame (identical to Google demo)
        val bitmapBuffer = Bitmap.createBitmap(
            imgWidth, imgHeight, Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, imgWidth.toFloat(), imgHeight.toFloat())
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
            matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        poseLandmarker?.detectAsync(mpImage, frameTime)
    }

    private fun returnLivestreamResult(result: PoseLandmarkerResult, input: MPImage) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        listener?.onResults(
            ResultBundle(
                result = result,
                inferenceTime = inferenceTime,
                inputImageHeight = input.height,
                inputImageWidth = input.width
            )
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        listener?.onError(error.message ?: "Unknown error")
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    data class ResultBundle(
        val result: PoseLandmarkerResult,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int
    )

    interface LandmarkerListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }

    companion object {
        const val TAG = "PoseLandmarkerHelper"
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
        const val DEFAULT_POSE_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_POSE_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_POSE_PRESENCE_CONFIDENCE = 0.5F
    }
}
