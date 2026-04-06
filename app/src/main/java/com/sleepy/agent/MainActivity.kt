package com.sleepy.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sleepy.agent.di.AppModule
import com.sleepy.agent.service.FloatingButtonService
import com.sleepy.agent.ui.screens.MainScreen
import com.sleepy.agent.ui.screens.MainViewModel
import com.sleepy.agent.ui.screens.MainViewModelFactory
import com.sleepy.agent.ui.screens.SettingsScreen
import com.sleepy.agent.ui.screens.SettingsViewModel
import com.sleepy.agent.ui.theme.SleepyAgentTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var appModule: AppModule
    
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { entry ->
            android.util.Log.d("MainActivity", "Permission ${entry.key}: ${entry.value}")
        }
    }
    
    // For MediaProjection (screenshot capture)
    private var mediaProjectionLauncher: ActivityResultLauncher<Intent>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        appModule = SleepyAgentApplication.getAppModule(application)
        
        // Setup MediaProjection launcher for screenshots
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                FloatingButtonService.mediaProjection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                Toast.makeText(this, "Screen capture enabled", Toast.LENGTH_SHORT).show()
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissionsIfNeeded()
        }
        
        // Handle intent from floating button with screenshot
        handleIntent(intent)
        
        enableEdgeToEdge()
        setContent {
            SleepyAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var screenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
                    var autoAnalyze by remember { mutableStateOf(false) }
                    
                    // Handle screenshot from intent
                    LaunchedEffect(Unit) {
                        intent.getStringExtra("screenshot_path")?.let { path ->
                            screenshotBitmap = BitmapFactory.decodeFile(path)
                            autoAnalyze = intent.getBooleanExtra("auto_analyze", false)
                        }
                    }
                    
                    NavHost(
                        navController = navController,
                        startDestination = "main"
                    ) {
                        composable("main") { backStackEntry ->
                            val viewModel: MainViewModel = viewModel(
                                viewModelStoreOwner = backStackEntry,
                                factory = MainViewModelFactory(appModule, applicationContext, backStackEntry)
                            )
                            
                            var pendingImageText by remember { mutableStateOf("") }
                            
                            val imagePickerLauncher = rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.GetContent()
                            ) { uri: Uri? ->
                                uri?.let {
                                    val bitmap = loadBitmapFromUri(it)
                                    viewModel.onImageSelected(bitmap, pendingImageText)
                                    pendingImageText = ""
                                }
                            }
                            
                            // Handle screenshot from floating button
                            LaunchedEffect(screenshotBitmap) {
                                screenshotBitmap?.let { bitmap ->
                                    if (autoAnalyze) {
                                        viewModel.onImageSelected(bitmap, "Analyze this screenshot and tell me what you see. Ask if I have follow-up questions.")
                                    } else {
                                        viewModel.onImageSelected(bitmap, "")
                                    }
                                    screenshotBitmap = null
                                    autoAnalyze = false
                                }
                            }
                            
                            MainScreen(
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                },
                                viewModel = viewModel,
                                onPickImage = { text ->
                                    pendingImageText = text
                                    imagePickerLauncher.launch("image/*")
                                }
                            )
                        }
                        composable("settings") {
                            val viewModel = appModule.createSettingsViewModel()
                            SettingsScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                viewModel = viewModel,
                                onRequestOverlayPermission = {
                                    requestOverlayPermission()
                                },
                                onRequestMediaProjection = {
                                    requestMediaProjection()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        intent?.let {
            // Check if we need to handle screenshot
            if (it.getBooleanExtra("from_floating_button", false)) {
                android.util.Log.d("MainActivity", "Opened from floating button")
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        // Only ask for permissions on first launch - store flag in SharedPreferences
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasRequestedPermissions = prefs.getBoolean("has_requested_permissions", false)
        
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty() && !hasRequestedPermissions) {
            permissionLauncher.launch(permissionsToRequest)
            prefs.edit().putBoolean("has_requested_permissions", true).apply()
        }
    }
    
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
    
    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher?.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
    
    override fun onResume() {
        super.onResume()
        // Check floating button setting and start/stop service
        lifecycleScope.launch {
            try {
                val userSettings = appModule.userSettings
                val floatingEnabled = userSettings.floatingButtonEnabled.first()
                val hasOverlayPermission = Settings.canDrawOverlays(this@MainActivity)
                
                if (floatingEnabled && hasOverlayPermission) {
                    // Only start if not already running
                    if (!FloatingButtonService.isRunning) {
                        FloatingButtonService.start(this@MainActivity)
                    }
                } else {
                    // Stop if running
                    if (FloatingButtonService.isRunning) {
                        FloatingButtonService.stop(this@MainActivity)
                    }
                    
                    // If enabled but no permission, disable the setting
                    if (floatingEnabled && !hasOverlayPermission) {
                        userSettings.setFloatingButtonEnabled(false)
                        android.util.Log.w("MainActivity", "Floating button disabled: overlay permission not granted")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error managing floating button service", e)
            }
        }
    }
    
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, options)
                
                var sampleSize = 1
                while (options.outWidth / sampleSize > 2048 || options.outHeight / sampleSize > 2048) {
                    sampleSize *= 2
                }
                
                contentResolver.openInputStream(uri)?.use { stream2 ->
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream2, null, decodeOptions)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error loading bitmap", e)
            null
        }
    }
}
