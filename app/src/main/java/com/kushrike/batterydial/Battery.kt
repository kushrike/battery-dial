package com.kushrike.batterydial

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.min
import kotlin.math.roundToLong

const val DAY = 86_400_000L

/** One reading of the sticky ACTION_BATTERY_CHANGED broadcast. */
data class BatteryInfo(
    val level: Int,
    val status: Int,
    val plugged: Boolean,
    val health: Int = BatteryManager.BATTERY_HEALTH_UNKNOWN,
    val tempC: Float = 0f,
    val volts: Float = 0f,
    val technology: String = "",
) {
    val charging get() = status == BatteryManager.BATTERY_STATUS_CHARGING
    val full get() = plugged && (status == BatteryManager.BATTERY_STATUS_FULL || level >= 100)

    val statusText
        get() = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

    val healthText
        get() = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

    companion object {
        fun from(i: Intent) = BatteryInfo(
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1),
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f,
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty().ifEmpty { "Unknown" },
        )

        /** The sticky broadcast. A null receiver is allowed even from inside a BroadcastReceiver. */
        fun read(ctx: Context): BatteryInfo? =
            ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::from)
    }
}

/** One history point, written whenever the level or the power source changes. */
data class Sample(val t: Long, val level: Int, val plugged: Boolean)

/** Level history in a flat file, one "millis,level,plugged" line per sample, cached in memory. */
object History {
    private var cache: MutableList<Sample>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "history.csv")

    private fun all(ctx: Context) = cache ?: file(ctx)
        .let { if (it.exists()) it.readLines().mapNotNull(::parse) else emptyList() }
        .toMutableList().also { cache = it }

    @Synchronized fun samples(ctx: Context): List<Sample> = all(ctx).toList()

    @Synchronized fun last(ctx: Context): Sample? = all(ctx).lastOrNull()

    @Synchronized fun add(ctx: Context, s: Sample) {
        all(ctx).add(s)
        file(ctx).appendText(line(s))
    }

    @Synchronized fun prune(ctx: Context, days: Int) {
        val keep = all(ctx).filter { it.t >= System.currentTimeMillis() - days * DAY }
        cache = keep.toMutableList()
        file(ctx).writeText(keep.joinToString("") { line(it) })
    }

    @Synchronized fun clear(ctx: Context) {
        cache = mutableListOf()
        file(ctx).delete()
    }

    private fun line(s: Sample) = "${s.t},${s.level},${if (s.plugged) 1 else 0}\n"

    private fun parse(line: String): Sample? {
        val f = line.split(',')
        return Sample(f.getOrNull(0)?.toLongOrNull() ?: return null, f.getOrNull(1)?.toIntOrNull() ?: return null, f.getOrNull(2) == "1")
    }
}

/**
 * Millis until empty (on battery) or full (plugged in); null until there's enough data.
 * The current session's rate wins once the level has moved twice: its first move is skipped because a
 * session can start anywhere inside a percent. Until then, the average of past sessions of the same kind.
 */
fun timeLeft(history: List<Sample>, now: Long, level: Int, plugged: Boolean): Long? {
    val target = if (plugged) 100 - level else level
    if (target <= 0) return 0
    val rate = sessionRate(history, plugged) ?: averageRate(history, plugged) ?: return null // percent per ms
    val last = history.lastOrNull()?.takeIf { it.level == level && it.plugged == plugged }
    val sinceChange = if (last == null) 0.0 else (now - last.t).coerceAtLeast(0).toDouble()
    // The level only reports whole percents: count down inside the current one, but never past it.
    return (target / rate - min(sinceChange, 1 / rate)).roundToLong().coerceAtLeast(0)
}

private fun moved(a: Sample, b: Sample, plugged: Boolean) = if (plugged) b.level - a.level else a.level - b.level

// ponytail: whole-session average; switch to a recent window if estimates lag behind changes in usage
private fun sessionRate(h: List<Sample>, plugged: Boolean): Double? {
    val session = h.takeLastWhile { it.plugged == plugged }
    if (session.size < 3) return null
    val d = moved(session[1], session.last(), plugged)
    val ms = session.last().t - session[1].t
    return if (d > 0 && ms > 0) d.toDouble() / ms else null
}

private fun averageRate(h: List<Sample>, plugged: Boolean): Double? {
    var d = 0
    var ms = 0L
    for ((a, b) in h.zipWithNext()) {
        if (a.plugged != plugged || b.plugged != plugged || moved(a, b, plugged) <= 0) continue
        d += moved(a, b, plugged)
        ms += b.t - a.t
    }
    return if (d >= 2 && ms > 0) d.toDouble() / ms else null
}

/** [timeLeft] for a live reading. On the charger the system's own estimate wins when it has one. */
fun estimate(ctx: Context, info: BatteryInfo, history: List<Sample>, now: Long = System.currentTimeMillis()): Long? = when {
    info.full -> 0
    info.plugged && !info.charging -> null
    info.plugged -> ctx.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining().takeIf { it > 0 }
        ?: timeLeft(history, now, info.level, true)
    else -> timeLeft(history, now, info.level, false)
}

fun timeLeftText(info: BatteryInfo, ms: Long?) = when {
    info.full -> "Full"
    info.plugged && !info.charging -> "—"
    ms == null -> "Calculating…"
    else -> formatDuration(ms)
}

fun formatDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
}
