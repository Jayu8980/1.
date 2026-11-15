package com.tubepilot.youtube.quota

import android.content.Context
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.KeyValueStore
import com.tubepilot.core.domain.QuotaRepository
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.error.QuotaExhaustedException
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.core.util.TubeJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class QuotaBucket(val id: String, val label: String, val dailyLimit: Int)

@Serializable
data class MethodCost(val bucket: String, val cost: Int)

/**
 * Quota cost table. The bundled default records the costs published by Google on [verifiedOn];
 * because Google changes these, the table can be edited in Settings or refreshed from a URL that
 * serves the same JSON shape. Methods not in the table are charged conservatively.
 */
@Serializable
data class QuotaCostTable(
    val version: Int,
    val verifiedOn: String,
    val source: String,
    val note: String = "",
    val buckets: List<QuotaBucket>,
    val methods: Map<String, MethodCost>,
) {
    fun costOf(method: String): MethodCost = methods[method] ?: MethodCost(DEFAULT_BUCKET, UNKNOWN_METHOD_COST)
    fun bucket(id: String): QuotaBucket? = buckets.firstOrNull { it.id == id }

    fun validate(): QuotaCostTable {
        require(buckets.isNotEmpty()) { "Quota table has no buckets" }
        require(buckets.all { it.dailyLimit > 0 }) { "Bucket limits must be positive" }
        require(methods.values.all { m -> m.cost >= 0 && buckets.any { it.id == m.bucket } }) { "Every method must reference a known bucket" }
        return this
    }

    companion object {
        const val DEFAULT_BUCKET = "default"
        const val UNKNOWN_METHOD_COST = 50
    }
}

data class BucketStatus(val bucket: QuotaBucket, val used: Int, val resetAtMs: Long) {
    val remaining: Int get() = (bucket.dailyLimit - used).coerceAtLeast(0)
    val fractionUsed: Float get() = (used.toFloat() / bucket.dailyLimit).coerceIn(0f, 1f)
}

/** Tracks YouTube Data API quota locally per Pacific-time day and gates calls that would overrun it. */
@Singleton
class QuotaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: QuotaRepository,
    private val kv: KeyValueStore,
    private val audit: AuditLog,
    private val time: TimeSource,
    private val http: OkHttpClient,
) {
    private val lock = Mutex()
    @Volatile private var table: QuotaCostTable? = null

    /** Units of the standard bucket kept free for comment moderation and analytics. */
    val reserveUnits = 500

    suspend fun table(): QuotaCostTable {
        table?.let { return it }
        val stored = kv.get(KEY_TABLE)?.let { json -> runCatching { TubeJson.decodeFromString(QuotaCostTable.serializer(), json).validate() }.getOrNull() }
        val loaded = stored ?: bundled()
        table = loaded
        return loaded
    }

    private suspend fun bundled(): QuotaCostTable = withContext(Dispatchers.IO) {
        context.assets.open("quota_costs.json").bufferedReader().use { TubeJson.decodeFromString(QuotaCostTable.serializer(), it.readText()) }
    }

    suspend fun saveTable(newTable: QuotaCostTable) {
        val valid = newTable.validate()
        kv.put(KEY_TABLE, TubeJson.encodeToString(QuotaCostTable.serializer(), valid))
        table = valid
        audit.record("QuotaManager", "quota.table_updated", "Quota table v${valid.version} verified ${valid.verifiedOn}", level = AuditLevel.INFO)
    }

    suspend fun resetTableToBundled() {
        val b = bundled()
        kv.put(KEY_TABLE, TubeJson.encodeToString(QuotaCostTable.serializer(), b))
        table = b
    }

    /** Downloads a cost table from [url] (same JSON shape as the bundled asset). */
    suspend fun refreshFromUrl(url: String): QuotaCostTable = withContext(Dispatchers.IO) {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw ProviderException("Quota table", "HTTP ${it.code}", it.code, retryable = it.code >= 500)
            val parsed = TubeJson.decodeFromString(QuotaCostTable.serializer(), it.body!!.string())
            saveTable(parsed)
            parsed
        }
    }

    suspend fun status(): List<BucketStatus> {
        val now = time.now()
        val used = repo.usedOn(TimeUtil.quotaDayKey(now))
        val reset = TimeUtil.nextQuotaResetMs(now)
        return table().buckets.map { BucketStatus(it, used[it.id] ?: 0, reset) }
    }

    /** Live quota status; re-subscribes when the Pacific-time day rolls over. */
    fun observeStatus(): Flow<List<BucketStatus>> = dayKeys().flatMapLatest { day ->
        combine(repo.observeDay(day), flow { emit(table()) }) { used, t ->
            val reset = TimeUtil.nextQuotaResetMs(time.now())
            t.buckets.map { BucketStatus(it, used[it.id] ?: 0, reset) }
        }
    }

    private fun dayKeys(): Flow<String> = flow {
        while (true) { // delay() is cancellable, so collection stops with the collector.
            emit(TimeUtil.quotaDayKey(time.now()))
            delay((TimeUtil.nextQuotaResetMs(time.now()) - time.now()).coerceAtLeast(1_000))
        }
    }

    /** True if every method in [plan] (method -> number of calls) fits in today's remaining quota. */
    suspend fun canAfford(plan: Map<String, Int>, keepReserve: Boolean = true): Boolean {
        val t = table()
        val remaining = status().associate { it.bucket.id to it.remaining }.toMutableMap()
        plan.forEach { (method, calls) ->
            val cost = t.costOf(method)
            remaining[cost.bucket] = (remaining[cost.bucket] ?: 0) - cost.cost * calls
        }
        val reserve = if (keepReserve) reserveUnits else 0
        return remaining.all { (bucket, left) -> left >= if (bucket == QuotaCostTable.DEFAULT_BUCKET) reserve else 0 }
    }

    fun nextResetMs(): Long = TimeUtil.nextQuotaResetMs(time.now())

    /**
     * Runs [block] for API [method] if the quota allows it. Units are recorded before the call
     * because Google charges for failed requests too.
     */
    suspend fun <T> track(method: String, block: suspend () -> T): T {
        val t = table()
        val cost = t.costOf(method)
        lock.withLock {
            val now = time.now()
            val day = TimeUtil.quotaDayKey(now)
            val used = repo.usedOn(day)[cost.bucket] ?: 0
            val limit = t.bucket(cost.bucket)?.dailyLimit ?: Int.MAX_VALUE
            if (used + cost.cost > limit) {
                audit.record("QuotaManager", "quota.exhausted", "$method needs ${cost.cost} units of '${cost.bucket}', $used/$limit used", level = AuditLevel.WARNING)
                throw QuotaExhaustedException(cost.bucket, TimeUtil.nextQuotaResetMs(now))
            }
            repo.record(day, method, cost.bucket, cost.cost)
        }
        return block()
    }

    private companion object {
        const val KEY_TABLE = "youtube.quota_table"
    }
}
