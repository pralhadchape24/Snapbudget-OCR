package com.snapbudget.ocr.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.snapbudget.ocr.data.model.Transaction
import java.util.Date

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, createdAt DESC")
    fun getAllTransactions(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): Transaction?

    @Query("SELECT * FROM transactions ORDER BY date DESC LIMIT :limit")
    fun getRecentTransactions(limit: Int): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentTransactionsSync(limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    fun getTransactionsByDateRange(startDate: Date, endDate: Date): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE category = :category ORDER BY date DESC")
    fun getTransactionsByCategory(category: String): LiveData<List<Transaction>>

    @Query("SELECT SUM(amount) FROM transactions WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalExpensesForPeriod(startDate: Date, endDate: Date): Double?

    @Query("SELECT category, SUM(amount) as total FROM transactions WHERE date BETWEEN :startDate AND :endDate GROUP BY category")
    suspend fun getCategoryWiseExpenses(startDate: Date, endDate: Date): List<CategoryExpense>

    @Query("SELECT * FROM transactions WHERE merchantName LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%' ORDER BY date DESC")
    fun searchTransactions(query: String): LiveData<List<Transaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction): Long

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun getTransactionCount(): Int

    @Query("SELECT date as day, SUM(amount) as total FROM transactions WHERE date BETWEEN :startDate AND :endDate GROUP BY date ORDER BY date ASC")
    suspend fun getDailySpending(startDate: Date, endDate: Date): List<DailySpending>

    @Query("SELECT merchantName, COUNT(*) as txnCount, SUM(amount) as totalSpent FROM transactions WHERE date BETWEEN :startDate AND :endDate GROUP BY merchantName ORDER BY totalSpent DESC LIMIT :limit")
    suspend fun getTopMerchants(startDate: Date, endDate: Date, limit: Int = 5): List<MerchantSpending>

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    data class CategoryExpense(
        val category: String,
        val total: Double
    )

    data class DailySpending(
        val day: Date,
        val total: Double
    )

    data class MerchantSpending(
        val merchantName: String,
        val txnCount: Int,
        val totalSpent: Double
    )
}