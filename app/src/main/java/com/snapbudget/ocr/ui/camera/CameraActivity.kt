package com.snapbudget.ocr.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.animation.AnimationUtils
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.snapbudget.ocr.R
import com.snapbudget.ocr.databinding.ActivityCameraBinding
import com.snapbudget.ocr.ui.receipt.ReceiptPreviewActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var isCapturing = false
    private var validFrameCount = 0

    companion object {
        const val EXTRA_RECEIPT_PATH = "extra_receipt_path"
        private const val TAG = "CameraActivity"
        private const val PREFS_NAME = "snapbudget_prefs"
        private const val PREF_SHOW_INSTRUCTION = "show_scan_instruction"
    }

    // Modern permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan receipts", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Modern gallery picker launcher (replaces deprecated startActivityForResult)
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            navigateToPreview(it.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupClickListeners()
        checkInstructionDialogAndStart()
    }

    private fun checkInstructionDialogAndStart() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val showInstruction = prefs.getBoolean(PREF_SHOW_INSTRUCTION, true)

        if (showInstruction) {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_scan_instruction, null)
            val cbDoNotShowAgain = dialogView.findViewById<CheckBox>(R.id.cbDoNotShowAgain)

            MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton("I Understand") { dialog, _ ->
                    if (cbDoNotShowAgain.isChecked) {
                        prefs.edit().putBoolean(PREF_SHOW_INSTRUCTION, false).apply()
                    }
                    dialog.dismiss()
                    checkCameraPermissionAndStart()
                }
                .show()
        } else {
            checkCameraPermissionAndStart()
        }
    }

    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(this, "Camera access is needed to scan receipts", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnGallery.setOnClickListener {
            openGallery()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Auto boundary catcher using ImageAnalysis
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    
                imageAnalysis.setAnalyzer(cameraExecutor, BoundaryCatcherAnalyzer())

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )

                // Start scan line animation
                val scanAnim = AnimationUtils.loadAnimation(this, R.anim.scan_line)
                binding.scanLine.startAnimation(scanAnim)

            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (isCapturing) return
        isCapturing = true
        
        val imageCapture = imageCapture ?: return

        // Use app-internal cache directory instead of externalMediaDirs (which can be null)
        val outputDir = File(cacheDir, "receipts").also { it.mkdirs() }
        val photoFile = File(
            outputDir,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: android.net.Uri.fromFile(photoFile)
                    navigateToPreview(savedUri.toString())
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, "Capture failed", Toast.LENGTH_SHORT).show()
                    isCapturing = false
                }
            }
        )
    }

    // Auto-capture boundary catcher based on ML Kit text detection
    private inner class BoundaryCatcherAnalyzer : ImageAnalysis.Analyzer {
        private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        
        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            if (isCapturing) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        // Calculate boundary of all detected text blocks
                        if (visionText.textBlocks.size > 3) {
                            var minX = Float.MAX_VALUE
                            var minY = Float.MAX_VALUE
                            var maxX = Float.MIN_VALUE
                            var maxY = Float.MIN_VALUE
                            
                            for (block in visionText.textBlocks) {
                                val rect = block.boundingBox
                                if (rect != null) {
                                    if (rect.left < minX) minX = rect.left.toFloat()
                                    if (rect.top < minY) minY = rect.top.toFloat()
                                    if (rect.right > maxX) maxX = rect.right.toFloat()
                                    if (rect.bottom > maxY) maxY = rect.bottom.toFloat()
                                }
                            }
                            
                            val width = maxX - minX
                            val height = maxY - minY
                            
                            // Check if the bounded area of text is significant enough relative to the image size
                            val areaPercentage = (width * height) / (imageProxy.width * imageProxy.height).toFloat()
                            
                            if (areaPercentage > 0.25f) { // If text takes up >25% of the frame, consider it a caught boundary
                                validFrameCount++
                                runOnUiThread {
                                    binding.tvOcrStatus.text = "● RECEIPT BOUNDARY CAUGHT"
                                    binding.tvOcrStatus.setTextColor(ContextCompat.getColor(this@CameraActivity, R.color.brand))
                                    // Make scan line green and faster maybe
                                }
                                
                                // Auto-capture when boundary is stable
                                if (validFrameCount > 3) { // Require 3 consecutive good frames
                                    runOnUiThread {
                                        binding.tvOcrStatus.text = "● AUTO CAPTURING..."
                                        takePhoto()
                                    }
                                }
                            } else {
                                validFrameCount = 0
                                runOnUiThread {
                                    binding.tvOcrStatus.text = "● ALIGN RECEIPT"
                                    binding.tvOcrStatus.setTextColor(ContextCompat.getColor(this@CameraActivity, R.color.warning))
                                }
                            }
                        } else {
                            validFrameCount = 0
                            runOnUiThread {
                                binding.tvOcrStatus.text = "● LOOKING FOR BOUNDARIES..."
                                binding.tvOcrStatus.setTextColor(ContextCompat.getColor(this@CameraActivity, R.color.text_secondary))
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Boundary recognition failed", e)
                        validFrameCount = 0
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun toggleFlash() {
        imageCapture?.let {
            val flashMode = if (it.flashMode == ImageCapture.FLASH_MODE_OFF) {
                ImageCapture.FLASH_MODE_ON
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
            it.flashMode = flashMode
            
            val icon = if (flashMode == ImageCapture.FLASH_MODE_ON) {
                R.drawable.ic_flash_on
            } else {
                R.drawable.ic_flash_off
            }
            binding.btnFlash.setImageResource(icon)
        }
    }

    private fun openGallery() {
        galleryLauncher.launch("image/*")
    }

    private fun navigateToPreview(imagePath: String) {
        val intent = Intent(this, ReceiptPreviewActivity::class.java).apply {
            putExtra(ReceiptPreviewActivity.EXTRA_IMAGE_PATH, imagePath)
            putExtra(ReceiptPreviewActivity.EXTRA_SOURCE, "camera")
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}