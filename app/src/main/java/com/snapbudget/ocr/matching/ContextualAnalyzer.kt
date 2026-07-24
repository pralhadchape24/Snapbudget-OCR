package com.snapbudget.ocr.matching

import android.util.Log

/**
 * Contextual Window Analysis Engine (Concept §4)
 *
 * Evaluates numbers based on surrounding words using:
 *  - Sliding window keyword proximity scoring
 *  - N-gram keyword proximity scoring
 *
 * Lightweight NLP approach — no heavy transformer models.
 */
object ContextualAnalyzer {

   private const val TAG = "ContextualAnalyzer"

/** Window size: how many tokens before/after a number to consider */
private const val DEFAULT_WINDOW_SIZE = 4

// ===============================
// TOTAL BOOST KEYWORDS
// ===============================
private val TOTAL_BOOST_KEYWORDS = mapOf(

    // Core Total Words
    "total" to 10,
    "grand" to 9,
    "grandtotal" to 11,
    "g.total" to 10,
    "g total" to 10,
    "ttl" to 9,
    "tot" to 8,

    // Net / Final
    "net" to 8,
    "nettotal" to 10,
    "netamt" to 10,
    "netamount" to 10,
    "final" to 9,
    "finaltotal" to 11,
    "finalamt" to 10,
    "finalamount" to 11,

    // Payable
    "payable" to 10,
    "amountpayable" to 12,
    "totalpayable" to 12,
    "balancepayable" to 10,
    "amountdue" to 9,
    "dueamount" to 9,
    "topay" to 12,
    "to pay" to 12,
    "you pay" to 12,
    "please pay" to 10,
    "paythis" to 9,

    // Amount Variants
    "amount" to 7,
    "billamount" to 10,
    "billamt" to 10,
    "invoiceamount" to 9,
    "amt" to 8,
    "amt." to 8,
    "amnt" to 8,

    // Gross
    "gross" to 8,
    "grossamount" to 9,

    // Rounding (usually near final)
    "roundoff" to 6,
    "round off" to 6,
    "rounding" to 5,
    "rounded" to 5,
    "adjustment" to 5,

    // Currency Indicators (Indian context)
    "rs" to 6,
    "rs." to 6,
    "inr" to 6,
    "rupees" to 6,

    // Hindi (Romanized)
    "kul" to 11,
    "kulamount" to 12,
    "kulrashi" to 12,
    "rashi" to 7,
    "antim" to 9,
    "antimamount" to 12,
    "antimrashi" to 12,
    "dey" to 9,
    "deyrashi" to 12,
    "bhugtan" to 8,
    "bhugtanrashi" to 12,
    "shuddh" to 8,
    "shuddhrashi" to 11,
    "netrashi" to 11,

    // Marathi (Romanized)
    "ekun" to 12,
    "ekunamount" to 13,
    "ekunrakkam" to 14,
    "rakkam" to 8,
    "antimrakkam" to 13,
    "deyrakkam" to 13,
    "bharna" to 8,
    "shevatchi" to 10,
    "shevatchirakkam" to 14,

    // POS Shortcuts
    "grndttl" to 12,
    "ttlamt" to 12,
    "totalamt" to 12,
    "netpay" to 12
)

// ===============================
// TOTAL PENALTY KEYWORDS
// ===============================
private val TOTAL_PENALTY_KEYWORDS = mapOf(

    // Subtotals
    "subtotal" to -9,
    "sub total" to -9,
    "subttl" to -8,
    "sub" to -5,
    "interim" to -6,

    // Tender / Payment Section
    "tendered" to -15,
    "cashtendered" to -15,
    "cash" to -7,
    "card" to -7,
    "credit" to -7,
    "debit" to -7,
    "upi" to -8,
    "phonepe" to -8,
    "gpay" to -8,
    "googlepay" to -8,
    "paytm" to -8,
    "received" to -10,
    "paid" to -9,
    "change" to -15,
    "balancereturn" to -12,
    "return" to -9,
    "refund" to -12,
    "transaction" to -6,
    "txn" to -6,

    // Discounts / Offers
    "discount" to -12,
    "disc" to -9,
    "saving" to -12,
    "saved" to -12,
    "offer" to -8,
    "promo" to -8,
    "cashback" to -10,
    "rebate" to -9,
    "coupon" to -8,
    "loyalty" to -7,

    // GST / Taxes
    "cgst" to -7,
    "sgst" to -7,
    "igst" to -7,
    "gst" to -6,
    "gstincluded" to -15,
    "gstincl" to -12,
    "included" to -8,
    "inclusive" to -8,
    "tax" to -7,
    "vat" to -7,
    "cess" to -6,
    "servicetax" to -8,
    "servicecharge" to -8,
    "taxable" to -7,
    "roundoff" to -8,
    "rounding" to -6,

    // Item Level Noise
    "qty" to -7,
    "quantity" to -7,
    "rate" to -7,
    "mrp" to -7,
    "hsn" to -8,
    "sac" to -8,
    "item" to -5,
    "sr" to -4,
    "serial" to -4,
    "no" to -3,
    "description" to -5,
    "unitprice" to -7,
    "price" to -6,
    "each" to -6,
    "weight" to -6,
    "gm" to -6,
    "kg" to -6,
    "ltr" to -6
)

// ===============================
// MERCHANT BOOST KEYWORDS
// ===============================
private val MERCHANT_BOOST_KEYWORDS = mapOf(

    // Company Structure
    "pvt" to 7,
    "ltd" to 7,
    "limited" to 7,
    "privatelimited" to 9,
    "llp" to 7,
    "co." to 6,
    "company" to 6,

    // Retail Categories
    "store" to 6,
    "mart" to 6,
    "supermart" to 7,
    "supermarket" to 7,
    "hypermarket" to 7,
    "restaurant" to 7,
    "resto" to 6,
    "cafe" to 7,
    "hotel" to 7,
    "bar" to 6,
    "bakery" to 7,
    "sweets" to 7,
    "dairy" to 6,
    "medical" to 7,
    "pharmacy" to 7,
    "chemist" to 7,
    "provision" to 6,
    "enterprises" to 6,
    "traders" to 6,
    "agency" to 6,
    "industries" to 6,
    "corporation" to 6,
    "wholesale" to 6,
    "retail" to 6,

    // Common Indian Business Names
    "andsons" to 7,
    "sons" to 6,
    "brothers" to 6,
    "bros" to 6,
    "associates" to 6,
    "group" to 6
)

    /**
     * Analysis result for a numeric token in context.
     */
    data class ContextScore(
        val numericValue: Double,
        val contextScore: Int,
        val boostKeywords: List<String>,
        val penaltyKeywords: List<String>,
        val positionInReceipt: Double,  // 0.0 = top, 1.0 = bottom
        val windowTokens: List<String>
    )

    /**
     * Analyzes all numeric values in a tokenized receipt and scores each
     * based on surrounding keyword context.
     *
     * @param tokens  All tokens from the OCR text (split by whitespace)
     * @param windowSize  Number of tokens to look before/after each number
     * @return List of scored numeric candidates, sorted by score descending
     */
    fun scoreNumericCandidates(
        tokens: List<String>,
        windowSize: Int = DEFAULT_WINDOW_SIZE
    ): List<ContextScore> {
        Log.d(TAG, "Step 1: scoreNumericCandidates | ${tokens.size} tokens, windowSize=$windowSize")

        val results = mutableListOf<ContextScore>()
        val totalTokens = tokens.size

        for ((index, token) in tokens.withIndex()) {
            // Try to parse this token as a number
            val numericValue = parseNumericToken(token) ?: continue

            // Skip very small numbers (likely quantities) and very large (likely invoice/barcode)
            if (numericValue < 1.0 || numericValue > 10_000_000) continue

            // Build context window
            val windowStart = maxOf(0, index - windowSize)
            val windowEnd = minOf(totalTokens - 1, index + windowSize)
            val windowTokens = tokens.subList(windowStart, windowEnd + 1)
                .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
                .filter { it.isNotBlank() && it != token.lowercase() }

            // Score based on surrounding keywords
            var contextScore = 0
            val boosts = mutableListOf<String>()
            val penalties = mutableListOf<String>()

            for (windowToken in windowTokens) {
                // Check boost keywords
                TOTAL_BOOST_KEYWORDS[windowToken]?.let { points ->
                    contextScore += points
                    boosts.add("$windowToken(+$points)")
                }

                // Check penalty keywords
                TOTAL_PENALTY_KEYWORDS[windowToken]?.let { points ->
                    contextScore += points  // points are already negative
                    penalties.add("$windowToken($points)")
                }

                // Check multi-word ngrams (e.g., "grand total" → check adjacent pairs)
                val tokenIdx = windowTokens.indexOf(windowToken)
                if (tokenIdx < windowTokens.size - 1) {
                    val bigram = "$windowToken ${windowTokens[tokenIdx + 1]}"
                    when (bigram) {
                        "grand total" -> { contextScore += 10; boosts.add("'grand total'(+10)") }
                        "net amount" -> { contextScore += 8; boosts.add("'net amount'(+8)") }
                        "amount payable" -> { contextScore += 8; boosts.add("'amount payable'(+8)") }
                        "sub total", "subtotal" -> { contextScore -= 6; penalties.add("'sub total'(-6)") }
                        "cash tendered" -> { contextScore -= 12; penalties.add("'cash tendered'(-12)") }
                        "you saved", "you save" -> { contextScore -= 10; penalties.add("'you saved'(-10)") }
                    }
                }
            }

            // Position bonus: numbers in bottom 40% of receipt get a boost
            val positionRatio = index.toDouble() / totalTokens.toDouble()
            if (positionRatio > 0.6) {
                val posBonus = ((positionRatio - 0.6) * 10).toInt()
                contextScore += posBonus
                if (posBonus > 0) boosts.add("position(+$posBonus)")
            }

            // Currency symbol proximity bonus
            if (index > 0) {
                val prevToken = tokens[index - 1].lowercase()
                if (prevToken.contains("₹") || prevToken.contains("rs") || prevToken == "inr") {
                    contextScore += 5
                    boosts.add("currency_prefix(+5)")
                }
            }

            results.add(
                ContextScore(
                    numericValue = numericValue,
                    contextScore = contextScore,
                    boostKeywords = boosts,
                    penaltyKeywords = penalties,
                    positionInReceipt = positionRatio,
                    windowTokens = windowTokens
                )
            )
        }

        val sorted = results.sortedByDescending { it.contextScore }
        if (sorted.isNotEmpty()) {
            Log.d(TAG, "Step 2: scoreNumericCandidates | Top 3 candidates:")
            sorted.take(3).forEachIndexed { i, candidate ->
                Log.d(TAG, "  #${i+1}: value=${candidate.numericValue}, score=${candidate.contextScore}, " +
                        "boosts=${candidate.boostKeywords}, penalties=${candidate.penaltyKeywords}")
            }
        }

        return sorted
    }

    /**
     * Scores a specific line's context for merchant name likelihood.
     *
     * @param lineTokens  Tokens of the line being evaluated
     * @param surroundingTokens  Tokens from adjacent lines
     * @param positionRatio  Vertical position (0.0 = top, 1.0 = bottom)
     * @return Score (higher = more likely to be merchant name)
     */
    fun scoreMerchantContext(
        lineTokens: List<String>,
        surroundingTokens: List<String>,
        positionRatio: Double
    ): Int {
        var score = 0
        val allTokens = (lineTokens + surroundingTokens).map { it.lowercase() }

        // Boost from merchant-related keywords in context
        for (token in allTokens) {
            MERCHANT_BOOST_KEYWORDS[token]?.let { score += it }
        }

        // Position: top 15% gets strong boost, top 25% gets moderate
        when {
            positionRatio < 0.15 -> score += 10
            positionRatio < 0.25 -> score += 5
            positionRatio > 0.50 -> score -= 5  // Bottom half is unlikely merchant
        }

        // Font size heuristic: if line has many uppercase letters, likely a header/merchant
        val uppercaseRatio = lineTokens.joinToString("").count { it.isUpperCase() }.toDouble() /
                maxOf(1, lineTokens.joinToString("").length)
        if (uppercaseRatio > 0.6) score += 4

        // Penalize if line looks like an address or phone number
        val lineText = lineTokens.joinToString(" ").lowercase()
        if (lineText.matches(Regex(".*\\d{10,}.*"))) score -= 5  // Phone number
        if (lineText.matches(Regex(".*@.*\\..*"))) score -= 5    // Email
        if (lineText.contains("road") || lineText.contains("street") ||
            lineText.contains("nagar") || lineText.contains("lane") ||
            lineText.contains("colony") || lineText.contains("sector") ||
            lineText.contains("district") || lineText.contains("pincode") ||
            lineText.contains("pin code") || lineText.contains("avenue")) score -= 4
        // US-style: "City State Zipcode" (e.g. "Palo Alto California 94301")
        if (lineText.matches(Regex(".*[a-z]+\\s+\\d{5}(-\\d{4})?\\s*$"))) score -= 8
        // Comma-separated address components (e.g. "123, Main Road, City")
        if (lineText.count { it == ',' } >= 2 && lineText.any { it.isDigit() }) score -= 4
        // Line starts with "Address" or "Adress"
        if (lineText.matches(Regex("^a?dre?ss?:.*"))) score -= 8

        Log.d(TAG, "Step 3: scoreMerchantContext | score=$score, posRatio=%.2f, tokens=${lineTokens.take(5)}".format(positionRatio))
        return score
    }

    /**
     * Parses a token as a numeric value, handling commas, currency symbols, etc.
     * Returns null if not parseable.
     */
    private fun parseNumericToken(token: String): Double? {
        val cleaned = token
            .replace(",", "")
            .replace("₹", "")
            .replace(Regex("(?i)^(rs\\.?|inr)"), "")
            .replace(Regex("[^0-9.]"), "")
            .trim()

        if (cleaned.isEmpty()) return null
        if (cleaned.count { it == '.' } > 1) return null  // Multiple decimals

        return cleaned.toDoubleOrNull()
    }
}
