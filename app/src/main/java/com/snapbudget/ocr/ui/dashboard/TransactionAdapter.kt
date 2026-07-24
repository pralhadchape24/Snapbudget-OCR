package com.snapbudget.ocr.ui.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.snapbudget.ocr.R
import com.snapbudget.ocr.data.model.Category
import com.snapbudget.ocr.data.model.Transaction
import com.snapbudget.ocr.util.CurrencyFormatter
import com.snapbudget.ocr.util.DateFormatter

class TransactionAdapter(
    private val onItemClick: (Transaction) -> Unit,
    private val onDeleteClick: (Transaction) -> Unit
) : ListAdapter<Transaction, TransactionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Root is now a LinearLayout (id = cardTransaction)
        private val root: View = itemView.findViewById(R.id.cardTransaction)
        private val tvMerchant: TextView = itemView.findViewById(R.id.tvMerchant)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val ivCategoryIcon: android.widget.ImageView = itemView.findViewById(R.id.ivCategoryIcon)
        private val categoryIconContainer: View = itemView.findViewById(R.id.categoryIconContainer)

        fun bind(transaction: Transaction) {
            tvMerchant.text = transaction.merchantName
            tvCategory.text = Category.fromName(transaction.category).displayName

            // Format amount — expenses show −₹ in red, income/refunds show +₹ in green
            val amount = transaction.amount
            if (amount >= 0) {
                // Expense (scanned receipts store positive amounts)
                tvAmount.text = "−${CurrencyFormatter.format(amount)}"
                tvAmount.setTextColor(Color.parseColor("#FF6B6B"))
            } else {
                // Income / Refund
                tvAmount.text = "+${CurrencyFormatter.format(kotlin.math.abs(amount))}"
                tvAmount.setTextColor(Color.parseColor("#00D68F"))
            }

            // Category · date
            val category = Category.fromName(transaction.category)
            tvDate.text = "${category.displayName} · ${DateFormatter.format(transaction.date)}"

            // Category icon — set the vector drawable with category color tint
            ivCategoryIcon.setImageResource(category.iconResId)
            try {
                val catColor = Color.parseColor(category.color)
                ivCategoryIcon.setColorFilter(catColor)

                // Tinted background container (category color at 20% opacity)
                categoryIconContainer.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.argb(51, Color.red(catColor), Color.green(catColor), Color.blue(catColor)))
                }
            } catch (e: Exception) {
                val fallbackColor = Color.parseColor("#525E72")
                ivCategoryIcon.setColorFilter(fallbackColor)
                categoryIconContainer.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.argb(51, Color.red(fallbackColor), Color.green(fallbackColor), Color.blue(fallbackColor)))
                }
            }

            // Accessibility: composed content description for screen readers (WCAG 4.1.2)
            itemView.contentDescription = buildString {
                append(transaction.merchantName)
                append(", ")
                append(category.displayName)
                append(" category, ")
                append(if (amount >= 0) "minus " else "plus ")
                append(CurrencyFormatter.format(kotlin.math.abs(amount)))
                append(", ")
                append(DateFormatter.format(transaction.date))
            }
            // Set category icon content description instead of hiding from accessibility
            ivCategoryIcon.contentDescription = category.displayName

            root.setOnClickListener { onItemClick(transaction) }
            root.setOnLongClickListener {
                onDeleteClick(transaction)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Transaction>() {
        override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction) = oldItem == newItem
    }
}