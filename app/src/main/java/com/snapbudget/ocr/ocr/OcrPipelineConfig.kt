package com.snapbudget.ocr.ocr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.snapbudget.ocr.BuildConfig

/**
 * OCR Pipeline operating modes.
 *
 *  OFFLINE   — ML Kit OCR + regex parsing, no network
 *  HYBRID    — ML Kit OCR + regex, then Groq AI for correction/categorization
 *  CLOUD_AI  — Groq Vision API does OCR + parsing + categorization
 */
enum class OcrPipelineMode {
    OFFLINE,
    HYBRID,
    CLOUD_AI
}

/**
 * Reads/writes the current pipeline mode and validates API key availability.
 */
object OcrPipelineConfig {

    private const val PREFS_NAME = "ocr_pipeline_prefs"
    private const val KEY_MODE = "pipeline_mode"
    private const val TAG = "OcrPipelineConfig"

    fun getMode(context: Context): OcrPipelineMode {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_MODE, OcrPipelineMode.OFFLINE.name)
            ?: OcrPipelineMode.OFFLINE.name

        val requested = try {
            OcrPipelineMode.valueOf(stored)
        } catch (_: Exception) {
            OcrPipelineMode.OFFLINE
        }

        // Force OFFLINE if API key is missing but an AI mode was selected
        if (requested != OcrPipelineMode.OFFLINE && !hasApiKey()) {
            Log.w(TAG, "No GROQ_API_KEY found — falling back to OFFLINE mode")
            return OcrPipelineMode.OFFLINE
        }

        return requested
    }

    fun setMode(context: Context, mode: OcrPipelineMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        Log.d(TAG, "Pipeline mode set to ${mode.name}")
    }

    fun hasApiKey(): Boolean =
        BuildConfig.GROQ_API_KEY.isNotBlank()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
