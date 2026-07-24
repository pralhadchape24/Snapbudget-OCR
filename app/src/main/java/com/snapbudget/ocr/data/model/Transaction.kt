package com.snapbudget.ocr.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchantName: String,
    val amount: Double,
    val date: Date,
    val category: String,
    val gstNumber: String? = null,
    val rawOcrText: String? = null,
    val confidenceScore: Float = 0f,
    val imagePath: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val isSynced: Boolean = false,
    val notes: String? = null
)