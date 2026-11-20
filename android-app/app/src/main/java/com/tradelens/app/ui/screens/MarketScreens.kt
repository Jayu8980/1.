package com.tradelens.app.ui.screens

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tradelens.app.data.broker.BrokerRegistry
import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.delta.DeltaBalance
import com.tradelens.app.data.delta.DeltaClient
import com.tradelens.app.data.delta.DeltaTicker
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.engine.StrategyPerf
import com.tradelens.app.data.market.MarketRepository
import com.tradelens.app.data.market.Universe
import com.tradelens.app.domain.BlackScholes
import com.tradelens.app.domain.Holding
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketDepth
import com.tradelens.app.domain.OptionAnalytics
import com.tradelens.app.domain.OptionChain
import com.tradelens.app.domain.OptionType
import com.tradelens.app.domain.Order
import com.tradelens.app.domain.Position
import com.tradelens.app.domain.Timeframe
import com.tradelens.app.ui.Routes
import com.tradelens.app.ui.components.Disclaimer
import com.tradelens.app.ui.components.ErrorText
import com.tradelens.app.ui.components.KV
import com.tradelens.app.ui.components.LineChart
import com.tradelens.app.ui.components.ListPadding
import com.tradelens.app.ui.components.Loading
import com.tradelens.app.ui.components.SectionCard
import com.tradelens.app.ui.components.SourceChip
import com.tradelens.app.ui.components.fmtMoney
import com.tradelens.app.ui.components.fmtPct
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.fmtTime
import com.tradelens.app.ui.components.pnlColor
import com.tradelens.app.ui.theme.Accent
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Bull
import com.tradelens.app.ui.theme.Muted
import com.tradelens.app.ui.theme.Surface2
import com.tradelens.app.ui.theme.Warn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

// ---------------- Portfolio ----------------

data class PortfolioUi(
    val holdings: List<Holding> = emptyList(), val positions: List<Position> = emptyList(), val funds: List<String> = emptyList(),
    val deltaBalances: List<DeltaBalance> = emptyList(), val sectors: Map<String, Double> = emptyMap(), val errors: List<String> = emptyList(), val loading: Boolean = false,
)

@HiltViewModel
class PortfolioViewModel @Inject constructor(private val brokers: BrokerRegistry, private val delta: DeltaClient, private val universe: Universe) : ViewModel() {
    val ui = MutableStateFlow(PortfolioUi())
    fun load() = viewModelScope.launch {
        ui.value = ui.value.copy(loading = true)
        val h = mutableListOf<Holding>(); val p = mutableListOf<Position>(); val f = mutableListOf<String>(); val e = mutableListOf<String>()
        for (b in brokers.connected()) {
            runCatching { h += b.getHoldings() }.onFailure { e += "${b.displayName} holdings: ${it.message}" }
            runCatching { p += b.getPositions() }.onFailure { e += "${b.displayName} positions: ${it.message}" }
            runCatching { b.getFunds() }.onSuccess { f += "${b.displayName}: available ${fmtMoney(it.available)}, used ${fmtMoney(it.used)}" }.onFailure { e += "${b.displayName} funds: ${it.message}" }
        }
        var bal = emptyList<DeltaBalance>()
        if (delta.hasKeys()) {
            runCatching { p += delta.positions() }.onFailure { e += "Delta positions: ${it.message}" }
            runCatching { bal = delta.balances() }.onFailure { e += "Delta wallet: ${it.message}" }
        }
        if (brokers.connected().isEmpty() && !delta.hasKeys()) e += "No broker connected – Settings → Brokers. (Paper portfolio lives on the Paper tab.)"
        val members = runCatching { universe.members("nifty500") }.getOrDefault(Universe.NIFTY50)
        val withSector = h.map { it.copy(sector = universe.sectorOf(it.symbol, members)) }
        val total = withSector.sumOf { it.current }.takeIf { it > 0 } ?: 1.0
        ui.value = PortfolioUi(withSector, p, f, bal, withSector.groupBy { it.sector }.mapValues { (_, v) -> v.sumOf { it.current } / total * 100 }, e, false)
    }
}

@Composable
fun PortfolioScreen(vm: PortfolioViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Portfolio – all brokers", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = { vm.load() }) { Text("Refresh") }
            }
        }
        if (ui.loading) item { Loading() }
        items(ui.errors) { ErrorText(it) }
        item {
            val inv = ui.holdings.sumOf { it.invested }; val cur = ui.holdings.sumOf { it.current }; val day = ui.holdings.sumOf { it.dayPnl } + ui.positions.filter { !it.instrumentKey.startsWith("DELTA") }.sumOf { it.dayPnl }
            SectionCard("Summary") {
                KV("Invested", fmtMoney(inv)); KV("Current", fmtMoney(cur)); KV("Overall P&L", "${fmtMoney(cur - inv)} (${fmtPct(if (inv > 0) (cur / inv - 1) * 100 else 0.0)})", pnlColor(cur - inv))
                KV("Day P&L", fmtMoney(day), pnlColor(day))
                ui.funds.forEach { Text(it, fontSize = 12.sp, color = Muted) }
                ui.deltaBalances.filter { it.balance > 0 }.forEach { Text("Delta ${it.asset}: ${fmtPrice(it.balance)} (avail ${fmtPrice(it.available)})", fontSize = 12.sp, color = Muted) }
            }
        }
        if (ui.sectors.isNotEmpty()) item {
            SectionCard("Sector allocation") {
                ui.sectors.entries.sortedByDescending { it.value }.forEach { (s, pct) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s, fontSize = 12.sp, modifier = Modifier.width(140.dp), maxLines = 1)
                        Spacer(Modifier.width((pct * 1.6).dp).padding(vertical = 6.dp).background(if (pct > 25) Warn else Accent))
                        Text(" %.1f%%".format(pct), fontSize = 12.sp, color = if (pct > 25) Warn else Muted)
                    }
                }
            }
        }
        item { Text("Holdings", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        items(ui.holdings) { h ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f)) { Text(h.symbol, fontWeight = FontWeight.SemiBold); Text("${h.qty.toLong()} @ ${fmtPrice(h.avgPrice)} · ${h.broker} · ${h.sector}", color = Muted, fontSize = 11.sp) }
                Column(horizontalAlignment = Alignment.End) { Text(fmtMoney(h.pnl), color = pnlColor(h.pnl)); Text("day ${fmtMoney(h.dayPnl)}", color = pnlColor(h.dayPnl), fontSize = 11.sp) }
            }
            HorizontalDivider()
        }
        item { Text("Positions", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        items(ui.positions) { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${p.symbol} ${p.qty}", fontWeight = FontWeight.SemiBold)
                    Text("avg ${fmtPrice(p.avgPrice)} · ltp ${fmtPrice(p.ltp)} · ${p.broker}" + (p.liquidationPrice?.let { " · liq ${fmtPrice(it)} · ${p.leverage}x" } ?: ""), color = Muted, fontSize = 11.sp)
                }
                Text(fmtPrice(p.pnl), color = pnlColor(p.pnl))
            }
            HorizontalDivider()
        }
    }
}

// ---------------- Option chain ----------------

data class ChainRow(val strike: Double, val ce: com.tradelens.app.domain.OptionQuote?, val pe: com.tradelens.app.domain.OptionQuote?, val ceIv: Double?, val peIv: Double?, val ceDelta: Double?, val peDelta: Double?, val gamma: Double?, val ceTheta: Double?, val vega: Double?)

data class ChainUi(val chain: OptionChain? = null, val rows: List<ChainRow> = emptyList(), val expiries: List<Long> = emptyList(), val pcr: Double = 0.0, val maxPain: Double? = null,
                   val loading: Boolean = false, val error: String? = null, val depth: MarketDepth? = null)

@HiltViewModel
class OptionChainViewModel @Inject constructor(private val market: MarketRepository, private val delta: DeltaClient) : ViewModel() {
    val ui = MutableStateFlow(ChainUi())
    fun load(u: Instrument, expiry: Long?) = viewModelScope.launch {
        ui.value = ui.value.copy(loading = true, error = null)
        runCatching {
            val ch = market.optionChain(u, expiry) ?: error(if (u.isCrypto) "No Delta options for ${u.symbol}" else "Option chain needs a connected broker (Upstox, Fyers or Dhan) – Yahoo has no option data")
            val years = OptionAnalytics.yearsTo(ch.expiry)
            val rate = if (u.isCrypto) 0.0 else 0.065
            val rows = ch.rows.groupBy { it.strike }.toSortedMap().map { (k, v) ->
                val ce = v.firstOrNull { it.type == OptionType.CE }; val pe = v.firstOrNull { it.type == OptionType.PE }
                val ceIv = ce?.iv?.div(100) ?: ce?.takeIf { it.ltp > 0 }?.let { BlackScholes.impliedVol(OptionType.CE, it.ltp, ch.spot, k, years, rate) }
                val peIv = pe?.iv?.div(100) ?: pe?.takeIf { it.ltp > 0 }?.let { BlackScholes.impliedVol(OptionType.PE, it.ltp, ch.spot, k, years, rate) }
                val gc = ceIv?.let { BlackScholes.greeks(OptionType.CE, ch.spot, k, years, it, rate) }
                val gp = peIv?.let { BlackScholes.greeks(OptionType.PE, ch.spot, k, years, it, rate) }
                ChainRow(k, ce, pe, ceIv, peIv, gc?.delta, gp?.delta, gc?.gamma ?: gp?.gamma, gc?.theta, gc?.vega ?: gp?.vega)
            }
            val exps = if (u.isCrypto) delta.optionExpiries(u.underlying ?: u.symbol.removeSuffix("USD")) else ui.value.expiries.ifEmpty { listOf(ch.expiry) }
            ChainUi(ch, rows, exps, OptionAnalytics.pcr(ch.rows), OptionAnalytics.maxPain(ch.rows))
        }.onSuccess { ui.value = it }.onFailure { ui.value = ui.value.copy(loading = false, error = it.message) }
    }
    fun depth(symbol: String, crypto: Boolean) = viewModelScope.launch {
        ui.value = ui.value.copy(depth = runCatching { market.depth(if (crypto) Instrument.parse(symbol, true) else Instrument.parse(symbol)) }.getOrNull())
    }
}

@Composable
fun OptionChainScreen(initial: Instrument?, vm: OptionChainViewModel = hiltViewModel()) {
    val choices = listOf(Instrument.parse("NIFTY 50"), Instrument.parse("BANKNIFTY"), Instrument.parse("FINNIFTY"),
        Instrument("BTC", com.tradelens.app.domain.Exchange.DELTA, com.tradelens.app.domain.Segment.CRYPTO_OPTION, underlying = "BTC"),
        Instrument("ETH", com.tradelens.app.domain.Exchange.DELTA, com.tradelens.app.domain.Segment.CRYPTO_OPTION, underlying = "ETH"))
    var u by remember { mutableStateOf(initial ?: choices.first()) }
    var hideIlliquid by remember { mutableStateOf(true) }
    val ui by vm.ui.collectAsState()
    LaunchedEffect(u.key) { vm.load(u, null) }
    val ch = ui.chain
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                (choices + listOfNotNull(initial).filter { i -> choices.none { it.key == i.key } }).forEach { c -> FilterChip(u.key == c.key, { u = c }, { Text(c.symbol) }); Spacer(Modifier.width(4.dp)) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                ui.expiries.take(10).forEach { e -> FilterChip(ch?.expiry == e, { vm.load(u, e) }, { Text(fmtTime(e, "dd MMM")) }); Spacer(Modifier.width(4.dp)) }
            }
        }
        if (ui.loading) item { Loading() }
        item { ErrorText(ui.error) }
        if (ch != null) {
            item {
                SectionCard("${ch.underlying} · spot ${fmtPrice(ch.spot)} · exp ${fmtTime(ch.expiry, "dd MMM yy")}", trailing = { SourceChip(ch.badge) }) {
                    KV("PCR (OI)", "%.2f".format(ui.pcr), if (ui.pcr > 1.2) Bull else if (ui.pcr < 0.7) Bear else Muted)
                    KV("Max pain", fmtPrice(ui.maxPain))
                    val ceMax = ch.rows.filter { it.type == OptionType.CE }.maxByOrNull { it.oi }; val peMax = ch.rows.filter { it.type == OptionType.PE }.maxByOrNull { it.oi }
                    KV("Highest CE OI (resistance)", fmtPrice(ceMax?.strike)); KV("Highest PE OI (support)", fmtPrice(peMax?.strike))
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(hideIlliquid, { hideIlliquid = it }); Text(" Skip illiquid strikes (no volume / no bid)", fontSize = 12.sp) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().background(Surface2).padding(vertical = 6.dp, horizontal = 4.dp)) {
                    listOf("OI", "ΔOI", "IV", "Δ", "LTP", "STRIKE", "LTP", "Δ", "IV", "ΔOI", "OI").forEach { Text(it, fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f)) }
                }
            }
            val atm = ui.rows.minByOrNull { abs(it.strike - ch.spot) }?.strike
            val rows = ui.rows.filter { r -> !hideIlliquid || ((r.ce?.volume ?: 0.0) > 0 || (r.pe?.volume ?: 0.0) > 0 || (r.ce?.bid ?: 0.0) > 0 || (r.pe?.bid ?: 0.0) > 0) }
            items(rows, key = { it.strike }) { r ->
                val itmCe = r.strike < ch.spot
                Row(Modifier.fillMaxWidth().background(if (r.strike == atm) Accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 5.dp, horizontal = 4.dp)) {
                    @Composable fun cell(t: String, c: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, onClick: (() -> Unit)? = null) =
                        Text(t, fontSize = 10.sp, color = c, modifier = Modifier.weight(1f).let { m -> onClick?.let { m.clickable(onClick = it) } ?: m })
                    cell(compact(r.ce?.oi), if (itmCe) Warn else androidx.compose.ui.graphics.Color.Unspecified)
                    cell(compact(r.ce?.oiChange), pnlColor(r.ce?.oiChange ?: 0.0))
                    cell(r.ceIv?.let { "%.1f".format(it * 100) } ?: "—")
                    cell(r.ceDelta?.let { "%.2f".format(it) } ?: "—")
                    cell(fmtPrice(r.ce?.ltp), Bull) { r.ce?.symbol?.takeIf { it.isNotBlank() }?.let { vm.depth(it, u.isCrypto) } }
                    Text(fmtPrice(r.strike), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    cell(fmtPrice(r.pe?.ltp), Bear) { r.pe?.symbol?.takeIf { it.isNotBlank() }?.let { vm.depth(it, u.isCrypto) } }
                    cell(r.peDelta?.let { "%.2f".format(it) } ?: "—")
                    cell(r.peIv?.let { "%.1f".format(it * 100) } ?: "—")
                    cell(compact(r.pe?.oiChange), pnlColor(r.pe?.oiChange ?: 0.0))
                    cell(compact(r.pe?.oi), if (!itmCe) Warn else androidx.compose.ui.graphics.Color.Unspecified)
                }
            }
            item {
                SectionCard("OI buildup (by strike, calls / puts)") {
                    ui.rows.filter { atm != null && abs(it.strike - atm) <= (ui.rows.getOrNull(1)?.strike?.minus(ui.rows[0].strike) ?: 50.0) * 5 }.forEach { r ->
                        val ce = r.ce?.let { OptionAnalytics.buildup(it.priceChange, it.oiChange).label } ?: "—"
                        val pe = r.pe?.let { OptionAnalytics.buildup(it.priceChange, it.oiChange).label } ?: "—"
                        KV(fmtPrice(r.strike), "CE: $ce · PE: $pe")
                    }
                    Text("Greeks: Black-Scholes (r = ${if (u.isCrypto) "0%" else "6.5%"}); theta per day, vega per vol-point. Tap an LTP for market depth.", color = Muted, fontSize = 11.sp)
                }
            }
            ui.depth?.let { d ->
                item {
                    SectionCard("Market depth") {
                        d.bids.zip(d.asks).take(20).forEach { (b, a) ->
                            Row { Text("${compact(b.qty)} @ ${fmtPrice(b.price)}", color = Bull, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${fmtPrice(a.price)} × ${compact(a.qty)}", color = Bear, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        item { Disclaimer() }
    }
}

fun compact(v: Double?): String = when {
    v == null -> "—"
    abs(v) >= 1e7 -> "%.1fCr".format(v / 1e7)
    abs(v) >= 1e5 -> "%.1fL".format(v / 1e5)
    abs(v) >= 1e3 -> "%.1fK".format(v / 1e3)
    else -> "%.0f".format(v)
}

// ---------------- Crypto analytics ----------------

data class CryptoUi(val perps: List<DeltaTicker> = emptyList(), val futures: List<DeltaTicker> = emptyList(), val funding: List<Pair<Long, Double>> = emptyList(),
                    val fundingSymbol: String = "BTCUSD", val positions: List<Position> = emptyList(), val orders: List<Order> = emptyList(),
                    val balances: List<DeltaBalance> = emptyList(), val error: String? = null, val loading: Boolean = false)

@HiltViewModel
class CryptoViewModel @Inject constructor(val delta: DeltaClient) : ViewModel() {
    val ui = MutableStateFlow(CryptoUi())
    fun load() = viewModelScope.launch {
        ui.value = ui.value.copy(loading = true, error = null)
        runCatching {
            val perps = delta.tickers("perpetual_futures").sortedByDescending { it.turnoverUsd }.take(40)
            val fut = runCatching { delta.tickers("futures") }.getOrDefault(emptyList())
            ui.value = ui.value.copy(perps = perps, futures = fut, loading = false)
        }.onFailure { ui.value = ui.value.copy(error = it.message, loading = false) }
        funding(ui.value.fundingSymbol)
        if (delta.hasKeys()) runCatching {
            ui.value = ui.value.copy(positions = delta.positions(), orders = delta.openOrders(), balances = delta.balances())
        }.onFailure { ui.value = ui.value.copy(error = "Private data: ${it.message}") }
    }
    fun funding(sym: String) = viewModelScope.launch {
        ui.value = ui.value.copy(fundingSymbol = sym, funding = runCatching { delta.fundingHistory(sym, 30) }.getOrDefault(emptyList()))
    }
    /** Live ticker updates for the visible top perps. */
    suspend fun live() {
        val syms = ui.value.perps.take(15).map { it.symbol }
        if (syms.isEmpty()) return
        delta.tickerStream(syms).collect { t -> ui.value = ui.value.copy(perps = ui.value.perps.map { if (it.symbol == t.symbol) t else it }) }
    }
}

@Composable
fun CryptoScreen(nav: NavController, vm: CryptoViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(ui.perps.isNotEmpty()) { if (ui.perps.isNotEmpty()) vm.live() }
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Delta Exchange India", fontWeight = FontWeight.Bold); Text(if (vm.delta.isTestnet) "TESTNET" else "PRODUCTION", color = if (vm.delta.isTestnet) Warn else Bull, fontSize = 11.sp) }
                TextButton(onClick = { vm.load() }) { Text("Refresh") }
            }
        }
        if (ui.loading) item { Loading() }
        item { ErrorText(ui.error) }
        item {
            SectionCard("Funding rate history – ${ui.fundingSymbol} (30d, % per 8h)") {
                LineChart(listOf(ui.funding.map { it.second } to Accent), fillBelowZero = true)
                ui.funding.lastOrNull()?.let { KV("Latest", "%.4f%%  (≈ %.1f%% annualised)".format(it.second, it.second * 3 * 365)) }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD").forEach { s -> FilterChip(ui.fundingSymbol == s, { vm.funding(s) }, { Text(s) }); Spacer(Modifier.width(4.dp)) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().background(Surface2).padding(8.dp)) {
                listOf("Perp", "Mark", "24h", "Funding", "OI $", "Basis").forEach { Text(it, fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f)) }
            }
        }
        items(ui.perps, key = { it.symbol }) { t ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.instrument(Instrument.parse(t.symbol, crypto = true))) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(t.symbol, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(fmtPrice(t.mark), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(fmtPct(t.changePct), color = pnlColor(t.changePct), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("%.4f%%".format(t.fundingRate), color = if (abs(t.fundingRate) > 0.05) Warn else pnlColor(-t.fundingRate), fontSize = 11.sp, modifier = Modifier.weight(1f).clickable { vm.funding(t.symbol) })
                Text(compact(t.oiValueUsd), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("%.3f%%".format(t.basisPct), fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
        }
        if (ui.futures.isNotEmpty()) item {
            SectionCard("Dated futures – basis vs spot index") {
                ui.futures.sortedBy { it.settlement ?: 0 }.take(12).forEach { f ->
                    val days = ((f.settlement ?: 0) - System.currentTimeMillis() / 1000) / 86400.0
                    KV(f.symbol, "basis %.2f%% · ann. %.1f%% · OI %s".format(f.basisPct, if (days > 0) f.basisPct * 365 / days else 0.0, compact(f.oiValueUsd)))
                }
            }
        }
        item {
            SectionCard("Options") {
                Row { TextButton(onClick = { nav.navigate(Routes.options(Instrument("BTC", com.tradelens.app.domain.Exchange.DELTA, com.tradelens.app.domain.Segment.CRYPTO_OPTION, underlying = "BTC"))) }) { Text("BTC options chain") }
                    TextButton(onClick = { nav.navigate(Routes.options(Instrument("ETH", com.tradelens.app.domain.Exchange.DELTA, com.tradelens.app.domain.Segment.CRYPTO_OPTION, underlying = "ETH"))) }) { Text("ETH options chain") } }
                TextButton(onClick = { nav.navigate(Routes.RISK) }) { Text("Liquidation-price calculator") }
            }
        }
        if (vm.delta.hasKeys()) {
            item {
                SectionCard("Wallet") { ui.balances.filter { it.balance != 0.0 }.forEach { KV(it.asset, "${fmtPrice(it.balance)} (avail ${fmtPrice(it.available)}, margin ${fmtPrice(it.blocked)})") } }
            }
            item {
                SectionCard("Open positions") {
                    if (ui.positions.isEmpty()) Text("None", color = Muted)
                    ui.positions.forEach { p ->
                        KV("${p.symbol} ${p.qty} @ ${fmtPrice(p.avgPrice)} (${p.leverage}x)", "uPnL ${fmtPrice(p.pnl)}", pnlColor(p.pnl))
                        Text("mark ${fmtPrice(p.ltp)} · liquidation ${fmtPrice(p.liquidationPrice)}", color = Bear, fontSize = 11.sp)
                    }
                }
            }
            item {
                SectionCard("Open orders") {
                    if (ui.orders.isEmpty()) Text("None", color = Muted)
                    ui.orders.forEach { o -> KV("${o.side} ${o.qty} ${o.instrumentKey.substringAfter(':')}", "${o.type} ${fmtPrice(o.price)} · ${o.status}") }
                }
            }
        } else item { Text("Add Delta API keys in Settings to see wallet, positions and orders.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
        item { Disclaimer() }
    }
}

// ---------------- Reports ----------------

@HiltViewModel
class ReportsViewModel @Inject constructor(private val engine: EngineClient, private val db: AppDatabase) : ViewModel() {
    val board = MutableStateFlow<List<StrategyPerf>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    fun load() = viewModelScope.launch {
        if (!engine.isConfigured()) { error.value = "Connect the engine to see the strategy leaderboard. Paper-journal export works offline."; return@launch }
        runCatching { board.value = engine.api.leaderboard() }.onFailure { error.value = it.message }
    }
    suspend fun engineExport(ctx: android.content.Context, format: String): File {
        val f = File(ctx.cacheDir, "tradelens_report.$format")
        engine.api.export(format).byteStream().use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }
    /** Local PDF of the paper-trading journal (works without the engine). */
    suspend fun journalPdf(ctx: android.content.Context): File {
        val trades = db.paper().trades().first()
        val doc = PdfDocument(); val paint = Paint().apply { textSize = 10f }
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()); var y = 40f; var n = 1
        page.canvas.drawText("TradeLens – paper trading journal (not financial advice)", 30f, y, paint.apply { textSize = 14f }); paint.textSize = 9f; y += 24
        page.canvas.drawText("Trades: ${trades.size}   Net P&L: ${fmtMoney(trades.sumOf { it.pnl })}   Charges: ${fmtMoney(trades.sumOf { it.charges })}   Win rate: ${if (trades.isEmpty()) 0 else trades.count { it.pnl > 0 } * 100 / trades.size}%", 30f, y, paint); y += 20
        for (t in trades) {
            if (y > 810) { doc.finishPage(page); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); y = 40f }
            page.canvas.drawText("${fmtTime(t.closedAt / 1000, "dd MMM yy")}  ${t.symbol} ${t.side} ${t.qty}  ${fmtPrice(t.entry)} → ${fmtPrice(t.exit)}  P&L ${fmtMoney(t.pnl)}  [${t.entryReason} / ${t.exitReason}] ${t.notes}".take(130), 30f, y, paint); y += 14
        }
        doc.finishPage(page)
        val f = File(ctx.cacheDir, "tradelens_journal.pdf"); f.outputStream().use { doc.writeTo(it) }; doc.close()
        return f
    }
    suspend fun journalCsv(): String = "closed,symbol,side,qty,entry,exit,pnl,charges,entry_reason,exit_reason,notes\n" +
        db.paper().trades().first().joinToString("\n") { "${fmtTime(it.closedAt / 1000, "yyyy-MM-dd HH:mm")},${it.symbol},${it.side},${it.qty},${it.entry},${it.exit},${"%.2f".format(it.pnl)},${"%.2f".format(it.charges)},\"${it.entryReason}\",\"${it.exitReason}\",\"${it.notes}\"" }
}

@Composable
fun ReportsScreen(vm: ReportsViewModel = hiltViewModel()) {
    val board by vm.board.collectAsState()
    val err by vm.error.collectAsState()
    var sel by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = ListPadding) {
        item { Text("Reports", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                TextButton(onClick = { scope.launch { runCatching { shareFile(ctx, vm.engineExport(ctx, "pdf"), "application/pdf") }.onFailure { msg = it.message } } }) { Text("Engine PDF") }
                TextButton(onClick = { scope.launch { runCatching { shareFile(ctx, vm.engineExport(ctx, "csv"), "text/csv") }.onFailure { msg = it.message } } }) { Text("Engine CSV") }
                TextButton(onClick = { scope.launch { runCatching { shareFile(ctx, vm.journalPdf(ctx), "application/pdf") }.onFailure { msg = it.message } } }) { Text("Journal PDF") }
                TextButton(onClick = { scope.launch { runCatching { shareText(ctx, "tradelens_journal.csv", vm.journalCsv()) }.onFailure { msg = it.message } } }) { Text("Journal CSV") }
            }
        }
        item { ErrorText(err); ErrorText(msg) }
        item {
            Row(Modifier.fillMaxWidth().background(Surface2).padding(8.dp)) {
                listOf("Strategy", "CAGR", "Sharpe", "Calmar", "MaxDD", "Alpha").forEach { Text(it, fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f)) }
            }
        }
        items(board.sortedByDescending { it.sharpe }, key = { it.name }) { p ->
            Row(Modifier.fillMaxWidth().clickable { sel = if (sel == p.name) null else p.name }.padding(8.dp)) {
                Text(p.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("%.1f%%".format(p.cagr), fontSize = 11.sp, color = pnlColor(p.cagr), modifier = Modifier.weight(1f))
                Text("%.2f".format(p.sharpe), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("%.2f".format(p.calmar), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("%.1f%%".format(p.maxDd), fontSize = 11.sp, color = if (p.maxDd > 15) Bear else Muted, modifier = Modifier.weight(1f))
                Text("%.1f%%".format(p.alpha), fontSize = 11.sp, color = pnlColor(p.alpha), modifier = Modifier.weight(1f))
            }
            if (sel == p.name) Column {
                SectionCard("${p.name} vs ${p.benchmark}") {
                    LineChart(listOf(p.equity.map { it.v } to Accent, p.benchmarkEquity.map { it.v } to Muted))
                    Text("Drawdown", fontWeight = FontWeight.SemiBold); LineChart(listOf(p.drawdown.map { it.v } to Bear), fillBelowZero = true)
                    KV("Sortino", "%.2f".format(p.sortino)); KV("Beta / IR", "%.2f / %.2f".format(p.beta, p.ir)); KV("Win rate / PF", "%.1f%% / %.2f".format(p.winRate, p.profitFactor))
                    KV("Expectancy", fmtMoney(p.expectancy)); KV("Turnover (ann.)", "%.1fx".format(p.turnover)); KV("Trades", "${p.trades}")
                    Text("Monthly returns", fontWeight = FontWeight.SemiBold)
                    p.monthly.entries.groupBy { it.key.take(4) }.toSortedMap().forEach { (y, ms) ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Text("$y ", fontSize = 10.sp, modifier = Modifier.width(36.dp))
                            ms.sortedBy { it.key }.forEach { Text("%+.1f".format(it.value), fontSize = 10.sp, color = pnlColor(it.value), modifier = Modifier.width(38.dp)) }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
        item { Disclaimer() }
    }
}
