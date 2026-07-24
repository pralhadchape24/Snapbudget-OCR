package com.snapbudget.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapbudget.ocr.categorization.CategoryClassifier
import com.snapbudget.ocr.ocr.ReceiptProcessor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OcrAccuracyTest {

    private lateinit var context: Context
    private lateinit var receiptProcessor: ReceiptProcessor
    private val tag = "OCR_ACCURACY_TEST"

    // ── Snapshot file persisted on-device so consecutive test runs can be compared ──
    private val snapshotFile by lazy {
        File(context.filesDir, "ocr_accuracy_snapshot.json")
    }

    // ── Field-level result container ─────────────────────────────────────────────
    data class BillResult(
        val fileName: String,
        val totalScore: Int,
        val merchantScore: Int,
        val amountScore: Int,
        val dateScore: Int,
        val extractedMerchant: String,
        val expectedMerchant: String,
        val extractedAmount: Double,
        val expectedAmount: Double,
        val extractedDate: String,
        val expectedDate: String,
        val processingTimeMs: Long,
        val failed: Boolean = false,
        val failReason: String = ""
    )

    // ── Scoring constants ─────────────────────────────────────────────────────────
    private val merchantFull    = 30
    private val merchantPartial = 20
    private val amountFull      = 50
    private val amountPartial   = 30
    private val dateFull        = 20

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val categoryClassifier = CategoryClassifier(context)
        receiptProcessor = ReceiptProcessor(context, categoryClassifier)
    }

    @Test
    fun testReceiptAccuracy() {
        runBlocking {
            val assetManager = InstrumentationRegistry.getInstrumentation().context.assets
            val jsonContent = assetManager.open("test_receipts/all_receipts_extended.json").use {
                InputStreamReader(it).readText()
            }
            val groundTruth = JSONObject(jsonContent)

            val currentResults  = mutableListOf<BillResult>()
            val previousSnapshot = loadSnapshot()

            // ── Process every bill ────────────────────────────────────────────────
            val keys = groundTruth.keys()
            while (keys.hasNext()) {
                val fileName = keys.next()
                val expected = groundTruth.getJSONObject(fileName)

                Log.d(tag, "")
                Log.d(tag, "╔══════════════════════════════════════════════════╗")
                Log.d(tag, "║  BILL: $fileName")
                Log.d(tag, "╚══════════════════════════════════════════════════╝")

                try {
                    val inputStream = assetManager.open("test_receipts/$fileName")
                    val bitmap     = BitmapFactory.decodeStream(inputStream)

                    val startTime = System.currentTimeMillis()
                    val result    = receiptProcessor.processReceipt(bitmap)
                    val duration  = System.currentTimeMillis() - startTime

                    if (result != null) {
                        val billResult = evaluateBill(fileName, result, expected, duration)
                        currentResults.add(billResult)
                        logBillDetail(billResult, previousSnapshot[fileName])
                    } else {
                        val failed = BillResult(
                            fileName, 0, 0, 0, 0,
                            "", expected.optString("merchant_name", "?"),
                            0.0, resolveExpectedAmount(expected),
                            "", expected.optString("date", "?"),
                            duration, failed = true, failReason = "processReceipt() returned null"
                        )
                        currentResults.add(failed)
                        Log.e(tag, "  ✖ PROCESSING FAILED — processReceipt() returned null")
                        logRegressionVsPrevious(failed, previousSnapshot[fileName])
                    }
                } catch (e: Exception) {
                    Log.e(tag, "  ✖ EXCEPTION: ${e.message}", e)
                    val failed = BillResult(
                        fileName, 0, 0, 0, 0,
                        "", expected.optString("merchant_name", "?"),
                        0.0, resolveExpectedAmount(expected),
                        "", expected.optString("date", "?"),
                        0L, failed = true, failReason = e.message ?: "unknown"
                    )
                    currentResults.add(failed)
                    logRegressionVsPrevious(failed, previousSnapshot[fileName])
                }
            }

            // ── Aggregate + diff report ───────────────────────────────────────────
            printFinalReport(currentResults, previousSnapshot)

            // ── Persist snapshot for next run ─────────────────────────────────────
            saveSnapshot(currentResults)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Evaluation
    // ─────────────────────────────────────────────────────────────────────────────

    private fun evaluateBill(
        fileName: String,
        result: ReceiptProcessor.ProcessingResult,
        expected: JSONObject,
        duration: Long
    ): BillResult {
        // ── Merchant ──────────────────────────────────────────────────────────────
        val expectedMerchant = expected.optString("merchant_name", "")
        val expectedCompany = expected.optString("company", "")
        val actualMerchant   = result.transaction.merchantName
        
        val normActual = actualMerchant.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
        val normExpected = expectedMerchant.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
        val normCompany = expectedCompany.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

        val merchantScore = when {
            normExpected.isNotEmpty() && (normActual.contains(normExpected) || normExpected.contains(normActual)) -> merchantFull
            normCompany.isNotEmpty() && (normActual.contains(normCompany) || normCompany.contains(normActual)) -> merchantFull
            levenshtein(normActual, normExpected) < 5 -> merchantPartial
            expectedCompany.isNotEmpty() && levenshtein(normActual, normCompany) < 5 -> merchantPartial
            levenshtein(actualMerchant.lowercase(), expectedMerchant.lowercase()) < 5 -> merchantPartial
            expectedCompany.isNotEmpty() && levenshtein(actualMerchant.lowercase(), expectedCompany.lowercase()) < 5 -> merchantPartial
            else -> 0
        }

        // ── Amount ────────────────────────────────────────────────────────────────
        val expectedAmount = resolveExpectedAmount(expected)
        val expectedSubtotal = expected.optDouble("subtotal", -1.0)
        val actualAmount   = result.transaction.amount
        val diff           = abs(actualAmount - expectedAmount)
        val amountScore = when {
            diff < 0.01                                          -> amountFull
            expectedAmount > 0 && diff < expectedAmount * 0.10  -> amountPartial
            expectedSubtotal > 0 && abs(actualAmount - expectedSubtotal) < 0.01 -> amountPartial
            else                                                 -> 0
        }

        // ── Date ──────────────────────────────────────────────────────────────────
        val expectedDateStr = expected.optString("date", "")
        var dateScore = 0
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        if (expectedDateStr.isNotEmpty()) {
            try {
                val expectedDate = sdf.parse(expectedDateStr)
                if (isSameDay(result.transaction.date, expectedDate)) dateScore = dateFull
            } catch (_: Exception) {}
        }

        val extractedDateStr = try { sdf.format(result.transaction.date) } catch (_: Exception) { "?" }

        return BillResult(
            fileName      = fileName,
            totalScore    = merchantScore + amountScore + dateScore,
            merchantScore = merchantScore,
            amountScore   = amountScore,
            dateScore     = dateScore,
            extractedMerchant = actualMerchant,
            expectedMerchant  = expectedMerchant,
            extractedAmount   = actualAmount,
            expectedAmount    = expectedAmount,
            extractedDate     = extractedDateStr,
            expectedDate      = expectedDateStr,
            processingTimeMs  = duration
        )
    }

    private fun resolveExpectedAmount(expected: JSONObject): Double =
        expected.optDouble("total", 0.0)

    // ─────────────────────────────────────────────────────────────────────────────
    // Per-bill logging
    // ─────────────────────────────────────────────────────────────────────────────

    private fun logBillDetail(current: BillResult, previous: BillResult?) {
        // ── Merchant ──
        val merchantStatus = fieldStatus(current.merchantScore, merchantFull)
        Log.d(tag, "  [MERCHANT]  $merchantStatus  ${current.merchantScore}/$merchantFull pts")
        Log.d(tag, "    Expected : '${current.expectedMerchant}'")
        Log.d(tag, "    Extracted: '${current.extractedMerchant}'")
        if (previous != null) {
            val delta = current.merchantScore - previous.merchantScore
            if (delta != 0) Log.d(tag, "    Δ vs prev: ${signedDelta(delta)} (${previous.extractedMerchant})")
        }

        // ── Amount ──
        val amountStatus = fieldStatus(current.amountScore, amountFull)
        val diff = abs(current.extractedAmount - current.expectedAmount)
        val pct  = if (current.expectedAmount > 0) String.format("%.1f%%", diff / current.expectedAmount * 100) else "n/a"
        Log.d(tag, "  [AMOUNT]    $amountStatus  ${current.amountScore}/$amountFull pts")
        Log.d(tag, "    Expected : ${current.expectedAmount}")
        Log.d(tag, "    Extracted: ${current.extractedAmount}  (diff=$diff, $pct)")
        if (previous != null) {
            val delta = current.amountScore - previous.amountScore
            if (delta != 0) Log.d(tag, "    Δ vs prev: ${signedDelta(delta)} (was ${previous.extractedAmount})")
        }

        // ── Date ──
        val dateStatus = fieldStatus(current.dateScore, dateFull)
        Log.d(tag, "  [DATE]      $dateStatus  ${current.dateScore}/$dateFull pts")
        Log.d(tag, "    Expected : '${current.expectedDate}'")
        Log.d(tag, "    Extracted: '${current.extractedDate}'")
        if (previous != null) {
            val delta = current.dateScore - previous.dateScore
            if (delta != 0) Log.d(tag, "    Δ vs prev: ${signedDelta(delta)} (was ${previous.extractedDate})")
        }

        // ── Total ──
        val totalDeltaStr = if (previous != null) {
            val d = current.totalScore - previous.totalScore
            if (d != 0) "  ${signedDelta(d)}" else ""
        } else ""
        Log.d(tag, "  ─────────────────────────────────────────────────")
        Log.d(tag, "  TOTAL SCORE: ${current.totalScore}/100$totalDeltaStr  |  Time: ${current.processingTimeMs}ms")
        if (previous == null) Log.d(tag, "  (no previous snapshot for comparison)")
    }

    private fun logRegressionVsPrevious(current: BillResult, previous: BillResult?) {
        if (previous != null && !previous.failed) {
            Log.e(tag, "  ⚠ REGRESSION: ${current.fileName} was ${previous.totalScore}/100, now FAILED")
        }
    }

    private fun fieldStatus(score: Int, max: Int): String = when {
        score == max -> "✔ PASS     "
        score > 0    -> "~ PARTIAL  "
        else         -> "✖ FAIL     "
    }

    private fun signedDelta(d: Int): String = if (d > 0) "▲ +$d (IMPROVED)" else "▼ $d (REGRESSED)"

    // ─────────────────────────────────────────────────────────────────────────────
    // Final aggregate report
    // ─────────────────────────────────────────────────────────────────────────────

    private fun printFinalReport(current: List<BillResult>, previous: Map<String, BillResult>) {
        val succeeded = current.filter { !it.failed }
        val failed    = current.filter { it.failed }

        val avgScore     = if (succeeded.isNotEmpty()) succeeded.map { it.totalScore }.average() else 0.0
        val avgMerchant  = if (succeeded.isNotEmpty()) succeeded.map { it.merchantScore }.average() else 0.0
        val avgAmount    = if (succeeded.isNotEmpty()) succeeded.map { it.amountScore }.average() else 0.0
        val avgDate      = if (succeeded.isNotEmpty()) succeeded.map { it.dateScore }.average() else 0.0
        val avgTime      = if (succeeded.isNotEmpty()) succeeded.map { it.processingTimeMs }.average() else 0.0

        val prevSucceeded = previous.values.filter { !it.failed }
        val prevAvg       = if (prevSucceeded.isNotEmpty()) prevSucceeded.map { it.totalScore }.average() else null

        Log.d(tag, "")
        Log.d(tag, "╔══════════════════════════════════════════════════════════╗")
        Log.d(tag, "║              FINAL ACCURACY REPORT                      ║")
        Log.d(tag, "╠══════════════════════════════════════════════════════════╣")
        Log.d(tag, "║  Bills processed : ${succeeded.size} / ${current.size}  (${failed.size} failed)")
        Log.d(tag, "║  Avg total score : ${String.format("%.2f", avgScore)}/100" +
                (if (prevAvg != null) "  (prev: ${String.format("%.2f", prevAvg)}  Δ ${String.format("%+.2f", avgScore - prevAvg)})" else "  (no previous snapshot)"))
        Log.d(tag, "║  Avg merchant    : ${String.format("%.2f", avgMerchant)}/$merchantFull")
        Log.d(tag, "║  Avg amount      : ${String.format("%.2f", avgAmount)}/$amountFull")
        Log.d(tag, "║  Avg date        : ${String.format("%.2f", avgDate)}/$dateFull")
        Log.d(tag, "║  Avg time        : ${String.format("%.0f", avgTime)}ms / bill")
        Log.d(tag, "╠══════════════════════════════════════════════════════════╣")

        // ── Per-bill score table ──────────────────────────────────────────────────
        Log.d(tag, "║  BILL SCORECARD")
        Log.d(tag, "║  %-35s %5s %5s %5s %6s %s".format("File", "Merch", "Amt", "Date", "Total", "Δ"))
        Log.d(tag, "║  " + "─".repeat(65))
        current.sortedByDescending { it.totalScore }.forEach { r ->
            val prev  = previous[r.fileName]
            val delta = if (prev != null) String.format("%+d", r.totalScore - prev.totalScore) else "new"
            val flag  = when {
                r.failed                                                    -> " ✖FAIL"
                prev != null && r.totalScore > prev.totalScore              -> " ▲"
                prev != null && r.totalScore < prev.totalScore              -> " ▼"
                else                                                        -> ""
            }
            Log.d(tag, "║  %-35s %5d %5d %5d %6d %s%s".format(
                r.fileName.take(35), r.merchantScore, r.amountScore, r.dateScore, r.totalScore, delta, flag))
        }

        // ── Improvements ─────────────────────────────────────────────────────────
        val improved = current.filter { r ->
            val p = previous[r.fileName]; p != null && r.totalScore > p.totalScore
        }
        if (improved.isNotEmpty()) {
            Log.d(tag, "╠══════════════════════════════════════════════════════════╣")
            Log.d(tag, "║  ▲ IMPROVEMENTS (${improved.size})")
            improved.forEach { r ->
                val p = previous[r.fileName]!!
                Log.d(tag, "║    ${r.fileName}")
                Log.d(tag, "║      Score: ${p.totalScore} → ${r.totalScore}  (+${r.totalScore - p.totalScore})")
                if (r.merchantScore != p.merchantScore)
                    Log.d(tag, "║      Merchant: ${p.merchantScore}→${r.merchantScore}  '${p.extractedMerchant}'→'${r.extractedMerchant}'")
                if (r.amountScore != p.amountScore)
                    Log.d(tag, "║      Amount:   ${p.amountScore}→${r.amountScore}  ${p.extractedAmount}→${r.extractedAmount}")
                if (r.dateScore != p.dateScore)
                    Log.d(tag, "║      Date:     ${p.dateScore}→${r.dateScore}  '${p.extractedDate}'→'${r.extractedDate}'")
            }
        }

        // ── Regressions ───────────────────────────────────────────────────────────
        val regressed = current.filter { r ->
            val p = previous[r.fileName]; p != null && r.totalScore < p.totalScore
        }
        if (regressed.isNotEmpty()) {
            Log.d(tag, "╠══════════════════════════════════════════════════════════╣")
            Log.d(tag, "║  ▼ REGRESSIONS (${regressed.size})")
            regressed.forEach { r ->
                val p = previous[r.fileName]!!
                Log.d(tag, "║    ${r.fileName}")
                Log.d(tag, "║      Score: ${p.totalScore} → ${r.totalScore}  (${r.totalScore - p.totalScore})")
                if (r.merchantScore != p.merchantScore)
                    Log.d(tag, "║      Merchant: ${p.merchantScore}→${r.merchantScore}  '${p.extractedMerchant}'→'${r.extractedMerchant}'")
                if (r.amountScore != p.amountScore)
                    Log.d(tag, "║      Amount:   ${p.amountScore}→${r.amountScore}  ${p.extractedAmount}→${r.extractedAmount}")
                if (r.dateScore != p.dateScore)
                    Log.d(tag, "║      Date:     ${p.dateScore}→${r.dateScore}  '${p.extractedDate}'→'${r.extractedDate}'")
            }
        }

        // ── New / removed bills ───────────────────────────────────────────────────
        val newBills = current.filter { it.fileName !in previous }
        if (newBills.isNotEmpty()) {
            Log.d(tag, "╠══════════════════════════════════════════════════════════╣")
            Log.d(tag, "║  ★ NEW BILLS (${newBills.size}): ${newBills.joinToString { it.fileName }}")
        }
        val removedBills = previous.keys.filter { k -> current.none { it.fileName == k } }
        if (removedBills.isNotEmpty()) {
            Log.d(tag, "╠══════════════════════════════════════════════════════════╣")
            Log.d(tag, "║  ✖ REMOVED BILLS (${removedBills.size}): ${removedBills.joinToString()}")
        }

        // ── Failures ─────────────────────────────────────────────────────────────
        if (failed.isNotEmpty()) {
            Log.d(tag, "╠══════════════════════════════════════════════════════════╣")
            Log.d(tag, "║  FAILED BILLS (${failed.size})")
            failed.forEach { r -> Log.d(tag, "║    ${r.fileName}: ${r.failReason}") }
        }

        Log.d(tag, "╚══════════════════════════════════════════════════════════╝")
        Log.d(tag, "")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Snapshot persistence  (simple JSON in app filesDir)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun saveSnapshot(results: List<BillResult>) {
        try {
            val json = JSONObject()
            results.forEach { r ->
                val obj = JSONObject().apply {
                    put("totalScore",    r.totalScore)
                    put("merchantScore", r.merchantScore)
                    put("amountScore",   r.amountScore)
                    put("dateScore",     r.dateScore)
                    put("extractedMerchant", r.extractedMerchant)
                    put("expectedMerchant",  r.expectedMerchant)
                    put("extractedAmount",   r.extractedAmount)
                    put("expectedAmount",    r.expectedAmount)
                    put("extractedDate",     r.extractedDate)
                    put("expectedDate",      r.expectedDate)
                    put("processingTimeMs",  r.processingTimeMs)
                    put("failed",            r.failed)
                    put("failReason",        r.failReason)
                }
                json.put(r.fileName, obj)
            }
            snapshotFile.writeText(json.toString(2))
            Log.d(tag, "Snapshot saved → ${snapshotFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save snapshot: ${e.message}")
        }
    }

    private fun loadSnapshot(): Map<String, BillResult> {
        if (!snapshotFile.exists()) {
            Log.d(tag, "No previous snapshot found — this is the baseline run.")
            return emptyMap()
        }
        return try {
            val json = JSONObject(snapshotFile.readText())
            val map  = mutableMapOf<String, BillResult>()
            json.keys().forEach { key ->
                val o = json.getJSONObject(key)
                map[key] = BillResult(
                    fileName          = key,
                    totalScore        = o.optInt("totalScore"),
                    merchantScore     = o.optInt("merchantScore"),
                    amountScore       = o.optInt("amountScore"),
                    dateScore         = o.optInt("dateScore"),
                    extractedMerchant = o.optString("extractedMerchant"),
                    expectedMerchant  = o.optString("expectedMerchant"),
                    extractedAmount   = o.optDouble("extractedAmount"),
                    expectedAmount    = o.optDouble("expectedAmount"),
                    extractedDate     = o.optString("extractedDate"),
                    expectedDate      = o.optString("expectedDate"),
                    processingTimeMs  = o.optLong("processingTimeMs"),
                    failed            = o.optBoolean("failed"),
                    failReason        = o.optString("failReason")
                )
            }
            Log.d(tag, "Previous snapshot loaded — ${map.size} bills.")
            map
        } catch (e: Exception) {
            Log.e(tag, "Failed to load snapshot: ${e.message}")
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private fun isSameDay(date1: Date, date2: Date?): Boolean {
        if (date2 == null) return false
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp[s1.length][s2.length]
    }
}