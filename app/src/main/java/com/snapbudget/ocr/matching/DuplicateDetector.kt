package com.snapbudget.ocr.matching

import android.util.Log
import com.snapbudget.ocr.data.model.Transaction
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Duplicate Detection Engine (Guide §6)
 *
 * Uses a weighted scoring model:
 *   Duplicate Score = 0.5 × Merchant Similarity
 *                   + 0.3 × Amount Match
 *                   + 0.2 × Date Match
 *
 * Threshold: 0.90 (Guide §6.2)
 */
object DuplicateDetector {

    private const val TAG = "DuplicateDetector"

    /** Threshold above which a transaction pair is flagged as duplicate (Guide §6.2) */
    private const val DUPLICATE_THRESHOLD = 0.90

    // Weights (Guide §6.1)
    private const val WEIGHT_MERCHANT = 0.5
    private const val WEIGHT_AMOUNT = 0.3
    private const val WEIGHT_DATE = 0.2

    /** Date tolerance: exact or ±1 day (Guide §6.3) */
    private const val DATE_TOLERANCE_DAYS = 1L

    /**
     * Result of a duplicate check for a single pair.
     */
    data class DuplicateCheckResult(
        val isDuplicate: Boolean,
        val score: Double,
        val threshold: Double,
        val merchantScore: Double,
        val amountScore: Double,
        val dateScore: Double,
        val confidenceLabel: String,
        val existingTransactionId: Long
    )

    /**
     * Checks if a new transaction is a duplicate of any in the existing list.
     *
     * @param newMerchantName  Merchant name from the newly scanned receipt
     * @param newAmount        Total amount from the newly scanned receipt
     * @param newDate          Date from the newly scanned receipt
     * @param existingTransactions  List of previously stored transactions to compare against
     * @return The best (highest scoring) duplicate result, or null if no potential duplicate found
     */
    fun checkForDuplicate(
        newMerchantName: String,
        newAmount: Double,
        newDate: Date,
        existingTransactions: List<Transaction>
    ): DuplicateCheckResult? {
        Log.d(TAG, "Step 1: checkForDuplicate | merchant='$newMerchantName', amount=$newAmount, date=$newDate, existingCount=${existingTransactions.size}")

        if (existingTransactions.isEmpty()) {
            Log.d(TAG, "Step 2: checkForDuplicate | No existing transactions to compare against")
            return null
        }

        val normalizedNewMerchant = TextNormalizer.normalizeForMatching(newMerchantName)
        var bestResult: DuplicateCheckResult? = null

        for (existing in existingTransactions) {
            val result = comparePair(
                normalizedNewMerchant, newAmount, newDate,
                existing.merchantName, existing.amount, existing.date, existing.id
            )

            if (bestResult == null || result.score > bestResult.score) {
                bestResult = result
            }
        }

        if (bestResult != null) {
            Log.d(TAG, "Step 3: checkForDuplicate | Best match: id=${bestResult.existingTransactionId}, " +
                    "score=%.4f (merchant=%.4f, amount=%.4f, date=%.4f), isDuplicate=${bestResult.isDuplicate}"
                        .format(bestResult.score, bestResult.merchantScore, bestResult.amountScore, bestResult.dateScore))
        }

        // Only return result if it's actually a plausible duplicate (score > 0.5 at least)
        return if (bestResult != null && bestResult.score > 0.5) bestResult else null
    }

    /**
     * Compares a single pair of transactions.
     */
    private fun comparePair(
        normalizedNewMerchant: String,
        newAmount: Double,
        newDate: Date,
        existingMerchantName: String,
        existingAmount: Double,
        existingDate: Date,
        existingId: Long
    ): DuplicateCheckResult {
        // 1. Merchant similarity (fuzzy match — Guide §6.3)
        val normalizedExistingMerchant = TextNormalizer.normalizeForMatching(existingMerchantName)
        val merchantScore = SimilarityScorer.jaroWinkler(normalizedNewMerchant, normalizedExistingMerchant)

        // 2. Amount match (exact — Guide §6.3)
        val amountScore = if (newAmount > 0 && existingAmount > 0 && newAmount == existingAmount) 1.0 else 0.0

        // 3. Date match (exact or ±1 day — Guide §6.3)
        val daysDiff = abs(TimeUnit.MILLISECONDS.toDays(newDate.time - existingDate.time))
        val dateScore = when {
            daysDiff == 0L -> 1.0
            daysDiff <= DATE_TOLERANCE_DAYS -> 0.8
            daysDiff <= 3L -> 0.3
            else -> 0.0
        }

        // Weighted score (Guide §6.1)
        val totalScore = WEIGHT_MERCHANT * merchantScore +
                WEIGHT_AMOUNT * amountScore +
                WEIGHT_DATE * dateScore

        val isDuplicate = totalScore >= DUPLICATE_THRESHOLD
        val label = SimilarityScorer.confidenceLabel(totalScore, DUPLICATE_THRESHOLD)

        return DuplicateCheckResult(
            isDuplicate = isDuplicate,
            score = totalScore,
            threshold = DUPLICATE_THRESHOLD,
            merchantScore = merchantScore,
            amountScore = amountScore,
            dateScore = dateScore,
            confidenceLabel = label,
            existingTransactionId = existingId
        )
    }
}
