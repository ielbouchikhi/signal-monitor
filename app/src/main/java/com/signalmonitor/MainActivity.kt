package com.signalmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.signalmonitor.data.preferences.UserPreferences
import com.signalmonitor.service.MonitoringService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.signalmonitor.ui.chart.FullScreenChartScreen
import com.signalmonitor.ui.chart.MetricKey
import com.signalmonitor.ui.dashboard.DashboardScreen
import com.signalmonitor.ui.history.HistoryScreen
import com.signalmonitor.ui.map.MapScreen
import com.signalmonitor.ui.settings.SettingsScreen
import com.signalmonitor.ui.stats.StatsScreen
import com.signalmonitor.ui.theme.SignalMonitorTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var userPreferences: UserPreferences

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted or denied — UI handles gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        restoreMonitoringIfNeeded()

        setContent {
            SignalMonitorTheme {
                val navController = rememberNavController()

                val navItems = listOf(
                    NavItem("dashboard", "Dashboard", Icons.Default.SignalCellularAlt),
                    NavItem("history", "History", Icons.Default.History),
                    NavItem("stats", "Stats", Icons.Default.BarChart),
                    NavItem("map", "Map", Icons.Default.Map),
                    NavItem("settings", "Settings", Icons.Default.Settings),
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentDestination = navBackStackEntry?.destination
                            navItems.forEach { item ->
                                NavigationBarItem(
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label) },
                                    selected = currentDestination?.hierarchy
                                        ?.any { it.route == item.route } == true,
                                    onClick = {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "dashboard",
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable("dashboard") {
                            DashboardScreen(
                                onChartClick = { key ->
                                    navController.navigate("chart/${key.name}")
                                }
                            )
                        }
                        composable("history") { HistoryScreen() }
                        composable("stats") { StatsScreen() }
                        composable("map") { MapScreen() }
                        composable("settings") { SettingsScreen() }
                        composable("chart/{metricKey}") { backStack ->
                            val key = MetricKey.valueOf(
                                backStack.arguments?.getString("metricKey") ?: MetricKey.RSRP.name
                            )
                            FullScreenChartScreen(
                                metricKey = key,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun restoreMonitoringIfNeeded() {
        lifecycleScope.launch {
            val wasEnabled = userPreferences.settings.first().monitoringEnabled
            if (wasEnabled) {
                startForegroundService(MonitoringService.startIntent(this@MainActivity))
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)
