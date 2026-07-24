 package com.snapbudget.ocr.ocr

import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.text.Text
import com.snapbudget.ocr.matching.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.math.abs

import com.snapbudget.ocr.data.model.Category

data class CategoryAnalysis(
    val finalCategory: Category,
    val isMerchantOverride: Boolean,
    val scoreBreakdown: Map<Category, Int>,
    val matchedKeywords: List<String>
)

object ReceiptPatterns {
    val datePatterns = listOf(
        Pattern.compile("(?:Date|Dt|Dated|Invoice Date|Bill Date|Txn Date)[:\\s]+(\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})"),
        Pattern.compile("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})"),
        Pattern.compile("(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{2,4})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})")
    )

    val merchantPatterns = listOf(
        Regex("^(?:Restaurant|Hotel|Store|Shop|Mart|Cafe|Bistro)\\s+[A-Z]", RegexOption.IGNORE_CASE),
        Regex("^(?:Big\\s*Bazaar|DMart|Reliance|More|Mega|Star|EasyDay)", RegexOption.IGNORE_CASE),
        Regex(".*(?:Pvt|Ltd|Limited|Private).*", RegexOption.IGNORE_CASE)
    )

    val gstNoRegex = Regex("^GST\\s*(No|Number|#|:).*", RegexOption.IGNORE_CASE)
    val digitsOnlyRegex = Regex("^\\d+$")
    val tenOrMoreDigitsRegex = Regex(".*\\d{10,}.*")
    val emailRegex = Regex(".*@.*\\..*")
    val indianAddressRegex = Regex("^\\d+[,\\s].*(?:road|street|lane|nagar|colony|sector).*", RegexOption.IGNORE_CASE)
    val usAddressRegex = Regex("^[A-Za-z\\s]+\\d{5}(-\\d{4})?$")
    val generalAddressRegex = Regex("^\\d+[,\\s]+.{5,}", RegexOption.IGNORE_CASE)
    val addressLabelRegex = Regex("^A?[Dd]re?ss?:.*", RegexOption.IGNORE_CASE)

    val invoicePatterns = listOf(
        Pattern.compile("(?:Invoice|Inv|Bill)\\s*(?:No|Number|#)?[:\\s]*([A-Za-z0-9/-]{3,20})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Receipt|Rcpt)\\s*(?:No|Number|#)?[:\\s]*([A-Za-z0-9/-]{3,20})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Ref|Reference)\\s*(?:No|Number|#)?[:\\s]*([A-Za-z0-9/-]{3,20})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Bill|Transaction)\\s*(?:No|ID|#)?[:\\s]*([A-Za-z0-9/-]{3,20})", Pattern.CASE_INSENSITIVE)
    )
    
    val datePrefixRegex = Regex("(?i)^(dated?|dt|invoice date|bill date|txn date)[:\\s]+")
}


class ReceiptParser {

    private val tag = "ReceiptParser"

    // 1. Compile Regex outside loops (Performance Boost)
    private val genericAmountPattern = Pattern.compile("(\\d{1,7}(?:[.,]\\d{1,2})?)")
    private val strictCurrencyPattern = Pattern.compile("(?:₹|rs\\.?|inr)?\\s*(\\d{1,7}(?:[.,]\\d{2}))", Pattern.CASE_INSENSITIVE)

    // 3. Keyword Scoring weights for Total Amount
    private val weightedKeywords = mapOf(
        "grand total" to 50,
        "net amount" to 40,
        "amount payable" to 40,
        "total" to 30,
        "payable" to 20
    )

    // Mapping from ItemCategoryMapper output → Category model
    private val categoryNameToModel = mapOf(
        "GROCERY" to Category.Grocery,
        "FOOD" to Category.Food,
        "SHOPPING" to Category.Shopping,
        "HEALTH" to Category.Health,
        "TRAVEL" to Category.Travel,
        "ENTERTAINMENT" to Category.Entertainment,
        "UTILITIES" to Category.Utilities,
        "EDUCATION" to Category.Education,
        "OTHERS" to Category.Others
    )

    // 8. Stronger Final Selection Strategy for Total Amount
    data class TotalCandidate(
        val amount: Double,
        val score: Int,
        val source: String // Helpful for debug logging!
    )

    data class ParsedReceipt(
        val merchantName: String,
        val totalAmount: Double,
        val categoryAnalysis: CategoryAnalysis,
        val date: Date,
        val gstNumber: String?,
        val invoiceNumber: String?,
        val lineItems: List<LineItem>,
        val confidenceScores: Map<String, Float>,
        val rawText: String,
        val dateExtractedFromText: Boolean = false,
        val receiptType: ReceiptTypeClassifier.ReceiptType = ReceiptTypeClassifier.ReceiptType.UNKNOWN,
        val anomalyIssues: List<String> = emptyList(),
        val confidenceReport: ConfidenceEstimator.ConfidenceReport? = null
    ) {
        val overallConfidence: Float
            get() = confidenceReport?.overallScore
                ?: if (confidenceScores.isEmpty()) 0f
                else confidenceScores.values.average().toFloat()
    }

    data class LineItem(
        val name: String,
        val quantity: Int,
        val unitPrice: Double,
        val totalPrice: Double
    )

    fun parse(visionText: Text, rawOcrText: String): ParsedReceipt? {
        Log.d(tag, "parse | Step 1: Starting receipt parsing")
        
        if (visionText.textBlocks.isEmpty()) {
            Log.w(tag, "parse | Step 1: Empty OCR blocks, cannot parse")
            return null
        }
        
        // Log the exact structural layout for debugging
        logVisionText(visionText)
        
        // Build lines with spatial awareness (Layout-Aware Parsing §1)
        val allLines = mutableListOf<Text.Line>()
        for (block in visionText.textBlocks) {
            allLines.addAll(block.lines)
        }
        
        // Sort lines by vertical position (Y coordinate), then horizontal (X coordinate)
        val sortedLines = allLines.sortedWith(Comparator { l1, l2 ->
            val rect1 = l1.boundingBox ?: Rect()
            val rect2 = l2.boundingBox ?: Rect()
            if (abs(rect1.top - rect2.top) < 20) {
                rect1.left.compareTo(rect2.left)
            } else {
                rect1.top.compareTo(rect2.top)
            }
        })

        // Determine min/max bounding boundaries for the whole receipt
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (line in allLines) {
            line.boundingBox?.let { rect ->
                if (rect.top < minY) minY = rect.top
                if (rect.bottom > maxY) maxY = rect.bottom
            }
        }
        val receiptHeight = if (maxY > minY) maxY - minY else 1
        Log.d(tag, "parse | Step 2: Layout bounds — minY=$minY, maxY=$maxY, height=$receiptHeight, lines=${sortedLines.size}")
        
        // ---- Merchant Extraction with Position Heuristics (§8) ----
        val merchantName = extractMerchantName(sortedLines, minY, receiptHeight)
        val matchResult = MerchantMatcher.matchMerchant(merchantName)
        val correctedMerchantName = matchResult.canonicalName
        Log.d(tag, "parse | Step 3: Merchant — raw='$merchantName' → corrected='$correctedMerchantName'")
        Log.d(tag, "     - Score: %.4f, Threshold: %.2f, Label: ${matchResult.confidenceLabel}, Source: ${matchResult.matchSource}".format(matchResult.score, matchResult.threshold))
        
        // ---- Weighted Scoring Engine for Total Amount (§2) + Contextual Window (§4) ----
        val totalAmountResult = extractTotalAmount(sortedLines, receiptHeight, maxY, rawOcrText)
        val totalAmount = totalAmountResult.first
        val amountSource = totalAmountResult.second
        
        // ---- Regex-Based Structured Extraction (§3) ----
        val (date, dateFromText) = extractDate(rawOcrText)
        val gstNumber = extractGSTNumber(rawOcrText)
        val invoiceNumber = extractInvoiceNumber(rawOcrText)
        val lineItems = extractLineItems(rawOcrText.lines().map { it.trim() }.filter { it.isNotEmpty() })
        Log.d(tag, "parse | Step 4: Structured extraction — date=$date(fromText=$dateFromText), gst=$gstNumber, invoice=$invoiceNumber, items=${lineItems.size}")

        // ---- Receipt-Type Classification (§9) ----
        val receiptTypeResult = ReceiptTypeClassifier.classify(rawOcrText)
        Log.d(tag, "parse | Step 5: Receipt type — ${receiptTypeResult.receiptType.displayName} (confidence=%.4f, label=${receiptTypeResult.confidenceLabel})".format(receiptTypeResult.confidence))

        // ---- Category Determination ----
        val categoryAnalysis = determineCategory(correctedMerchantName, receiptTypeResult)
        Log.d(tag, "parse | Step 6: Category — ${categoryAnalysis.finalCategory} (override=${categoryAnalysis.isMerchantOverride})")
        if (!categoryAnalysis.isMerchantOverride) {
            Log.d(tag, "     - Score Breakdown: ${categoryAnalysis.scoreBreakdown}")
            Log.d(tag, "     - Keywords: ${categoryAnalysis.matchedKeywords.take(5).joinToString(", ")}")
        }

        // ---- Outlier / Anomaly Detection (§10) ----
        val anomalyResult = OutlierDetector.checkAmount(totalAmount, receiptTypeResult.receiptType)
        Log.d(tag, "parse | Step 7: Anomaly check — isAnomaly=${anomalyResult.isAnomaly}, score=%.4f, action=${anomalyResult.suggestedAction}".format(anomalyResult.anomalyScore))

        // ---- Numeric Consistency Validation (§5) ----
        val lineItemsSum = lineItems.sumOf { it.totalPrice }
        val consistencyResult = if (lineItemsSum > 0 && totalAmount > 0) {
            OutlierDetector.validateConsistency(totalAmount, lineItemsSum)
        } else null
        if (consistencyResult != null) {
            Log.d(tag, "parse | Step 8: Consistency — anomaly=${consistencyResult.isAnomaly}, issues=${consistencyResult.issues}")
        }

        // ---- Confidence Estimation Layer (§12) ----
        val fieldConfidences = mutableListOf(
            ConfidenceEstimator.estimateMerchantConfidence(
                correctedMerchantName, matchResult.isMatch, matchResult.score
            ),
            ConfidenceEstimator.estimateAmountConfidence(
                totalAmount, amountSource, anomalyResult, consistencyResult
            ),
            ConfidenceEstimator.estimateDateConfidence(dateFromText),
            ConfidenceEstimator.estimateGstConfidence(gstNumber, gstNumber?.let { isValidGST(it) } ?: false),
            ConfidenceEstimator.estimateCategoryConfidence(
                categoryAnalysis.scoreBreakdown.values.maxOrNull() ?: 0,
                categoryAnalysis.isMerchantOverride
            )
        )
        val confidenceReport = ConfidenceEstimator.computeOverallConfidence(fieldConfidences)
        Log.d(tag, "parse | Step 9: Confidence — overall=%.4f, label=${confidenceReport.overallLabel}, needsConfirmation=${confidenceReport.needsUserConfirmation}".format(confidenceReport.overallScore))

        // Build legacy confidence map for backward compatibility
        val confidenceScores = mutableMapOf<String, Float>()
        for (fc in fieldConfidences) {
            confidenceScores[fc.fieldName] = fc.score
        }

        // Collect all anomaly issues
        val allAnomalyIssues = mutableListOf<String>()
        allAnomalyIssues.addAll(anomalyResult.issues)
        consistencyResult?.let { allAnomalyIssues.addAll(it.issues) }

        if (totalAmount <= 0) {
            Log.w(tag, "parse | Step 10: WARNING — Failed to extract valid total amount")
        }
        
        return ParsedReceipt(
            merchantName = correctedMerchantName.ifEmpty { "Unknown Merchant" },
            totalAmount = totalAmount,
            categoryAnalysis = categoryAnalysis,
            date = date,
            gstNumber = gstNumber,
            invoiceNumber = invoiceNumber,
            lineItems = lineItems,
            confidenceScores = confidenceScores,
            rawText = rawOcrText,
            dateExtractedFromText = dateFromText,
            receiptType = receiptTypeResult.receiptType,
            anomalyIssues = allAnomalyIssues,
            confidenceReport = confidenceReport
        )
    }

    // =========================================================================
    // determineCategory — delegates to consistent document-level matching
    // Uses MerchantMatcher for fast-path and ReceiptTypeClassifier for document analysis
    // =========================================================================
    private val receiptTypeToModel = mapOf(
        ReceiptTypeClassifier.ReceiptType.RESTAURANT to Category.Food,
        ReceiptTypeClassifier.ReceiptType.GROCERY to Category.Grocery,
        ReceiptTypeClassifier.ReceiptType.RETAIL to Category.Shopping,
        ReceiptTypeClassifier.ReceiptType.MEDICAL to Category.Health,
        ReceiptTypeClassifier.ReceiptType.FUEL to Category.Travel,
        ReceiptTypeClassifier.ReceiptType.TRANSPORT to Category.Travel,
        ReceiptTypeClassifier.ReceiptType.ENTERTAINMENT to Category.Entertainment,
        ReceiptTypeClassifier.ReceiptType.UTILITY to Category.Utilities,
        ReceiptTypeClassifier.ReceiptType.UNKNOWN to Category.Others
    )

    private fun determineCategory(
        merchantName: String?, 
        typeResult: ReceiptTypeClassifier.ClassificationResult
    ): CategoryAnalysis {
        Log.d(tag, "determineCategory | Step 1: Starting category determination")
        Log.d(tag, "determineCategory | merchant='$merchantName'")

        // 1. Merchant Fast Path — use MerchantMatcher result to infer category
        if (!merchantName.isNullOrBlank() && merchantName != "Unknown" && merchantName != "Unknown Merchant") {
            val merchantMatch = MerchantMatcher.matchMerchant(merchantName)
            if (merchantMatch.isMatch && merchantMatch.score >= 0.95) {
                val impliedType = ReceiptTypeClassifier.classify(merchantMatch.canonicalName).receiptType
                if (impliedType != ReceiptTypeClassifier.ReceiptType.UNKNOWN) {
                    val modelCategory = receiptTypeToModel[impliedType] ?: Category.Others
                    Log.d(tag, "determineCategory | Step 2: Merchant fast-path hit: '${merchantMatch.canonicalName}' → ${modelCategory.name}")
                    return CategoryAnalysis(
                        finalCategory = modelCategory,
                        isMerchantOverride = true,
                        scoreBreakdown = mapOf(modelCategory to 100),
                        matchedKeywords = listOf("MERCHANT_EXACT: ${merchantMatch.canonicalName}")
                    )
                }
            }
        }

        // 2. Map the ReceiptTypeClassifier result directly to unified Category
        Log.d(tag, "determineCategory | Step 3: No direct merchant rule, applying document classifier result: ${typeResult.receiptType.name}")
        val bestModelCategory = receiptTypeToModel[typeResult.receiptType] ?: Category.Others

        // Convert score breakdown
        val modelScoreBreakdown = typeResult.scoreBreakdown.mapNotNull { (type, score) ->
            val modelCat = receiptTypeToModel[type]
            if (modelCat != null) modelCat to (score * 100).toInt() else null
        }.toMap()

        Log.d(tag, "determineCategory | Step 4: Keyword result: ${bestModelCategory.name} (label=${typeResult.confidenceLabel})")

        return CategoryAnalysis(
            finalCategory = bestModelCategory,
            isMerchantOverride = false,
            scoreBreakdown = modelScoreBreakdown,
            matchedKeywords = typeResult.topKeywords
        )
    }


    /**
     * Merchant Extraction Using Position Heuristics (§8)
     * Uses layout-weighted ranking + token importance scoring + contextual analysis.
     */
    private fun extractMerchantName(lines: List<Text.Line>, minY: Int, receiptHeight: Int): String {
        Log.d(tag, "extractMerchantName | Step 1: Scanning top region for merchant name")
        
        // Consider only the top 25% of the receipt for merchant name (widened from 20%)
        val topRegionThreshold = minY + (receiptHeight * 0.25)

        // Score each candidate line using spatial + contextual + token importance
        data class MerchantCandidate(val text: String, val score: Int, val source: String)
        val candidates = mutableListOf<MerchantCandidate>()

        for ((index, line) in lines.withIndex()) {
            val text = line.text.trim()
            val rect = line.boundingBox ?: continue
            
            if (rect.top > topRegionThreshold) continue
            
            // Skip non-merchant content
            // Be careful: only skip standalone labels like "RECEIPT", "BILL", "TAX INVOICE"
            // Don't skip compound names like "Cash Receipt" or "Bill's Restaurant"
            val textUpper = text.uppercase().trim()
            val standaloneLabels = setOf("TAX INVOICE", "BILL", "RECEIPT", "INVOICE", "TAX BILL")
            if (textUpper in standaloneLabels ||
                text.contains("GSTIN", ignoreCase = true) ||
                text.matches(ReceiptPatterns.gstNoRegex) ||
                text.matches(ReceiptPatterns.digitsOnlyRegex) ||
                text.matches(ReceiptPatterns.tenOrMoreDigitsRegex) ||
                text.matches(ReceiptPatterns.emailRegex) ||
                // Indian-style address: "123, Road Name, Nagar"
                text.matches(ReceiptPatterns.indianAddressRegex) ||
                // US-style address: "City State Zipcode" (e.g. "Palo Alto California 94301")
                text.matches(ReceiptPatterns.usAddressRegex) ||
                // General address: starts with digits + comma/space + address words
                text.matches(ReceiptPatterns.generalAddressRegex) ||
                // Line starts with "Address" or "Adress" (OCR variant)
                text.matches(ReceiptPatterns.addressLabelRegex) ||
                text.length < 3) {
                continue
            }

            var lineScore = 0
            var source = "default"

            // Spatial scoring: position ratio (§8 — position heuristics)
            val positionRatio = (rect.top - minY).toDouble() / receiptHeight
            when {
                positionRatio < 0.10 -> { lineScore += 15; source = "top_10%" }
                positionRatio < 0.15 -> { lineScore += 10; source = "top_15%" }
                positionRatio < 0.25 -> { lineScore += 5; source = "top_25%" }
            }

            // Font size heuristic: larger bounding boxes suggest headers
            val lineHeight = rect.height()
            val avgLineHeight = lines.mapNotNull { it.boundingBox?.height() }.average()
            if (lineHeight > avgLineHeight * 1.3) {
                lineScore += 5
                source += "+large_font"
            }

            // Pattern match boost
            for (patternRegex in ReceiptPatterns.merchantPatterns) {
                try {
                    if (text.matches(patternRegex)) {
                        lineScore += 20
                        source += "+pattern_match"
                        break
                    }
                } catch (e: Exception) { /* skip */ }
            }
            
            // Uppercase-dominant boost (merchant headers are often uppercase)
            val uppercaseRatio = text.count { it.isUpperCase() }.toDouble() / text.length
            if (uppercaseRatio > 0.6 && text.length in 3..40 &&
                !text.contains("TOTAL") && !text.contains("DATE") &&
                !text.contains("AMOUNT") && !text.matches(Regex(".*\\d{5,}.*"))) {
                lineScore += 8
                source += "+uppercase"
            }

            // Contextual scoring (§4 — surrounding tokens)
            val surroundingTokens = lines
                .filter { abs((it.boundingBox?.top ?: 0) - rect.top) < receiptHeight * 0.10 && it != line }
                .flatMap { it.text.split(" ") }
            val contextScore = ContextualAnalyzer.scoreMerchantContext(
                text.split(" "), surroundingTokens, positionRatio
            )
            lineScore += contextScore

            if (lineScore > 0) {
                candidates.add(MerchantCandidate(text, lineScore, source))
            }
        }

        // Pick the highest-scoring candidate
        val bestCandidate = candidates.sortedByDescending { it.score }.firstOrNull()
        if (bestCandidate != null) {
            Log.d(tag, "extractMerchantName | Step 2: Best candidate: '${bestCandidate.text}' (score=${bestCandidate.score}, source=${bestCandidate.source})")
            Log.d(tag, "extractMerchantName | Step 3: All candidates: ${candidates.sortedByDescending { it.score }.take(3).map { "'${it.text}'(${it.score})" }}")
            return bestCandidate.text.titleCase()
        }
        
        // Fallback: use first meaningful line in the top 15%
        val fallback = lines.firstOrNull { 
            (it.boundingBox?.top ?: Int.MAX_VALUE) < minY + (receiptHeight * 0.15) &&
            it.text.length > 3 && 
            !it.text.matches(ReceiptPatterns.digitsOnlyRegex) 
        }?.text?.trim()?.titleCase() ?: "Unknown"
        Log.d(tag, "extractMerchantName | Step 2: Fallback merchant: '$fallback'")
        return fallback
    }
    
    // NOTE: fuzzyCorrectMerchantName and calculateLevenshteinDistance removed.
    // These are now handled by MerchantMatcher and SimilarityScorer respectively.
    // See: com.snapbudget.ocr.matching.MerchantMatcher
    // See: com.snapbudget.ocr.matching.SimilarityScorer

    /**
     * Amount parsing with OCR Error Correction (§7).
     * Delegates character-level fixes to OcrErrorCorrector.
     */
    private fun parseAmount(raw: String?): Double {
        if (raw == null) return 0.0

        // Use centralized OCR error correction (§7)
        val correctionResult = OcrErrorCorrector.correctAmountString(raw)
        val cleaned = correctionResult.corrected
            .replace(",", "")
            .trim()

        if (correctionResult.wasModified) {
            Log.d(tag, "parseAmount | OCR correction: '$raw' → '$cleaned' | ${correctionResult.corrections}")
        }

        // Reject very large integer-only numbers (likely invoice numbers, barcodes)
        // Relaxed from 6 to 8 digits — valid receipt totals can be 6-7 digits (e.g. ₹1,00,000)
        if (!cleaned.contains(".") && cleaned.length >= 8) return 0.0

        val amount = cleaned.toDoubleOrNull() ?: 0.0

        // Validate using OcrErrorCorrector plausibility check
        if (amount > 0 && !OcrErrorCorrector.isPlausibleAmount(cleaned)) {
            Log.d(tag, "parseAmount | Amount $amount rejected as implausible")
            return 0.0
        }

        return amount
    }

    /**
     * Total Amount Extraction — Weighted Scoring Engine (§2) + Contextual Window (§4)
     * Returns Pair(amount, source) for confidence tracking.
     */
    private fun extractTotalAmount(lines: List<Text.Line>, receiptHeight: Int, maxY: Int, rawOcrText: String = ""): Pair<Double, String> {
        val candidates = mutableListOf<TotalCandidate>()
        Log.d(tag, "extractTotalAmount | Step 1: Starting weighted scoring engine")
        
        val sortedLines = lines.sortedBy { it.boundingBox?.top ?: 0 }

        for (line in sortedLines) {
            val text = line.text.lowercase()
            var lineScore = 0

            // Heuristics and Penalties
            if (text.contains("sub total") || text.contains("subtotal")) lineScore -= 30
            if (text.contains("tendered") || text.contains("change") || text.contains("saving")) lineScore -= 100
            // Penalty for tax component lines — these are NOT the total, they are parts of it
            if (text.contains("gst included") || text.contains("gst incl")) lineScore -= 50
            if (text.contains("cgst") || text.contains("sgst") || text.contains("igst")) lineScore -= 40
            if (text.contains("vat") && !text.contains("total")) lineScore -= 30
            if (text.contains("round off") || text.contains("roundoff")) lineScore -= 30
            if (text.contains("discount")) lineScore -= 20
            if (text.contains("service charge") || text.contains("service tax")) lineScore -= 20
            // "included" alone suggests this is a tax info line, not the total
            if (text.contains("included") && !text.contains("total")) lineScore -= 25
            
            // Keyword scoring (§2)
            lineScore += weightedKeywords.filter { text.contains(it.key) }.values.sum()

            // Layout-Aware: position-based scoring (§1)
            line.boundingBox?.let { rect ->
                val positionRatio = (rect.bottom).toDouble() / maxY.toDouble()
                if (positionRatio > 0.7 && lineScore > 0) {
                    lineScore += 5  // Bottom region boost for keyword-matched lines
                }
                // Font size bonus: larger text in total lines
                val avgHeight = sortedLines.mapNotNull { it.boundingBox?.height() }.average()
                if (rect.height() > avgHeight * 1.2 && lineScore > 0) {
                    lineScore += 3
                }
            }

            // Strategy A: Keyword matched
            if (lineScore > 0) {
                // A1: Same line
                val sameLineMatcher = genericAmountPattern.matcher(text)
                while (sameLineMatcher.find()) {
                    val amount = parseAmount(sameLineMatcher.group(1))
                    if (amount > 0) {
                        candidates.add(TotalCandidate(amount, lineScore + 10, "Keyword + Same Line"))
                    }
                }

                // A2: Adjacent lines (Spatial Logic — §1)
                line.boundingBox?.let { rect ->
                    val yTolerance = rect.height() * 0.6 
                    val adjacentLines = sortedLines.filter { other ->
                        other != line &&
                        other.boundingBox != null &&
                        abs(other.boundingBox!!.exactCenterY() - rect.exactCenterY()) < yTolerance &&
                        other.boundingBox!!.left > rect.right - 50
                    }
                    for (adjLine in adjacentLines) {
                        val adjMatcher = genericAmountPattern.matcher(adjLine.text.lowercase())
                        while (adjMatcher.find()) {
                            val amount = parseAmount(adjMatcher.group(1))
                            if (amount > 0) {
                                candidates.add(TotalCandidate(amount, lineScore + 5, "Keyword + Adjacent Line"))
                            }
                        }
                    }
                }
            }

            // Strategy B: Bottom 30% Fallback
            val bottomRegionThreshold = maxY - (receiptHeight * 0.30)
            if ((line.boundingBox?.bottom ?: 0) >= bottomRegionThreshold) {
                var bottomScore = 10
                if (text.contains("₹") || text.contains("rs") || text.contains("inr")) bottomScore += 20
                if (text.contains("tendered") || text.contains("paid")) bottomScore -= 100
                if (bottomScore > 0) {
                    val bottomMatcher = strictCurrencyPattern.matcher(text)
                    while (bottomMatcher.find()) {
                        val amount = parseAmount(bottomMatcher.group(1))
                        if (amount > 0) {
                            candidates.add(TotalCandidate(amount, bottomScore, "Bottom 30% Fallback"))
                        }
                    }
                }
            }
        }

        // Strategy C: Contextual Window Analysis (§4)
        if (rawOcrText.isNotBlank()) {
            val allTokens = rawOcrText.split(Regex("\\s+")).filter { it.isNotBlank() }
            val contextScores = ContextualAnalyzer.scoreNumericCandidates(allTokens)
            for (cs in contextScores.take(5)) {
                if (cs.contextScore > 5 && cs.numericValue > 0) {
                    // Add as candidate with contextual score mapped to our scoring scale
                    val mappedScore = cs.contextScore * 2  // Scale up 
                    candidates.add(TotalCandidate(cs.numericValue, mappedScore, "Contextual Window (ctx=${cs.contextScore})"))
                }
            }
        }

        // Pick the best candidate
        val bestCandidate = candidates
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<TotalCandidate> { it.score }
            .thenByDescending { it.amount })
            .firstOrNull()

        if (bestCandidate != null) {
            Log.d(tag, "extractTotalAmount | Step 2: Best candidate: ₹${bestCandidate.amount} (Score: ${bestCandidate.score}, Source: ${bestCandidate.source})")
            Log.d(tag, "extractTotalAmount | Step 3: All candidates (top 5): ${candidates.sortedByDescending { it.score }.take(5).map { "₹${it.amount}(${it.score}:${it.source})" }}")
        } else {
            Log.d(tag, "extractTotalAmount | Step 2: No suitable total amount candidate found")
        }
        
        val amount = bestCandidate?.amount ?: 0.0
        val source = bestCandidate?.source ?: "none"
        return Pair(amount, source)
    }

    private fun extractDate(text: String): Pair<Date, Boolean> {
        Log.d(tag, "extractDate | Step 1: Scanning for date patterns")

        // First, filter out lines containing Mfg or Expiry dates to avoid false positives
        val filteredText = text.lines()
            .filter { line -> 
                val lower = line.lowercase()
                !lower.contains("mfg") && !lower.contains("manufacturing") && 
                !lower.contains("exp") && !lower.contains("expiry") &&
                !lower.contains("best before") && !lower.contains("dob")
            }
            .joinToString("\n")

        for (pattern in ReceiptPatterns.datePatterns) {
            val matcher = pattern.matcher(filteredText)
            while (matcher.find()) {
                try {
                    val rawDateStr = matcher.group(0) ?: continue
                    val sanitized = sanitizeDateString(rawDateStr)
                    if (sanitized != rawDateStr) {
                        Log.d(tag, "extractDate | Step 2: Sanitized date string: '$rawDateStr' → '$sanitized'")
                    }
                    val parsedDate = parseDateString(sanitized)
                    if (parsedDate != null && !isFutureDate(parsedDate) && isReasonableYear(parsedDate)) {
                        Log.d(tag, "extractDate | Step 3: Valid date found: $parsedDate (from '$sanitized')")
                        return Pair(parsedDate, true)
                    } else if (parsedDate != null) {
                        Log.d(tag, "extractDate | Step 2: Rejected date '$sanitized' — future=${isFutureDate(parsedDate)}, reasonable=${isReasonableYear(parsedDate)}")
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }

        Log.d(tag, "extractDate | Step 3: No valid date found, defaulting to today")
        return Pair(Calendar.getInstance().time, false)
    }

    /**
     * Sanitizes an OCR-extracted date string before parsing.
     *
     * Fixes known OCR errors in date strings:
     *  1. Year '0018' → '2018' — OCR often reads '2' as '0', corrupting 4-digit years
     *     that start with '20XX' or '19XX' into '00XX' or '0X XX'.
     *  2. 2-digit years: '18', '24' → '2018', '2024' (21st century assumption for receipts).
     *  3. Removes prefix noise like 'Date:', 'Dt:', 'Dated:'
     */
    private fun sanitizeDateString(raw: String): String {
        var s = raw.trim()
            .replace(ReceiptPatterns.datePrefixRegex, "")
            .trim()

        // Detect and fix the 4-digit year portion
        // Matches separators: / - .
        // Pattern: captures the last token as the year in DD/MM/YYYY or YYYY/MM/DD
        val sep = when {
            s.contains('/') -> '/'
            s.contains('-') -> '-'
            s.contains('.') -> '.'
            else -> return s
        }

        val parts = s.split(sep)
        if (parts.size != 3) return s

        // Determine which part is the year (4-digit or 2-digit)
        val mutableParts = parts.toMutableList()

        // Case 1: Last part is the year (DD/MM/YYYY format)
        val lastPart = mutableParts[2]
        if (lastPart.length == 4) {
            mutableParts[2] = fixFourDigitYear(lastPart)
        } else if (lastPart.length == 2) {
            // 2-digit year: assume 21st century for receipts (00–30 → 2000–2030)
            val yr = lastPart.toIntOrNull() ?: return s
            mutableParts[2] = if (yr <= 30) "20$lastPart" else "19$lastPart"
            Log.d(tag, "sanitizeDateString | Expanded 2-digit year '$lastPart' → '${mutableParts[2]}'")
        }

        // Case 2: First part is the year (YYYY/MM/DD format)
        val firstPart = mutableParts[0]
        if (firstPart.length == 4 && firstPart.toIntOrNull()?.let { it > 999 } == true) {
            mutableParts[0] = fixFourDigitYear(firstPart)
        }

        return mutableParts.joinToString(sep.toString())
    }

    /**
     * Fixes a 4-digit year corrupted by OCR.
     *
     * OCR commonly reads '2' as '0', causing:
     *   2018 → 0018 (year prefix '20' becomes '00')
     *   2024 → 0024
     *   1998 → 1998 (unaffected — starts with '19', OCR rarely misreads '1')
     *
     * Heuristic: if year starts with '00' and the corrected '20' prefix gives
     * a year in the valid receipt range (2000–2030), apply the correction.
     */
    private fun fixFourDigitYear(year: String): String {
        if (year.length != 4) return year
        val yi = year.toIntOrNull() ?: return year

        return when {
            // '00XX' pattern: OCR read '20XX' as '00XX'
            year.startsWith("00") -> {
                val corrected = "20${year.substring(2)}"
                val correctedInt = corrected.toIntOrNull() ?: return year
                if (correctedInt in 2000..2035) {
                    Log.d(tag, "fixFourDigitYear | OCR year correction: '$year' → '$corrected'")
                    corrected
                } else year
            }
            // '0XXX' pattern: OCR read '2XXX' as '0XXX' (e.g., '2024' → '0024' treated as int=24)
            year.startsWith("0") && yi < 100 -> {
                val corrected = "20${year.takeLast(2)}"
                val correctedInt = corrected.toIntOrNull() ?: return year
                if (correctedInt in 2000..2035) {
                    Log.d(tag, "fixFourDigitYear | OCR year correction (0XXX): '$year' → '$corrected'")
                    corrected
                } else year
            }
            else -> year
        }
    }

    fun parseDateString(dateStr: String?): Date? {
        if (dateStr == null) return null

        val cleaned = dateStr.replace(ReceiptPatterns.datePrefixRegex, "").trim()

        val formats = listOf(
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()),
            SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()),
            SimpleDateFormat("dd/MM/yy", Locale.getDefault()),
            SimpleDateFormat("dd-MM-yy", Locale.getDefault()),
            SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()),
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()),
            SimpleDateFormat("MMM dd yyyy", Locale.getDefault()),
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        )

        for (format in formats) {
            try {
                format.isLenient = false
                val parsed = format.parse(cleaned) ?: continue
                // Secondary year sanity check: reject if year is outside 1990–2035
                // Catches cases where sanitizeDateString didn't fully fix an edge case
                val cal = Calendar.getInstance().apply { time = parsed }
                val year = cal.get(Calendar.YEAR)
                if (year < 1990 || year > 2035) {
                    Log.d(tag, "parseDateString | Rejected year $year from '$cleaned' (out of range 1990–2035)")
                    continue
                }
                return parsed
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * Returns true if the parsed date's year is within the plausible receipt range.
     * Receipts are typically from 2000 onwards.
     */
    private fun isReasonableYear(date: Date): Boolean {
        val cal = Calendar.getInstance().apply { time = date }
        val year = cal.get(Calendar.YEAR)
        return year in 2000..2035
    }

    private fun isFutureDate(date: Date): Boolean {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 1) // Allow for timezone differences
        return date.after(calendar.time)
    }

    private fun extractGSTNumber(text: String): String? {
        // Preprocess text to fix common 0/O, 1/I, S/5 OCR confusions for GST extraction
        // But only locally to not ruin other extractions
        
        // Match Indian GST pattern: 2 digits + 5 letters + 4 digits + 1 letter + 1 digit/letter + Z + digit/letter
        // using fuzzy tolerant regex boundaries and normalizing space
        val normalizedText = text.replace(" ", "")
        
        val gstPatterns = listOf(
            Pattern.compile("([0-3][0-9][A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z])", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:GSTIN|GST)[:\\s]*([A-Z0-9]{15})", Pattern.CASE_INSENSITIVE) // Fallback for scanning 15 alphanumeric if prefix matches
        )
        
        for (pattern in gstPatterns) {
            val matcher = pattern.matcher(normalizedText)
            while (matcher.find()) {
                var candidate = matcher.group(1)?.uppercase() ?: continue
                
                // OCR Auto-Correction logic over the GST format
                // 1. First 2 chars must be digits
                candidate = candidate.replaceRange(0, 2, candidate.substring(0, 2).replace('O', '0').replace('I', '1').replace('S', '5'))
                
                // 2. Next 5 must be letters
                candidate = candidate.replaceRange(2, 7, candidate.substring(2, 7).replace('0', 'O').replace('1', 'I').replace('5', 'S'))
                
                // 3. Next 4 must be digits
                candidate = candidate.replaceRange(7, 11, candidate.substring(7, 11).replace('O', '0').replace('I', '1').replace('S', '5'))
                
                // 4. Character 11 must be letter, 12 alphanumeric, 13 must be Z
                candidate = candidate.replaceRange(11, 12, candidate.substring(11, 12).replace('0', 'O').replace('1', 'I'))
                
                // Ensure 13th is Z (often read as 2)
                if (candidate[13] == '2') candidate = candidate.replaceRange(13, 14, "Z")
                
                if (isValidGST(candidate)) {
                    return candidate
                }
            }
        }
        
        return null
    }

    private fun isValidGST(gst: String?): Boolean {
        if (gst == null || gst.length != 15) return false
        val stateCode = gst.substring(0, 2).toIntOrNull() ?: return false
        if (stateCode !in 1..37) return false
        val panPart = gst.substring(2, 12)
        if (!panPart.matches(Regex("[A-Z]{5}[0-9]{4}[A-Z]"))) return false
        if (!gst[12].isLetterOrDigit()) return false
        if (gst[13].uppercaseChar() != 'Z') return false
        if (!gst[14].isLetterOrDigit()) return false
        return true
    }

    /**
     * Invoice Number Extraction (§3 — Regex-Based Structured Extraction)
     */
    private fun extractInvoiceNumber(text: String): String? {
        for (pattern in ReceiptPatterns.invoicePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val invoiceNum = matcher.group(1)?.trim()
                if (invoiceNum != null && invoiceNum.length >= 3) {
                    Log.d(tag, "extractInvoiceNumber | Found: '$invoiceNum'")
                    return invoiceNum
                }
            }
        }

        Log.d(tag, "extractInvoiceNumber | No invoice number found")
        return null
    }

    private fun extractLineItems(lines: List<String>): List<LineItem> {
        val items = mutableListOf<LineItem>()
        val itemPattern = Pattern.compile("^(.+?)\\s+(\\d+)\\s*[xX*×]\\s*(\\d+(?:[.,]\\d{2}))\\s*(?:=)?\\s*(\\d+(?:[.,]\\d{2}))?")
        
        for (line in lines) {
            if (line.contains("total", ignoreCase = true) || line.contains("subtotal", ignoreCase = true) || line.contains("tax", ignoreCase = true) || line.contains("gst", ignoreCase = true)) continue
            
            val matcher = itemPattern.matcher(line)
            if (matcher.find()) {
                try {
                    val name = matcher.group(1)?.trim() ?: continue
                    val qty = matcher.group(2)?.toIntOrNull() ?: 1
                    val unitPrice = matcher.group(3)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                    val totalPrice = matcher.group(4)?.replace(",", "")?.toDoubleOrNull() ?: (unitPrice * qty)
                    
                    if (name.isNotEmpty() && unitPrice > 0) items.add(LineItem(name, qty, unitPrice, totalPrice))
                } catch (e: Exception) { continue }
            }
        }
        
        return items
    }

    // NOTE: Primary confidence is now computed by ConfidenceEstimator (§12).
    // This legacy method is kept for backward compatibility only.
    @Deprecated("Use ConfidenceEstimator for field-level confidence", replaceWith = ReplaceWith("ConfidenceEstimator.estimateMerchantConfidence() / estimateAmountConfidence()"))
    private fun calculateConfidence(value: String?, fieldType: String): Float {
        if (value.isNullOrEmpty() || value == "Unknown Merchant") return 0.2f
        return when (fieldType) {
            "merchant" -> when {
                value.length < 3 -> 0.4f
                value.matches(Regex("^[A-Za-z].*")) -> 0.9f
                else -> 0.7f
            }
            "amount" -> when {
                value.toDoubleOrNull() == null -> 0.3f
                value.toDouble() <= 0 -> 0.2f
                value.toDouble() > 1000000 -> 0.5f
                else -> 0.9f
            }
            else -> 0.7f
        }
    }

    private fun String.titleCase(): String {
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun logVisionText(visionText: Text) {
        val sb = StringBuilder()
        sb.append("\n=== EXACT OCR DETECTED FORMAT ===\n")
        
        for ((bIndex, block) in visionText.textBlocks.withIndex()) {
            val bRect = block.boundingBox ?: Rect()
            sb.append("BLOCK $bIndex [Top:${bRect.top}, Bottom:${bRect.bottom}, Left:${bRect.left}, Right:${bRect.right}]\n")
            sb.append("  Text: [${block.text.replace("\n", "\\n")}]\n")
            
            for ((lIndex, line) in block.lines.withIndex()) {
                val lRect = line.boundingBox ?: Rect()
                sb.append("  -> LINE $lIndex [Top:${lRect.top}, Bot:${lRect.bottom}, L:${lRect.left}, R:${lRect.right}]: \"${line.text}\"\n")
                
                // If you also want element level (words)
                // for ((eIndex, element) in line.elements.withIndex()) {
                //      val eRect = element.boundingBox ?: Rect()
                //      sb.append("       -> ELEM $eIndex [L:${eRect.left}, R:${eRect.right}]: \"${element.text}\"\n")
                // }
            }
            sb.append("--------------------------------------------------\n")
        }
        sb.append("===================================\n")
        Log.d(tag, sb.toString())
    }
}