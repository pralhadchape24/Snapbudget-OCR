package com.snapbudget.ocr.ui.receipt

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.snapbudget.ocr.R
import android.content.res.ColorStateList
import com.snapbudget.ocr.data.db.AppDatabase
import com.snapbudget.ocr.data.model.Category
import com.snapbudget.ocr.data.model.Transaction
import com.snapbudget.ocr.data.repository.TransactionRepository
import com.snapbudget.ocr.categorization.CategoryClassifier
import androidx.recyclerview.widget.LinearLayoutManager
import com.snapbudget.ocr.databinding.ActivityReceiptPreviewBinding
import com.snapbudget.ocr.ocr.ReceiptProcessor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ReceiptPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiptPreviewBinding
    private lateinit var receiptProcessor: ReceiptProcessor
    private lateinit var categoryClassifier: CategoryClassifier
    private lateinit var transactionRepository: TransactionRepository

    private var currentTransaction: Transaction? = null
    private var imagePath: String? = null
    private var isEditing = false
    private var selectedCategoryIndex = 0  // Tracks selected category for AutoCompleteTextView
    
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
        private const val TAG = "ReceiptPreviewActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("STEP_BY_STEP", "1. ReceiptPreviewActivity onCreate started")
        binding = ActivityReceiptPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set toolbar as ActionBar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize components
        categoryClassifier = CategoryClassifier(this)
        val database = AppDatabase.getDatabase(this)
        val transactionDao = database.transactionDao()
        receiptProcessor = ReceiptProcessor(this, categoryClassifier, transactionDao)
        transactionRepository = TransactionRepository(transactionDao)

        setupUI()
        setupCategorySpinner()
        
        // Load data
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1)
        if (transactionId != -1L) {
            Log.d("STEP_BY_STEP", "2a. Loading existing transaction: $transactionId")
            loadExistingTransaction(transactionId)
        } else {
            imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
            Log.d("STEP_BY_STEP", "2b. New image upload path: $imagePath")
            if (imagePath != null) {
                loadAndProcessImage()
            } else {
                Log.d("STEP_BY_STEP", "2c. Manual entry mode - no image path provided")
            }
        }
    }

    private fun setupUI() {
        binding.btnSave.setOnClickListener {
            saveTransaction()
        }

        binding.btnRetake.setOnClickListener {
            finish()
        }

        binding.edtDate.setOnClickListener {
            showDatePicker()
        }

        binding.edtAmount.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateAmount()
            }
        }

        binding.edtMerchant.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                suggestCategory()
            }
        }

        binding.btnShowRawOcr.setOnClickListener {
            if (binding.cardRawOcr.visibility == View.VISIBLE) {
                binding.cardRawOcr.visibility = View.GONE
                binding.btnShowRawOcr.text = "VIEW EXTRACTED TEXT"
            } else {
                binding.cardRawOcr.visibility = View.VISIBLE
                binding.btnShowRawOcr.text = "HIDE EXTRACTED TEXT"
            }
        }
    }

    private fun setupCategorySpinner() {
        val categories = Category.getAllCategories().map { it.displayName }
        // Use custom layout for dropdown items to match palette
        val adapter = ArrayAdapter(this, R.layout.list_item_dropdown, categories)
        binding.spinnerCategory.setAdapter(adapter)
        binding.spinnerCategory.setText(categories[0], false)

        binding.spinnerCategory.setOnItemClickListener { _, _, position, _ ->
            selectedCategoryIndex = position
        }
    }

    private fun loadExistingTransaction(transactionId: Long) {
        lifecycleScope.launch {
            try {
                val transaction = transactionRepository.getTransactionById(transactionId)
                if (transaction != null) {
                    currentTransaction = transaction
                    isEditing = true
                    populateFields(transaction)
                    transaction.imagePath?.let { path ->
                        displayImage(path)
                    }
                } else {
                    Toast.makeText(this@ReceiptPreviewActivity, 
                        "Transaction not found (ID: $transactionId)", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e("STEP_BY_STEP", "ERROR: Failed to load transaction $transactionId", e)
                Toast.makeText(this@ReceiptPreviewActivity, 
                    "Error loading transaction: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun loadAndProcessImage() {
        showLoading(true)
        Log.d("STEP_BY_STEP", "3. Starting image loading and processing")
        
        lifecycleScope.launch {
            try {
                val uri = Uri.parse(imagePath)
                Log.d("STEP_BY_STEP", "4. Displaying image URI: $uri")
                displayImage(uri.toString())
                
                Log.d("STEP_BY_STEP", "5. Calling ReceiptProcessor.processReceipt")
                val result = receiptProcessor.processReceipt(uri)
                
                if (result != null) {
                    Log.d("STEP_BY_STEP", "9. Receipt data extracted successfully")
                    currentTransaction = result.transaction
                    populateFields(result.transaction)
                    
                    // Show line items
                    if (result.lineItems.isNotEmpty()) {
                        binding.cardLineItems.visibility = View.VISIBLE
                        binding.chipItemCount.text = result.lineItems.size.toString()
                        binding.rvLineItems.layoutManager = LinearLayoutManager(this@ReceiptPreviewActivity)
                        binding.rvLineItems.adapter = LineItemAdapter(result.lineItems)
                        
                        val subtotal = result.lineItems.sumOf { it.totalPrice }
                        binding.tvItemsSubtotal.text = "₹%.2f".format(subtotal)
                    } else {
                        binding.cardLineItems.visibility = View.GONE
                    }

                    // Show validation warnings if any
                    if (result.validationIssues.isNotEmpty()) {
                        Log.d("STEP_BY_STEP", "9a. Validation issues found: ${result.validationIssues.size}")
                        showValidationWarnings(result.validationIssues)
                    }

                    // Show duplicate warning if detected (§6)
                    if (result.isDuplicate) {
                        Log.d("STEP_BY_STEP", "9b. DUPLICATE detected! score=%.4f, existingId=${result.duplicateOfTransactionId}".format(result.duplicateScore))
                        showDuplicateWarning(result)
                    }

                    // Show inline OCR warning for low confidence (replaces dialog)
                    if (result.overallConfidence < 0.70f) {
                        Log.d("STEP_BY_STEP", "9c. Low confidence (${(result.overallConfidence * 100).toInt()}%) — showing inline warning")
                        showOcrWarningBanner(
                            isComplete = false,
                            confidence = result.overallConfidence,
                            confidenceScores = result.confidenceScores
                        )
                    }

                    // Show raw OCR comparison + receipt type
                    binding.tvRawOcr.text = "Raw OCR:\n${result.rawOcrText}"
                    binding.chipConfidence.text = "${(result.overallConfidence * 100).toInt()}% • ${result.receiptType}"
                    
                    // Style confidence chip based on confidence level
                    val chipColor = when {
                        result.overallConfidence >= 0.70f -> R.color.brand
                        result.overallConfidence >= 0.40f -> R.color.warning
                        else -> R.color.danger
                    }
                    val chipBgColor = when {
                        result.overallConfidence >= 0.70f -> R.color.brand_subtle
                        result.overallConfidence >= 0.40f -> R.color.warning_bg
                        else -> R.color.danger_bg
                    }
                    binding.chipConfidence.setTextColor(getColor(chipColor))
                    binding.chipConfidence.chipStrokeColor = ColorStateList.valueOf(getColor(chipColor))
                    binding.chipConfidence.chipBackgroundColor = ColorStateList.valueOf(getColor(chipBgColor))
                    
                    Log.d("STEP_BY_STEP", "9d. Receipt type: ${result.receiptType}, anomalies: ${result.anomalyIssues.size}")
                } else {
                    Log.e("STEP_BY_STEP", "9. ERROR: ReceiptProcessor returned null result")
                    // Show inline warning card instead of Toast
                    showOcrWarningBanner(
                        isComplete = true,
                        confidence = 0f,
                        confidenceScores = emptyMap()
                    )
                    // Pre-fill defaults for manual entry
                    prefillManualEntryDefaults()
                }
            } catch (e: Exception) {
                Log.e("STEP_BY_STEP", "9. ERROR during loadAndProcessImage", e)
                // Show inline warning card for errors too
                showOcrWarningBanner(
                    isComplete = true,
                    confidence = 0f,
                    confidenceScores = emptyMap()
                )
                prefillManualEntryDefaults()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun displayImage(path: String) {
        try {
            val uri = Uri.parse(path)
            if (path.startsWith("content:")) {
                binding.imgReceipt.setImageURI(uri)
            } else {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    binding.imgReceipt.setImageBitmap(bitmap)
                } else {
                    // Try as content URI
                    try {
                        val stream = contentResolver.openInputStream(uri)
                        val bmp = BitmapFactory.decodeStream(stream)
                        stream?.close()
                        binding.imgReceipt.setImageBitmap(bmp)
                    } catch (e: Exception) {
                        Log.e("STEP_BY_STEP", "ERROR: Could not display image from path/URI: $path", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("STEP_BY_STEP", "ERROR: Image display exception", e)
        }
    }

    private fun populateFields(transaction: Transaction) {
        binding.edtMerchant.setText(transaction.merchantName)
        binding.edtAmount.setText(String.format("%.2f", transaction.amount))
        binding.edtDate.setText(dateFormat.format(transaction.date))
        binding.edtGst.setText(transaction.gstNumber ?: "")
        
        // Set category
        val categoryPosition = Category.getAllCategories()
            .indexOfFirst { it.name == transaction.category }
        if (categoryPosition >= 0) {
            selectedCategoryIndex = categoryPosition
            binding.spinnerCategory.setText(
                Category.getAllCategories()[categoryPosition].displayName, false
            )
        }
        
        // Show raw OCR if available
        transaction.rawOcrText?.let {
            binding.tvRawOcr.text = "Raw OCR:\n$it"
        }
        
        binding.chipConfidence.text = "Confidence: ${(transaction.confidenceScore * 100).toInt()}%"
    }

    private fun suggestCategory() {
        val merchant = binding.edtMerchant.text.toString()
        if (merchant.isNotEmpty()) {
            val suggestions = categoryClassifier.getCategorySuggestions(merchant)
            if (suggestions.isNotEmpty()) {
                val topCategory = suggestions.first().first
                val position = Category.getAllCategories().indexOf(topCategory)
                if (position >= 0) {
                    selectedCategoryIndex = position
                    binding.spinnerCategory.setText(topCategory.displayName, false)
                }
            }
        }
    }

    private fun validateAmount(): Boolean {
        val amountStr = binding.edtAmount.text.toString()
        val amount = amountStr.toDoubleOrNull()
        
        if (amount == null || amount <= 0) {
            binding.edtAmount.error = "Please enter a valid amount"
            return false
        }
        
        if (amount > 1000000) {
            binding.edtAmount.error = "Amount seems unusually high"
            return false
        }
        
        binding.edtAmount.error = null
        return true
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        
        // Parse the existing date from the field if available
        val currentDateStr = binding.edtDate.text.toString()
        if (currentDateStr.isNotEmpty()) {
            try {
                val existingDate = dateFormat.parse(currentDateStr)
                if (existingDate != null) {
                    calendar.time = existingDate
                }
            } catch (e: Exception) {
                // Fall through — use today
            }
        }
        
        val dialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(year, month, day)
                binding.edtDate.setText(dateFormat.format(selectedCal.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        
        // Don't allow future dates
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun saveTransaction() {
        if (!validateAmount()) return
        
        val merchant = binding.edtMerchant.text.toString().trim()
        if (merchant.isEmpty()) {
            binding.edtMerchant.error = "Please enter merchant name"
            return
        }
        
        val amount = binding.edtAmount.text.toString().toDoubleOrNull() ?: 0.0
        val date = parseDate(binding.edtDate.text.toString()) ?: Date()
        val gst = binding.edtGst.text.toString().trim()
        val category = Category.getAllCategories().getOrElse(selectedCategoryIndex) { Category.Others }
        
        val transaction = currentTransaction?.copy(
            merchantName = merchant,
            amount = amount,
            date = date,
            gstNumber = gst.ifEmpty { null },
            category = category.name,
            updatedAt = Date()
        ) ?: Transaction(
            merchantName = merchant,
            amount = amount,
            date = date,
            category = category.name,
            gstNumber = gst.ifEmpty { null },
            imagePath = imagePath
        )
        
        Log.d("STEP_BY_STEP", "10. Saving transaction to database: ${transaction.merchantName}")
        lifecycleScope.launch {
            try {
                if (isEditing && transaction.id != 0L) {
                    transactionRepository.updateTransaction(transaction)
                } else {
                    transactionRepository.insertTransaction(transaction)
                }
                Log.d("STEP_BY_STEP", "11. Transaction saved successfully")
                
                Toast.makeText(this@ReceiptPreviewActivity, 
                    if (isEditing) "Transaction updated" else "Transaction saved", 
                    Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Log.e("STEP_BY_STEP", "11. ERROR: Saving transaction failed", e)
                Toast.makeText(this@ReceiptPreviewActivity, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showValidationWarnings(issues: List<String>) {
        val message = issues.joinToString("\n• ", "Please review:\n• ")
        AlertDialog.Builder(this)
            .setTitle("Validation Warnings")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showDuplicateWarning(result: ReceiptProcessor.ProcessingResult) {
        val scorePercent = (result.duplicateScore * 100).toInt()
        AlertDialog.Builder(this)
            .setTitle("⚠️ Possible Duplicate")
            .setMessage(
                "This receipt looks like a duplicate of an existing transaction.\n\n" +
                "Similarity: ${scorePercent}%\n" +
                "Confidence: ${result.duplicateConfidenceLabel}\n\n" +
                "Would you like to save it anyway?"
            )
            .setPositiveButton("Save Anyway", null)
            .setNegativeButton("Discard") { _, _ -> finish() }
            .show()
    }

    private fun showConfirmationPrompt(result: ReceiptProcessor.ProcessingResult) {
        val confidence = (result.overallConfidence * 100).toInt()
        // Build a user-friendly message listing weak fields from the confidence report
        val fieldList = result.confidenceScores
            .filter { it.value < 0.65f }
            .entries
            .joinToString("\n• ") { (field, score) ->
                "${field.replaceFirstChar { it.uppercase() }}: ${(score * 100).toInt()}%"
            }
        val message = buildString {
            append("Extraction confidence is $confidence%.\n\n")
            if (fieldList.isNotEmpty()) {
                append("Please review these fields:\n• $fieldList\n\n")
            }
            append("Receipt type detected: ${result.receiptType}\n\n")
            append("You can edit the fields below before saving.")
        }
        Log.d("STEP_BY_STEP", "9c. showConfirmationPrompt | confidence=$confidence%, weakFields=$fieldList")
        AlertDialog.Builder(this)
            .setTitle("🔍 Please Verify")
            .setMessage(message)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSave.isEnabled = !show
    }

    /**
     * Shows the inline OCR warning banner card with appropriate messaging.
     *
     * @param isComplete true if OCR completely failed (null result), false if partial/low confidence
     * @param confidence overall confidence score (0.0 - 1.0)
     * @param confidenceScores per-field confidence map
     */
    private fun showOcrWarningBanner(
        isComplete: Boolean,
        confidence: Float,
        confidenceScores: Map<String, Float>
    ) {
        binding.cardOcrWarning.visibility = View.VISIBLE

        if (isComplete) {
            // OCR completely failed
            binding.tvOcrWarningTitle.text = getString(R.string.ocr_failed_title)
            binding.tvOcrWarningMessage.text = getString(R.string.ocr_failed_message)
            binding.tvOcrWarningTitle.setTextColor(getColor(R.color.danger))
            binding.cardOcrWarning.strokeColor = getColor(R.color.danger)
            binding.cardOcrWarning.setCardBackgroundColor(getColor(R.color.danger_bg))
            binding.ivWarningIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.danger))

            // Update confidence chip to show failure
            binding.chipConfidence.text = "? Low Match"
            binding.chipConfidence.setTextColor(getColor(R.color.danger))
            binding.chipConfidence.chipStrokeColor = ColorStateList.valueOf(getColor(R.color.danger))
            binding.chipConfidence.chipBackgroundColor = ColorStateList.valueOf(getColor(R.color.danger_bg))

            // Make Retake button equal prominence to Save
            equalizeActionButtons()
        } else {
            // Low confidence — show field-level details
            binding.tvOcrWarningTitle.text = getString(R.string.ocr_low_confidence_title)
            
            // Build per-field confidence breakdown
            val weakFields = confidenceScores
                .filter { (key, _) -> key in listOf("amount", "merchant", "date", "category", "gst") }
                .filter { (_, score) -> score < 0.50f }
            
            val message = if (weakFields.isNotEmpty()) {
                val fieldList = weakFields.entries.joinToString("\n") { (field, score) ->
                    val label = field.replaceFirstChar { it.uppercase() }
                    "  • $label: ${(score * 100).toInt()}%"
                }
                "${getString(R.string.ocr_low_confidence_message)}\n\n$fieldList"
            } else {
                getString(R.string.ocr_low_confidence_message)
            }
            binding.tvOcrWarningMessage.text = message

            // If really low confidence (<30%), promote Retake button
            if (confidence < 0.30f) {
                equalizeActionButtons()
            }
        }
    }

    /**
     * Pre-fills form defaults for manual entry when OCR fails.
     * Date → today, Category → Others.
     */
    private fun prefillManualEntryDefaults() {
        // Pre-fill date with today
        binding.edtDate.setText(dateFormat.format(Date()))
        
        // Default category to "Others"
        val othersIndex = Category.getAllCategories()
            .indexOfFirst { it.name == "Others" }
        if (othersIndex >= 0) {
            selectedCategoryIndex = othersIndex
            binding.spinnerCategory.setText(
                Category.getAllCategories()[othersIndex].displayName, false
            )
        }
        
        // Set focus to Amount field for immediate input
        binding.edtAmount.requestFocus()
        Log.d("STEP_BY_STEP", "9e. Manual entry defaults: date=today, category=Others")
    }

    /**
     * Makes Retake and Save buttons equal weight so the user
     * can easily choose to retake a bad scan.
     */
    private fun equalizeActionButtons() {
        val retakeParams = binding.btnRetake.layoutParams as? android.widget.LinearLayout.LayoutParams
        val saveParams = binding.btnSave.layoutParams as? android.widget.LinearLayout.LayoutParams
        if (retakeParams != null && saveParams != null) {
            retakeParams.weight = 1f
            saveParams.weight = 1f
            binding.btnRetake.layoutParams = retakeParams
            binding.btnSave.layoutParams = saveParams
        }
    }

    private fun parseDate(dateStr: String): Date? {
        return try {
            dateFormat.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        receiptProcessor.close()
    }
}