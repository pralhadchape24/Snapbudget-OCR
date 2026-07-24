package com.snapbudget.ocr.ui.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapbudget.ocr.data.repository.TransactionRepository
import kotlinx.coroutines.launch
import java.util.Calendar

data class CategoryBudget(
    val categoryName: String,
    val displayName: String,
    val color: String,
    val budgetAmount: Double,
    val spentAmount: Double
) {
    val percentage: Double get() = if (budgetAmount > 0) (spentAmount / budgetAmount * 100) else 0.0
    val isOverBudget: Boolean get() = spentAmount > budgetAmount
    val remaining: Double get() = budgetAmount - spentAmount
}

class WalletViewModel(
    private val repository: TransactionRepository,
    private val prefs: SharedPreferences
) : ViewModel() {

    private val _monthlyBudget = MutableLiveData<Double>()
    val monthlyBudget: LiveData<Double> = _monthlyBudget

    private val _totalSpent = MutableLiveData<Double>()
    val totalSpent: LiveData<Double> = _totalSpent

    private val _categoryBudgets = MutableLiveData<List<CategoryBudget>>()
    val categoryBudgets: LiveData<List<CategoryBudget>> = _categoryBudgets

    private val _hasBudget = MutableLiveData<Boolean>()
    val hasBudget: LiveData<Boolean> = _hasBudget

    init {
        loadBudgetData()
    }

    fun loadBudgetData() {
        val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
        _monthlyBudget.value = budget
        _hasBudget.value = budget > 0

        if (budget > 0) {
            loadSpendingData(budget)
        }
    }

    private fun loadSpendingData(budget: Double) {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH)

            val spent = repository.getTotalExpensesForMonth(year, month)
            _totalSpent.value = spent

            val categoryData = repository.getCategoryWiseExpensesForMonth(year, month)

            // Default budget split evenly across categories that have spending
            val categories = com.snapbudget.ocr.data.model.Category.getAllCategories()
            val catBudgets = categories.mapNotNull { cat ->
                val catBudgetAmount = prefs.getFloat("budget_${cat.name}", 0f).toDouble()
                val catSpent = categoryData[cat.name] ?: 0.0

                // Only show categories with budget or spending
                if (catBudgetAmount > 0 || catSpent > 0) {
                    CategoryBudget(
                        categoryName = cat.name,
                        displayName = cat.displayName,
                        color = cat.color,
                        budgetAmount = if (catBudgetAmount > 0) catBudgetAmount else budget / categories.size,
                        spentAmount = catSpent
                    )
                } else null
            }.sortedByDescending { it.spentAmount }

            _categoryBudgets.value = catBudgets
        }
    }

    fun setMonthlyBudget(amount: Double) {
        prefs.edit().putFloat("monthly_budget", amount.toFloat()).apply()
        _monthlyBudget.value = amount
        _hasBudget.value = amount > 0
        loadSpendingData(amount)
    }

    class Factory(
        private val repository: TransactionRepository,
        private val prefs: SharedPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WalletViewModel::class.java)) {
                return WalletViewModel(repository, prefs) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
