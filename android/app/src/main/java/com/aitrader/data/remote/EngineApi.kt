package com.aitrader.data.remote

import com.aitrader.data.model.*
import retrofit2.http.*

/** Contract with the cloud engine. Endpoints marked (signed) require X-Action-Signature,
 *  produced by a biometric-bound Android Keystore key (see security/ActionSigner). */
interface EngineApi {
    @POST("auth/login") suspend fun login(@Body body: LoginRequest): LoginResponse
    @GET("dashboard") suspend fun dashboard(): Dashboard
    @GET("positions") suspend fun positions(): List<Position>
    @GET("orders") suspend fun orders(): List<Order>
    @GET("signals") suspend fun signals(): List<Signal>
    @GET("strategies") suspend fun strategies(): List<StrategyStats>
    @GET("risk") suspend fun risk(): RiskConfig
    @GET("analyst/{symbol}") suspend fun analyst(@Path("symbol") symbol: String): Analysis
    @GET("audit") suspend fun audit(@Query("limit") limit: Int = 200): List<AuditEntry>
    @POST("backtests") suspend fun backtest(@Body body: BacktestRequest): Tearsheet
    @GET("brokers") suspend fun brokers(): List<BrokerInfo>
    @GET("watchlist") suspend fun watchlist(): List<WatchItem>
    @POST("watchlist") suspend fun addWatch(@Body c: ContractDto): ActionResult
    @DELETE("watchlist/{exchange}/{symbol}") suspend fun removeWatch(@Path("exchange") exchange: String,
                                                                     @Path("symbol") symbol: String): ActionResult
    @GET("instruments/search") suspend fun search(@Query("q") q: String, @Query("exchange") exchange: String): List<InstrumentHit>
    @POST("brokers/{id}/disconnect") suspend fun disconnectBroker(@Path("id") id: String): ActionResult

    // ---- money-touching (signed) ----
    @POST("positions/exit") suspend fun exit(@Body body: ExitRequest): ActionResult
    @PATCH("strategies/{id}") suspend fun updateStrategy(@Path("id") id: String, @Body body: StrategyUpdate): ActionResult
    @PUT("risk") suspend fun updateRisk(@Body body: RiskConfig): ActionResult
    @POST("mode") suspend fun setMode(@Body body: ModeRequest): ActionResult
    @POST("brokers/{id}/connect") suspend fun connectBroker(@Path("id") id: String): ConnectResult
    @POST("brokers/{id}/activate") suspend fun activateBroker(@Path("id") id: String): ActionResult
    // Kill switch is intentionally NOT biometric-gated: stopping must be instant.
    @POST("kill-switch") suspend fun killSwitch(@Body body: KillSwitchRequest): ActionResult
}
