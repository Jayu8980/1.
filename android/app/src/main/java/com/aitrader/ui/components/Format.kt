package com.aitrader.ui.components

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val inr: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
private val inr2: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}

object Fmt {
    fun money(v: Double): String = inr.format(v)
    fun price(v: Double): String = inr2.format(v)
    fun signedMoney(v: Double): String = (if (v > 0) "+" else if (v < 0) "−" else "") + inr.format(abs(v))
    fun pct(v: Double, digits: Int = 2): String = "%+.${digits}f%%".format(v)
    fun plainPct(v: Double, digits: Int = 1): String = "%.${digits}f%%".format(v)
    fun num(v: Double, digits: Int = 2): String = "%.${digits}f".format(v)
    /** Compact Indian units: 1.2 L, 3.4 Cr. */
    fun compact(v: Double): String {
        val a = abs(v); val s = if (v < 0) "−" else ""
        return when {
            a >= 1e7 -> "$s₹%.2f Cr".format(a / 1e7)
            a >= 1e5 -> "$s₹%.2f L".format(a / 1e5)
            else -> s + inr.format(a)
        }
    }
    fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
    fun dateTime(ms: Long): String = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ms))
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ms) / 1000
        return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
    }
}
