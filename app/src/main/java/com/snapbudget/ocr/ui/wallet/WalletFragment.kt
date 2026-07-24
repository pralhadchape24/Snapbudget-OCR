package com.snapbudget.ocr.ui.wallet

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.snapbudget.ocr.R
import com.snapbudget.ocr.data.db.AppDatabase
import com.snapbudget.ocr.data.model.Category
import com.snapbudget.ocr.data.model.Transaction
import com.snapbudget.ocr.data.repository.TransactionRepository
import com.snapbudget.ocr.databinding.FragmentWalletBinding
import com.snapbudget.ocr.util.CurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class WalletFragment : Fragment() {

    private var _binding: FragmentWalletBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WalletViewModel by viewModels {
        val database = AppDatabase.getDatabase(requireContext())
        val repository = TransactionRepository(database.transactionDao())
        val prefs = requireContext().getSharedPreferences("snapbudget_prefs", Context.MODE_PRIVATE)
        WalletViewModel.Factory(repository, prefs)
    }

    // Keep repository reference for manual bill insertion
    private val repository: TransactionRepository by lazy {
        val database = AppDatabase.getDatabase(requireContext())
        TransactionRepository(database.transactionDao())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWalletBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        setupObservers()
    }

    private fun setupClickListeners() {
        binding.btnSetBudget.setOnClickListener { showBudgetDialog() }
        binding.btnEditBudget.setOnClickListener { showBudgetDialog() }
        binding.cardBudgetOverview.setOnClickListener { showBudgetDialog() }
        binding.fabAddBill.setOnClickListener { showAddBillDialog() }
    }

    private fun setupObservers() {
        viewModel.hasBudget.observe(viewLifecycleOwner) { hasBudget ->
            binding.layoutWalletEmpty.visibility = if (hasBudget) View.GONE else View.VISIBLE
            binding.layoutWalletContent.visibility = if (hasBudget) View.VISIBLE else View.GONE
        }

        viewModel.monthlyBudget.observe(viewLifecycleOwner) { budget ->
            binding.tvBudgetAmount.text = CurrencyFormatter.format(budget)
        }

        viewModel.totalSpent.observe(viewLifecycleOwner) { spent ->
            val budget = viewModel.monthlyBudget.value ?: 0.0
            val remaining = budget - spent
            val percent = if (budget > 0) (spent / budget * 100).coerceAtMost(100.0) else 0.0
            val percentInt = percent.toInt()

            binding.tvBudgetSpent.text = "${CurrencyFormatter.format(spent)} spent"
            binding.tvBudgetRemaining.text = "${CurrencyFormatter.format(kotlin.math.abs(remaining))} ${if (remaining >= 0) "remaining" else "over budget"}"
            binding.tvBudgetRemaining.setTextColor(Color.parseColor(if (remaining >= 0) "#00D68F" else "#FF6B6B"))
            binding.tvBudgetPercent.text = "$percentInt%"

            // Update progress bar
            val fillParams = binding.progressBudgetFill.layoutParams as LinearLayout.LayoutParams
            val remainParams = binding.progressBudgetRemain.layoutParams as LinearLayout.LayoutParams
            fillParams.weight = percentInt.toFloat()
            remainParams.weight = (100 - percentInt).toFloat()
            binding.progressBudgetFill.layoutParams = fillParams
            binding.progressBudgetRemain.layoutParams = remainParams
        }

        viewModel.categoryBudgets.observe(viewLifecycleOwner) { budgets ->
            buildCategoryBudgets(budgets)
        }
    }

    private fun buildCategoryBudgets(budgets: List<CategoryBudget>) {
        binding.layoutCategoryBudgets.removeAllViews()

        for (budget in budgets) {
            val catColor = try { Color.parseColor(budget.color) } catch (e: Exception) { Color.GRAY }
            val percentClamped = budget.percentage.coerceAtMost(100.0).toInt()

            // Determine progress bar color based on percentage
            val progressColor = when {
                budget.percentage >= 95 -> Color.parseColor("#FF6B6B")   // danger
                budget.percentage >= 80 -> Color.parseColor("#FF8C47")   // orange
                budget.percentage >= 60 -> Color.parseColor("#FFB547")   // warning
                else -> Color.parseColor("#00D68F")                       // positive
            }

            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.spacing_3)
                }
                radius = resources.getDimensionPixelSize(R.dimen.radius_lg).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor("#161B24"))
                strokeColor = Color.parseColor("#12FFFFFF")
                strokeWidth = 1
            }

            val innerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val pad = resources.getDimensionPixelSize(R.dimen.spacing_4)
                setPadding(pad, pad, pad, pad)
            }

            // Row 1: Icon + Category Name + Over Budget label
            val row1 = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val icon = android.widget.ImageView(requireContext()).apply {
                val cat = Category.fromName(budget.categoryName)
                setImageResource(cat.iconResId)
                setColorFilter(catColor)
                val iconSize = resources.getDimensionPixelSize(R.dimen.icon_size)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_2)
                }
            }
            row1.addView(icon)

            val catName = TextView(requireContext()).apply {
                text = budget.displayName
                setTextColor(Color.parseColor("#EEF0F4"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(catName)

            if (budget.isOverBudget) {
                val overLabel = TextView(requireContext()).apply {
                    text = "OVER BUDGET"
                    setTextColor(Color.parseColor("#FF6B6B"))
                    textSize = 9f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                row1.addView(overLabel)
            }
            innerLayout.addView(row1)

            // Row 2: Spent / Budget
            val amounts = TextView(requireContext()).apply {
                text = "${CurrencyFormatter.format(budget.spentAmount)} / ${CurrencyFormatter.format(budget.budgetAmount)}"
                setTextColor(Color.parseColor("#8899AA"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = resources.getDimensionPixelSize(R.dimen.spacing_1)
                layoutParams = lp
            }
            innerLayout.addView(amounts)

            // Row 3: Progress bar + percentage
            val progressRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val topMargin = resources.getDimensionPixelSize(R.dimen.spacing_2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { this.topMargin = topMargin }
            }

            val progressTrack = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, resources.getDimensionPixelSize(R.dimen.spacing_1), 1f).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_2)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 100f
                    setColor(Color.parseColor("#1FFFFFFF"))
                }
            }

            val fillView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, percentClamped.toFloat())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 100f
                    setColor(progressColor)
                }
            }
            progressTrack.addView(fillView)

            val remainView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - percentClamped).toFloat())
            }
            progressTrack.addView(remainView)

            progressRow.addView(progressTrack)

            val percentTv = TextView(requireContext()).apply {
                text = "${budget.percentage.toInt()}%"
                setTextColor(Color.parseColor("#8899AA"))
                textSize = 11f
            }
            progressRow.addView(percentTv)

            innerLayout.addView(progressRow)

            card.addView(innerLayout)
            binding.layoutCategoryBudgets.addView(card)
        }
    }

    // ─── Custom Budget Dialog ───────────────────────────────────────────
    private fun showBudgetDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_set_budget, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        // Transparent background so our custom shape shows
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        val etAmount = dialogView.findViewById<EditText>(R.id.etBudgetAmount)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveBudget)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelBudget)
        val chip5k = dialogView.findViewById<MaterialButton>(R.id.chip5k)
        val chip10k = dialogView.findViewById<MaterialButton>(R.id.chip10k)
        val chip20k = dialogView.findViewById<MaterialButton>(R.id.chip20k)
        val chip50k = dialogView.findViewById<MaterialButton>(R.id.chip50k)

        // Pre-fill current budget
        val currentBudget = viewModel.monthlyBudget.value ?: 0.0
        if (currentBudget > 0) {
            etAmount.setText(currentBudget.toInt().toString())
            etAmount.selectAll()
        }

        // Quick-fill chips
        chip5k.setOnClickListener { etAmount.setText("5000"); etAmount.setSelection(etAmount.text.length) }
        chip10k.setOnClickListener { etAmount.setText("10000"); etAmount.setSelection(etAmount.text.length) }
        chip20k.setOnClickListener { etAmount.setText("20000"); etAmount.setSelection(etAmount.text.length) }
        chip50k.setOnClickListener { etAmount.setText("50000"); etAmount.setSelection(etAmount.text.length) }

        btnSave.setOnClickListener {
            val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (amount > 0) {
                viewModel.setMonthlyBudget(amount)
                dialog.dismiss()
            } else {
                etAmount.error = "Enter a valid amount"
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()

        // Make dialog width match parent with margins
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ─── Manual Bill Entry Dialog ───────────────────────────────────────
    private fun showAddBillDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_bill, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        val etMerchant = dialogView.findViewById<EditText>(R.id.etMerchant)
        val etAmount = dialogView.findViewById<EditText>(R.id.etAmount)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
        val tvDate = dialogView.findViewById<TextView>(R.id.tvDate)
        val etNotes = dialogView.findViewById<EditText>(R.id.etNotes)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveBill)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelBill)

        // Setup category spinner
        val categories = Category.getAllCategories()
        val categoryNames = categories.map { it.displayName }
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            categoryNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerCategory.adapter = spinnerAdapter

        // Date picker
        val calendar = Calendar.getInstance()
        var selectedDate: Date = calendar.time
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        tvDate.text = dateFormat.format(selectedDate)

        tvDate.setOnClickListener {
            val dpd = DatePickerDialog(
                requireContext(),
                R.style.Theme_SnapBudgetOCR, // use dark theme
                { _, year, month, day ->
                    calendar.set(year, month, day)
                    selectedDate = calendar.time
                    tvDate.text = dateFormat.format(selectedDate)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            dpd.datePicker.maxDate = System.currentTimeMillis()
            dpd.show()
        }

        btnSave.setOnClickListener {
            val merchant = etMerchant.text.toString().trim()
            val amountStr = etAmount.text.toString().trim()
            val amount = amountStr.toDoubleOrNull()
            val selectedCategoryIndex = spinnerCategory.selectedItemPosition
            val notes = etNotes.text.toString().trim()

            // Validation
            if (merchant.isEmpty()) {
                etMerchant.error = "Enter merchant name"
                return@setOnClickListener
            }
            if (amount == null || amount <= 0) {
                etAmount.error = "Enter valid amount"
                return@setOnClickListener
            }

            val category = categories[selectedCategoryIndex]

            val transaction = Transaction(
                merchantName = merchant,
                amount = amount,
                date = selectedDate,
                category = category.name,
                confidenceScore = 1.0f, // manual entry = full confidence
                notes = notes.ifBlank { null }
            )

            // Insert via coroutine
            viewLifecycleOwner.lifecycleScope.launch {
                repository.insertTransaction(transaction)
                viewModel.loadBudgetData()
                Toast.makeText(requireContext(), "Bill added successfully", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadBudgetData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
