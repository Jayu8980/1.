package com.tradelens.app.data.broker

import com.tradelens.app.data.db.AppDatabase
import com.tradelens.app.data.db.AuditEntity
import com.tradelens.app.data.db.InstrumentEntity
import com.tradelens.app.data.market.BrokerProvider
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrokerRegistry @Inject constructor(
    kite: KiteBroker, upstox: UpstoxBroker, angel: AngelBroker, fyers: FyersBroker, dhan: DhanBroker,
    private val secure: SecureStore,
    private val settings: SettingsStore,
    private val db: AppDatabase,
) {
    val all: List<BrokerProvider> = listOf(upstox, angel, fyers, dhan, kite)

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Brokers with a valid session. */
    fun connected(): List<BrokerProvider> = all.filter { it.isLoggedIn() }

    /** Brokers whose daily token has expired → "Reconnect broker" banner. */
    val expired: Flow<List<BrokerProvider>> = secure.version.map { all.filter { b -> b.isExpired() } }

    /** Preferred live-data broker: user's primary if connected, else any connected one. */
    fun primary(): BrokerProvider? = byId(settings.value.primaryBroker)?.takeIf { it.isLoggedIn() } ?: connected().firstOrNull()

    suspend fun refreshInstruments(b: BrokerProvider): Int {
        db.instruments().clear(b.id)
        var n = 0
        b.downloadInstruments { batch ->
            db.instruments().upsertAll(batch.map { InstrumentEntity.from(b.id, it) }); n += batch.size
        }
        secure.putLong("instruments.${b.id}.at", System.currentTimeMillis())
        db.audit().log(AuditEntity(category = "AUTH", message = "Downloaded $n instruments from ${b.displayName}"))
        return n
    }

    fun instrumentsAge(b: BrokerProvider) = System.currentTimeMillis() - secure.getLong("instruments.${b.id}.at")
}
