package com.snapbudget.ocr.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {
    
    // SimpleDateFormat is NOT thread-safe, so create new instances per call
    
    fun format(date: Date): String {
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
    }
    
    fun formatShort(date: Date): String {
        return SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(date)
    }
    
    fun formatWithTime(date: Date): String {
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
        return "$dateStr $timeStr"
    }
}