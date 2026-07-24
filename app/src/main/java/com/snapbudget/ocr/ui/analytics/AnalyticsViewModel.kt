package com.snapbudget.ocr.ui.analytics

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieEntry
import com.snapbudget.ocr.data.db.TransactionDao
import com.snapbudget.ocr.data.repository.TransactionRepository
import kotlinx.coroutines.launch
import java.util.Calendar

class AnalyticsViewModel(private val repository: TransactionRepository) : ViewModel() {

    private val _dailySpending = MutableLiveData<List<Entry>>()
    val dailySpending: LiveData<List<Entry>> = _dailySpending

    private val _pieChartEntries = MutableLiveData<List<PieEntry>>()
    val pieChartEntries: LiveData<List<PieEntry>> = _pieChartEntries

    private val _categoryList = MutableLiveData<List<Pair<String, Double>>>()
    val categoryList: LiveData<List<Pair<String, Double>>> = _categoryList

    private val _topMerchants = MutableLiveData<List<TransactionDao.MerchantSpending>>()
    val topMerchants: LiveData<List<TransactionDao.MerchantSpending>> = _topMerchants

    private val _hasData = MutableLiveData<Boolean>()
    val hasData: LiveData<Boolean> = _hasData

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private var currentYear: Int
    private var currentMonth: Int

    init {
        val cal = Calendar.getInstance()
        currentYear = cal.get(Calendar.YEAR)
        currentMonth = cal.get(Calendar.MONTH)
        loadAnalytics()
    }

    fun loadAnalytics(year: Int = currentYear, month: Int = currentMonth) {
        currentYear = year
        currentMonth = month

        viewModelScope.launch {
            _isLoading.value = true

            // Category breakdown
            val categoryData = repository.getCategoryWiseExpensesForMonth(year, month)
            if (categoryData.isEmpty()) {
                _hasData.value = false
                _isLoading.value = false
                return@launch
            }

            _hasData.value = true

            // Pie entries
            val pieEntries = categoryData
                .entries
                .sortedByDescending { it.value }
                .map { (cat, amount) -> PieEntry(amount.toFloat(), cat) }
            _pieChartEntries.value = pieEntries

            // Category list sorted by spend
            _categoryList.value = categoryData
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value }

            // Daily spending for line chart
            val daily = repository.getDailySpending(year, month)
            val lineEntries = daily.mapIndexed { index, ds ->
                Entry(index.toFloat(), ds.total.toFloat())
            }
            _dailySpending.value = lineEntries

            // Top merchants
            val merchants = repository.getTopMerchants(year, month, 5)
            _topMerchants.value = merchants

            _isLoading.value = false
        }
    }

    class Factory(private val repository: TransactionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AnalyticsViewModel::class.java)) {
                return AnalyticsViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
