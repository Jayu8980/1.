package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.BacktestRequest
import com.aitrader.data.model.Tearsheet
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class BacktestViewModel @Inject constructor(private val repo: TradingRepository) : ViewModel() {
    val strategies = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val running = MutableStateFlow(false)
    val result = MutableStateFlow<Tearsheet?>(null)
    val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            runCatching { repo.strategies() }.onSuccess { l -> strategies.value = l.data.map { it.id to it.name } }
        }
    }

    fun run(id: String, years: Int) = viewModelScope.launch {
        running.value = true; error.value = null
        val to = LocalDate.now(); val from = to.minusYears(years.toLong())
        runCatching { repo.backtest(BacktestRequest(id, from.toString(), to.toString())) }
            .onSuccess { result.value = it.data }.onFailure { error.value = it.message }
        running.value = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacktestScreen(vm: BacktestViewModel = hiltViewModel()) {
    val strategies by vm.strategies.collectAsStateCompat()
    val running by vm.running.collectAsStateCompat()
    val result by vm.result.collectAsStateCompat()
    val error by vm.error.collectAsStateCompat()
    var sel by remember(strategies) { mutableStateOf(strategies.firstOrNull()) }
    var years by remember { mutableIntStateOf(3) }
    var open by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExposedDropdownMenuBox(open, { open = it }) {
            OutlinedTextField(sel?.second ?: "", {}, readOnly = true, label = { Text("Strategy") },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(open, { open = false }) {
                strategies.forEach { s -> DropdownMenuItem({ Text(s.second) }, { sel = s; open = false }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 5, 10).forEach { y -> FilterChip(years == y, { years = y }, label = { Text("${y}y") }) }
        }
        Button(onClick = { sel?.let { vm.run(it.first, years) } }, enabled = sel != null && !running,
            modifier = Modifier.fillMaxWidth()) { Text(if (running) "Running on server…" else "Run backtest") }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = Loss) }
        result?.let { Tearsheet(it) }
    }
}

@Composable
private fun Tearsheet(t: Tearsheet) {
    if (t.synthetic) Text("SYNTHETIC demo tearsheet — connect the engine for real walk-forward results.",
        color = Warn, style = MaterialTheme.typography.labelMedium)
    val passGate = t.trades >= 200 && t.deflatedSharpe > 0.5
    SectionCard("${t.from} → ${t.to}") {
        LineChart(listOf(Series("Strategy", t.curve.map { it.equity }, MaterialTheme.colorScheme.primary),
            Series("NIFTY 50", t.curve.map { it.nifty }, Nifty, true)), description = "backtest equity")
        Spacer(Modifier.height(8.dp))
        Text("Drawdown", style = MaterialTheme.typography.labelSmall)
        DrawdownChart(t.drawdown)
        Text("Monthly returns", style = MaterialTheme.typography.labelSmall)
        BarChart(t.monthlyReturns)
    }
    SectionCard("Performance (after all costs)") {
        @Composable fun row(vararg s: Pair<String, String>) = Row { s.forEach { Stat(it.first, it.second, Modifier.weight(1f)) } }
        row("CAGR" to Fmt.pct(t.cagr * 100, 1), "Sharpe" to Fmt.num(t.sharpe), "Sortino" to Fmt.num(t.sortino))
        Spacer(Modifier.height(8.dp))
        row("Max DD" to Fmt.plainPct(t.maxDdPct), "Calmar" to Fmt.num(t.calmar), "Profit factor" to Fmt.num(t.profitFactor))
        Spacer(Modifier.height(8.dp))
        row("Alpha" to Fmt.pct(t.alpha * 100, 1), "Beta" to Fmt.num(t.beta), "Cost drag" to Fmt.plainPct(t.costDragPct))
        Spacer(Modifier.height(8.dp))
        row("Expectancy" to Fmt.money(t.expectancy), "Trades" to t.trades.toString(), "Deflated SR" to Fmt.num(t.deflatedSharpe))
    }
    Surface(color = (if (passGate) Profit else Loss).copy(alpha = 0.15f), shape = MaterialTheme.shapes.medium) {
        Text(if (passGate) "Passes validation gate (≥200 trades, deflated Sharpe > 0.5) → eligible for paper"
             else "Fails validation gate — not eligible for paper/live",
            Modifier.padding(12.dp).fillMaxWidth(), color = if (passGate) Profit else Loss, fontWeight = FontWeight.SemiBold)
    }
}
