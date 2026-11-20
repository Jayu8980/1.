package com.tradelens.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tradelens.app.data.broker.BrokerRegistry
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AuditEntity
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.domain.Instrument
import com.tradelens.app.ui.screens.AlertsScreen
import com.tradelens.app.ui.screens.AuditScreen
import com.tradelens.app.ui.screens.BacktestScreen
import com.tradelens.app.ui.screens.ChatScreen
import com.tradelens.app.ui.screens.CryptoScreen
import com.tradelens.app.ui.screens.EngineScreen
import com.tradelens.app.ui.screens.HomeScreen
import com.tradelens.app.ui.screens.InstrumentScreen
import com.tradelens.app.ui.screens.MoreScreen
import com.tradelens.app.ui.screens.OptionChainScreen
import com.tradelens.app.ui.screens.PaperScreen
import com.tradelens.app.ui.screens.PortfolioScreen
import com.tradelens.app.ui.screens.ReportsScreen
import com.tradelens.app.ui.screens.RiskToolsScreen
import com.tradelens.app.ui.screens.ScreenerScreen
import com.tradelens.app.ui.screens.SettingsScreen
import com.tradelens.app.ui.screens.WatchlistScreen
import com.tradelens.app.ui.theme.Warn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** OAuth redirects arrive in MainActivity and are forwarded here. */
@Singleton
class DeepLinks @Inject constructor() {
    private val _auth = MutableSharedFlow<Pair<String, Map<String, String>>>(replay = 1, extraBufferCapacity = 4)
    val auth: SharedFlow<Pair<String, Map<String, String>>> = _auth
    fun emitAuth(broker: String, params: Map<String, String>) { _auth.tryEmit(broker to params) }
}

@HiltViewModel
class AppViewModel @Inject constructor(
    val settings: SettingsStore,
    private val brokers: BrokerRegistry,
    private val deepLinks: DeepLinks,
    private val db: AppDatabase,
) : ViewModel() {
    val expired = brokers.expired.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    init {
        viewModelScope.launch {
            if (db.watchlists().count() == 0) {
                val id = db.watchlists().insertList(com.tradelens.app.data.db.WatchlistEntity(name = "My Watchlist"))
                listOf("NIFTY 50", "BANKNIFTY", "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK").forEachIndexed { k, s ->
                    db.watchlists().insertItem(com.tradelens.app.data.db.WatchlistItemEntity(listId = id, instrumentJson = AppJson.encodeToString(Instrument.serializer(), Instrument.parse(s)), position = k))
                }
                val c = db.watchlists().insertList(com.tradelens.app.data.db.WatchlistEntity(name = "Crypto", position = 1))
                listOf("BTCUSD", "ETHUSD").forEachIndexed { k, s ->
                    db.watchlists().insertItem(com.tradelens.app.data.db.WatchlistItemEntity(listId = c, instrumentJson = AppJson.encodeToString(Instrument.serializer(), Instrument.parse(s, crypto = true)), position = k))
                }
            }
        }
        viewModelScope.launch {
            deepLinks.auth.collect { (id, params) ->
                val b = brokers.byId(id) ?: return@collect
                runCatching { b.login(params) }
                    .onSuccess {
                        db.audit().log(AuditEntity(category = "AUTH", message = "${b.displayName} connected"))
                        messages.emit("${b.displayName} connected. Downloading instrument master…")
                        runCatching { brokers.refreshInstruments(b) }.onSuccess { n -> messages.emit("${b.displayName}: $n instruments cached") }
                            .onFailure { e -> messages.emit("Instrument download failed: ${e.message}") }
                    }
                    .onFailure { e -> messages.emit("${b.displayName} login failed: ${e.message}") }
            }
        }
    }

    fun acceptDisclaimer() = settings.update { it.copy(disclaimerAccepted = true) }
}

object Routes {
    const val HOME = "home"; const val WATCH = "watch"; const val PAPER = "paper"; const val ENGINE = "engine"; const val MORE = "more"
    const val SCREENER = "screener"; const val BACKTEST = "backtest"; const val PORTFOLIO = "portfolio"; const val OPTIONS = "options"
    const val CRYPTO = "crypto"; const val ALERTS = "alerts"; const val AUDIT = "audit"; const val SETTINGS = "settings"
    const val RISK = "risk"; const val REPORTS = "reports"
    fun instrument(i: Instrument) = "instrument?i=" + Uri.encode(AppJson.encodeToString(Instrument.serializer(), i))
    fun chat(i: Instrument) = "chat?i=" + Uri.encode(AppJson.encodeToString(Instrument.serializer(), i))
    fun options(i: Instrument) = "options?i=" + Uri.encode(AppJson.encodeToString(Instrument.serializer(), i))
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val settings by vm.settings.state.collectAsState()
    val expired by vm.expired.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    if (!settings.disclaimerAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Important – not financial advice") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("TradeLens is an analysis tool. Indicators, patterns, AI analysis, backtests and strategy signals are educational and can be wrong. " +
                        "Past performance does not guarantee future results. Derivatives and leveraged crypto can lose more than your margin.\n\n" +
                        "Orders: by default the app never places Indian broker orders from your phone (SEBI static-IP rules). " +
                        "Live crypto orders are off and Testnet-only until you enable them. Every live order needs your approval.\n\n" +
                        "You are solely responsible for your trading decisions.")
                }
            },
            confirmButton = { TextButton(onClick = vm::acceptDisclaimer) { Text("I understand") } },
        )
    }

    val tabs = listOf(
        Tab(Routes.HOME, "Home", Icons.Default.Home), Tab(Routes.WATCH, "Watchlist", Icons.AutoMirrored.Filled.List),
        Tab(Routes.PAPER, "Paper", Icons.Default.AccountBalanceWallet), Tab(Routes.ENGINE, "Engine", Icons.Default.Memory),
        Tab(Routes.MORE, "More", Icons.Default.MoreHoriz),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (expired.isNotEmpty()) Row(
                Modifier.fillMaxWidth().background(Warn.copy(alpha = 0.2f)).padding(top = 36.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .clickable { nav.navigate(Routes.SETTINGS) },
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("Reconnect broker: ${expired.joinToString { it.displayName }} session expired – tap to log in again", color = Warn) }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route, onClick = { nav.navigate(t.route) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        AppNavHost(nav, Modifier.padding(pad))
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, modifier: Modifier) {
    val instArg = listOf(navArgument("i") { type = NavType.StringType })
    fun decode(s: String?) = AppJson.decodeFromString(Instrument.serializer(), Uri.decode(s ?: ""))
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.WATCH) { WatchlistScreen(nav) }
        composable(Routes.PAPER) { PaperScreen(nav) }
        composable(Routes.ENGINE) { EngineScreen(nav) }
        composable(Routes.MORE) { MoreScreen(nav) }
        composable(Routes.SCREENER) { ScreenerScreen(nav) }
        composable(Routes.BACKTEST) { BacktestScreen() }
        composable(Routes.PORTFOLIO) { PortfolioScreen() }
        composable(Routes.OPTIONS) { OptionChainScreen(null) }
        composable("options?i={i}", instArg) { OptionChainScreen(decode(it.arguments?.getString("i"))) }
        composable(Routes.CRYPTO) { CryptoScreen(nav) }
        composable(Routes.ALERTS) { AlertsScreen() }
        composable(Routes.AUDIT) { AuditScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.RISK) { RiskToolsScreen() }
        composable(Routes.REPORTS) { ReportsScreen() }
        composable("instrument?i={i}", instArg) { InstrumentScreen(decode(it.arguments?.getString("i")), nav) }
        composable("chat?i={i}", instArg) { ChatScreen(decode(it.arguments?.getString("i"))) }
    }
}
