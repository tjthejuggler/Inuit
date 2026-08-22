package com.example.inuit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
                InuitNavHost(graph)
            }
        }
    }
}

@Composable
private fun InuitNavHost(graph: AppGraph) {
    val nav = rememberNavController()
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(graph))
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScreen(viewModel = viewModel, onOpenSettings = { nav.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
    }
}
