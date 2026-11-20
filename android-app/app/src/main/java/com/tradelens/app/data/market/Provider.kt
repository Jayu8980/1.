package com.tradelens.app.data.market

import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.DataSource
import com.tradelens.app.domain.Funds
import com.tradelens.app.domain.Holding
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketDepth
import com.tradelens.app.domain.OptionChain
import com.tradelens.app.domain.Order
import com.tradelens.app.domain.OrderRequest
import com.tradelens.app.domain.Position
import com.tradelens.app.domain.Quote
import com.tradelens.app.domain.Timeframe
import kotlinx.coroutines.flow.Flow

/** Read-only market data. */
interface DataProvider {
    val source: DataSource
    val realtime: Boolean
    suspend fun getQuote(i: Instrument): Quote
    suspend fun getQuotes(list: List<Instrument>): List<Quote> = list.map { getQuote(it) }
    suspend fun getCandles(i: Instrument, tf: Timeframe, from: Long, to: Long): List<Candle>
    fun streamTicks(list: List<Instrument>): Flow<Quote>
    suspend fun getDepth(i: Instrument): MarketDepth? = null
    suspend fun getOptionChain(underlying: Instrument, expiry: Long?): OptionChain? = null
}

/** Login methods supported by brokers. */
sealed interface LoginMethod {
    /** Open [url] in a Custom Tab; broker redirects to tradelens://auth/{id}?... (directly or via the engine's bounce endpoint). */
    data class OAuth(val url: String) : LoginMethod
    /** Client code + PIN + TOTP (Angel One). PIN is used once and never stored. */
    data object Credentials : LoginMethod
    /** Paste an access token generated on the broker's developer portal (Dhan). */
    data object AccessToken : LoginMethod
}

data class BrokerField(val key: String, val label: String, val secret: Boolean = false, val hint: String = "")

interface BrokerProvider : DataProvider {
    val id: String
    val displayName: String
    /** API credentials the user must enter (api key / secret / client id...). */
    val configFields: List<BrokerField>
    fun isConfigured(): Boolean
    fun isLoggedIn(): Boolean
    /** True when a token exists but is past the broker's daily expiry. */
    fun isExpired(): Boolean
    fun loginMethod(redirectUri: String): LoginMethod
    /** Completes login from the OAuth redirect query parameters or the credential form. */
    suspend fun login(params: Map<String, String>)
    fun logout()
    suspend fun getHoldings(): List<Holding>
    suspend fun getPositions(): List<Position>
    suspend fun getOrders(): List<Order>
    suspend fun getFunds(): Funds
    /** Downloads the broker's instrument master and returns unified instruments (streamed, filtered). */
    suspend fun downloadInstruments(sink: suspend (List<Instrument>) -> Unit)
    /** Direct order placement is disabled on-device (SEBI static-IP rule); orders go through the relay. */
    suspend fun placeOrder(req: OrderRequest): Order = throw UnsupportedOperationException("Use the relay server for Indian broker orders")
    suspend fun cancelOrder(orderId: String): Unit = throw UnsupportedOperationException("Use the relay server for Indian broker orders")
    /** Package + deep link of the broker's own app for the "Open in broker app" button. */
    fun brokerAppLink(i: Instrument): BrokerAppLink
}

data class BrokerAppLink(val packageName: String, val webUrl: String)
