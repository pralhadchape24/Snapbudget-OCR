package com.snapbudget.ocr.data.model

import com.snapbudget.ocr.R

sealed class Category(val name: String, val displayName: String, val color: String, val iconResId: Int) {
    object Grocery : Category("GROCERY", "Grocery", "#4CAF50", R.drawable.ic_cat_grocery)
    object Food : Category("FOOD", "Food & Dining", "#FFB547", R.drawable.ic_cat_food)
    object Travel : Category("TRAVEL", "Travel & Transport", "#5B8DEF", R.drawable.ic_cat_travel)
    object Shopping : Category("SHOPPING", "Shopping", "#EC4899", R.drawable.ic_cat_shopping)
    object Utilities : Category("UTILITIES", "Utilities & Bills", "#8B5CF6", R.drawable.ic_cat_utilities)
    object Entertainment : Category("ENTERTAINMENT", "Entertainment", "#FF6B6B", R.drawable.ic_cat_entertainment)
    object Health : Category("HEALTH", "Health & Medical", "#00D68F", R.drawable.ic_cat_health)
    object Education : Category("EDUCATION", "Education", "#06B6D4", R.drawable.ic_cat_education)
    object Others : Category("OTHERS", "Others", "#525E72", R.drawable.ic_cat_others)

    companion object {
        fun getAllCategories(): List<Category> = listOf(
            Grocery, Food, Travel, Shopping, Utilities, 
            Entertainment, Health, Education, Others
        )

        fun fromName(name: String): Category {
            return getAllCategories().find { it.name == name.uppercase() } ?: Others
        }

        fun fromDisplayName(displayName: String): Category {
            return getAllCategories().find { it.displayName == displayName } ?: Others
        }
    }
}
