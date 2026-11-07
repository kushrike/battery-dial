package com.kushrike.batterydial

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Bundle
import android.provider.AlarmClock
import android.text.SpannableString
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the designer can change. Colors are ARGB, so each one carries its own transparency. */
@Serializable
data class DialStyle(
    val low: Int = 0xFFE8231A.toInt(),
    val mid: Int = 0xFFF5D90A.toInt(),
    val high: Int = 0xFFB4E61E.toInt(),
    val midPos: Float = 0.55f,
    val track: Int = 0xD90D1E33.toInt(),
    val text: Int = 0xFFFFFFFF.toInt(),
    val background: Int = 0x590D1E33,
    val border: Int = 0x99000000.toInt(),
    val thickness: Float = 0.16f, // of the radius
    val gap: Float = 0f, // between ring and face, of the radius
    val textScale: Float = 1f,
    val use24h: Boolean = false,
    val showDate: Boolean = true,
    val shortDate: Boolean = false,
    val showTimeLeft: Boolean = false,
    val showPercent: Boolean = true,
    val shadow: Boolean = false,
)

private fun c(argb: Long) = argb.toInt()

val PRESETS = listOf(
    "Sunset" to DialStyle(),
    "Neon" to DialStyle(
        low = c(0xFF2A00FF), mid = c(0xFFE800E8), high = c(0xFFF3E5F5), midPos = 0.5f, track = c(0xE6050A14),
        text = c(0xFFA7FFEB), background = c(0x99102030), border = c(0xCC0A1A2A), thickness = 0.24f,
    ),
    "Ocean" to DialStyle(
        low = c(0xFF0B3D91), mid = c(0xFF1565C0), high = c(0xFF2196F3), midPos = 0.5f, track = c(0x660B3D91),
        text = c(0xFFA7FFEB), background = c(0x660A2540), border = c(0x660B3D91), thickness = 0.08f,
    ),
    "Ruby" to DialStyle(
        low = c(0xFFC2185B), mid = c(0xFFE53935), high = c(0xFFEF5350), midPos = 0.5f, track = c(0x66B0BEC5),
        text = c(0xFFFFCDD2), background = c(0x993A1E4A), border = 0, thickness = 0.08f,
    ),
    "Mono" to DialStyle(
        low = c(0xFF000000), mid = c(0xFF000000), high = c(0xFF000000), track = c(0xFFFFFFFF),
        text = c(0xFF000000), background = 0, border = 0, thickness = 0.1f,
    ),
    "Aqua" to DialStyle(
        low = c(0xFF0D47A1), mid = c(0xFF1E88E5), high = c(0xFF42A5F5), midPos = 0.5f, track = c(0xFF0A0A0A),
        text = c(0xFF0A1A50), background = c(0xFF00CFF5), border = c(0xFF1565C0), thickness = 0.03f,
        showDate = false, showTimeLeft = true, shadow = true,
    ),
    "Lemon" to DialStyle(
        low = c(0xFF8B0000), mid = c(0xFFB71C1C), high = c(0xFFD50000), midPos = 0.5f, track = 0,
        text = c(0xFF000000), background = c(0xFFF5E50A), border = 0, thickness = 0.1f, gap = 0.06f,
        use24h = true, showDate = false,
    ),
    "Graphite" to DialStyle(
        low = c(0xFF555555), mid = c(0xFF777777), high = c(0xFF9E9E9E), midPos = 0.5f, track = c(0xFF0D2840),
        background = c(0x990D2840), border = c(0x66000000), thickness = 0.1f,
    ),
)

/** Settings and dial styles. A widget without its own style follows the default one. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun style(widgetId: Int? = null): DialStyle = widgetId?.let { read("style_$it") } ?: read("style") ?: DialStyle()

    fun setStyle(widgetId: Int?, s: DialStyle) =
        p.edit().putString(if (widgetId == null) "style" else "style_$widgetId", json.encodeToString(DialStyle.serializer(), s)).apply()

    fun forget(widgetId: Int) = p.edit().remove("style_$widgetId").apply()

    var fahrenheit: Boolean
        get() = p.getBoolean("fahrenheit", false)
        set(v) = p.edit().putBoolean("fahrenheit", v).apply()

    var historyDays: Int
        get() = p.getInt("historyDays", 3)
        set(v) = p.edit().putInt("historyDays", v).apply()

    var bigNumber: Boolean
        get() = p.getBoolean("bigNumber", false)
        set(v) = p.edit().putBoolean("bigNumber", v).apply()

    private fun read(key: String) =
        p.getString(key, null)?.let { runCatching { json.decodeFromString(DialStyle.serializer(), it) }.getOrNull() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

object Dial {
    /** Ring and face only. The text sits on top as views, so the clock ticks without waking the app. */
    fun ring(s: DialStyle, level: Int, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = px / 2f
        val border = px * 0.012f
        val outer = center - border
        val width = outer * s.thickness
        val r = outer - width / 2 // ring centerline
        val face = outer - width - outer * s.gap
        val box = RectF(center - r, center - r, center + r, center + r)
        val sweep = 360f * level.coerceIn(0, 100) / 100
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = s.background
        canvas.drawCircle(center, center, face, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.color = s.track
        canvas.drawArc(box, -90f - sweep, sweep - 360f, false, p)
        // Counter-clockwise from 12 o'clock. The gradient is pinned to the angle, so the tip's color reads as the level.
        p.shader = SweepGradient(center, center, intArrayOf(s.low, s.mid, s.high), floatArrayOf(0f, s.midPos.coerceIn(0.01f, 0.99f), 1f))
            .apply { setLocalMatrix(Matrix().apply { setRotate(-90f, center, center); postScale(-1f, 1f, center, center) }) }
        canvas.drawArc(box, -90f, -sweep, false, p)
        p.shader = null

        p.strokeWidth = border
        p.color = s.border
        canvas.drawCircle(center, center, outer, p)
        canvas.drawCircle(center, center, face, p)
        return bmp
    }

    /** The whole widget. In-app previews pass [interactive] = false so taps don't fire the widget's intents. */
    fun views(ctx: Context, s: DialStyle, info: BatteryInfo, left: Long?, sizeDp: Float, interactive: Boolean = true): RemoteViews {
        val px = (sizeDp * ctx.resources.displayMetrics.density).roundToInt().coerceIn(64, 640)
        val k = sizeDp * s.textScale
        val leftText = if (s.showTimeLeft) left?.let { timeLeftText(info, it) } else null

        val t = RemoteViews(ctx.packageName, if (s.shadow) R.layout.dial_text_shadow else R.layout.dial_text)
        // TextClock keeps spans through DateFormat, so AM/PM can be smaller than the digits.
        val clock: CharSequence = if (s.use24h) "HH:mm" else SpannableString("hh:mma").apply { setSpan(RelativeSizeSpan(0.4f), 5, 6, 0) }
        val date = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (s.shortDate) "dMMMyyyy" else "EEEEMMMd")
        for ((id, format) in listOf(R.id.clock to clock, R.id.date to date)) {
            // Both set, so the style's choice wins over the system 12/24h setting.
            t.setCharSequence(id, "setFormat12Hour", format)
            t.setCharSequence(id, "setFormat24Hour", format)
        }
        t.setTextViewText(R.id.time_left, leftText)
        t.setTextViewText(R.id.percent, "${info.level}%")
        for ((id, size) in listOf(R.id.time_left to 0.08f, R.id.clock to 0.2f, R.id.date to 0.072f, R.id.percent to 0.09f)) {
            t.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, k * size)
            t.setTextColor(id, s.text)
        }
        for ((id, size) in listOf(R.id.bolt_top to 0.09f, R.id.bolt to 0.075f)) {
            t.setViewLayoutWidth(id, k * size, TypedValue.COMPLEX_UNIT_DIP)
            t.setViewLayoutHeight(id, k * size, TypedValue.COMPLEX_UNIT_DIP)
            t.setInt(id, "setColorFilter", s.text)
        }
        t.setViewLayoutMargin(R.id.percent_row, RemoteViews.MARGIN_TOP, k * 0.05f, TypedValue.COMPLEX_UNIT_DIP)
        t.setViewVisibility(R.id.time_left, visible(leftText != null))
        t.setViewVisibility(R.id.bolt_top, visible(info.plugged && leftText == null))
        t.setViewVisibility(R.id.bolt, visible(info.plugged && leftText != null))
        t.setViewVisibility(R.id.date, visible(s.showDate))
        t.setViewVisibility(R.id.percent_row, visible(s.showPercent))

        return RemoteViews(ctx.packageName, R.layout.widget_dial).apply {
            setImageViewBitmap(R.id.dial, ring(s, info.level, px))
            // Launchers reapply updates onto the existing views, so clear the previous text first.
            removeAllViews(R.id.text_slot)
            addView(R.id.text_slot, t)
            if (interactive) setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(
                    ctx, 1, clockApp(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    private fun visible(b: Boolean) = if (b) View.VISIBLE else View.GONE

    /**
     * The default clock app's main screen. Android has no "open clock" intent, so find whichever app handles
     * alarms and launch it. Falls back to the alarms list, e.g. the chooser when no default clock is set.
     */
    private fun clockApp(ctx: Context): Intent {
        val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val pkg = ctx.packageManager.resolveActivity(alarms, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return pkg?.let(ctx.packageManager::getLaunchIntentForPackage) ?: alarms
    }
}

class DialWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        BatteryService.start(ctx)
        update(ctx, ids)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = update(ctx, intArrayOf(id))

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach(Prefs(ctx)::forget)

    companion object {
        fun update(ctx: Context, ids: IntArray? = null) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val targets = ids ?: mgr.getAppWidgetIds(ComponentName(ctx, DialWidget::class.java))
            if (targets.isEmpty()) return
            val info = BatteryInfo.read(ctx) ?: return
            val left = estimate(ctx, info, History.samples(ctx))
            val prefs = Prefs(ctx)
            for (id in targets) {
                val o = mgr.getAppWidgetOptions(id)
                // Portrait cell is min width × max height; the dial is the largest circle that fits.
                val size = min(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
                mgr.updateAppWidget(id, Dial.views(ctx, prefs.style(id), info, left, (if (size > 0) size else 110).toFloat()))
            }
        }
    }
}
