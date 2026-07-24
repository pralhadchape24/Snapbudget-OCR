package com.snapbudget.ocr

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class SnapBudgetApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Support dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}