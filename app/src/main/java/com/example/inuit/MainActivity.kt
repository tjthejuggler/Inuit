package com.example.inuit

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import com.example.inuit.ui.MainScreen
import com.example.inuit.ui.MainViewModel
import com.example.inuit.ui.SettingsScreen
import com.example.inuit.ui.theme.InuitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as InuitApp).graph
        setContent {
            InuitTheme {
                RequestNotificationPermission()
                InuitNavHost(graph)
            }
        }
    }
}

/** Android 13+: ask once for POST_NOTIFICATIONS so the batch-generation
 *  foreground service can show its progress notification (the service works
 *  even if the user declines — the notification is just hidden). */
@Composable
private fun RequestNotificationPermission() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun InuitNavHost(graph: AppGraph) {
    val nav = rememberNavController()
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(graph))

    // Blind-training invariant: stats & Socratic threads stay frozen while
    // the user answers; they only absorb new answers when the user comes
    // BACK to the app (activity resume), never in real time.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onSessionResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScreen(viewModel = viewModel, onOpenSettings = { nav.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
    }
}
