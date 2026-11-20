package com.tradelens.app.data.market

import android.content.Context
import com.tradelens.app.data.net.Http
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class UniverseMember(val symbol: String, val name: String, val sector: String)

/**
 * Index constituents (NIFTY 50 / 200 / 500) from NSE's published CSVs, cached on disk for a week.
 * Falls back to an embedded NIFTY 50 list when offline.
 */
@Singleton
class Universe @Inject constructor(@ApplicationContext private val ctx: Context) {

    suspend fun members(index: String = "nifty500"): List<UniverseMember> {
        val f = File(ctx.filesDir, "universe_$index.csv")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86400_000L) {
            runCatching {
                val body = Http.execute(
                    Request.Builder().url("https://archives.nseindia.com/content/indices/ind_${index}list.csv")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36").build(),
                    retries = 1,
                )
                if (body.lines().size > 10) f.writeText(body)
            }
        }
        val parsed = if (f.exists()) parse(f.readText()) else emptyList()
        return parsed.ifEmpty { NIFTY50 }
    }

    fun sectorOf(symbol: String, members: List<UniverseMember>) = members.firstOrNull { it.symbol == symbol }?.sector ?: "Other"

    companion object {
        fun parse(csv: String): List<UniverseMember> {
            val lines = csv.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList()
            val h = lines.first().split(",").map { it.trim() }
            val iName = h.indexOf("Company Name"); val iInd = h.indexOf("Industry"); val iSym = h.indexOf("Symbol")
            if (iSym < 0) return emptyList()
            return lines.drop(1).mapNotNull { l ->
                val c = l.split(",")
                c.getOrNull(iSym)?.trim()?.takeIf { it.isNotEmpty() }?.let { UniverseMember(it, c.getOrNull(iName)?.trim() ?: it, c.getOrNull(iInd)?.trim() ?: "Other") }
            }
        }

        val NIFTY50 = listOf(
            "ADANIENT" to "Metals & Mining", "ADANIPORTS" to "Services", "APOLLOHOSP" to "Healthcare", "ASIANPAINT" to "Consumer Durables",
            "AXISBANK" to "Financial Services", "BAJAJ-AUTO" to "Automobile", "BAJFINANCE" to "Financial Services", "BAJAJFINSV" to "Financial Services",
            "BEL" to "Capital Goods", "BHARTIARTL" to "Telecommunication", "CIPLA" to "Healthcare", "COALINDIA" to "Oil Gas & Fuels",
            "DRREDDY" to "Healthcare", "EICHERMOT" to "Automobile", "ETERNAL" to "Consumer Services", "GRASIM" to "Construction Materials",
            "HCLTECH" to "Information Technology", "HDFCBANK" to "Financial Services", "HDFCLIFE" to "Financial Services", "HEROMOTOCO" to "Automobile",
            "HINDALCO" to "Metals & Mining", "HINDUNILVR" to "FMCG", "ICICIBANK" to "Financial Services", "INDUSINDBK" to "Financial Services",
            "INFY" to "Information Technology", "ITC" to "FMCG", "JIOFIN" to "Financial Services", "JSWSTEEL" to "Metals & Mining",
            "KOTAKBANK" to "Financial Services", "LT" to "Construction", "M&M" to "Automobile", "MARUTI" to "Automobile", "NESTLEIND" to "FMCG",
            "NTPC" to "Power", "ONGC" to "Oil Gas & Fuels", "POWERGRID" to "Power", "RELIANCE" to "Oil Gas & Fuels", "SBILIFE" to "Financial Services",
            "SBIN" to "Financial Services", "SHRIRAMFIN" to "Financial Services", "SUNPHARMA" to "Healthcare", "TATACONSUM" to "FMCG",
            "TATAMOTORS" to "Automobile", "TATASTEEL" to "Metals & Mining", "TCS" to "Information Technology", "TECHM" to "Information Technology",
            "TITAN" to "Consumer Durables", "TRENT" to "Consumer Services", "ULTRACEMCO" to "Construction Materials", "WIPRO" to "Information Technology",
        ).map { UniverseMember(it.first, it.first, it.second) }

        val SECTOR_INDICES = listOf("NIFTY IT", "NIFTY BANK", "NIFTY AUTO", "NIFTY PHARMA", "NIFTY FMCG", "NIFTY METAL", "NIFTY REALTY", "NIFTY ENERGY", "NIFTY PSU BANK")
    }
}
