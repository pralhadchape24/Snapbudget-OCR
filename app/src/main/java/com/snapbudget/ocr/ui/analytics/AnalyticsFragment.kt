package com.snapbudget.ocr.ui.analytics

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.formatter.PercentFormatter
import com.snapbudget.ocr.R
import com.snapbudget.ocr.data.db.AppDatabase
import com.snapbudget.ocr.data.model.Category
import com.snapbudget.ocr.data.repository.TransactionRepository
import com.snapbudget.ocr.databinding.FragmentAnalyticsBinding
import com.snapbudget.ocr.util.CurrencyFormatter

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AnalyticsViewModel by activityViewModels {
        val database = AppDatabase.getDatabase(requireContext())
        val repository = TransactionRepository(database.transactionDao())
        AnalyticsViewModel.Factory(repository)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLineChart()
        setupPieChart()
        setupObservers()
    }

    private fun setupLineChart() {
        binding.lineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = Color.parseColor("#8899AA")
                textSize = 10f
                granularity = 1f
            }
            axisLeft.apply {
                textColor = Color.parseColor("#8899AA")
                textSize = 10f
                setDrawGridLines(true)
                gridColor = Color.parseColor("#1FFFFFFF")
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
            legend.isEnabled = false
            setExtraBottomOffset(8f)
        }
    }

    private fun setupPieChart() {
        binding.pieChart.apply {
            description.isEnabled = false
            isRotationEnabled = true
            setUsePercentValues(true)
            setDrawEntryLabels(false)
            setHoleColor(Color.parseColor("#161B24"))
            setCenterTextColor(Color.parseColor("#EEF0F4"))
            holeRadius = 50f
            transparentCircleRadius = 55f
            setTransparentCircleColor(Color.parseColor("#161B24"))
            setTransparentCircleAlpha(120)

            legend.apply {
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
                isWordWrapEnabled = true
                isEnabled = true
                textSize = 10f
                formSize = 10f
                xEntrySpace = 12f
                yEntrySpace = 4f
                textColor = Color.parseColor("#8E97AA")
            }
        }
    }

    private fun setupObservers() {
        viewModel.hasData.observe(viewLifecycleOwner) { hasData ->
            binding.layoutAnalyticsEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
            binding.layoutAnalyticsContent.visibility = if (hasData) View.VISIBLE else View.GONE
        }

        viewModel.dailySpending.observe(viewLifecycleOwner) { entries ->
            if (entries.isNotEmpty()) {
                val dataSet = LineDataSet(entries, "Daily Spending").apply {
                    color = Color.parseColor("#00D68F")
                    lineWidth = 2f
                    setDrawCircles(true)
                    circleRadius = 3f
                    setCircleColor(Color.parseColor("#00D68F"))
                    setDrawFilled(true)
                    fillColor = Color.parseColor("#00D68F")
                    fillAlpha = 40
                    setDrawValues(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                binding.lineChart.data = LineData(dataSet)
                binding.lineChart.invalidate()
            }
        }

        viewModel.pieChartEntries.observe(viewLifecycleOwner) { entries ->
            if (entries.isNotEmpty()) {
                val colors = entries.mapNotNull { entry ->
                    try {
                        Color.parseColor(Category.fromName(entry.label).color)
                    } catch (e: Exception) { Color.GRAY }
                }
                val dataSet = PieDataSet(entries, "").apply {
                    this.colors = colors
                    valueTextSize = 10f
                    valueTextColor = Color.parseColor("#EEF0F4")
                    valueFormatter = PercentFormatter(binding.pieChart)
                    sliceSpace = 3f
                }
                binding.pieChart.data = PieData(dataSet)
                binding.pieChart.invalidate()
            }
        }

        viewModel.categoryList.observe(viewLifecycleOwner) { categories ->
            buildCategoryList(categories)
        }

        viewModel.topMerchants.observe(viewLifecycleOwner) { merchants ->
            buildMerchantList(merchants)
        }
    }

    private fun buildCategoryList(categories: List<Pair<String, Double>>) {
        binding.layoutCategoryList.removeAllViews()
        val maxAmount = categories.maxOfOrNull { it.second } ?: 1.0

        for ((catName, amount) in categories) {
            val cat = Category.fromName(catName)
            val catColor = try { Color.parseColor(cat.color) } catch (e: Exception) { Color.GRAY }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.spacing_3)
                layoutParams = lp
            }

            // Category dot
            val dot = View(requireContext()).apply {
                val dotSize = resources.getDimensionPixelSize(R.dimen.spacing_2)
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_2)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(catColor)
                }
            }
            row.addView(dot)

            // Category name
            val name = TextView(requireContext()).apply {
                text = cat.displayName
                setTextColor(Color.parseColor("#EEF0F4"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)

            // Progress bar
            val bar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, resources.getDimensionPixelSize(R.dimen.spacing_1_5), 1.5f).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_3)
                }
                max = 100
                progress = ((amount / maxAmount) * 100).toInt()
                progressDrawable = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 100f
                    setColor(catColor)
                }
            }
            row.addView(bar)

            // Amount
            val amountTv = TextView(requireContext()).apply {
                text = CurrencyFormatter.format(amount)
                setTextColor(Color.parseColor("#8E97AA"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            row.addView(amountTv)

            binding.layoutCategoryList.addView(row)
        }
    }

    private fun buildMerchantList(merchants: List<com.snapbudget.ocr.data.db.TransactionDao.MerchantSpending>) {
        binding.layoutMerchantList.removeAllViews()

        for ((index, merchant) in merchants.withIndex()) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padding = resources.getDimensionPixelSize(R.dimen.spacing_4)
                setPadding(padding, padding / 2, padding, padding / 2)
                minimumHeight = resources.getDimensionPixelSize(R.dimen.list_item_min_height)
            }

            // Rank
            val rank = TextView(requireContext()).apply {
                text = "${index + 1}."
                setTextColor(Color.parseColor("#8899AA"))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_3) }
            }
            row.addView(rank)

            // Name
            val name = TextView(requireContext()).apply {
                text = merchant.merchantName
                setTextColor(Color.parseColor("#EEF0F4"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)

            // Txn count
            val count = TextView(requireContext()).apply {
                text = "${merchant.txnCount} txns"
                setTextColor(Color.parseColor("#8899AA"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_3) }
            }
            row.addView(count)

            // Total
            val total = TextView(requireContext()).apply {
                text = CurrencyFormatter.format(merchant.totalSpent)
                setTextColor(Color.parseColor("#EEF0F4"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            row.addView(total)

            binding.layoutMerchantList.addView(row)

            // Divider (except last)
            if (index < merchants.size - 1) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(Color.parseColor("#12FFFFFF"))
                }
                binding.layoutMerchantList.addView(divider)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAnalytics()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
