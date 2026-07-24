package com.snapbudget.ocr.matching

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Centralized similarity scoring engine (Guide §4, §5, §6).
 *
 * Provides:
 *  - Jaro-Winkler similarity (primary — Guide §4.2)
 *  - Levenshtein distance (utility)
 *  - Normalized Levenshtein similarity
 *
 * All similarity functions in the app should delegate here to avoid duplication.
 */
object SimilarityScorer {

    private const val TAG = "SimilarityScorer"

    // ============================================================
    // Jaro-Winkler Similarity — Guide §4.2
    // Strong for short strings, rewards prefix similarity
    // ============================================================

    /**
     * Calculates Jaro-Winkler similarity between two strings.
     * @return value in [0.0, 1.0] where 1.0 = identical
     */
    fun jaroWinkler(s1: String, s2: String, prefixScale: Double = 0.1): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val jaro = jaroSimilarity(s1, s2)

        // Count common prefix up to 4 characters
        val prefixLength = min(4, min(s1.length, s2.length))
        var commonPrefix = 0
        for (i in 0 until prefixLength) {
            if (s1[i] == s2[i]) commonPrefix++ else break
        }

        val result = jaro + commonPrefix * prefixScale * (1.0 - jaro)
        Log.d(TAG, "jaroWinkler('$s1', '$s2') = %.4f (jaro=%.4f, prefix=$commonPrefix)".format(result, jaro))
        return result
    }

    /**
     * Calculates Jaro similarity (base for Jaro-Winkler).
     */
    private fun jaroSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0

        val maxLen = max(s1.length, s2.length)
        val matchDistance = max(maxLen / 2 - 1, 0)

        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        var transpositions = 0

        // Find matches
        for (i in s1.indices) {
            val start = max(0, i - matchDistance)
            val end = min(i + matchDistance + 1, s2.length)

            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        // Count transpositions
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        return (matches.toDouble() / s1.length +
                matches.toDouble() / s2.length +
                (matches - transpositions / 2.0) / matches) / 3.0
    }

    // ============================================================
    // Levenshtein Distance & Similarity
    // ============================================================

    /**
     * Calculates Levenshtein edit distance between two strings.
     * Uses space-optimized single-row DP.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val costs = IntArray(s2.length + 1) { it }

        for (i in 1..s1.length) {
            var lastValue = i
            for (j in 1..s2.length) {
                val newValue = if (s1[i - 1] == s2[j - 1]) {
                    costs[j - 1]
                } else {
                    1 + minOf(costs[j - 1], lastValue, costs[j])
                }
                costs[j - 1] = lastValue
                lastValue = newValue
            }
            costs[s2.length] = lastValue
        }

        return costs[s2.length]
    }

    /**
     * Normalized Levenshtein similarity in [0.0, 1.0].
     * 1.0 = identical, 0.0 = completely dissimilar.
     */
    fun levenshteinSimilarity(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1 else s2
        if (longer.isEmpty()) return 1.0
        val distance = levenshteinDistance(s1, s2)
        return (longer.length - distance) / longer.length.toDouble()
    }

    // ============================================================
    // Confidence Labeling — Guide §7
    // ============================================================

    /**
     * Returns a human-readable confidence label.
     */
    fun confidenceLabel(score: Double, threshold: Double): String {
        return when {
            score >= threshold + 0.05 -> "High"
            score >= threshold -> "Medium"
            else -> "Low"
        }
    }
}
