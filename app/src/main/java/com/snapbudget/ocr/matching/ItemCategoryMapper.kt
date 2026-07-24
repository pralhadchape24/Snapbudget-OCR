package com.snapbudget.ocr.matching

import android.util.Log

/**
 * Line Item Category Mapping Engine (Guide §5)
 *
 * Hybrid keyword + fuzzy model:
 *  Step 1: Exact keyword match against structured dictionary
 *  Step 2: Fuzzy match against dictionary keys if no exact match
 *
 * Produces token-level comparisons per Guide §5.2.
 */
object ItemCategoryMapper {

    private const val TAG = "ItemCategoryMapper"

    /** Threshold for fuzzy keyword match acceptance (Guide §5.1: 0.80–0.85) */
    private const val FUZZY_THRESHOLD = 0.82

    /**
     * Structured keyword dictionary for India-specific items.
     * keyword → (category, weight)
     */
    private val KEYWORD_DICTIONARY: Map<String, Pair<String, Int>> = buildMap {
        // Dairy
        listOf("milk", "dahi", "curd", "paneer", "ghee", "butter", "cheese", "lassi", "amul", "cream")
            .forEach { put(it, "GROCERY" to 4) }

        // Staples
        listOf("atta", "dal", "rice", "besan", "maida", "suji", "flour", "grain", "wheat", "jowar", "bajra", "ragi")
            .forEach { put(it, "GROCERY" to 4) }

        // Vegetables (Marathi/Hindi)
        listOf("kanda", "batata", "tamatar", "mirchi", "palak", "methi", "bhindi", "brinjal",
            "onion", "potato", "tomato", "vegetable", "sabzi", "bhaji")
            .forEach { put(it, "GROCERY" to 5) }

        // Grocery general
        listOf("kirana", "supermarket", "biscuit", "soap", "shampoo", "detergent", "oil",
            "namkeen", "chips", "noodles", "maggi", "tea", "sugar", "salt", "spice", "masala")
            .forEach { put(it, "GROCERY" to 3) }
        put("kg", "GROCERY" to 1)
        put("weight", "GROCERY" to 1)

        // Dining
        listOf("restaurant", "dine in", "takeaway", "parcel", "thali", "misal", "pav",
            "dosa", "idli", "biryani", "pizza", "burger", "naan", "roti", "curry",
            "meal", "lunch", "dinner", "breakfast", "snack")
            .forEach { put(it, "FOOD" to 5) }
        listOf("table no", "fssai", "waiter", "order no", "dine", "amrutulya", "cafe",
            "bistro", "canteen", "mess", "dhaba", "hotel")
            .forEach { put(it, "FOOD" to 6) }
        put("cgst restaurant", "FOOD" to 7)

        // Shopping
        listOf("apparel", "garments", "saree", "kurti", "jeans", "silk", "lace",
            "boutique", "tailor", "shoes", "shirt", "trouser", "dress", "fabric")
            .forEach { put(it, "SHOPPING" to 5) }
        listOf("mens", "womens", "kids", "fashion", "clothing")
            .forEach { put(it, "SHOPPING" to 3) }

        // Healthcare
        listOf("pharmacy", "chemist", "clinic", "hospital", "rx", "ointment",
            "paracetamol", "syrup", "tablet", "capsule", "injection")
            .forEach { put(it, "HEALTH" to 6) }
        listOf("dr", "expiry", "exp date", "batch no", "medicine", "medical", "diagnostic")
            .forEach { put(it, "HEALTH" to 4) }

        // Fuel
        listOf("petrol", "diesel", "cng", "fuel")
            .forEach { put(it, "TRAVEL" to 7) }
        listOf("pump", "litres", "ltr", "volume", "density", "nozzle")
            .forEach { put(it, "TRAVEL" to 5) }

        // Entertainment
        listOf("ticket", "screen", "seat", "showtime", "multiplex", "popcorn", "cinema",
            "movie", "theatre", "theater")
            .forEach { put(it, "ENTERTAINMENT" to 6) }

        // Transport
        listOf("rickshaw", "cab", "toll", "fastag", "driver", "bus", "metro")
            .forEach { put(it, "TRAVEL" to 5) }
        listOf("auto", "trip")
            .forEach { put(it, "TRAVEL" to 3) }

        // Utilities
        listOf("electricity", "water bill", "broadband", "recharge", "consumer no",
            "meter no", "tariff", "bijli", "lpg", "cylinder", "wifi")
            .forEach { put(it, "UTILITIES" to 6) }

        // Education
        listOf("school", "college", "university", "institute", "academy", "coaching",
            "tuition", "course", "exam", "fee", "book", "stationery")
            .forEach { put(it, "EDUCATION" to 5) }
    }

    /**
     * Data class for category match result with confidence (Guide §7).
     */
    data class CategoryMatchResult(
        val category: String,
        val totalScore: Int,
        val matchedKeywords: List<String>,
        val scoreBreakdown: Map<String, Int>,
        val confidenceLabel: String
    )

    /**
     * Classifies a single item name by hybrid keyword + fuzzy matching.
     *
     * Step 1: Exact keyword lookup (Guide §5.1)
     * Step 2: Fuzzy match against dictionary keys (Guide §5.1 Step 2)
     */
    fun classifyItem(itemName: String): CategoryMatchResult {
        val normalizedItem = TextNormalizer.cleanText(itemName)
        val tokens = normalizedItem.split(" ").filter { it.isNotBlank() }

        Log.d(TAG, "Step 1: classifyItem | input='$itemName' → tokens=$tokens")

        val scoreBoard = mutableMapOf<String, Int>()
        val matchedKeywords = mutableListOf<String>()

        for (token in tokens) {
            // Step 1: Exact keyword match
            val exactMatch = KEYWORD_DICTIONARY[token]
            if (exactMatch != null) {
                val (category, points) = exactMatch
                scoreBoard[category] = (scoreBoard[category] ?: 0) + points
                matchedKeywords.add("$token (exact +$points → $category)")
                Log.d(TAG, "Step 2: classifyItem | Exact match: '$token' → $category (+$points)")
                continue
            }

            // Step 2: Fuzzy match against all dictionary keys
            var bestFuzzyScore = 0.0
            var bestFuzzyKey = ""
            var bestFuzzyEntry: Pair<String, Int>? = null

            for ((keyword, entry) in KEYWORD_DICTIONARY) {
                // Only fuzzy-match tokens of similar length to avoid false positives
                if (kotlin.math.abs(token.length - keyword.length) > 3) continue

                val score = SimilarityScorer.jaroWinkler(token, keyword)
                if (score > bestFuzzyScore) {
                    bestFuzzyScore = score
                    bestFuzzyKey = keyword
                    bestFuzzyEntry = entry
                }
            }

            if (bestFuzzyScore >= FUZZY_THRESHOLD && bestFuzzyEntry != null) {
                val (category, points) = bestFuzzyEntry
                // Discount fuzzy matches by 1 point to prefer exact
                val adjustedPoints = maxOf(1, points - 1)
                scoreBoard[category] = (scoreBoard[category] ?: 0) + adjustedPoints
                matchedKeywords.add("$token ≈ $bestFuzzyKey (fuzzy %.2f +$adjustedPoints → $category)".format(bestFuzzyScore))
                Log.d(TAG, "Step 3: classifyItem | Fuzzy match: '$token' ≈ '$bestFuzzyKey' (%.4f) → $category (+$adjustedPoints)".format(bestFuzzyScore))
            }
        }

        val bestCategory = scoreBoard.maxByOrNull { it.value }
        val finalCategory = if (bestCategory != null && bestCategory.value >= 3) bestCategory.key else "OTHERS"
        val confidenceLabel = when {
            (bestCategory?.value ?: 0) >= 8 -> "High"
            (bestCategory?.value ?: 0) >= 4 -> "Medium"
            else -> "Low"
        }

        Log.d(TAG, "Step 4: classifyItem | Result: category='$finalCategory', scores=$scoreBoard, label=$confidenceLabel")

        return CategoryMatchResult(
            category = finalCategory,
            totalScore = bestCategory?.value ?: 0,
            matchedKeywords = matchedKeywords,
            scoreBreakdown = scoreBoard,
            confidenceLabel = confidenceLabel
        )
    }

    /**
     * Classifies a full receipt OCR text by scanning all tokens.
     * Returns the best category with complete score breakdown.
     */
    fun classifyReceiptText(ocrText: String): CategoryMatchResult {
        val normalizedText = TextNormalizer.cleanText(ocrText)
        val tokens = normalizedText.split(" ").filter { it.isNotBlank() }.distinct()

        Log.d(TAG, "Step 1: classifyReceiptText | Unique tokens: ${tokens.size}")

        val scoreBoard = mutableMapOf<String, Int>()
        val matchedKeywords = mutableListOf<String>()

        for (token in tokens) {
            // Exact keyword match
            val exactMatch = KEYWORD_DICTIONARY[token]
            if (exactMatch != null) {
                val (category, points) = exactMatch
                scoreBoard[category] = (scoreBoard[category] ?: 0) + points
                matchedKeywords.add("$token (exact +$points → $category)")
                continue
            }

            // Fuzzy match (skip very short tokens to avoid noise)
            if (token.length < 3) continue

            var bestFuzzyScore = 0.0
            var bestFuzzyKey = ""
            var bestFuzzyEntry: Pair<String, Int>? = null

            for ((keyword, entry) in KEYWORD_DICTIONARY) {
                if (kotlin.math.abs(token.length - keyword.length) > 3) continue
                val score = SimilarityScorer.jaroWinkler(token, keyword)
                if (score > bestFuzzyScore) {
                    bestFuzzyScore = score
                    bestFuzzyKey = keyword
                    bestFuzzyEntry = entry
                }
            }

            if (bestFuzzyScore >= FUZZY_THRESHOLD && bestFuzzyEntry != null) {
                val (category, points) = bestFuzzyEntry
                val adjustedPoints = maxOf(1, points - 1)
                scoreBoard[category] = (scoreBoard[category] ?: 0) + adjustedPoints
                matchedKeywords.add("$token ≈ $bestFuzzyKey (fuzzy %.2f +$adjustedPoints → $category)".format(bestFuzzyScore))
            }
        }

        // Also check multi-word keywords (e.g., "table no", "exp date", "water bill")
        for ((keyword, entry) in KEYWORD_DICTIONARY) {
            if (!keyword.contains(" ")) continue // Only multi-word keywords
            if (normalizedText.contains(keyword)) {
                val (category, points) = entry
                scoreBoard[category] = (scoreBoard[category] ?: 0) + points
                matchedKeywords.add("$keyword (multi-word exact +$points → $category)")
            }
        }

        val bestCategory = scoreBoard.maxByOrNull { it.value }
        val finalCategory = if (bestCategory != null && bestCategory.value >= 4) bestCategory.key else "OTHERS"
        val confidenceLabel = when {
            (bestCategory?.value ?: 0) >= 10 -> "High"
            (bestCategory?.value ?: 0) >= 5 -> "Medium"
            else -> "Low"
        }

        Log.d(TAG, "Step 2: classifyReceiptText | Final: category='$finalCategory', scores=$scoreBoard, label=$confidenceLabel, keywords=${matchedKeywords.size}")

        return CategoryMatchResult(
            category = finalCategory,
            totalScore = bestCategory?.value ?: 0,
            matchedKeywords = matchedKeywords,
            scoreBreakdown = scoreBoard,
            confidenceLabel = confidenceLabel
        )
    }
}
