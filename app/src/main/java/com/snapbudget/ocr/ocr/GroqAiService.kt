package com.snapbudget.ocr.ocr

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.snapbudget.ocr.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq AI service for receipt analysis.
 *
 * Two entry points:
 *  • [correctAndCategorize] — HYBRID mode: takes raw OCR text, returns corrections + category
 *  • [ocrAndParse]          — CLOUD_AI mode: takes an image, returns full parsed receipt
 */
class GroqAiService {

    private val apiKey: String = BuildConfig.GROQ_API_KEY
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"
    private val textModel = "llama-3.3-70b-versatile"
    private val visionModel = "meta-llama/llama-4-scout-17b-16e-instruct"

    companion object {
        private const val TAG = "GroqAiService"
        private const val TIMEOUT_MS = 15_000
    }

    // ─── Result ──────────────────────────────────────────────────────────

    data class AiLineItem(
        val name: String,
        val quantity: Int = 1,
        val unitPrice: Double = 0.0,
        val totalPrice: Double = 0.0
    )

    data class AiResult(
        val merchantName: String? = null,
        val totalAmount: Double? = null,
        val date: String? = null,
        val gstNumber: String? = null,
        val category: String? = null,
        val confidence: Float = 0.8f,
        val items: List<AiLineItem> = emptyList()
    )

    // ─── HYBRID mode ─────────────────────────────────────────────────────

    /**
     * Sends raw OCR text to Groq for correction and categorization.
     * Returns null on error (caller should fall back to offline result).
     */
    suspend fun correctAndCategorize(rawOcrText: String): AiResult? {
        if (apiKey.isBlank()) {
            Log.w(TAG, "correctAndCategorize: GROQ_API_KEY is blank — cannot call AI. Check .env and rebuild.")
            return null
        }
        Log.d(TAG, "correctAndCategorize: Starting Groq AI call (model=$textModel, textLen=${rawOcrText.length})")

        val prompt = """
            You are an expert Indian receipt extraction AI.
            I will give you raw OCR text from an Indian retail receipt.
            The text may have OCR errors (e.g. O→0, S→5, B→8).

            Return ONLY a valid JSON object (no markdown, no code blocks):
            {
              "merchant_name": "String",
              "total_amount": Number,
              "date": "DD/MM/YYYY or null",
              "gst_number": "String or null",
              "category": "one of: Food, Grocery, Shopping, Health, Travel, Entertainment, Utilities, Others",
              "confidence": Number (0.0-1.0),
              "items": [
                {"name": "String", "quantity": Number, "unit_price": Number, "total_price": Number}
              ]
            }

            Rules:
            1. Fix obvious OCR spelling mistakes for Indian chains (DMart, Reliance, Big Bazaar, etc).
            2. Total = final payable amount. NOT cash tendered or change.
            3. GST must be exactly 15 chars matching Indian format.
            4. Category must be one of the 8 listed values.
            5. Date MUST be the transaction, billing, or invoice date. EXPLICITLY IGNORE manufacturing (Mfg) and expiry dates.
            6. Extract ALL individual items/products listed on the receipt with their quantity, unit price, and line total.
               If quantity is not specified, assume 1. If unit_price is not clear, use total_price.

            RAW OCR TEXT:
            $rawOcrText
        """.trimIndent()

        return callTextModel(prompt)
    }

    // ─── CLOUD_AI mode ───────────────────────────────────────────────────

    /**
     * Sends a receipt image directly to Groq Vision for OCR + parsing.
     * Returns null on error.
     */
    suspend fun ocrAndParse(bitmap: Bitmap): AiResult? {
        if (apiKey.isBlank()) {
            Log.w(TAG, "ocrAndParse: GROQ_API_KEY is blank — cannot call AI. Check .env and rebuild.")
            return null
        }
        Log.d(TAG, "ocrAndParse: Starting Groq Vision AI call (model=$visionModel, img=${bitmap.width}x${bitmap.height})")

        val base64 = bitmapToBase64(bitmap)

        val systemMsg = JSONObject().apply {
            put("role", "system")
            put("content", "You are an expert Indian receipt OCR and extraction AI.")
        }

        val imageContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", """
                    Analyze this receipt image. Extract all information and return ONLY valid JSON (no markdown):
                    {
                      "merchant_name": "String",
                      "total_amount": Number,
                      "date": "DD/MM/YYYY or null",
                      "gst_number": "String or null",
                      "category": "one of: Food, Grocery, Shopping, Health, Travel, Entertainment, Utilities, Others",
                      "confidence": Number (0.0-1.0),
                      "items": [
                        {"name": "String", "quantity": Number, "unit_price": Number, "total_price": Number}
                      ]
                    }
                    Rules: Total = final payable. GST = 15 chars Indian format. Category must be one of the 8 values. Date must be the transaction/invoice date (ignore mfg/expiry dates). Extract ALL individual items/products with quantity, unit price, and line total.
                """.trimIndent())
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64")
                })
            })
        }

        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", imageContent)
        }

        val body = JSONObject().apply {
            put("model", visionModel)
            put("messages", JSONArray().apply {
                put(systemMsg)
                put(userMsg)
            })
            put("temperature", 0.1)
            put("max_tokens", 1024)
        }

        return callApi(body)
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private suspend fun callTextModel(prompt: String): AiResult? {
        val body = JSONObject().apply {
            put("model", textModel)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert Indian receipt extraction AI. Return only valid JSON.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 1024)
        }

        return callApi(body)
    }

    private suspend fun callApi(body: JSONObject): AiResult? = withContext(Dispatchers.IO) {
        try {
            val model = body.optString("model", "unknown")
            Log.d(TAG, "callApi: Sending request to Groq (model=$model, endpoint=$endpoint)")
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }

            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "Groq API error ($responseCode): $error")
                return@withContext null
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(responseText)
            val content = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            parseAiResponse(content)
        } catch (e: Exception) {
            Log.e(TAG, "Groq API call failed", e)
            null
        }
    }

    private fun parseAiResponse(raw: String): AiResult? {
        return try {
            // Strip any accidental markdown fencing
            val clean = raw
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(clean)

            // Parse line items array
            val items = mutableListOf<AiLineItem>()
            val itemsArray = json.optJSONArray("items")
            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    try {
                        val itemObj = itemsArray.getJSONObject(i)
                        val name = itemObj.optString("name", "").trim()
                        if (name.isNotBlank()) {
                            val qty = itemObj.optInt("quantity", 1).coerceAtLeast(1)
                            val unitPrice = itemObj.optDouble("unit_price", 0.0)
                            val totalPrice = itemObj.optDouble("total_price", unitPrice * qty)
                            items.add(AiLineItem(name, qty, unitPrice, totalPrice))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse item at index $i", e)
                    }
                }
                Log.d(TAG, "parseAiResponse: Parsed ${items.size} line items")
            }

            AiResult(
                merchantName = json.optString("merchant_name", "").takeIf { it.isNotBlank() && it != "null" },
                totalAmount = json.optDouble("total_amount", 0.0).takeIf { it > 0.0 },
                date = json.optString("date", "").takeIf { it.isNotBlank() && it != "null" },
                gstNumber = json.optString("gst_number", "").takeIf { it.isNotBlank() && it != "null" && it.length == 15 },
                category = json.optString("category", "").takeIf { it.isNotBlank() && it != "null" },
                confidence = json.optDouble("confidence", 0.8).toFloat(),
                items = items
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Groq response: $raw", e)
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Downscale if too large to keep request size reasonable
        val maxDim = 1024
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else bitmap

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
