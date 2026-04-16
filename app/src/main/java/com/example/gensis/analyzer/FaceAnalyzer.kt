package com.example.gensis.analyzer

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.sqrt

class FaceAnalyzer(private val onFaceDetected: (FaceData) -> Unit) : ImageAnalysis.Analyzer {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()

    private val detector = FaceDetection.getClient(options)

    data class FaceData(
        val leftEyeOpenProb: Float?,
        val rightEyeOpenProb: Float?,
        val isDrowsy: Boolean,
        val faceDetected: Boolean
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        onFaceDetected(FaceData(null, null, false, false))
                    } else {
                        val face = faces[0]
                        val leftOpen = face.leftEyeOpenProbability
                        val rightOpen = face.rightEyeOpenProbability

                        // For simplicity, probabilities are used for
                        // If they are low for a certain duration, this indicated drowsiness
                        // True EAR requires eye contours which is more heavy
                        val isDrowsy = (leftOpen != null && leftOpen < 0.2f) && (rightOpen != null && rightOpen < 0.2f)

                        onFaceDetected(FaceData(leftOpen, rightOpen, isDrowsy, true))
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
