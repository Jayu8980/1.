package com.tradelens.app.domain

import kotlinx.serialization.Serializable

/**
 * Tiny no-code rule language shared by the screener, the backtester and indicator alerts.
 *
 * Grammar:  expr := cond (("AND" | "OR") cond)*
 *           cond := operand op operand        op := < <= > >= CROSSES_ABOVE CROSSES_BELOW
 *           operand := number | CLOSE | OPEN | HIGH | LOW | VOLUME | NAME(p1[,p2...])
 * Supported names: SMA EMA RSI ATR ADX VWAP MACD MACD_SIGNAL MACD_HIST STOCH_K STOCH_D BB_UPPER BB_LOWER BB_MID
 *                  SUPERTREND OBV DONCHIAN_HIGH DONCHIAN_LOW AVG_VOLUME CHANGE_PCT
 * Example:  "RSI(14) < 30 AND CLOSE > EMA(200)"
 */
@Serializable
data class Operand(val name: String, val params: List<Double> = emptyList(), val constant: Double? = null) {
    override fun toString() = constant?.let { trimNum(it) } ?: if (params.isEmpty()) name else "$name(${params.joinToString(",") { trimNum(it) }})"
}

@Serializable
data class Condition(val left: Operand, val op: String, val right: Operand) {
    override fun toString() = "$left $op $right"
}

@Serializable
data class RuleSet(val conditions: List<Condition>, val joins: List<String>) {
    override fun toString() = buildString {
        conditions.forEachIndexed { i, c -> if (i > 0) append(" ${joins[i - 1]} "); append(c) }
    }
}

private fun trimNum(d: Double) = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e12) d.toLong().toString() else d.toString()

object Rules {
    private val OPS = listOf("CROSSES_ABOVE", "CROSSES_BELOW", "<=", ">=", "<", ">")

    fun parse(text: String): RuleSet {
        val conds = mutableListOf<Condition>(); val joins = mutableListOf<String>()
        val parts = text.split(Regex("\\s+(AND|OR)\\s+", RegexOption.IGNORE_CASE))
        Regex("\\s+(AND|OR)\\s+", RegexOption.IGNORE_CASE).findAll(text).forEach { joins += it.groupValues[1].uppercase() }
        require(text.isNotBlank()) { "Empty rule" }
        for (p in parts) {
            val op = OPS.firstOrNull { p.uppercase().contains(it) } ?: throw IllegalArgumentException("No operator in '$p'")
            val idx = p.uppercase().indexOf(op)
            conds += Condition(operand(p.substring(0, idx)), op, operand(p.substring(idx + op.length)))
        }
        return RuleSet(conds, joins)
    }

    private fun operand(s: String): Operand {
        val t = s.trim().uppercase()
        t.toDoubleOrNull()?.let { return Operand("CONST", constant = it) }
        val m = Regex("^([A-Z_]+)\\s*(?:\\(([^)]*)\\))?$").matchEntire(t) ?: throw IllegalArgumentException("Bad operand '$s'")
        val params = m.groupValues[2].takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toDouble() } ?: emptyList()
        val name = m.groupValues[1]
        require(name in KNOWN) { "Unknown indicator '$name'" }
        return Operand(name, params)
    }

    val KNOWN = setOf("CONST", "CLOSE", "OPEN", "HIGH", "LOW", "VOLUME", "SMA", "EMA", "RSI", "ATR", "ADX", "VWAP", "MACD", "MACD_SIGNAL",
        "MACD_HIST", "STOCH_K", "STOCH_D", "BB_UPPER", "BB_LOWER", "BB_MID", "SUPERTREND", "OBV", "DONCHIAN_HIGH", "DONCHIAN_LOW", "AVG_VOLUME", "CHANGE_PCT")

    /** Pre-computes every operand series once so rules can be evaluated bar-by-bar cheaply. */
    class Evaluator(private val c: List<Candle>) {
        private val cache = HashMap<String, DoubleArray>()
        private val cl = Indicators.closes(c)

        fun series(o: Operand): DoubleArray = cache.getOrPut(o.toString()) { compute(o) }

        private fun p(o: Operand, i: Int, d: Double) = o.params.getOrNull(i) ?: d

        private fun compute(o: Operand): DoubleArray = when (o.name) {
            "CONST" -> DoubleArray(c.size) { o.constant!! }
            "CLOSE" -> cl
            "OPEN" -> DoubleArray(c.size) { c[it].open }
            "HIGH" -> DoubleArray(c.size) { c[it].high }
            "LOW" -> DoubleArray(c.size) { c[it].low }
            "VOLUME" -> DoubleArray(c.size) { c[it].volume }
            "SMA" -> Indicators.sma(cl, p(o, 0, 20.0).toInt())
            "EMA" -> Indicators.ema(cl, p(o, 0, 20.0).toInt())
            "RSI" -> Indicators.rsi(cl, p(o, 0, 14.0).toInt())
            "ATR" -> Indicators.atr(c, p(o, 0, 14.0).toInt())
            "ADX" -> Indicators.adx(c, p(o, 0, 14.0).toInt()).adx
            "VWAP" -> Indicators.vwap(c)
            "MACD" -> Indicators.macd(cl, p(o, 0, 12.0).toInt(), p(o, 1, 26.0).toInt(), p(o, 2, 9.0).toInt()).macd
            "MACD_SIGNAL" -> Indicators.macd(cl, p(o, 0, 12.0).toInt(), p(o, 1, 26.0).toInt(), p(o, 2, 9.0).toInt()).signal
            "MACD_HIST" -> Indicators.macd(cl, p(o, 0, 12.0).toInt(), p(o, 1, 26.0).toInt(), p(o, 2, 9.0).toInt()).hist
            "STOCH_K" -> Indicators.stochastic(c, p(o, 0, 14.0).toInt()).k
            "STOCH_D" -> Indicators.stochastic(c, p(o, 0, 14.0).toInt()).d
            "BB_UPPER" -> Indicators.bollinger(cl, p(o, 0, 20.0).toInt(), p(o, 1, 2.0)).upper
            "BB_LOWER" -> Indicators.bollinger(cl, p(o, 0, 20.0).toInt(), p(o, 1, 2.0)).lower
            "BB_MID" -> Indicators.bollinger(cl, p(o, 0, 20.0).toInt(), p(o, 1, 2.0)).middle
            "SUPERTREND" -> Indicators.supertrend(c, p(o, 0, 10.0).toInt(), p(o, 1, 3.0)).line
            "OBV" -> Indicators.obv(c)
            "DONCHIAN_HIGH" -> Indicators.donchian(c, p(o, 0, 20.0).toInt()).first.let { a -> DoubleArray(a.size) { if (it == 0) Double.NaN else a[it - 1] } }
            "DONCHIAN_LOW" -> Indicators.donchian(c, p(o, 0, 20.0).toInt()).second.let { a -> DoubleArray(a.size) { if (it == 0) Double.NaN else a[it - 1] } }
            "AVG_VOLUME" -> Indicators.sma(DoubleArray(c.size) { c[it].volume }, p(o, 0, 20.0).toInt())
            "CHANGE_PCT" -> DoubleArray(c.size) { if (it == 0) Double.NaN else (cl[it] / cl[it - 1] - 1) * 100 }
            else -> error("Unknown operand ${o.name}")
        }

        fun cond(cd: Condition, i: Int): Boolean {
            val l = series(cd.left); val r = series(cd.right)
            val a = l[i]; val b = r[i]
            if (a.isNaN() || b.isNaN()) return false
            return when (cd.op) {
                "<" -> a < b; "<=" -> a <= b; ">" -> a > b; ">=" -> a >= b
                "CROSSES_ABOVE" -> i > 0 && !l[i - 1].isNaN() && !r[i - 1].isNaN() && l[i - 1] <= r[i - 1] && a > b
                "CROSSES_BELOW" -> i > 0 && !l[i - 1].isNaN() && !r[i - 1].isNaN() && l[i - 1] >= r[i - 1] && a < b
                else -> false
            }
        }

        /** Left-to-right evaluation; AND binds tighter than OR. */
        fun eval(rs: RuleSet, i: Int): Boolean {
            if (rs.conditions.isEmpty()) return false
            var orAcc = false; var andAcc = cond(rs.conditions[0], i)
            for (k in 1 until rs.conditions.size) {
                val v = cond(rs.conditions[k], i)
                if (rs.joins[k - 1] == "AND") andAcc = andAcc && v else { orAcc = orAcc || andAcc; andAcc = v }
            }
            return orAcc || andAcc
        }
    }
}
