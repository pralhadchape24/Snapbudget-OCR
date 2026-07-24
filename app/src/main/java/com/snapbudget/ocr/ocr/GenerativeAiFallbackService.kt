package com.snapbudget.ocr.ocr

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import org.json.JSONObject

class GenerativeAiFallbackService(private val context: Context) {
    private val tag = "GenerativeAiFallbackService"

    suspend fun analyzeReceipt(rawText: String, currentConfidence: Float): AiFallbackResult? {
        return try {
            val apiKey = context.getString(context.resources.getIdentifier("gemini_api_key", "string", context.packageName))
            if (apiKey.isEmpty()) {
                Log.w(tag, "Gemini API key missing. Skipping fallback.")
                return null
            }

            val generativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = apiKey
            )

            val prompt = """
                You are an expert India receipt extraction AI. I am going to give you raw OCR text from an Indian retail receipt.
                Please extract the following information and return ONLY a valid JSON object with the exact following schema:
                {
                    "merchant_name": "String",
                    "total_amount": Number,
                    "date": "DD/MM/YYYY or null",
                    "gst_number": "String or null",
                    "confidence_score": Number (between 0.0 and 1.0)
                }
                
                Rules:
                1. Fix obvious OCR spelling mistakes for Indian chains (e.g. DMart, Domino's, Reliance).
                2. Total Amount should be the final payable amount containing decimals. Exclude "cash tendered" or "change".
                3. GST number must be exactly 15 characters, conforming to Indian GST format (2 digits, 5 chars, 4 digits, 1 char, 1 char, Z, 1 char).
                4. Date MUST be the transaction, billing, or invoice date. EXPLICITLY IGNORE manufacturing (mfg) and expiry dates.
                5. DO NOT wrap JSON in code blocks. Just return the raw JSON object.

                RAW OCR TEXT:
                $rawText
            """.trimIndent()

            val response = generativeModel.generateContent(prompt)
            val responseText = response.text?.trim() ?: ""
            Log.d(tag, "Gemini Response: $responseText")

            val cleanJson = responseText.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(cleanJson)

            AiFallbackResult(
                merchantName = json.optString("merchant_name", "").takeIf { it.isNotEmpty() && it != "null" },
                totalAmount = json.optDouble("total_amount", 0.0).takeIf { it > 0.0 },
                date = json.optString("date", "").takeIf { it.isNotEmpty() && it != "null" },
                gstNumber = json.optString("gst_number", "").takeIf { it.isNotEmpty() && it != "null" && it.length == 15 },
                confidenceScore = json.optDouble("confidence_score", 0.8).toFloat()
            )
        } catch (e: Exception) {
            Log.e(tag, "Gemini AI fallback error", e)
            null
        }
    }

    data class AiFallbackResult(
        val merchantName: String?,
        val totalAmount: Double?,
        val date: String?,
        val gstNumber: String?,
        val confidenceScore: Float
    )
}
