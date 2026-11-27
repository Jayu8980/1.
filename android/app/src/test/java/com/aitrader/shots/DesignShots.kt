package com.aitrader.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aitrader.data.demo.DemoEngine
import com.aitrader.data.repo.FeedState
import com.aitrader.ui.components.TapeItem
import com.aitrader.ui.screens.*
import com.aitrader.ui.theme.*
import org.junit.Rule
import org.junit.Test

class DesignShots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.5)

    private val demo = DemoEngine(seed = 7, now = { 1_795_000_000_000 })
    private fun state(): HomeState {
        val d = demo.dashboard().copy(autoTrade = true, autoOpenTrades = 2)
        val q = demo.quotes()
        val q2 = demo.quotes()
        val tape = q2.mapIndexed { i, x ->
            TapeItem(x.symbol, "%,.2f".format(x.ltp), (x.ltp / q[i].ltp - 1) * 100 + if (i % 2 == 0) 0.8 else -0.6)
        }
        return HomeState(d, demo = false, venue = "current", positions = demo.positions(), tape = tape)
    }

    @Composable private fun Frame(a: Appearance, content: @Composable () -> Unit) =
        AITraderTheme(a) { Surface(color = MaterialTheme.colorScheme.background) { content() } }

    @Test fun home_pro_midnight() = paparazzi.snapshot { Frame(Appearance(LayoutStyle.PRO)) { HomeContent(state(), HomeActions()) } }
    @Test fun home_cards_midnight() = paparazzi.snapshot { Frame(Appearance(LayoutStyle.CARDS)) { HomeContent(state(), HomeActions()) } }
    @Test fun home_focus_amoled() = paparazzi.snapshot {
        Frame(Appearance(LayoutStyle.MINIMAL, ThemeStyle.AMOLED, Accent.EMERALD)) { HomeContent(state(), HomeActions()) }
    }
    @Test fun home_cards_light_violet() = paparazzi.snapshot {
        Frame(Appearance(LayoutStyle.CARDS, ThemeStyle.LIGHT, Accent.VIOLET)) { HomeContent(state(), HomeActions()) }
    }

    @Test fun markets_heatmap() = paparazzi.snapshot {
        val items = demo.watchlist(); demo.quotes(); val q = demo.quotes().associateBy { it.key }
        val q0 = q.mapValues { (_, v) -> v.copy(sessionOpen = v.ltp * (1 + (v.symbol.length % 5 - 2) * 0.004), prev = v.ltp * 0.999) }
        Frame(Appearance(LayoutStyle.CARDS)) { MarketsContent(items, q0, FeedState.Live(mapOf("angelone-ws" to emptyList(), "delta-ws" to emptyList()),
            emptyList(), "angelone"), "heat", {}, {}, {}) }
    }
    @Test fun markets_table_graphite() = paparazzi.snapshot {
        val items = demo.watchlist(); val q = demo.quotes().associateBy { it.key }
            .mapValues { (_, v) -> v.copy(sessionOpen = v.ltp * 0.995, prev = v.ltp * 1.0002, source = "angelone-ws") }
        Frame(Appearance(LayoutStyle.PRO, ThemeStyle.GRAPHITE, Accent.CYAN)) {
            MarketsContent(items, q, FeedState.Live(mapOf("angelone-ws" to emptyList()), emptyList(), "angelone"), "table", {}, {}, {})
        }
    }
    @Test fun home_cards_pending_approval() = paparazzi.snapshot {
        Frame(Appearance(LayoutStyle.CARDS)) { HomeContent(state().copy(pendingApprovals = 1), HomeActions()) }
    }
    @Test fun appearance_picker() = paparazzi.snapshot {
        Frame(Appearance(LayoutStyle.CARDS)) { AppearancePicker(Appearance(LayoutStyle.CARDS)) {} }
    }
}

class TabletShots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_C, maxPercentDifference = 0.5)
    @Test fun home_pro_tablet() = paparazzi.snapshot {
        val demo = DemoEngine(seed = 7, now = { 1_795_000_000_000 })
        AITraderTheme(Appearance(LayoutStyle.PRO)) {
            Surface(color = MaterialTheme.colorScheme.background) {
                HomeContent(HomeState(demo.dashboard(), false, "current", demo.positions(),
                    demo.quotes().map { TapeItem(it.symbol, "%,.2f".format(it.ltp), 0.42) }), HomeActions())
            }
        }
    }
}
