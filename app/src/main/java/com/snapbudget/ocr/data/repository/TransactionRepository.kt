package com.snapbudget.ocr.data.repository

import androidx.lifecycle.LiveData
import com.snapbudget.ocr.data.db.TransactionDao
import com.snapbudget.ocr.data.model.Transaction
import java.util.*

class TransactionRepository(private val transactionDao: TransactionDao) {

    val allTransactions: LiveData<List<Transaction>> = transactionDao.getAllTransactions()

    suspend fun insertTransaction(transaction: Transaction): Long {
        return transactionDao.insertTransaction(transaction)
    }

    suspend fun updateTransaction(transaction: Transaction) {
        transactionDao.updateTransaction(transaction)
    }

    suspend fun deleteTransaction(transaction: Transaction) {
        transactionDao.deleteTransaction(transaction)
    }

    suspend fun deleteTransactionById(id: Long) {
        transactionDao.deleteTransactionById(id)
    }

    suspend fun getTransactionById(id: Long): Transaction? {
        return transactionDao.getTransactionById(id)
    }

    fun getRecentTransactions(limit: Int = 10): LiveData<List<Transaction>> {
        return transactionDao.getRecentTransactions(limit)
    }

    fun getTransactionsByDateRange(startDate: Date, endDate: Date): LiveData<List<Transaction>> {
        return transactionDao.getTransactionsByDateRange(startDate, endDate)
    }

    fun getTransactionsByCategory(category: String): LiveData<List<Transaction>> {
        return transactionDao.getTransactionsByCategory(category)
    }

    suspend fun getTotalExpensesForMonth(year: Int, month: Int): Double = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, 1, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0) // Clear milliseconds for precision
        val startDate = calendar.time
        
        calendar.add(Calendar.MONTH, 1)
        calendar.set(Calendar.MILLISECOND, 0)
        val endDate = calendar.time
        
        return@withContext transactionDao.getTotalExpensesForPeriod(startDate, endDate) ?: 0.0
    }

    suspend fun getCategoryWiseExpensesForMonth(year: Int, month: Int): Map<String, Double> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, 1, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startDate = calendar.time
        
        calendar.add(Calendar.MONTH, 1)
        calendar.set(Calendar.MILLISECOND, 0)
        val endDate = calendar.time
        
        return@withContext transactionDao.getCategoryWiseExpenses(startDate, endDate)
            .associate { it.category to it.total }
    }

    fun searchTransactions(query: String): LiveData<List<Transaction>> {
        return transactionDao.searchTransactions(query)
    }

    suspend fun getDailySpending(year: Int, month: Int): List<TransactionDao.DailySpending> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, 1, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startDate = calendar.time

        calendar.add(Calendar.MONTH, 1)
        calendar.set(Calendar.MILLISECOND, 0)
        val endDate = calendar.time

        return@withContext transactionDao.getDailySpending(startDate, endDate)
    }

    suspend fun getTopMerchants(year: Int, month: Int, limit: Int = 5): List<TransactionDao.MerchantSpending> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, 1, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startDate = calendar.time

        calendar.add(Calendar.MONTH, 1)
        calendar.set(Calendar.MILLISECOND, 0)
        val endDate = calendar.time

        return@withContext transactionDao.getTopMerchants(startDate, endDate, limit)
    }

    suspend fun deleteAllTransactions() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        transactionDao.deleteAllTransactions()
    }
}