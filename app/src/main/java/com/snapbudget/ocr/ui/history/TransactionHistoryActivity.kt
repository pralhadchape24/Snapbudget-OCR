package com.snapbudget.ocr.ui.history

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.snapbudget.ocr.R
import com.snapbudget.ocr.data.model.Category
import com.snapbudget.ocr.data.model.Transaction
import com.snapbudget.ocr.data.repository.TransactionRepository
import com.snapbudget.ocr.databinding.ActivityTransactionHistoryBinding
import com.snapbudget.ocr.ui.dashboard.TransactionAdapter
import com.snapbudget.ocr.ui.receipt.ReceiptPreviewActivity
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.snapbudget.ocr.data.db.AppDatabase

class TransactionHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransactionHistoryBinding
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var adapter: TransactionAdapter

    private var currentFilterCategory: String? = null
    private var startDate: Calendar? = null
    private var endDate: Calendar? = null

    // Track current LiveData & observer to avoid leak
    private var currentLiveData: LiveData<List<Transaction>>? = null
    private val transactionObserver = Observer<List<Transaction>> { transactions ->
        displayTransactions(transactions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Transaction History"

        // Initialize repository
        val database = AppDatabase.getDatabase(this)
        transactionRepository = TransactionRepository(database.transactionDao())

        setupRecyclerView()
        setupFilters()
        loadTransactions()
    }

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(
            onItemClick = { transaction ->
                openTransactionDetail(transaction)
            },
            onDeleteClick = { transaction ->
                confirmDelete(transaction)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupFilters() {
        // Category filter — Material Exposed Dropdown
        val categories = listOf("All") + Category.getAllCategories().map { it.displayName }
        val categoryAdapter = ArrayAdapter(this, R.layout.list_item_dropdown, categories)
        val dropdown = binding.spinnerFilterCategory
        dropdown.setAdapter(categoryAdapter)
        dropdown.setText("All", false)

        dropdown.setOnItemClickListener { _, _, position, _ ->
            currentFilterCategory = if (position == 0) null else categories[position]
            applyFilters()
        }

        // Date range filters
        binding.btnFilterDateFrom.setOnClickListener {
            showDatePicker(true)
        }

        binding.btnFilterDateTo.setOnClickListener {
            showDatePicker(false)
        }

        binding.btnClearFilters.setOnClickListener {
            clearFilters()
        }
    }

    private fun showDatePicker(isFrom: Boolean) {
        val calendar = Calendar.getInstance()
        
        DatePickerDialog(
            this,
            { _, year, month, day ->
                // Clone the calendar to avoid shared-reference issues
                val selectedCal = Calendar.getInstance()
                selectedCal.set(year, month, day, 0, 0, 0)
                selectedCal.set(Calendar.MILLISECOND, 0)
                
                if (isFrom) {
                    startDate = selectedCal.clone() as Calendar
                    binding.btnFilterDateFrom.text = "From: ${day}/${month + 1}/${year}"
                } else {
                    selectedCal.set(Calendar.HOUR_OF_DAY, 23)
                    selectedCal.set(Calendar.MINUTE, 59)
                    selectedCal.set(Calendar.SECOND, 59)
                    endDate = selectedCal.clone() as Calendar
                    binding.btnFilterDateTo.text = "To: ${day}/${month + 1}/${year}"
                }
                applyFilters()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun clearFilters() {
        currentFilterCategory = null
        startDate = null
        endDate = null
        binding.spinnerFilterCategory.setText("All", false)
        binding.btnFilterDateFrom.text = "From Date"
        binding.btnFilterDateTo.text = "To Date"
        loadTransactions()
    }

    /**
     * Observes a new LiveData, removing the observer from any previously observed LiveData first.
     */
    private fun observeTransactions(liveData: LiveData<List<Transaction>>) {
        // Remove observer from the old LiveData source
        currentLiveData?.removeObserver(transactionObserver)
        currentLiveData = liveData
        liveData.observe(this, transactionObserver)
    }

    private fun applyFilters() {
        when {
            startDate != null && endDate != null -> {
                val source = transactionRepository.getTransactionsByDateRange(
                    startDate!!.time,
                    endDate!!.time
                )
                observeTransactions(source)
            }
            currentFilterCategory != null -> {
                val category = Category.getAllCategories()
                    .find { it.displayName == currentFilterCategory }?.name ?: "OTHERS"
                val source = transactionRepository.getTransactionsByCategory(category)
                observeTransactions(source)
            }
            else -> {
                loadTransactions()
            }
        }
    }

    private fun loadTransactions() {
        observeTransactions(transactionRepository.allTransactions)
    }

    private fun displayTransactions(transactions: List<Transaction>) {
        // Apply category filter on top of date-filtered results if both are active
        val filtered = if (currentFilterCategory != null && startDate != null && endDate != null) {
            val categoryName = Category.getAllCategories()
                .find { it.displayName == currentFilterCategory }?.name
            transactions.filter { it.category == categoryName }
        } else {
            transactions
        }
        
        adapter.submitList(filtered)
        
        // Show/hide empty state
        if (filtered.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.tvEmptyState.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE
        }

        // Update total
        val total = filtered.sumOf { it.amount }
        binding.tvTotalAmount.text = String.format("Total: ₹%.2f", total)
    }

    private fun openTransactionDetail(transaction: Transaction) {
        val intent = Intent(this, ReceiptPreviewActivity::class.java).apply {
            putExtra(ReceiptPreviewActivity.EXTRA_TRANSACTION_ID, transaction.id)
        }
        startActivity(intent)
    }

    private fun confirmDelete(transaction: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("Delete Transaction")
            .setMessage("Are you sure you want to delete this transaction?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    transactionRepository.deleteTransaction(transaction)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }
            
            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { searchTransactions(it) }
                return true
            }
        })
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_export -> {
                exportTransactions()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun searchTransactions(query: String) {
        if (query.length >= 2) {
            val source = transactionRepository.searchTransactions(query)
            observeTransactions(source)
        } else if (query.isEmpty()) {
            loadTransactions()
        }
    }

    private fun exportTransactions() {
        lifecycleScope.launch {
            try {
                val transactions = adapter.currentList
                if (transactions.isEmpty()) {
                    Toast.makeText(this@TransactionHistoryActivity, 
                        "No transactions to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(System.currentTimeMillis())
                
                val exportDir = File(cacheDir, "exports").also { it.mkdirs() }
                val file = File(exportDir, "snapbudget_export_$fileTimestamp.csv")
                
                FileWriter(file).use { writer ->
                    // CSV Header
                    writer.append("Merchant,Amount,Date,Category,GST Number,Notes\n")
                    
                    for (t in transactions) {
                        val merchant = t.merchantName.replace(",", ";")
                        val date = dateFormat.format(t.date)
                        val category = Category.fromName(t.category).displayName
                        val gst = t.gstNumber ?: ""
                        val notes = (t.notes ?: "").replace(",", ";")
                        writer.append("\"$merchant\",${t.amount},\"$date\",\"$category\",\"$gst\",\"$notes\"\n")
                    }
                }
                
                // Share the CSV file
                val uri = FileProvider.getUriForFile(
                    this@TransactionHistoryActivity,
                    "${packageName}.fileprovider",
                    file
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_SUBJECT, "SnapBudget Export")
                }
                startActivity(Intent.createChooser(shareIntent, "Export Transactions"))
                
            } catch (e: Exception) {
                Toast.makeText(this@TransactionHistoryActivity, 
                    "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up observer
        currentLiveData?.removeObserver(transactionObserver)
    }
}