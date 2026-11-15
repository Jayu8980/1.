package com.tubepilot.app.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tubepilot.app.ui.components.BackTopBar
import com.tubepilot.app.ui.components.SectionCard
import com.tubepilot.app.ui.components.formatDate
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ReportRepository
import com.tubepilot.core.model.VideoPerformance
import com.tubepilot.pipeline.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    reports: ReportRepository,
    private val memory: MemoryRepository,
    private val scheduler: PipelineScheduler,
) : ViewModel() {
    val report = reports.observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val performance = memory.observePerformance().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lessons = memory.observeLessons().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun generate() = scheduler.runNow(PipelineScheduler.PeriodicKind.ANALYTICS)
    fun setLessonActive(id: Long, active: Boolean) = viewModelScope.launch { memory.setLessonActive(id, active) }
}

@Composable
fun AnalyticsScreen(onBack: () -> Unit, vm: AnalyticsViewModel = hiltViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val perf by vm.performance.collectAsStateWithLifecycle()
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Analytics", onBack) { TextButton(onClick = vm::generate) { Text("Report now") } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val r = report
                if (r == null) {
                    SectionCard("Weekly report") { Text("No report yet. The Analytics agent writes one every week, or tap “Report now”.") }
                } else {
                    SectionCard("Week of ${formatDate(r.weekStart)}") {
                        Text(r.headline, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tile("Views", "%,d".format(r.totals.views), Modifier.weight(1f))
                            Tile("Watch h", "%.1f".format(r.totals.watchMinutes / 60), Modifier.weight(1f))
                            Tile("Subs", "+${r.totals.subscribersGained}", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tile("Avg CTR", r.totals.avgCtrPercent?.let { "%.1f%%".format(it) } ?: "n/a", Modifier.weight(1f))
                            Tile("Avg viewed", r.totals.avgViewPercentage?.let { "%.0f%%".format(it) } ?: "n/a", Modifier.weight(1f))
                            Tile("Published", "${r.totals.videosPublished}", Modifier.weight(1f))
                        }
                        Text(r.markdown.replace("#", "").trim(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (perf.isNotEmpty()) item { SectionCard("Views by video") { ViewsChart(perf.take(12)) } }
            items(perf.take(30), key = { it.videoId }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${p.format.label} · ${"%,d".format(p.views)} views · CTR ${p.ctrPercent?.let { "%.1f%%".format(it) } ?: "n/a"} · " +
                                "viewed ${p.avgViewPercentage?.let { "%.0f%%".format(it) } ?: "n/a"} · +${p.subscribersGained} subs",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item { Text("Lessons learned (${lessons.count { it.active }} active)", style = MaterialTheme.typography.titleMedium) }
            items(lessons, key = { it.id }) { l ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.text, style = MaterialTheme.typography.bodyMedium)
                        Text("${l.category.name.lowercase()} · confidence ${(l.confidence * 100).toInt()}% · ${l.evidence}", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(checked = l.active, onCheckedChange = { vm.setLessonActive(l.id, it) })
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ViewsChart(items: List<VideoPerformance>) {
    val color = MaterialTheme.colorScheme.primary
    val max = (items.maxOfOrNull { it.views } ?: 1L).coerceAtLeast(1L).toFloat()
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val gap = 6.dp.toPx()
        val barWidth = (size.width - gap * (items.size - 1)) / items.size.coerceAtLeast(1)
        items.reversed().forEachIndexed { i, p ->
            val h = (p.views / max) * size.height
            drawRoundRect(color, topLeft = Offset(i * (barWidth + gap), size.height - h), size = Size(barWidth, h), cornerRadius = CornerRadius(4.dp.toPx()))
        }
    }
    Text("Oldest → newest, most recent ${items.size} videos", style = MaterialTheme.typography.labelSmall)
}
