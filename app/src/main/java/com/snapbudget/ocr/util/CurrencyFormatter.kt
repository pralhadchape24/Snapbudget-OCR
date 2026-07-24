package com.snapbudget.ocr.util

import java.text.NumberFormat
import java.util.Locale

object CurrencyFormatter {
    
    fun format(amount: Double): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        return formatter.format(amount)
    }
    
    fun formatWithoutSymbol(amount: Double): String {
        return String.format("%.2f", amount)
    }
}