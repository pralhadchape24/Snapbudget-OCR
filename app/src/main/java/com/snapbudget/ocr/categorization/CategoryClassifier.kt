package com.snapbudget.ocr.categorization

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.snapbudget.ocr.data.model.Category
import com.snapbudget.ocr.matching.ReceiptTypeClassifier
import org.json.JSONObject

class CategoryClassifier(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("category_rules", Context.MODE_PRIVATE)
    private val tag = "CategoryClassifier"

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

    fun getUserRule(merchantName: String): Category? {
        val rules = getUserRules()
        val normalized = merchantName.trim().lowercase()
        
        rules.forEach { (pattern, categoryName) ->
            if (normalized.contains(pattern) || pattern.contains(normalized)) {
                return Category.fromName(categoryName)
            }
        }
        
        return null
    }

    fun classify(merchantName: String, itemNames: List<String> = emptyList()): Category {
        // Deprecated: Kept for compat with any lingering tests.
        // Document-level classification via ReceiptParser is the source of truth now.
        return Category.Others
    }

    fun addUserRule(merchantPattern: String, category: Category) {
        val rules = getUserRules().toMutableMap()
        rules[merchantPattern.lowercase()] = category.name
        saveUserRules(rules)
    }

    fun removeUserRule(merchantPattern: String) {
        val rules = getUserRules().toMutableMap()
        rules.remove(merchantPattern.lowercase())
        saveUserRules(rules)
    }

    fun getUserRules(): Map<String, String> {
        val rulesJson = prefs.getString("user_rules", "{}") ?: "{}"
        val json = JSONObject(rulesJson)
        val rules = mutableMapOf<String, String>()
        
        json.keys().forEach { key ->
            rules[key] = json.getString(key)
        }
        
        return rules
    }

    private fun saveUserRules(rules: Map<String, String>) {
        val json = JSONObject()
        rules.forEach { (key, value) ->
            json.put(key, value)
        }
        prefs.edit().putString("user_rules", json.toString()).apply()
    }

    fun getAllCategories(): List<Category> {
        return Category.getAllCategories()
    }

    fun getCategorySuggestions(merchantName: String): List<Pair<Category, Float>> {
        // Delegate to the powerful centralized document classifier
        val result = ReceiptTypeClassifier.classify(merchantName)
        val suggestions = mutableListOf<Pair<Category, Float>>()
        
        result.scoreBreakdown.forEach { (type, score) ->
            val cat = receiptTypeToModel[type]
            if (cat != null && cat != Category.Others) {
                suggestions.add(Pair(cat, score.toFloat()))
            }
        }

        // Always put strongest match first
        return suggestions.sortedByDescending { it.second }.take(3)
    }
}