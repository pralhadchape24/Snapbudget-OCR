package com.snapbudget.ocr

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.snapbudget.ocr.databinding.ActivityMainBinding
import com.snapbudget.ocr.ui.camera.CameraActivity

/**
 * Main Activity — hosts the navigation graph and bottom navigation bar.
 *
 * The 5-slot bottom nav has:
 *   Home | Analytics | Camera (center) | Wallet | Profile
 *
 * The center Camera item is NOT a tab — it launches CameraActivity as a
 * standalone screen and returns false so the tab is never selected.
 *
 * Fragment state (scroll position, ViewModel data) is preserved across tab
 * switches via saveState/restoreState in NavOptions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
    }

    private fun setupNavigation() {
        // Retrieve NavController from the NavHostFragment
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        // Build shared NavOptions with animations and state preservation
        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.nav_enter)
            .setExitAnim(R.anim.nav_exit)
            .setPopEnterAnim(R.anim.nav_pop_enter)
            .setPopExitAnim(R.anim.nav_pop_exit)
            .setPopUpTo(R.id.nav_home, inclusive = false, saveState = true)
            .setRestoreState(true)
            .build()

        // Handle FAB camera button click
        binding.fabCamera.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        // Handle bottom nav item selection
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_camera -> {
                    // Do nothing, handled by FAB
                    false
                }
                else -> {
                    // Standard fragment switching via NavigationUI
                    // Use custom navOptions for smooth animations + state preservation
                    val currentDest = navController.currentDestination?.id
                    if (currentDest != item.itemId) {
                        navController.navigate(item.itemId, null, navOptions)
                    }
                    true
                }
            }
        }

        // Sync bottom nav selection state when navigating via back button etc.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Update the bottom nav to reflect the current destination
            // without triggering the listener again
            binding.bottomNav.menu.findItem(destination.id)?.let { menuItem ->
                if (!menuItem.isChecked) {
                    menuItem.isChecked = true
                }
            }
        }

        // Re-select Home tab = scroll-to-top (standard Material 3 behavior)
        binding.bottomNav.setOnItemReselectedListener { item ->
            // For now, do nothing on re-select.
            // Phase 2 can add scroll-to-top for HomeFragment here.
        }
    }

    /**
     * Let NavController handle back presses for proper back-stack behavior:
     * - From HomeFragment: exit app
     * - From any other fragment: return to HomeFragment
     */
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}