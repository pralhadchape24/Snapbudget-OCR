package com.snapbudget.ocr.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.PieEntry
import com.snapbudget.ocr.data.model.Transaction
import com.snapbudget.ocr.data.repository.TransactionRepository
import android.content.SharedPreferences
import kotlinx.coroutines.launch
import java.util.Calendar

class DashboardViewModel(
    private val repository: TransactionRepository,
    private val prefs: SharedPreferences
) : ViewModel() {

    private val _monthlyTotal = MutableLiveData<Double>()
    val monthlyTotal: LiveData<Double> = _monthlyTotal

    private val _monthlyBudget = MutableLiveData<Double>()
    val monthlyBudget: LiveData<Double> = _monthlyBudget

    private val _categoryBreakdown = MutableLiveData<Map<String, Double>>()
    val categoryBreakdown: LiveData<Map<String, Double>> = _categoryBreakdown

    private val _pieChartEntries = MutableLiveData<List<PieEntry>>()
    val pieChartEntries: LiveData<List<PieEntry>> = _pieChartEntries

    val recentTransactions: LiveData<List<Transaction>> = repository.getRecentTransactions(10)

    val allTransactions: LiveData<List<Transaction>> = repository.allTransactions

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading



    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _isLoading.value = true
            
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            
            val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
            _monthlyBudget.value = budget
            
            // Load monthly total
            val total = repository.getTotalExpensesForMonth(year, month)
            _monthlyTotal.value = total
            
            // Load category breakdown
            val categoryData = repository.getCategoryWiseExpensesForMonth(year, month)
            _categoryBreakdown.value = categoryData
            
            // Convert to pie chart entries
            val pieEntries = categoryData.map { (category, amount) ->
                PieEntry(amount.toFloat(), category)
            }
            _pieChartEntries.value = pieEntries
            
            _isLoading.value = false
        }
    }

    fun loadDataForMonth(year: Int, month: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            
            val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
            _monthlyBudget.value = budget
            
            val total = repository.getTotalExpensesForMonth(year, month)
            _monthlyTotal.value = total
            
            val categoryData = repository.getCategoryWiseExpensesForMonth(year, month)
            _categoryBreakdown.value = categoryData
            
            val pieEntries = categoryData.map { (category, amount) ->
                PieEntry(amount.toFloat(), category)
            }
            _pieChartEntries.value = pieEntries
            
            _isLoading.value = false
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            // Refresh dashboard data after deletion
            loadDashboardData()
        }
    }


    class Factory(
        private val repository: TransactionRepository,
        private val prefs: SharedPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
                return DashboardViewModel(repository, prefs) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}