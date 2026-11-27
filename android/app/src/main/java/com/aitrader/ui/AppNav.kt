package com.aitrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.aitrader.ui.components.GlassCard
import com.aitrader.ui.components.collectAsStateCompat
import com.aitrader.ui.screens.*
import com.aitrader.ui.theme.*

enum class Dest(val route: String, val title: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.SpaceDashboard),
    Markets("markets", "Markets", Icons.AutoMirrored.Filled.ShowChart),
    Positions("positions", "Positions", Icons.AutoMirrored.Filled.List),
    Signals("signals", "Signals", Icons.Filled.Bolt),
    Risk("risk", "Risk", Icons.Filled.Shield),
    More("more", "More", Icons.Filled.GridView),
    Strategies("strategies", "Strategies", Icons.Filled.Tune),
    Backtest("backtest", "Backtest", Icons.Filled.QueryStats),
    Analyst("analyst", "AI Analyst", Icons.Filled.Psychology),
    Logs("logs", "Logs & alerts", Icons.Filled.History),
    Brokers("brokers", "Brokers", Icons.Filled.AccountBalance),
    Appearance("appearance", "Appearance", Icons.Filled.Palette),
    Api("api", "API & MCP", Icons.Filled.Hub),
    Approvals("approvals", "Approvals", Icons.Filled.VerifiedUser),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

private val primary = listOf(Dest.Home, Dest.Markets, Dest.Positions, Dest.Risk, Dest.More)

/** Shows the first-launch setup until the user picks engine (real) or demo; applies appearance. */
@Composable
fun Root(startRoute: String, pendingRoute: String?, onRouteHandled: () -> Unit) {
    val vm: RootViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val s by vm.settings.collectAsStateCompat()
    val appearance = s?.let {
        Appearance(runCatching { LayoutStyle.valueOf(it.layout) }.getOrDefault(LayoutStyle.PRO),
            runCatching { ThemeStyle.valueOf(it.theme) }.getOrDefault(ThemeStyle.MIDNIGHT),
            runCatching { Accent.valueOf(it.accent) }.getOrDefault(Accent.AZURE), it.compact)
    } ?: Appearance()
    AITraderTheme(appearance) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when {
                s == null -> Box(Modifier.fillMaxSize())
                !s!!.onboarded -> SetupScreen(onEngine = { vm.finish(false) }, onDemo = { vm.finish(true) })
                else -> AppNav(if (!s!!.demoMode && s!!.engineUrl.isBlank()) Dest.Settings.route else startRoute,
                    pendingRoute, onRouteHandled)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(startRoute: String = Dest.Home.route, pendingRoute: String? = null, onRouteHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val current = Dest.entries.firstOrNull { it.route == entry?.destination?.route } ?: Dest.Home
    LaunchedEffect(pendingRoute) {
        pendingRoute?.takeIf { r -> Dest.entries.any { it.route == r } }?.let { nav.navigate(it) { launchSingleTop = true } }
        if (pendingRoute != null) onRouteHandled()
    }
    fun go(route: String) = nav.navigate(route) {
        if (Dest.entries.firstOrNull { it.route == route } in primary) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true
        }
        launchSingleTop = true
    }

    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                header = { BrandMark(Modifier.padding(vertical = 12.dp)) }) {
                Spacer(Modifier.weight(1f))
                (primary.dropLast(1) + listOf(Dest.Signals, Dest.Strategies, Dest.Brokers, Dest.More)).forEach { d ->
                    NavigationRailItem(selected = d == current, onClick = { go(d.route) }, icon = { Icon(d.icon, d.title) },
                        label = { Text(d.title) }, modifier = Modifier.testTag("nav_${d.route}"))
                }
                Spacer(Modifier.weight(1f))
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { if (current == Dest.Home) BrandMark() else Text(current.title, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            if (current !in primary) IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.Filled.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { go(Dest.Appearance.route) }) { Icon(Icons.Filled.Palette, "Appearance") }
                            IconButton(onClick = { go(Dest.Settings.route) }) { Icon(Icons.Filled.Settings, "Settings") }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                        primary.forEach { d ->
                            val sel = d == current || (d == Dest.More && current !in primary)
                            NavigationBarItem(sel, onClick = { go(d.route) }, icon = { Icon(d.icon, d.title) },
                                label = { Text(d.title) }, modifier = Modifier.testTag("nav_${d.route}"),
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                        }
                    }
                },
            ) { pad ->
                NavHost(nav, startRoute, Modifier.padding(pad)) {
                    composable(Dest.Home.route) { HomeScreen(snackbar, onNavigate = ::go) }
                    composable(Dest.Markets.route) { MarketsScreen(snackbar) }
                    composable(Dest.Positions.route) { PositionsScreen(snackbar) }
                    composable(Dest.Signals.route) { SignalsScreen(snackbar) }
                    composable(Dest.Risk.route) { RiskScreen(snackbar) }
                    composable(Dest.More.route) { MoreScreen(::go) }
                    composable(Dest.Strategies.route) { StrategiesScreen(snackbar) }
                    composable(Dest.Backtest.route) { BacktestScreen() }
                    composable(Dest.Analyst.route) { AnalystScreen() }
                    composable(Dest.Logs.route) { LogsScreen(snackbar) }
                    composable(Dest.Brokers.route) { BrokersScreen(snackbar) }
                    composable(Dest.Appearance.route) { AppearanceScreen() }
                    composable(Dest.Api.route) { ApiScreen(snackbar) }
                    composable(Dest.Approvals.route) { ApprovalsScreen(snackbar) }
                    composable(Dest.Settings.route) { SettingsScreen(snackbar) }
                }
            }
        }
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(t.accent.color, t.accent.second))),
            contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ShowChart, null, Modifier.size(16.dp), tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text("AI Trader", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
    }
}

/** Launcher-style grid of every section. */
@Composable
fun MoreScreen(go: (String) -> Unit) {
    val items = listOf(Dest.Approvals, Dest.Api, Dest.Brokers, Dest.Signals, Dest.Strategies, Dest.Backtest, Dest.Analyst,
        Dest.Logs, Dest.Appearance, Dest.Settings)
    BoxWithConstraints {
        val cols = if (maxWidth > 600.dp) 4 else 2
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { d ->
                        GlassCard(Modifier.weight(1f).height(96.dp).testTag("more_${d.route}"), onClick = { go(d.route) }) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center) { Icon(d.icon, null, tint = MaterialTheme.colorScheme.primary) }
                            Spacer(Modifier.weight(1f))
                            Text(d.title, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
