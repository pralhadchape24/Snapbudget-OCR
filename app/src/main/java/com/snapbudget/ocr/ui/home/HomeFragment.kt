package com.snapbudget.ocr.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.snapbudget.ocr.R
import com.snapbudget.ocr.data.db.AppDatabase
import com.snapbudget.ocr.data.model.Transaction
import com.snapbudget.ocr.data.repository.TransactionRepository
import com.snapbudget.ocr.databinding.FragmentHomeBinding
import com.snapbudget.ocr.ui.dashboard.DashboardViewModel
import com.snapbudget.ocr.ui.dashboard.TransactionAdapter
import com.snapbudget.ocr.ui.history.TransactionHistoryActivity
import com.snapbudget.ocr.ui.receipt.ReceiptPreviewActivity
import com.snapbudget.ocr.util.CurrencyFormatter
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by activityViewModels {
        val database = AppDatabase.getDatabase(requireContext())
        val repository = TransactionRepository(database.transactionDao())
        val prefs = requireContext().getSharedPreferences("snapbudget_prefs", Context.MODE_PRIVATE)
        DashboardViewModel.Factory(repository, prefs)
    }

    private lateinit var adapter: TransactionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupGreeting()
        setupRecyclerView()
        setupObservers()
        setupClickListeners()
        setMonthLabel()
    }

    private fun setupGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        
        val prefs = requireContext().getSharedPreferences("snapbudget_prefs", Context.MODE_PRIVATE)
        val defaultName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: "User"
        val userName = prefs.getString("user_name", defaultName) ?: "User"
        
        binding.tvGreeting.text = "$greeting, $userName"
    }

    private fun setMonthLabel() {
        val calendar = Calendar.getInstance()
        val monthNames = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        binding.tvCurrentMonth.text = "${monthNames[calendar.get(Calendar.MONTH)]} ${calendar.get(Calendar.YEAR)}"
    }

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(
            onItemClick = { transaction -> openTransactionDetail(transaction) },
            onDeleteClick = { transaction -> showDeleteConfirmation(transaction) }
        )

        binding.recyclerRecentTransactions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecentTransactions.adapter = adapter
    }

    private fun setupObservers() {
        viewModel.monthlyBudget.observe(viewLifecycleOwner) { budget ->
            val spent = viewModel.monthlyTotal.value ?: 0.0
            updateIncomeAndBalance(budget, spent)
            updateBudgetProgress(budget, spent)
        }

        viewModel.monthlyTotal.observe(viewLifecycleOwner) { total ->
            val spent = total ?: 0.0
            val budget = viewModel.monthlyBudget.value ?: 0.0
            binding.tvMonthlyTotal.text = "-${CurrencyFormatter.format(spent)}"
            updateIncomeAndBalance(budget, spent)
            updateBudgetProgress(budget, spent)
        }

        viewModel.recentTransactions.observe(viewLifecycleOwner) { transactions ->
            // Limit to 5 items on Home screen
            val displayList = transactions?.take(5) ?: emptyList()
            adapter.submitList(displayList)

            if (displayList.isEmpty()) {
                binding.recyclerRecentTransactions.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.VISIBLE
                binding.tvViewAll.visibility = View.GONE
            } else {
                binding.recyclerRecentTransactions.visibility = View.VISIBLE
                binding.layoutEmptyState.visibility = View.GONE
                binding.tvViewAll.visibility = View.VISIBLE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun updateIncomeAndBalance(budget: Double, spent: Double) {
        binding.tvIncome.text = "+${CurrencyFormatter.format(budget)}"
        binding.tvBalance.text = CurrencyFormatter.format(budget - spent)
    }

    private fun updateBudgetProgress(budget: Double, spent: Double) {
        val percent = if (budget > 0) ((spent / budget) * 100).coerceAtMost(100.0) else 0.0
        val percentInt = percent.toInt()

        binding.tvBudgetPercent.text = "$percentInt%"

        // Update progress bar weights
        val fillParams = binding.progressBudget.layoutParams as LinearLayout.LayoutParams
        val remainParams = binding.progressBudgetRemaining.layoutParams as LinearLayout.LayoutParams
        fillParams.weight = percentInt.toFloat()
        remainParams.weight = (100 - percentInt).toFloat()
        binding.progressBudget.layoutParams = fillParams
        binding.progressBudgetRemaining.layoutParams = remainParams
    }

    private fun setupClickListeners() {
        binding.tvViewAll.setOnClickListener {
            startActivity(Intent(requireContext(), TransactionHistoryActivity::class.java))
        }

        binding.cardMonthlyTotal.setOnClickListener {
            showMonthSelector()
        }
    }

    private fun openTransactionDetail(transaction: Transaction) {
        val intent = Intent(requireContext(), ReceiptPreviewActivity::class.java).apply {
            putExtra(ReceiptPreviewActivity.EXTRA_TRANSACTION_ID, transaction.id)
        }
        startActivity(intent)
    }

    private fun showDeleteConfirmation(transaction: Transaction) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete Transaction")
            .setMessage("Are you sure you want to delete this transaction?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteTransaction(transaction)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMonthSelector() {
        val months = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Month")
            .setItems(months) { _, which ->
                viewModel.loadDataForMonth(currentYear, which)
                binding.tvCurrentMonth.text = "${months[which]} $currentYear"
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        setupGreeting()
        viewModel.loadDashboardData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
