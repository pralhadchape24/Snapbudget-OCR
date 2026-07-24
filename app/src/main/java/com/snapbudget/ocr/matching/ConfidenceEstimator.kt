package com.snapbudget.ocr.matching

import android.util.Log

/**
 * Confidence Estimation Layer (Concept §12)
 *
 * Unified confidence scoring engine that:
 *  - Aggregates per-field confidence scores
 *  - Normalizes to 0.0–1.0 range
 *  - Determines if user confirmation is needed
 *  - Stores corrected mappings for adaptive learning
 *
 * Uses weighted confidence scoring with deterministic normalization.
 */
object ConfidenceEstimator {

    private const val TAG = "ConfidenceEstimator"

    /** Threshold below which user confirmation should be prompted */
    private const val CONFIRMATION_THRESHOLD = 0.65

    /** Per-field weights for overall confidence */
    private val FIELD_WEIGHTS = mapOf(
        "merchant" to 0.25,
        "amount" to 0.35,
        "date" to 0.15,
        "gst" to 0.05,
        "category" to 0.10,
        "receipt_type" to 0.05,
        "consistency" to 0.05
    )

    /**
     * Per-field confidence with source tracking.
     */
    data class FieldConfidence(
        val fieldName: String,
        val score: Float,            // 0.0 to 1.0
        val source: String,          // e.g., "regex_exact", "fuzzy_match", "ai_fallback"
        val label: String            // "High", "Medium", "Low"
    )

    /**
     * Overall confidence estimation result.
     */
    data class ConfidenceReport(
        val overallScore: Float,            // Weighted average: 0.0–1.0
        val overallLabel: String,           // "High" / "Medium" / "Low"
        val needsUserConfirmation: Boolean,
        val fieldConfidences: List<FieldConfidence>,
        val weakFields: List<String>,       // Fields that scored below threshold
        val suggestions: List<String>       // What the user should review
    )

    /**
     * Estimates confidence for a merchant name extraction.
     */
    fun estimateMerchantConfidence(
        merchantName: String,
        wasMatchedByFuzzy: Boolean,
        matchScore: Double = 0.0,
        wasAiFallback: Boolean = false
    ): FieldConfidence {
        val score = when {
            merchantName.isEmpty() || merchantName == "Unknown" || merchantName == "Unknown Merchant" -> 0.1f
            wasAiFallback && merchantName.isNotEmpty() -> 0.85f
            wasMatchedByFuzzy && matchScore >= 0.95 -> 0.95f
            wasMatchedByFuzzy && matchScore >= 0.88 -> 0.85f
            wasMatchedByFuzzy && matchScore >= 0.80 -> 0.70f
            merchantName.length < 3 -> 0.30f
            merchantName.matches(Regex("^[A-Za-z].*")) && merchantName.length >= 3 -> 0.75f
            else -> 0.50f
        }
        val source = when {
            wasAiFallback -> "ai_fallback"
            wasMatchedByFuzzy -> "fuzzy_match(%.2f)".format(matchScore)
            else -> "raw_extraction"
        }
        val label = confidenceLabel(score)

        Log.d(TAG, "Step 1: estimateMerchantConfidence | merchant='$merchantName', score=$score, source=$source, label=$label")
        return FieldConfidence("merchant", score, source, label)
    }

    /**
     * Estimates confidence for an amount extraction.
     */
    fun estimateAmountConfidence(
        amount: Double,
        extractionSource: String,        // "keyword_same_line", "keyword_adjacent", "bottom_fallback", "ai"
        anomalyResult: OutlierDetector.AnomalyResult? = null,
        consistencyResult: OutlierDetector.AnomalyResult? = null
    ): FieldConfidence {
        var score = when {
            amount <= 0 -> 0.1f
            amount > 1_000_000 -> 0.3f
            else -> 0.70f  // Base score for any positive amount
        }

        // Boost based on extraction source
        score += when (extractionSource) {
            "keyword_same_line" -> 0.20f
            "keyword_adjacent" -> 0.15f
            "bottom_fallback" -> 0.05f
            "ai_fallback" -> 0.15f
            "contextual_window" -> 0.18f
            else -> 0.0f
        }

        // Penalize if anomaly detected
        if (anomalyResult?.isAnomaly == true) {
            score -= (anomalyResult.anomalyScore * 0.3).toFloat()
        }

        // Boost if consistency check passed
        if (consistencyResult != null && !consistencyResult.isAnomaly) {
            score += 0.05f
        }

        score = score.coerceIn(0.0f, 1.0f)
        val label = confidenceLabel(score)

        Log.d(TAG, "Step 2: estimateAmountConfidence | amount=$amount, source=$extractionSource, score=$score, label=$label")
        return FieldConfidence("amount", score, extractionSource, label)
    }

    /**
     * Estimates confidence for date extraction.
     */
    fun estimateDateConfidence(
        dateExtractedFromText: Boolean,
        dateMatchedFormat: Boolean = true
    ): FieldConfidence {
        val score = when {
            dateExtractedFromText && dateMatchedFormat -> 0.90f
            dateExtractedFromText -> 0.75f
            else -> 0.40f  // Fell back to today's date
        }
        val source = if (dateExtractedFromText) "regex_extracted" else "default_today"
        val label = confidenceLabel(score)

        Log.d(TAG, "Step 3: estimateDateConfidence | extracted=$dateExtractedFromText, score=$score, label=$label")
        return FieldConfidence("date", score, source, label)
    }

    /**
     * Estimates confidence for GST extraction.
     */
    fun estimateGstConfidence(
        gstNumber: String?,
        isValid: Boolean
    ): FieldConfidence {
        val score = when {
            gstNumber == null -> 0.30f           // Not found — might not be on receipt
            gstNumber.length != 15 -> 0.20f      // Wrong length
            isValid -> 0.95f                      // Valid format
            else -> 0.50f                         // Found but failed validation
        }
        val source = when {
            gstNumber == null -> "not_found"
            isValid -> "regex_validated"
            else -> "regex_unvalidated"
        }
        val label = confidenceLabel(score)

        Log.d(TAG, "Step 4: estimateGstConfidence | gst=$gstNumber, valid=$isValid, score=$score, label=$label")
        return FieldConfidence("gst", score, source, label)
    }

    /**
     * Estimates confidence for category classification.
     */
    fun estimateCategoryConfidence(
        categoryScore: Int,
        isMerchantOverride: Boolean
    ): FieldConfidence {
        val score = when {
            isMerchantOverride -> 0.95f
            categoryScore >= 10 -> 0.90f
            categoryScore >= 6 -> 0.75f
            categoryScore >= 4 -> 0.60f
            categoryScore >= 2 -> 0.40f
            else -> 0.20f
        }
        val source = if (isMerchantOverride) "merchant_override" else "keyword_score($categoryScore)"
        val label = confidenceLabel(score)

        Log.d(TAG, "Step 5: estimateCategoryConfidence | score=$categoryScore, override=$isMerchantOverride, confidence=$score, label=$label")
        return FieldConfidence("category", score, source, label)
    }

    /**
     * Computes the overall confidence report from individual field confidences.
     * Uses weighted averaging to produce a normalized 0–1 score.
     */
    fun computeOverallConfidence(fieldConfidences: List<FieldConfidence>): ConfidenceReport {
        Log.d(TAG, "Step 6: computeOverallConfidence | Computing from ${fieldConfidences.size} fields")

        // Weighted average
        var weightedSum = 0.0
        var weightTotal = 0.0
        val weakFields = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        for (fc in fieldConfidences) {
            val weight = FIELD_WEIGHTS[fc.fieldName] ?: 0.05
            weightedSum += fc.score * weight
            weightTotal += weight

            if (fc.score < CONFIRMATION_THRESHOLD) {
                weakFields.add(fc.fieldName)
                suggestions.add("Please verify ${fc.fieldName}: confidence ${(fc.score * 100).toInt()}% (${fc.source})")
            }
        }

        val overallScore = if (weightTotal > 0) (weightedSum / weightTotal).toFloat() else 0.0f
        val overallLabel = confidenceLabel(overallScore)
        val needsConfirmation = overallScore < CONFIRMATION_THRESHOLD || weakFields.isNotEmpty()

        Log.d(TAG, "Step 7: computeOverallConfidence | overall=%.4f, label=$overallLabel, needsConfirmation=$needsConfirmation".format(overallScore))
        Log.d(TAG, "Step 8: computeOverallConfidence | Weak fields: $weakFields")
        if (suggestions.isNotEmpty()) {
            Log.d(TAG, "Step 9: computeOverallConfidence | Suggestions: $suggestions")
        }

        return ConfidenceReport(
            overallScore = overallScore,
            overallLabel = overallLabel,
            needsUserConfirmation = needsConfirmation,
            fieldConfidences = fieldConfidences,
            weakFields = weakFields,
            suggestions = suggestions
        )
    }

    // =====================================================================
    // Utilities
    // =====================================================================

    private fun confidenceLabel(score: Float): String {
        return when {
            score >= 0.80 -> "High"
            score >= 0.55 -> "Medium"
            else -> "Low"
        }
    }
}
