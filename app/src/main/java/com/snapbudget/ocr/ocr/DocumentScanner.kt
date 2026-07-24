package com.snapbudget.ocr.ocr

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Document scanner that uses OpenCV to detect, isolate, and flatten
 * a receipt from a photograph. The 4-step pipeline:
 *   1. Canny Edge Detection
 *   2. Find Region of Interest (largest 4-corner contour)
 *   3. Perspective Transform (warp)
 *   4. Crop to receipt
 */
class DocumentScanner {

    companion object {
        private const val TAG = "STEP_BY_STEP"

        init {
            if (!OpenCVLoader.initLocal()) {
                Log.e(TAG, "DocumentScanner: OpenCV initialization FAILED")
            } else {
                Log.d(TAG, "DocumentScanner: OpenCV initialized successfully")
            }
        }
    }

    /**
     * Main entry point. Attempts the full scanning pipeline.
     * Returns the cropped, de-warped receipt bitmap, or the original bitmap
     * if scanning fails at any step.
     */
    fun processDocument(bitmap: Bitmap): Bitmap {
        Log.d(TAG, "6a-scan. DocumentScanner: Starting document scanning pipeline")
        Log.d(TAG, "   - Input: ${bitmap.width}x${bitmap.height}")

        // Convert Bitmap → OpenCV Mat
        val srcMat = Mat()
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Utils.bitmapToMat(bitmapCopy, srcMat)

        // ── STEP 1: Canny Edge Detection ──
        val edges = applyCannyEdgeDetection(srcMat)
        if (edges == null) {
            Log.w(TAG, "6a-scan. Edge detection failed, returning original image")
            srcMat.release()
            return bitmap
        }

        // ── STEP 2: Find ROI (largest 4-corner contour) ──
        val corners = findReceiptContour(edges)
        edges.release()
        if (corners == null) {
            Log.w(TAG, "6b-scan. WARNING: No 4-corner rectangle found. Returning original image.")
            srcMat.release()
            return bitmap
        }

        // ── STEP 3 & 4: Perspective Transform + Crop ──
        val result = applyPerspectiveTransform(srcMat, corners)
        srcMat.release()

        if (result == null) {
            Log.w(TAG, "6c-scan. Perspective transform failed. Returning original image.")
            return bitmap
        }

        // Convert back to Bitmap
        val outputBitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, outputBitmap)
        result.release()

        Log.d(TAG, "6d-scan. DocumentScanner: Pipeline complete. Output: ${outputBitmap.width}x${outputBitmap.height}")
        return outputBitmap
    }

    /**
     * Step 1: Apply Canny edge detection.
     * Converts to grayscale, applies Gaussian blur, then Canny.
     * Result: black image with white edges.
     */
    private fun applyCannyEdgeDetection(src: Mat): Mat? {
        return try {
            // Convert to grayscale
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // Apply Gaussian blur to reduce noise
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            gray.release()

            // Canny edge detection (thresholds tuned for receipts)
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            blurred.release()

            // Dilate edges slightly to close gaps
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val dilated = Mat()
            Imgproc.dilate(edges, dilated, kernel)
            edges.release()
            kernel.release()

            Log.d(TAG, "6a-scan. Canny edge detection COMPLETE")
            Log.d(TAG, "   - Edge matrix dimensions: ${dilated.cols()}x${dilated.rows()}")
            Log.d(TAG, "   - Edge matrix type: ${CvType.typeToString(dilated.type())}")

            dilated
        } catch (e: Exception) {
            Log.e(TAG, "6a-scan. ERROR during Canny edge detection", e)
            null
        }
    }

    /**
     * Step 2: Find the largest 4-corner contour in the edge image.
     * This represents the receipt boundary.
     * Returns the 4 corner points ordered: top-left, top-right, bottom-right, bottom-left.
     */
    private fun findReceiptContour(edges: Mat): Array<Point>? {
        return try {
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges.clone(), contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            hierarchy.release()

            Log.d(TAG, "6b-scan. Found ${contours.size} contours total")

            if (contours.isEmpty()) {
                Log.w(TAG, "6b-scan. WARNING: No contours found in edge image")
                return null
            }

            // Sort by area (largest first)
            val sortedContours = contours.sortedByDescending { Imgproc.contourArea(it) }

            // Minimum area threshold — contour must be at least 10% of image area
            val imageArea = edges.rows().toDouble() * edges.cols().toDouble()
            val minAreaThreshold = imageArea * 0.10

            for (contour in sortedContours) {
                val area = Imgproc.contourArea(contour)
                if (area < minAreaThreshold) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                val perimeter = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)
                contour2f.release()

                if (approx.rows() == 4) {
                    val points = approx.toArray()
                    approx.release()

                    // Order the 4 corners consistently
                    val ordered = orderCorners(points)

                    Log.d(TAG, "6b-scan. ROI found — 4-corner rectangle detected")
                    Log.d(TAG, "   - Top-Left:     (${ordered[0].x.toInt()}, ${ordered[0].y.toInt()})")
                    Log.d(TAG, "   - Top-Right:    (${ordered[1].x.toInt()}, ${ordered[1].y.toInt()})")
                    Log.d(TAG, "   - Bottom-Right: (${ordered[2].x.toInt()}, ${ordered[2].y.toInt()})")
                    Log.d(TAG, "   - Bottom-Left:  (${ordered[3].x.toInt()}, ${ordered[3].y.toInt()})")
                    Log.d(TAG, "   - Contour area: ${area.toInt()} px² (${(area / imageArea * 100).toInt()}% of image)")

                    // Clean up
                    contours.forEach { it.release() }
                    return ordered
                }
                approx.release()
            }

            Log.w(TAG, "6b-scan. WARNING: No 4-corner contour found among ${contours.size} candidates")
            contours.forEach { it.release() }
            null
        } catch (e: Exception) {
            Log.e(TAG, "6b-scan. ERROR during contour detection", e)
            null
        }
    }

    /**
     * Step 3 & 4: Warp + Crop.
     * Uses the 4 corners to compute a perspective transform and warp the image
     * into a flat, top-down rectangle.
     */
    private fun applyPerspectiveTransform(src: Mat, corners: Array<Point>): Mat? {
        return try {
            val tl = corners[0]
            val tr = corners[1]
            val br = corners[2]
            val bl = corners[3]

            // Compute output dimensions from corner distances
            val widthTop = distance(tl, tr)
            val widthBottom = distance(bl, br)
            val maxWidth = maxOf(widthTop, widthBottom).toInt()

            val heightLeft = distance(tl, bl)
            val heightRight = distance(tr, br)
            val maxHeight = maxOf(heightLeft, heightRight).toInt()

            if (maxWidth <= 0 || maxHeight <= 0) {
                Log.w(TAG, "6c-scan. WARNING: Computed dimensions are invalid: ${maxWidth}x${maxHeight}")
                return null
            }

            // Source points (the detected corners)
            val srcPoints = MatOfPoint2f(tl, tr, br, bl)

            // Destination points (a perfect rectangle)
            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(maxWidth - 1.0, 0.0),
                Point(maxWidth - 1.0, maxHeight - 1.0),
                Point(0.0, maxHeight - 1.0)
            )

            // Compute the perspective transform matrix
            val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            srcPoints.release()
            dstPoints.release()

            Log.d(TAG, "6c-scan. Perspective transform matrix CALCULATED")
            Log.d(TAG, "   - Output dimensions: ${maxWidth}x${maxHeight}")

            // Apply the warp
            val warped = Mat()
            Imgproc.warpPerspective(src, warped, transformMatrix, Size(maxWidth.toDouble(), maxHeight.toDouble()))
            transformMatrix.release()

            Log.d(TAG, "6c-scan. Perspective transform APPLIED successfully")
            Log.d(TAG, "   - Warped image size: ${warped.cols()}x${warped.rows()}")

            warped
        } catch (e: Exception) {
            Log.e(TAG, "6c-scan. ERROR during perspective transform", e)
            null
        }
    }

    /**
     * Order 4 points as: top-left, top-right, bottom-right, bottom-left.
     */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        // Sort by sum (x+y): smallest = top-left, largest = bottom-right
        val sorted = points.sortedBy { it.x + it.y }
        val tl = sorted[0]
        val br = sorted[3]

        // Sort by difference (y-x): smallest = top-right, largest = bottom-left
        val sortedDiff = points.sortedBy { it.y - it.x }
        val tr = sortedDiff[0]
        val bl = sortedDiff[3]

        return arrayOf(tl, tr, br, bl)
    }

    /**
     * Euclidean distance between two points.
     */
    private fun distance(p1: Point, p2: Point): Double {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return Math.sqrt(dx * dx + dy * dy)
    }
}
