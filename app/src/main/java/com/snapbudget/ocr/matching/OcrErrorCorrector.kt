package com.snapbudget.ocr.matching

import android.util.Log

/**
 * OCR Error Correction — Numeric Correction Layer (Concept §7)
 *
 * Fixes common OCR misreads in receipt text:
 *  - O ↔ 0, 8 ↔ B, 5 ↔ S, 1 ↔ I/l, 2 ↔ Z, 6 ↔ G
 *
 * Uses context-aware rules:
 *  - In numeric contexts (amounts, quantities): letters → digits
 *  - In alpha contexts (names, GST letters): digits → letters
 *  - Rule-based character substitution with numeric pattern validation
 */
object OcrErrorCorrector {

    private const val TAG = "OcrErrorCorrector"

    // Character confusion pairs: char → likely intended char in numeric context
    private val LETTER_TO_DIGIT = mapOf(
        'O' to '0', 'o' to '0',
        'I' to '1', 'l' to '1', 'i' to '1',
        'S' to '5', 's' to '5',
        'B' to '8', 'b' to '8',
        'Z' to '2', 'z' to '2',
        'G' to '6', 'g' to '6',
        'T' to '7',  // rare but happens
        'D' to '0'   // D/0 confusion on thermal prints
    )

    // Reverse: digit → likely intended letter
    private val DIGIT_TO_LETTER = mapOf(
        '0' to 'O',
        '1' to 'I',
        '5' to 'S',
        '8' to 'B',
        '2' to 'Z',
        '6' to 'G'
    )

    /**
     * Data class for correction results with tracking.
     */
    data class CorrectionResult(
        val original: String,
        val corrected: String,
        val corrections: List<String>,  // List of "pos X: 'a'→'b'" descriptions
        val wasModified: Boolean
    )

    // =====================================================================
    // Numeric Context Correction — for amounts, prices, quantities
    // =====================================================================

    /**
     * Corrects a string that should be numeric (amount, price, etc.).
     * Letters that look like digits are replaced with their digit equivalents.
     * Preserves decimal points and commas.
     */
    fun correctNumericString(raw: String): CorrectionResult {
        val corrections = mutableListOf<String>()
        val sb = StringBuilder()

        for ((i, ch) in raw.withIndex()) {
            when {
                ch.isDigit() || ch == '.' || ch == ',' || ch == '-' || ch == ' ' -> sb.append(ch)
                LETTER_TO_DIGIT.containsKey(ch) -> {
                    val replacement = LETTER_TO_DIGIT[ch]!!
                    corrections.add("pos $i: '$ch'→'$replacement'")
                    sb.append(replacement)
                }
                else -> sb.append(ch) // Keep unknown chars as-is
            }
        }

        val result = CorrectionResult(raw, sb.toString(), corrections, corrections.isNotEmpty())
        if (corrections.isNotEmpty()) {
            Log.d(TAG, "Step 1: correctNumericString | '$raw' → '${sb}' | corrections=$corrections")
        }
        return result
    }

    /**
     * Corrects a full amount string including currency symbols.
     * Strips currency prefixes (₹, Rs, INR), fixes OCR errors, returns clean numeric.
     */
    fun correctAmountString(raw: String): CorrectionResult {
        // Strip currency symbols first
        var cleaned = raw.trim()
            .replace(Regex("(?i)^(rs\\.?|₹|inr)\\s*"), "")
            .trim()

        val numResult = correctNumericString(cleaned)

        // Additional: fix "." at wrong position (e.g., "12.3" → keep, "1.234" → likely 1234)
        val finalValue = numResult.corrected
            .replace(",", "")  // Remove thousand separators
            .trim()

        return CorrectionResult(raw, finalValue, numResult.corrections, numResult.wasModified)
    }

    // =====================================================================
    // Alpha Context Correction — for merchant names, GST letters
    // =====================================================================

    /**
     * Corrects a string that should be alphabetic (merchant name segment).
     * Digits that look like letters are replaced with their letter equivalents.
     */
    fun correctAlphaString(raw: String): CorrectionResult {
        val corrections = mutableListOf<String>()
        val sb = StringBuilder()

        for ((i, ch) in raw.withIndex()) {
            when {
                ch.isLetter() || ch == ' ' || ch == '.' || ch == '\'' || ch == '-' -> sb.append(ch)
                DIGIT_TO_LETTER.containsKey(ch) -> {
                    val replacement = DIGIT_TO_LETTER[ch]!!
                    corrections.add("pos $i: '$ch'→'$replacement'")
                    sb.append(replacement)
                }
                else -> sb.append(ch)
            }
        }

        val result = CorrectionResult(raw, sb.toString(), corrections, corrections.isNotEmpty())
        if (corrections.isNotEmpty()) {
            Log.d(TAG, "Step 2: correctAlphaString | '$raw' → '${sb}' | corrections=$corrections")
        }
        return result
    }

    // =====================================================================
    // Context-Aware Full Line Correction
    // =====================================================================

    /**
     * Corrects an entire OCR line using context clues.
     * Segments the line into tokens and applies the right correction for each.
     */
    fun correctLine(line: String): CorrectionResult {
        val tokens = line.split(Regex("\\s+"))
        val allCorrections = mutableListOf<String>()
        val correctedTokens = mutableListOf<String>()

        for (token in tokens) {
            val digitCount = token.count { it.isDigit() || LETTER_TO_DIGIT.containsKey(it) && !it.isLetter() }
            val letterCount = token.count { it.isLetter() }
            val hasDecimal = token.contains('.') || token.contains(',')

            val corrected = when {
                // Looks like a number (majority digits, or has decimal/comma)
                (digitCount > letterCount && digitCount > 0) || (hasDecimal && digitCount >= 1) -> {
                    val r = correctNumericString(token)
                    allCorrections.addAll(r.corrections.map { "[$token] $it" })
                    r.corrected
                }
                // Looks like text (majority letters)
                letterCount > digitCount && letterCount > 0 -> {
                    val r = correctAlphaString(token)
                    allCorrections.addAll(r.corrections.map { "[$token] $it" })
                    r.corrected
                }
                else -> token
            }
            correctedTokens.add(corrected)
        }

        val result = correctedTokens.joinToString(" ")
        if (allCorrections.isNotEmpty()) {
            Log.d(TAG, "Step 3: correctLine | '$line' → '$result' | ${allCorrections.size} corrections")
        }
        return CorrectionResult(line, result, allCorrections, allCorrections.isNotEmpty())
    }

    // =====================================================================
    // Numeric Pattern Validation
    // =====================================================================

    /**
     * Validates that a corrected numeric string is a plausible amount.
     * Returns true if valid, false if correction likely introduced errors.
     */
    fun isPlausibleAmount(corrected: String): Boolean {
        val cleaned = corrected.replace(",", "").replace(" ", "").trim()
        val value = cleaned.toDoubleOrNull() ?: return false

        return when {
            value <= 0 -> false
            value > 10_000_000 -> false  // Amounts > 1 crore are suspicious on receipts
            // Check for reasonable decimal places (max 2)
            cleaned.contains(".") && cleaned.substringAfter(".").length > 2 -> false
            else -> true
        }
    }

    /**
     * Validates that a corrected string could be a valid Indian phone number.
     */
    fun isPlausiblePhoneNumber(corrected: String): Boolean {
        val digits = corrected.filter { it.isDigit() }
        return digits.length == 10 && digits[0] in '6'..'9'
    }
}
