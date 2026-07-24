package com.snapbudget.ocr.matching

import android.util.Log

/**
 * Merchant Matching Engine (Guide §4)
 *
 * Hybrid approach:
 *  1. Token-based normalization (clean + stopwords + sort)
 *  2. Jaro-Winkler similarity for final scoring
 *
 * Also handles fuzzy autocorrection of known Indian chains.
 */
object MerchantMatcher {

    private const val TAG = "MerchantMatcher"

    /** Threshold for accepting a merchant match (Guide §4.3: 0.88–0.92) */
    private const val MATCH_THRESHOLD = 0.88

    /**
     * Canonical merchant names → known OCR variations.
     * This list doubles as both a fuzzy-correction dictionary and
     * a canonical comparison store (Guide §4.4 step 4).
     */
    private val KNOWN_MERCHANTS: Map<String, List<String>> = mapOf(
        // Groceries
        "DMart" to listOf("d mart", "d-mart", "dmart", "d.mart", "d marr", "dm art"),
        "Big Bazaar" to listOf("big bazaar", "bigbazaar", "big bazar", "big baazar"),
        "Reliance Smart" to listOf("reliance smart", "reliancesmart", "smart point", "reliance fresh"),
        "Star Bazaar" to listOf("star bazaar", "starbazaar", "star bazar"),
        "More Supermarket" to listOf("more supermarket", "more store", "more mega"),
        "EasyDay" to listOf("easyday", "easy day", "easy-day"),
        "Spencer's" to listOf("spencers", "spencer", "spencer's"),
        "Nilgiris" to listOf("nilgiris", "nilgiri", "nelgiris"),
        "Heritage Fresh" to listOf("heritage fresh", "heritagefresh"),

        // Dining
        "Domino's" to listOf("domino", "doninos", "dominos", "domino's pizza", "dominos pizza"),
        "McDonald's" to listOf("mcdonald", "mc donald", "mcd", "mcdonalds", "mc donalds"),
        "KFC" to listOf("k f c", "kfc", "kentucky", "kentucky fried"),
        "Haldiram's" to listOf("haldiram", "haldirams", "haldiram's", "haldirams nagpur"),
        "Pizza Hut" to listOf("pizza hut", "pizzahut", "pizza hur"),
        "Starbucks" to listOf("starbuck", "starbucks", "star bucks"),
        "Burger King" to listOf("burger king", "burgerking", "bk", "burger klng"),
        "Subway" to listOf("subway", "sub way"),
        "Barbeque Nation" to listOf("barbeque nation", "barbeque", "bbq nation"),
        "Swiggy" to listOf("swiggy", "swigy"),
        "Zomato" to listOf("zomato", "zomatto"),
        "Blinkit" to listOf("blinkit", "blink it"),
        "Zepto" to listOf("zepto"),

        // Pune-specific
        "Chitale Bandhu" to listOf("chitale bandhu", "chitale", "chitlay", "chitaley"),
        "Vaishali" to listOf("vaishali", "vaishali restaurant"),
        "Roopali" to listOf("roopali", "rupali"),
        "Goodluck Cafe" to listOf("goodluck cafe", "goodluck", "good luck cafe"),
        "Yewale Amruttulya" to listOf("yewale", "yewale amruttulya", "amrutulya"),
        "Sujata Mastani" to listOf("sujata mastani", "sujata"),

        // Healthcare
        "Apollo Pharmacy" to listOf("apollo pharmacy", "apollo", "appolo pharmacy"),
        "MedPlus" to listOf("medplus", "med plus", "medpius"),
        "Wellness Forever" to listOf("wellness forever", "wellnessforever"),

        // Shopping
        "Zudio" to listOf("zudio", "zudlo"),
        "Westside" to listOf("westside", "west side"),
        "Pantaloons" to listOf("pantaloons", "pantaloon"),
        "Shoppers Stop" to listOf("shoppers stop", "shoppersstop"),

        // Entertainment
        "PVR" to listOf("pvr", "pvr cinemas", "pvr inox"),
        "Inox" to listOf("inox", "inox movies"),
        "BookMyShow" to listOf("bookmyshow", "book my show"),

        // Fuel
        "Indian Oil" to listOf("indian oil", "iocl", "indlan oil"),
        "Bharat Petroleum" to listOf("bharat petroleum", "bpcl", "bp", "bharat petrol"),
        "HPCL" to listOf("hpcl", "hindustan petroleum", "hp petrol"),

        // Transport
        "Ola" to listOf("ola", "ola cabs"),
        "Uber" to listOf("uber", "uber india"),

        // Utilities
        "MSEDCL" to listOf("msedcl", "msebd", "mahvitaran", "mahadiscom")
    )

    /**
     * Data class for merchant match result with confidence scoring (Guide §7).
     */
    data class MatchResult(
        val canonicalName: String,
        val score: Double,
        val threshold: Double,
        val confidenceLabel: String,
        val isMatch: Boolean,
        val matchSource: String // "exact_variation", "jaro_winkler_canonical", "jaro_winkler_variation"
    )

    /**
     * Matches a raw OCR merchant name against the known merchant database.
     *
     * Strategy (Guide §4.4):
     *  1. Normalize input (clean, remove stopwords, sort tokens)
     *  2. Check exact variation matches
     *  3. Compare against all canonical names with Jaro-Winkler
     *  4. Return highest match above threshold
     */
    fun matchMerchant(rawName: String): MatchResult {
        if (rawName.isBlank() || rawName == "Unknown") {
            Log.d(TAG, "Step 1: matchMerchant | Skipping blank/unknown name")
            return MatchResult(rawName, 0.0, MATCH_THRESHOLD, "Low", false, "none")
        }

        val normalizedInput = TextNormalizer.normalizeForMatching(rawName)
        val cleanedInput = TextNormalizer.cleanText(rawName)
        Log.d(TAG, "Step 2: matchMerchant | raw='$rawName' → normalized='$normalizedInput'")

        // Phase 1: Exact variation match (fastest path)
        for ((canonical, variations) in KNOWN_MERCHANTS) {
            for (variation in variations) {
                if (cleanedInput.contains(variation) || cleanedInput == variation) {
                    Log.d(TAG, "Step 3: matchMerchant | EXACT variation match: '$variation' → '$canonical'")
                    return MatchResult(canonical, 1.0, MATCH_THRESHOLD, "High", true, "exact_variation")
                }
            }
        }

        // Phase 2: Jaro-Winkler against canonical merchant names (Guide §4.4 step 5)
        var bestScore = 0.0
        var bestCanonical = rawName
        var bestSource = "none"

        for ((canonical, variations) in KNOWN_MERCHANTS) {
            // Compare normalized input against normalized canonical
            val normalizedCanonical = TextNormalizer.normalizeForMatching(canonical)
            val score = SimilarityScorer.jaroWinkler(normalizedInput, normalizedCanonical)
            if (score > bestScore) {
                bestScore = score
                bestCanonical = canonical
                bestSource = "jaro_winkler_canonical"
            }

            // Also compare against each variation (handles noisy OCR)
            for (variation in variations) {
                val normalizedVariation = TextNormalizer.normalizeForMatching(variation)
                val varScore = SimilarityScorer.jaroWinkler(normalizedInput, normalizedVariation)
                if (varScore > bestScore) {
                    bestScore = varScore
                    bestCanonical = canonical
                    bestSource = "jaro_winkler_variation"
                }
            }
        }

        val isMatch = bestScore >= MATCH_THRESHOLD
        val label = SimilarityScorer.confidenceLabel(bestScore, MATCH_THRESHOLD)

        Log.d(TAG, "Step 4: matchMerchant | Best: '$bestCanonical' (score=%.4f, threshold=%.2f, label=$label, matched=$isMatch, source=$bestSource)".format(bestScore, MATCH_THRESHOLD))

        return MatchResult(
            canonicalName = if (isMatch) bestCanonical else rawName,
            score = bestScore,
            threshold = MATCH_THRESHOLD,
            confidenceLabel = label,
            isMatch = isMatch,
            matchSource = bestSource
        )
    }

    /**
     * Quick correction — returns corrected name or original if no match.
     * Drop-in replacement for ReceiptParser.fuzzyCorrectMerchantName().
     */
    fun correctMerchantName(rawName: String): String {
        val result = matchMerchant(rawName)
        return result.canonicalName
    }
}
