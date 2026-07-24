package com.snapbudget.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OcrProcessor(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imagePreprocessor = ImagePreprocessor(context)
    private val tag = "OcrProcessor"

    suspend fun processImage(bitmap: Bitmap): OcrResult {
        return try {
            Log.d("STEP_BY_STEP", "6a. Preprocessing bitmap")
            val preprocessed = imagePreprocessor.preprocess(bitmap)
            val image = InputImage.fromBitmap(preprocessed, 0)
            processImageInternal(image)
        } catch (e: Exception) {
            Log.e("STEP_BY_STEP", "6a. ERROR: Bitmap processing failed", e)
            OcrResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun processImage(uri: Uri): OcrResult {
        return try {
            Log.d("STEP_BY_STEP", "6a. Preprocessing image from URI: $uri")
            val preprocessedBitmap = imagePreprocessor.preprocess(uri)
            if (preprocessedBitmap != null) {
                Log.d("STEP_BY_STEP", "6b. Image preprocessed successfully")
                val image = InputImage.fromBitmap(preprocessedBitmap, 0)
                processImageInternal(image)
            } else {
                Log.w("STEP_BY_STEP", "6b. Preprocessing failed, using raw image")
                val image = InputImage.fromFilePath(context, uri)
                processImageInternal(image)
            }
        } catch (e: Exception) {
            Log.e("STEP_BY_STEP", "6a/b. ERROR: URI processing failed", e)
            OcrResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun processImageInternal(image: InputImage): OcrResult = 
        suspendCancellableCoroutine { continuation ->
            Log.d("STEP_BY_STEP", "6c. Starting ML Kit Text Recognition")
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val rawText = visionText.text
                    Log.d("STEP_BY_STEP", "6d. ML Kit OCR Success. Text length: ${rawText.length}")
                    if (continuation.isActive) {
                        continuation.resume(OcrResult.Success(rawText, visionText))
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("STEP_BY_STEP", "6d. ERROR: ML Kit OCR failed", e)
                    if (continuation.isActive) {
                        continuation.resume(OcrResult.Error(e.message ?: "OCR processing failed"))
                    }
                }
                .addOnCanceledListener {
                    Log.w("STEP_BY_STEP", "6d. OCR Cancelled")
                    if (continuation.isActive) {
                        continuation.resume(OcrResult.Cancelled)
                    }
                }
        }

    fun close() {
        recognizer.close()
    }
}

sealed class OcrResult {
    data class Success(val text: String, val visionText: Text) : OcrResult()
    data class Error(val message: String) : OcrResult()
    object Cancelled : OcrResult()
}