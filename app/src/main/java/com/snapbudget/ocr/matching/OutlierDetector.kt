package com.snapbudget.ocr.matching

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Outlier / Anomaly Detection Engine (Concept §10)
 *
 * Rejects unrealistic totals using:
 *  - Statistical rule-based validation
 *  - Z-score outlier detection
 *  - Receipt-type aware thresholds
 *  - Cross-field consistency checks
 *
 * Lightweight statistical approach suitable for Android.
 */
object OutlierDetector {

    private const val TAG = "OutlierDetector"

    /**
     * Typical spending ranges per receipt type (in INR).
     * Based on Indian retail patterns.
     */
    private val TYPICAL_RANGES: Map<ReceiptTypeClassifier.ReceiptType, Pair<Double, Double>> = mapOf(
        ReceiptTypeClassifier.ReceiptType.RESTAURANT to (50.0 to 15_000.0),
        ReceiptTypeClassifier.ReceiptType.GROCERY to (20.0 to 25_000.0),
        ReceiptTypeClassifier.ReceiptType.RETAIL to (100.0 to 200_000.0),
        ReceiptTypeClassifier.ReceiptType.FUEL to (100.0 to 15_000.0),
        ReceiptTypeClassifier.ReceiptType.MEDICAL to (10.0 to 50_000.0),
        ReceiptTypeClassifier.ReceiptType.UTILITY to (50.0 to 50_000.0),
        ReceiptTypeClassifier.ReceiptType.ENTERTAINMENT to (50.0 to 10_000.0),
        ReceiptTypeClassifier.ReceiptType.TRANSPORT to (10.0 to 10_000.0),
        ReceiptTypeClassifier.ReceiptType.UNKNOWN to (1.0 to 500_000.0)
    )

    /**
     * Result of anomaly detection check.
     */
    data class AnomalyResult(
        val isAnomaly: Boolean,
        val anomalyScore: Double,      // 0.0 = normal, higher = more anomalous
        val issues: List<String>,
        val suggestedAction: String,   // "accept", "warn", "reject"
        val confidenceLabel: String
    )

    /**
     * Checks if a total amount is an outlier/anomaly.
     *
     * Uses multiple validation layers:
     *  1. Hard bounds check (≤0 or > 10M)
     *  2. Receipt-type-aware range check
     *  3. Z-score against user history (if provided)
     *  4. Digit pattern validation
     */
    fun checkAmount(
        amount: Double,
        receiptType: ReceiptTypeClassifier.ReceiptType = ReceiptTypeClassifier.ReceiptType.UNKNOWN,
        userHistoryAmounts: List<Double> = emptyList()
    ): AnomalyResult {
        Log.d(TAG, "Step 1: checkAmount | amount=$amount, type=${receiptType.displayName}, historySize=${userHistoryAmounts.size}")

        val issues = mutableListOf<String>()
        var anomalyScore = 0.0

        // Layer 1: Hard bounds
        if (amount <= 0) {
            Log.d(TAG, "Step 2: checkAmount | REJECT: amount ≤ 0")
            return AnomalyResult(true, 1.0, listOf("Amount is zero or negative"), "reject", "High")
        }
        if (amount > 10_000_000) {
            Log.d(TAG, "Step 2: checkAmount | REJECT: amount > ₹1 crore")
            return AnomalyResult(true, 1.0, listOf("Amount exceeds ₹1 crore — likely OCR error"), "reject", "High")
        }

        // Layer 2: Receipt-type-aware range check
        val (minExpected, maxExpected) = TYPICAL_RANGES[receiptType]
            ?: TYPICAL_RANGES[ReceiptTypeClassifier.ReceiptType.UNKNOWN]!!

        if (amount < minExpected * 0.5) {
            anomalyScore += 0.3
            issues.add("Amount ₹%.2f is unusually low for ${receiptType.displayName} (expected ≥₹%.0f)".format(amount, minExpected))
        }
        if (amount > maxExpected * 1.5) {
            anomalyScore += 0.5
            issues.add("Amount ₹%.2f is unusually high for ${receiptType.displayName} (expected ≤₹%.0f)".format(amount, maxExpected))
        }

        // Layer 3: Z-score against user history
        if (userHistoryAmounts.size >= 5) {
            val zScore = calculateZScore(amount, userHistoryAmounts)
            Log.d(TAG, "Step 3: checkAmount | Z-score = %.4f".format(zScore))

            if (abs(zScore) > 3.0) {
                anomalyScore += 0.4
                issues.add("Amount is %.1f standard deviations from your average (Z=%.2f)".format(zScore, zScore))
            } else if (abs(zScore) > 2.0) {
                anomalyScore += 0.2
                issues.add("Amount is somewhat unusual compared to your history (Z=%.2f)".format(zScore))
            }
        }

        // Layer 4: Digit pattern validation
        val amountStr = "%.2f".format(amount)
        if (amountStr.length > 10) {
            anomalyScore += 0.3
            issues.add("Amount has too many digits — possible OCR concatenation error")
        }

        // Round numbers check: exactly round amounts (like 10000.00) are less likely to be OCR errors
        val isRound = amount == amount.toLong().toDouble()
        if (isRound && amount > 10000) {
            // Round large numbers could be legitimate or OCR errors — mild flag
            anomalyScore += 0.1
            issues.add("Exact round amount ₹%.0f — verify if correct".format(amount))
        }

        // Determine action
        val (isAnomaly, action, label) = when {
            anomalyScore >= 0.7 -> Triple(true, "reject", "High")
            anomalyScore >= 0.4 -> Triple(true, "warn", "Medium")
            anomalyScore >= 0.2 -> Triple(false, "warn", "Low")
            else -> Triple(false, "accept", "Low")
        }

        Log.d(TAG, "Step 4: checkAmount | anomalyScore=%.4f, isAnomaly=$isAnomaly, action=$action, issues=${issues.size}".format(anomalyScore))

        return AnomalyResult(
            isAnomaly = isAnomaly,
            anomalyScore = anomalyScore,
            issues = issues,
            suggestedAction = action,
            confidenceLabel = label
        )
    }

    /**
     * Validates numeric consistency: total ≈ sum(items) + tax (Concept §5)
     *
     * @param total         Extracted total amount
     * @param lineItemsSum  Sum of individual line items
     * @param taxAmount     Extracted tax amount (CGST+SGST or total tax)
     * @param discountAmount Extracted discount amount
     */
    fun validateConsistency(
        total: Double,
        lineItemsSum: Double,
        taxAmount: Double = 0.0,
        discountAmount: Double = 0.0
    ): AnomalyResult {
        Log.d(TAG, "Step 1: validateConsistency | total=$total, items=$lineItemsSum, tax=$taxAmount, discount=$discountAmount")

        val issues = mutableListOf<String>()
        var anomalyScore = 0.0

        if (lineItemsSum <= 0 || total <= 0) {
            Log.d(TAG, "Step 2: validateConsistency | Insufficient data for consistency check")
            return AnomalyResult(false, 0.0, emptyList(), "accept", "Low")
        }

        // Expected total = items + tax - discount
        val expectedTotal = lineItemsSum + taxAmount - discountAmount
        val difference = abs(total - expectedTotal)
        val percentDiff = (difference / total) * 100

        Log.d(TAG, "Step 2: validateConsistency | expected=$expectedTotal, actual=$total, diff=%.2f (%.1f%%)".format(difference, percentDiff))

        when {
            percentDiff < 1.0 -> {
                // Perfect match
                Log.d(TAG, "Step 3: validateConsistency | Perfect consistency match")
            }
            percentDiff < 5.0 -> {
                // Minor discrepancy — likely rounding
                anomalyScore += 0.1
                issues.add("Minor rounding difference: ₹%.2f (%.1f%%)".format(difference, percentDiff))
            }
            percentDiff < 20.0 -> {
                // Moderate discrepancy — could be missing items or tax
                anomalyScore += 0.3
                issues.add("Moderate discrepancy between items sum and total: ₹%.2f (%.1f%%)".format(difference, percentDiff))
            }
            else -> {
                // Large discrepancy — likely extraction error
                anomalyScore += 0.6
                issues.add("Large discrepancy: items sum ₹%.2f vs total ₹%.2f (%.1f%% off)".format(lineItemsSum, total, percentDiff))
            }
        }

        // Additional check: if items sum > total (without considering tax/discount), might be wrong
        if (lineItemsSum > total * 1.1 && taxAmount == 0.0) {
            anomalyScore += 0.2
            issues.add("Line items sum exceeds total — possible extraction error")
        }

        val (isAnomaly, action, label) = when {
            anomalyScore >= 0.5 -> Triple(true, "warn", "High")
            anomalyScore >= 0.2 -> Triple(false, "warn", "Medium")
            else -> Triple(false, "accept", "Low")
        }

        Log.d(TAG, "Step 3: validateConsistency | anomalyScore=%.4f, action=$action".format(anomalyScore))

        return AnomalyResult(
            isAnomaly = isAnomaly,
            anomalyScore = anomalyScore,
            issues = issues,
            suggestedAction = action,
            confidenceLabel = label
        )
    }

    // =====================================================================
    // Statistical Utilities
    // =====================================================================

    /**
     * Calculates Z-score for a value against a sample of historical values.
     * Z = (x - mean) / stddev
     */
    private fun calculateZScore(value: Double, history: List<Double>): Double {
        if (history.isEmpty()) return 0.0

        val mean = history.average()
        val variance = history.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)

        if (stdDev == 0.0) return 0.0
        return (value - mean) / stdDev
    }
}
