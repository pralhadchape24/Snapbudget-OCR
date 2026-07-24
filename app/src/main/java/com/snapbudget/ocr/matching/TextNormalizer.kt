package com.snapbudget.ocr.matching

import android.util.Log

/**
 * Text Cleaning & Normalization Layer (Guide §3)
 *
 * Responsible for:
 *  - Lowercase conversion
 *  - Punctuation removal
 *  - Long numeric string removal (GST, invoice numbers)
 *  - Special symbol replacement (& → and)
 *  - Extra whitespace normalization
 *  - Indian retail stopword removal
 *  - Token-based normalization for matching
 */
object TextNormalizer {

    private const val TAG = "TextNormalizer"

    // Indian retail stopwords that reduce matching precision (Guide §3.2)
    private val STOPWORDS = setOf(
        "pvt", "ltd", "private", "limited", "supermarket", "mart",
        "enterprises", "traders", "store", "stores", "shop", "shops",
        "industries", "corporation", "corp", "inc", "co", "llp",
        "retail", "retails", "and", "the", "of", "india"
    )

    /**
     * Full cleaning pipeline: lowercase → punctuation → numeric → symbols → whitespace.
     * Returns a normalized string suitable for display or comparison.
     */
    fun cleanText(raw: String): String {
        Log.d(TAG, "Step 1: cleanText | input='$raw'")

        var text = raw.lowercase().trim()

        // Replace special symbols
        text = text.replace("&", " and ")
            .replace("@", " at ")
            .replace("#", "")

        // Remove punctuation except spaces and alphanumerics
        text = text.replace(Regex("[^a-z0-9\\s]"), " ")

        // Remove long numeric strings (GST, invoice, phone numbers: 6+ digits)
        text = text.replace(Regex("\\b\\d{6,}\\b"), "")

        // Collapse multiple spaces
        text = text.replace(Regex("\\s+"), " ").trim()

        Log.d(TAG, "Step 2: cleanText | output='$text'")
        return text
    }

    /**
     * Removes Indian retail stopwords from cleaned text.
     * Returns the meaningful tokens only.
     */
    fun removeStopwords(cleanedText: String): String {
        val tokens = cleanedText.split(" ").filter { it.isNotBlank() && it !in STOPWORDS }
        val result = tokens.joinToString(" ")
        Log.d(TAG, "Step 3: removeStopwords | input='$cleanedText' → output='$result'")
        return result
    }

    /**
     * Full normalization for matching: clean → remove stopwords → sort tokens.
     * Token sorting ensures "star bazaar" == "bazaar star" during comparison.
     */
    fun normalizeForMatching(raw: String): String {
        val cleaned = cleanText(raw)
        val withoutStopwords = removeStopwords(cleaned)
        val sorted = withoutStopwords.split(" ").filter { it.isNotBlank() }.sorted().joinToString(" ")
        Log.d(TAG, "Step 4: normalizeForMatching | '$raw' → '$sorted'")
        return sorted
    }

    /**
     * Quick clean for keyword-level comparison (no stopword removal or sorting).
     */
    fun normalizeToken(token: String): String {
        return token.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
    }
}
