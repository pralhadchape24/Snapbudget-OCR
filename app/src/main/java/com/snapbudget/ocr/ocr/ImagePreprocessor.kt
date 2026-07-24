package com.snapbudget.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * Preprocesses receipt images before OCR to improve text recognition accuracy.
 */
class ImagePreprocessor(private val context: Context) {

    companion object {
        private const val TAG = "STEP_BY_STEP"
        private const val MAX_DIMENSION = 2048
        private const val MIN_DIMENSION = 800
        private const val CONTRAST_FACTOR = 1.4f
        private const val BRIGHTNESS_OFFSET = 10f
        private const val DEBUG_MODE = true
    }

    private val documentScanner = DocumentScanner()

    /**
     * Full preprocessing pipeline with step-by-step logging.
     */
    fun preprocess(bitmap: Bitmap): Bitmap {
        val timestamp = System.currentTimeMillis()
        
        Log.d(TAG, "6a. ImagePreprocessor: Starting pipeline")
        Log.d(TAG, "   - Input dimensions: ${bitmap.width}x${bitmap.height}")
        
        var processed = bitmap
        saveDebugStep(processed, "step_0_original", timestamp)
        
        // Step 1: Document Scanning (Canny → ROI → Perspective Transform → Crop)
        processed = documentScanner.processDocument(processed)
        Log.d(TAG, "6b. ImagePreprocessor: Document scanning complete → ${processed.width}x${processed.height}")
        saveDebugStep(processed, "step_1_document_scan", timestamp)

        // Step 2: Resize
        processed = resizeOptimal(processed)
        Log.d(TAG, "7a. ImagePreprocessor: Resized to ${processed.width}x${processed.height}")
        saveDebugStep(processed, "step_2_resized", timestamp)
        
        // Step 3: Grayscale + Contrast Enhancement
        processed = enhanceForOcr(processed)
        Log.d(TAG, "8a. ImagePreprocessor: Contrast & Brightness applied")
        saveDebugStep(processed, "step_3_enhanced", timestamp)
        
        Log.d(TAG, "8b. ImagePreprocessor: Preprocessing complete")
        return processed
    }

    private fun saveDebugStep(bitmap: Bitmap, stepName: String, sessionToken: Long) {
        if (!DEBUG_MODE) return

        try {
            // Use internal cache directory
            val debugDir = File(context.cacheDir, "ocr_debug/$sessionToken")
            if (!debugDir.exists()) {
                debugDir.mkdirs()
            }
            
            val file = File(debugDir, "$stepName.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.d(TAG, "   >>> Debug image saved: $stepName at ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "   !!! Failed to save debug image: $stepName", e)
        }
    }

    fun preprocess(uri: Uri): Bitmap? {
        Log.d(TAG, "6. ImagePreprocessor: Loading from URI: $uri")
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()
            
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val stream2 = context.contentResolver.openInputStream(uri) ?: return null
            var bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions) ?: return null
            stream2.close()
            
            bitmap = fixOrientation(uri, bitmap)
            preprocess(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "6. ERROR: URI load failed", e)
            null
        }
    }

    private fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val contrast = CONTRAST_FACTOR
        val translate = ((-0.5f * contrast + 0.5f) * 255f) + BRIGHTNESS_OFFSET

        // Standard grayscale weights with contrast adjustment
        val lr = 0.2126f * contrast
        val lg = 0.7152f * contrast
        val lb = 0.0722f * contrast

        val colorMatrix = ColorMatrix(floatArrayOf(
            lr, lg, lb, 0f, translate,
            lr, lg, lb, 0f, translate,
            lr, lg, lb, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun resizeOptimal(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width in MIN_DIMENSION..MAX_DIMENSION && height in MIN_DIMENSION..MAX_DIMENSION) return bitmap

        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        val scale = when {
            maxDim > MAX_DIMENSION -> MAX_DIMENSION.toFloat() / maxDim
            minDim < MIN_DIMENSION -> MIN_DIMENSION.toFloat() / minDim
            else -> 1.0f
        }
        if (scale == 1.0f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val maxDim = maxOf(width, height)
        while (maxDim / sampleSize > MAX_DIMENSION * 2) { sampleSize *= 2 }
        return sampleSize
    }

    private fun fixOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) { bitmap }
    }
}