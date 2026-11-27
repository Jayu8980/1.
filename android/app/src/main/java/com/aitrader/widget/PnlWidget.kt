package com.aitrader.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aitrader.MainActivity
import com.aitrader.data.model.Dashboard
import com.aitrader.di.AppEntryPoint
import com.aitrader.ui.components.Fmt
import dagger.hilt.android.EntryPointAccessors

class PnlWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(context, AppEntryPoint::class.java).repository()
        val loaded = runCatching { repo.dashboard() }.getOrNull()
        provideContent { Content(loaded?.data, loaded?.fromCache ?: false, if (loaded != null) "" else "OFFLINE ") }
    }

    @Composable
    private fun Content(d: Dashboard?, stale: Boolean, label: String) {
        val bg = ColorProvider(Color(0xFF151A21))
        val white = ColorProvider(Color.White)
        val grey = ColorProvider(Color(0xFF9CA3AF))
        Column(GlanceModifier.fillMaxSize().background(bg).cornerRadius(16.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label + (d?.mode?.name ?: ""), style = TextStyle(color = grey, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                if (stale) Text("cached", style = TextStyle(color = ColorProvider(Color(0xFFF59E0B)), fontSize = 10.sp))
            }
            if (d == null) {
                Text("No data", style = TextStyle(color = white, fontSize = 16.sp)); return@Column
            }
            val c = if (d.dayPnl >= 0) Color(0xFF22C55E) else Color(0xFFEF4444)
            Text(Fmt.signedMoney(d.dayPnl), style = TextStyle(color = ColorProvider(c), fontSize = 24.sp, fontWeight = FontWeight.Bold))
            Text("%+.2f%% today · ${d.openPositions} open".format(d.dayPnlPct), style = TextStyle(color = grey, fontSize = 12.sp))
            Spacer(GlanceModifier.defaultWeight())
            Box(GlanceModifier.fillMaxWidth().height(40.dp).cornerRadius(10.dp)
                .background(ColorProvider(if (d.killSwitch) Color(0xFF3F1D1D) else Color(0xFFDC2626)))
                .clickable(actionRunCallback<KillAction>()), contentAlignment = Alignment.Center) {
                Text(if (d.killSwitch) "KILL SWITCH ON" else "KILL SWITCH",
                    style = TextStyle(color = white, fontWeight = FontWeight.Bold, fontSize = 14.sp))
            }
        }
    }
}

/** One tap from the home screen. Stopping is always safe, so no biometric here by design. */
class KillAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = EntryPointAccessors.fromApplication(context, AppEntryPoint::class.java).repository()
        repo.killSwitch(true, "home-screen widget")
        PnlWidget().update(context, glanceId)
    }
}

class PnlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PnlWidget()
}
