package com.snapbudget.ocr.ui.receipt

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.snapbudget.ocr.R
import com.snapbudget.ocr.ocr.ReceiptParser

class LineItemAdapter(
    private val items: List<ReceiptParser.LineItem>
) : RecyclerView.Adapter<LineItemAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvItemName: TextView = view.findViewById(R.id.tvItemName)
        val tvItemQtyPrice: TextView = view.findViewById(R.id.tvItemQtyPrice)
        val tvItemTotal: TextView = view.findViewById(R.id.tvItemTotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_line_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvItemName.text = item.name
        holder.tvItemQtyPrice.text = if (item.quantity > 1) {
            "${item.quantity} × ₹%.2f".format(item.unitPrice)
        } else {
            "1 × ₹%.2f".format(item.unitPrice)
        }
        holder.tvItemTotal.text = "₹%.2f".format(item.totalPrice)
    }

    override fun getItemCount(): Int = items.size
}
