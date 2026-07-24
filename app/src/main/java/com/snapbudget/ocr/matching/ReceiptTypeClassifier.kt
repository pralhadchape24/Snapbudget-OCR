package com.snapbudget.ocr.matching

import android.util.Log
import kotlin.math.sqrt

/**
 * Receipt-Type Classification Engine (Concept §9)
 *
 * Classifies receipts into types: Restaurant, Grocery, Retail, Fuel, Medical, Utility, etc.
 * Uses TF-IDF-inspired keyword scoring + linear classification.
 *
 * Lightweight and fast — no ML model needed on-device.
 */
object ReceiptTypeClassifier {

    private const val TAG = "ReceiptTypeClassifier"

    /**
     * Supported receipt types.
     */
    enum class ReceiptType(val displayName: String) {
        RESTAURANT("Food & Dining"),
        GROCERY("Grocery"),
        RETAIL("Shopping"),
        FUEL("Travel & Transport"),
        MEDICAL("Health & Medical"),
        UTILITY("Utilities & Bills"),
        ENTERTAINMENT("Entertainment"),
        TRANSPORT("Travel & Transport"),
        UNKNOWN("Others")
    }

    /**
     * TF-IDF-inspired keyword dictionaries per receipt type.
     * Higher weight = more distinctive (IDF-like).
     * Keywords that appear in many receipt types get lower weights.
     */
    private val TYPE_KEYWORDS: Map<ReceiptType, Map<String, Double>> = mapOf(

    // ===============================
    // RESTAURANT / FOOD
    // ===============================
    ReceiptType.RESTAURANT to mapOf(

        // Core
        "restaurant" to 6.5, "resto" to 6.0, "dine" to 5.5, "dining" to 5.5,
        "table" to 5.0, "waiter" to 5.5, "fssai" to 7.0,
        "order" to 3.5, "takeaway" to 5.0, "parcel" to 5.0,
        "home delivery" to 5.5, "zomato" to 6.5, "swiggy" to 6.5,

        // Indian dishes
        "thali" to 6.5, "biryani" to 6.0, "pulao" to 5.5,
        "dosa" to 5.5, "idli" to 5.5, "vada" to 5.0,
        "misal" to 6.5, "pavbhaji" to 6.5, "pav" to 4.0,
        "naan" to 5.5, "roti" to 4.5, "chapati" to 5.0,
        "curry" to 4.5, "sabji" to 5.0, "paneer tikka" to 6.0,

        // Marathi / Local
        "amrutulya" to 7.0, "dhaba" to 7.0,
        "canteen" to 5.5, "udupi" to 6.5,

        // General food words
        "cafe" to 6.0, "bistro" to 6.0, "kitchen" to 4.5,
        "menu" to 4.5, "chef" to 5.0,
        "appetizer" to 5.0, "starter" to 4.5,
        "dessert" to 4.5, "beverage" to 4.0,
        "juice" to 4.0, "coffee" to 4.5, "tea" to 4.0,
        "meal" to 4.0, "combo" to 4.5
    ),

    // ===============================
    // GROCERY / KIRANA
    // ===============================
    ReceiptType.GROCERY to mapOf(

        "kirana" to 7.0, "grocery" to 7.0,
        "supermarket" to 7.0, "hypermarket" to 7.0,
        "mart" to 6.0, "bazaar" to 6.0,

        // Weights & Units
        "kg" to 4.0, "gm" to 4.0, "gram" to 4.0,
        "ltr" to 4.0, "litre" to 4.0, "ml" to 4.0,
        "pcs" to 3.5, "weight" to 4.0,

        // Food Items
        "vegetable" to 5.5, "fruit" to 5.5,
        "atta" to 6.0, "dal" to 5.5, "rice" to 5.0,
        "oil" to 4.5, "milk" to 5.0, "dahi" to 6.0,
        "paneer" to 5.5, "bread" to 4.5,
        "biscuit" to 4.5, "namkeen" to 5.0,
        "masala" to 5.0, "spice" to 4.5,
        "sugar" to 4.5, "salt" to 4.0,

        // Maharashtra common
        "kanda" to 6.5, "batata" to 6.5,
        "tamatar" to 6.5, "mirchi" to 6.0,
        "poha" to 6.0, "sabudana" to 6.0,

        // Packaging
        "mrp" to 3.0, "batch" to 3.0,
        "expiry" to 3.5, "packed" to 3.5
    ),

    // ===============================
    // RETAIL (Clothing / Electronics / Jewellery)
    // ===============================
    ReceiptType.RETAIL to mapOf(

        "apparel" to 6.5, "garments" to 6.5,
        "fashion" to 6.0, "clothing" to 6.5,
        "saree" to 6.5, "kurti" to 6.5,
        "jeans" to 5.5, "shirt" to 5.0,
        "trouser" to 5.5, "tshirt" to 5.5,
        "footwear" to 6.0, "shoes" to 5.5,
        "boutique" to 6.5, "tailor" to 6.0,
        "fabric" to 6.0, "trial" to 4.5,
        "fitting" to 5.5, "size" to 4.0,
        "color" to 4.0,

        // Electronics
        "electronics" to 6.0, "mobile" to 5.5,
        "laptop" to 6.0, "charger" to 5.5,
        "headphones" to 5.5, "accessories" to 5.0,
        "gadget" to 5.5,

        // Jewellery
        "jewellery" to 7.0, "gold" to 6.0,
        "silver" to 5.5, "ornament" to 6.0
    ),

    // ===============================
    // FUEL
    // ===============================
    ReceiptType.FUEL to mapOf(

        "petrol" to 8.0, "diesel" to 8.0,
        "cng" to 8.0, "fuel" to 7.5,
        "petroleum" to 7.5,
        "litres" to 6.0, "ltr" to 5.5,
        "volume" to 5.5, "density" to 7.0,
        "nozzle" to 8.0, "odometer" to 8.0,
        "dispenser" to 7.5,

        // Indian Oil Companies
        "iocl" to 9.0, "bpcl" to 9.0,
        "hpcl" to 9.0, "reliance petroleum" to 9.0
    ),

    // ===============================
    // MEDICAL
    // ===============================
    ReceiptType.MEDICAL to mapOf(

        "pharmacy" to 8.0, "chemist" to 8.0,
        "medical" to 6.0,
        "medicine" to 6.5, "drug" to 6.0,
        "prescription" to 7.5,
        "rx" to 8.0,
        "tablet" to 6.0, "capsule" to 6.0,
        "syrup" to 6.0, "ointment" to 6.5,
        "injection" to 6.5,
        "dosage" to 6.5,

        "hospital" to 6.5, "clinic" to 6.5,
        "diagnostic" to 6.5,
        "pathology" to 7.0, "lab" to 4.5,
        "patient" to 6.5
    ),

    // ===============================
    // UTILITY
    // ===============================
    ReceiptType.UTILITY to mapOf(

        "electricity" to 8.0, "bijli" to 8.0,
        "power" to 5.0, "kwh" to 8.0,
        "meter" to 6.5, "reading" to 5.5,
        "tariff" to 7.0, "consumer" to 5.5,

        "water" to 5.5,
        "gas" to 5.0, "lpg" to 7.0,
        "cylinder" to 6.5,

        "broadband" to 7.5,
        "internet" to 6.5,
        "wifi" to 6.5,
        "recharge" to 6.0,
        "connection" to 5.5
    ),

    // ===============================
    // ENTERTAINMENT
    // ===============================
    ReceiptType.ENTERTAINMENT to mapOf(

        "cinema" to 8.0, "movie" to 7.5,
        "theatre" to 7.5, "screen" to 7.0,
        "seat" to 6.5, "showtime" to 8.0,
        "multiplex" to 8.0,
        "ticket" to 6.5,
        "booking" to 5.5,
        "popcorn" to 7.0,

        // Brands
        "pvr" to 9.0,
        "inox" to 9.0,
        "imax" to 9.0,
        "cinepolis" to 9.0
    ),

    // ===============================
    // TRANSPORT
    // ===============================
    ReceiptType.TRANSPORT to mapOf(

        "cab" to 6.5, "taxi" to 6.5,
        "auto" to 5.0, "rickshaw" to 7.0,
        "ride" to 5.5, "trip" to 5.0,
        "fare" to 7.0,
        "driver" to 6.0,
        "toll" to 7.5,
        "fastag" to 8.0,

        "bus" to 5.5, "metro" to 6.0,
        "railway" to 7.0, "train" to 6.0,
        "platform" to 6.0,
        "km" to 4.0, "distance" to 5.0,

        // Ride Apps
        "ola" to 7.5,
        "uber" to 7.5,
        "rapido" to 7.5,
        "pmpml" to 8.0
    )
)   

    /**
     * Classification result with confidence.
     */
    data class ClassificationResult(
        val receiptType: ReceiptType,
        val confidence: Double,      // 0.0 to 1.0
        val scoreBreakdown: Map<ReceiptType, Double>,
        val topKeywords: List<String>,
        val confidenceLabel: String   // High / Medium / Low
    )

    /**
     * Classifies a receipt based on its OCR text.
     *
     * Uses TF-IDF-inspired cosine similarity scoring:
     *  1. Tokenize the OCR text
     *  2. Compute term frequency for each token
     *  3. Multiply by the IDF-like weight from our dictionary
     *  4. Compute cosine similarity against each receipt type vector
     *  5. Return the best match with confidence
     */
    fun classify(ocrText: String): ClassificationResult {
        Log.d(TAG, "Step 1: classify | Starting receipt type classification")

        val normalizedText = TextNormalizer.cleanText(ocrText)
        val tokens = normalizedText.split(" ").filter { it.isNotBlank() }
        val totalTokens = tokens.size

        if (totalTokens == 0) {
            Log.d(TAG, "Step 2: classify | No tokens found, returning UNKNOWN")
            return ClassificationResult(ReceiptType.UNKNOWN, 0.0, emptyMap(), emptyList(), "Low")
        }

        // Compute term frequency
        val termFrequency = mutableMapOf<String, Int>()
        for (token in tokens) {
            termFrequency[token] = (termFrequency[token] ?: 0) + 1
        }

        // Score each receipt type using TF-IDF cosine similarity
        val scores = mutableMapOf<ReceiptType, Double>()
        val matchedKeywordsPerType = mutableMapOf<ReceiptType, MutableList<String>>()

        for ((type, keywords) in TYPE_KEYWORDS) {
            var dotProduct = 0.0
            var queryNorm = 0.0
            val matched = mutableListOf<String>()

            for ((keyword, idfWeight) in keywords) {
                val tf = termFrequency[keyword] ?: 0
                if (tf > 0) {
                    val tfNormalized = tf.toDouble() / totalTokens  // Normalized TF
                    dotProduct += tfNormalized * idfWeight
                    matched.add("$keyword(tf=$tf, w=%.1f)".format(idfWeight))
                }
                queryNorm += idfWeight * idfWeight
            }

            // Cosine-like scoring (simplified since document vector is sparse)
            val score = if (queryNorm > 0) dotProduct / sqrt(queryNorm) else 0.0
            scores[type] = score
            matchedKeywordsPerType[type] = matched
        }

        // Find best match
        val best = scores.maxByOrNull { it.value }
        val bestType = best?.key ?: ReceiptType.UNKNOWN
        val bestScore = best?.value ?: 0.0

        // Compute confidence: normalize best score against all scores
        val totalScore = scores.values.sum()
        val confidence = if (totalScore > 0) bestScore / totalScore else 0.0
        val confidenceLabel = when {
            confidence >= 0.5 -> "High"
            confidence >= 0.3 -> "Medium"
            else -> "Low"
        }

        val topKeywords = matchedKeywordsPerType[bestType]?.take(5) ?: emptyList()

        Log.d(TAG, "Step 2: classify | Result: ${bestType.displayName} (confidence=%.4f, label=$confidenceLabel)".format(confidence))
        Log.d(TAG, "Step 3: classify | Score breakdown: ${scores.filter { it.value > 0 }.entries.sortedByDescending { it.value }.take(3)}")
        Log.d(TAG, "Step 4: classify | Top keywords: $topKeywords")

        return ClassificationResult(
            receiptType = bestType,
            confidence = confidence,
            scoreBreakdown = scores.filter { it.value > 0 },
            topKeywords = topKeywords,
            confidenceLabel = confidenceLabel
        )
    }
}
