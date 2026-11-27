package com.aitrader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.aitrader.ui.screens.*

enum class Dest(val route: String, val title: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Dashboard),
    Positions("positions", "Positions", Icons.AutoMirrored.Filled.List),
    Signals("signals", "Signals", Icons.Filled.Bolt),
    Risk("risk", "Risk", Icons.Filled.Shield),
    More("more", "More", Icons.Filled.MoreHoriz),
    Strategies("strategies", "Strategies", Icons.Filled.Tune),
    Backtest("backtest", "Backtest", Icons.Filled.QueryStats),
    Analyst("analyst", "AI Analyst", Icons.Filled.Psychology),
    Logs("logs", "Logs & alerts", Icons.Filled.History),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

private val bottom = listOf(Dest.Home, Dest.Positions, Dest.Signals, Dest.Risk, Dest.More)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(startRoute: String = Dest.Home.route) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val current = Dest.entries.firstOrNull { it.route == route } ?: Dest.Home

    Scaffold(
        topBar = { TopAppBar(title = { Text(current.title) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                bottom.forEach { d ->
                    val selected = d == current || (d == Dest.More && current !in bottom)
                    NavigationBarItem(selected, onClick = {
                        nav.navigate(d.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }, icon = { Icon(d.icon, d.title) }, label = { Text(d.title) }, modifier = Modifier.testTag("nav_${d.route}"))
                }
            }
        },
    ) { pad ->
        NavHost(nav, startRoute, Modifier.padding(pad)) {
            composable(Dest.Home.route) { HomeScreen(snackbar) }
            composable(Dest.Positions.route) { PositionsScreen(snackbar) }
            composable(Dest.Signals.route) { SignalsScreen(snackbar) }
            composable(Dest.Risk.route) { RiskScreen(snackbar) }
            composable(Dest.More.route) { MoreScreen { nav.navigate(it.route) } }
            composable(Dest.Strategies.route) { StrategiesScreen(snackbar) }
            composable(Dest.Backtest.route) { BacktestScreen() }
            composable(Dest.Analyst.route) { AnalystScreen() }
            composable(Dest.Logs.route) { LogsScreen(snackbar) }
            composable(Dest.Settings.route) { SettingsScreen(snackbar) }
        }
    }
}

@Composable
private fun MoreScreen(go: (Dest) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        listOf(Dest.Strategies, Dest.Backtest, Dest.Analyst, Dest.Logs, Dest.Settings).forEach { d ->
            ListItem(headlineContent = { Text(d.title) }, leadingContent = { Icon(d.icon, null) },
                modifier = Modifier.clickable { go(d) }.testTag("more_${d.route}"))
        }
    }
}
