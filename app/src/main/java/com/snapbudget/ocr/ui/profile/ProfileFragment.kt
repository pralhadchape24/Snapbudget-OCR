package com.snapbudget.ocr.ui.profile

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import android.widget.EditText
import android.widget.FrameLayout
import com.snapbudget.ocr.R
import com.snapbudget.ocr.data.db.AppDatabase
import com.snapbudget.ocr.data.repository.TransactionRepository
import com.snapbudget.ocr.databinding.FragmentProfileBinding
import com.snapbudget.ocr.ocr.OcrPipelineConfig
import com.snapbudget.ocr.ocr.OcrPipelineMode
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUserProfile()
        setupClickListeners()
        setupOcrModeSelector()
    }

    private fun setupUserProfile() {
        val user = FirebaseAuth.getInstance().currentUser
        val prefs = requireContext().getSharedPreferences("snapbudget_prefs", Context.MODE_PRIVATE)
        val defaultName = user?.displayName ?: "SnapBudget User"
        val userName = prefs.getString("user_name", defaultName) ?: "SnapBudget User"
        
        binding.tvUserName.text = userName
        binding.tvUserEmail.text = user?.email ?: "v1.0"
    }

    private fun showEditNameDialog() {
        val prefs = requireContext().getSharedPreferences("snapbudget_prefs", Context.MODE_PRIVATE)
        val currentName = binding.tvUserName.text.toString()

        val input = EditText(requireContext())
        input.setText(currentName)
        input.setSelection(currentName.length)
        input.isSingleLine = true

        val container = FrameLayout(requireContext())
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.leftMargin = resources.getDimensionPixelSize(R.dimen.spacing_5)
        params.rightMargin = resources.getDimensionPixelSize(R.dimen.spacing_5)
        input.layoutParams = params
        container.addView(input)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Edit Username")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    // Update SharedPreferences
                    prefs.edit().putString("user_name", newName).apply()
                    binding.tvUserName.text = newName
                    
                    // Update Firebase
                    val user = FirebaseAuth.getInstance().currentUser
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(newName)
                        .build()
                    user?.updateProfile(profileUpdates)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupClickListeners() {
        binding.cardUserProfile.setOnClickListener {
            showEditNameDialog()
        }

        binding.rowExport.setOnClickListener {
            Toast.makeText(requireContext(), "Export coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.rowClearData.setOnClickListener {
            showClearDataConfirmation()
        }

        binding.rowSignOut.setOnClickListener {
            showSignOutConfirmation()
        }
    }

    // ─── OCR Mode Selector ───────────────────────────────────────────────

    private fun setupOcrModeSelector() {
        // Read current mode and reflect in UI
        val currentMode = OcrPipelineConfig.getMode(requireContext())
        updateRadioUi(currentMode)

        // Click handlers
        binding.rowModeOffline.setOnClickListener {
            selectMode(OcrPipelineMode.OFFLINE)
        }

        binding.rowModeHybrid.setOnClickListener {
            selectMode(OcrPipelineMode.HYBRID)
        }

        binding.rowModeCloudAi.setOnClickListener {
            selectMode(OcrPipelineMode.CLOUD_AI)
        }
    }

    private fun selectMode(mode: OcrPipelineMode) {
        // Check API key for AI modes
        if (mode != OcrPipelineMode.OFFLINE && !OcrPipelineConfig.hasApiKey()) {
            binding.txtApiKeyStatus.visibility = View.VISIBLE
            binding.txtApiKeyStatus.text = "⚠ Groq API key not configured. Add it to .env and rebuild."
            binding.txtApiKeyStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
            Toast.makeText(requireContext(), "API key required for AI modes", Toast.LENGTH_SHORT).show()
            return
        }

        OcrPipelineConfig.setMode(requireContext(), mode)
        updateRadioUi(mode)

        val label = when (mode) {
            OcrPipelineMode.OFFLINE -> "Offline (Local)"
            OcrPipelineMode.HYBRID -> "Hybrid (AI Assist)"
            OcrPipelineMode.CLOUD_AI -> "Cloud AI (Full)"
        }
        Toast.makeText(requireContext(), "OCR mode: $label", Toast.LENGTH_SHORT).show()
    }

    private fun updateRadioUi(selected: OcrPipelineMode) {
        val ctx = requireContext()
        val brandColor = ContextCompat.getColor(ctx, R.color.brand)
        val dimColor = ContextCompat.getColor(ctx, R.color.text_tertiary)

        // Helper to set checked/unchecked state
        fun setRadio(imageView: ImageView, isChecked: Boolean) {
            if (isChecked) {
                imageView.setImageResource(R.drawable.ic_radio_checked)
                imageView.setColorFilter(brandColor)
                imageView.contentDescription = "Selected"
            } else {
                imageView.setImageResource(R.drawable.ic_radio_unchecked)
                imageView.setColorFilter(dimColor)
                imageView.contentDescription = "Not selected"
            }
        }

        setRadio(binding.radioOffline, selected == OcrPipelineMode.OFFLINE)
        setRadio(binding.radioHybrid, selected == OcrPipelineMode.HYBRID)
        setRadio(binding.radioCloudAi, selected == OcrPipelineMode.CLOUD_AI)

        // Show API key status for AI modes
        if (selected != OcrPipelineMode.OFFLINE && OcrPipelineConfig.hasApiKey()) {
            binding.txtApiKeyStatus.visibility = View.VISIBLE
            binding.txtApiKeyStatus.text = "✓ Groq API key configured"
            binding.txtApiKeyStatus.setTextColor(ContextCompat.getColor(ctx, R.color.brand))
        } else {
            binding.txtApiKeyStatus.visibility = View.GONE
        }
    }

    // ─── Data Management ─────────────────────────────────────────────────

    private fun showClearDataConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear All Data")
            .setMessage("This will permanently delete all transactions and budgets. This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    val database = AppDatabase.getDatabase(requireContext())
                    val repository = TransactionRepository(database.transactionDao())
                    repository.deleteAllTransactions()

                    // Clear budget prefs
                    requireContext()
                        .getSharedPreferences("snapbudget_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .apply()

                    Toast.makeText(requireContext(), "All data cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSignOutConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Sign Out") { _, _ ->
                // Sign out from Firebase
                FirebaseAuth.getInstance().signOut()

                // Navigate back to LoginActivity
                val intent = android.content.Intent(requireContext(), com.snapbudget.ocr.LoginActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
