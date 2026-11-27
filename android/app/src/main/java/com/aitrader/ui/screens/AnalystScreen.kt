package com.aitrader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.model.Analysis
import com.aitrader.data.repo.Loaded
import com.aitrader.data.repo.TradingRepository
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalystViewModel @Inject constructor(private val repo: TradingRepository) : ViewModel() {
    val result = MutableStateFlow<Loaded<Analysis>?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    fun analyse(symbol: String) = viewModelScope.launch {
        loading.value = true; error.value = null
        runCatching { repo.analyst(symbol.trim().uppercase()) }.onSuccess { result.value = it }.onFailure { error.value = it.message }
        loading.value = false
    }
}

@Composable
fun AnalystScreen(vm: AnalystViewModel = hiltViewModel()) {
    val result by vm.result.collectAsStateCompat()
    val loading by vm.loading.collectAsStateCompat()
    val error by vm.error.collectAsStateCompat()
    var sym by remember { mutableStateOf("RELIANCE") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Advisory only. The analyst produces a sentiment feature for the meta-model; it never places orders.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(sym, { sym = it }, label = { Text("NSE symbol") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.analyse(sym) }))
        Button(onClick = { vm.analyse(sym) }, enabled = sym.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "Analysing…" else "Summarise news & results")
        }
        error?.let { Text(it, color = Loss) }
        result?.let { l ->
            if (l.fromCache) StaleBanner(l)
            val a = l.data
            SectionCard(a.symbol) {
                Row {
                    Stat("Sentiment", "%+.2f".format(a.sentiment), Modifier.weight(1f), pnlColor(a.sentiment))
                    Stat("Generated", Fmt.dateTime(a.generatedAt), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text(a.summary)
                Spacer(Modifier.height(10.dp))
                a.keyPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                Text("Sources: ${a.sources.joinToString()}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Light)
            }
        }
    }
}
