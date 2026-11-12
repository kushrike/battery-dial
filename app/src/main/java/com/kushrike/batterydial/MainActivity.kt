package com.kushrike.batterydial

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BatteryService.start(this)
        setContent { AppTheme { App(finish = ::finish) } }
    }
}

@Composable
private fun App(finish: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var fahrenheit by remember { mutableStateOf(prefs.fahrenheit) }
    val setFahrenheit = { f: Boolean -> fahrenheit = f; prefs.fahrenheit = f }
    var release by remember { mutableStateOf<Release?>(null) }
    // Offline or rate-limited: no banner this time.
    LaunchedEffect(Unit) { release = withContext(Dispatchers.IO) { runCatching { Updates.check(ctx) }.getOrNull() } }
    if (settings) SettingsScreen(prefs, fahrenheit, setFahrenheit) { settings = false }
    else Home(prefs, fahrenheit, setFahrenheit, release, openSettings = { settings = true }, finish)
}

@Composable
private fun Home(
    prefs: Prefs, fahrenheit: Boolean, setFahrenheit: (Boolean) -> Unit, release: Release?, openSettings: () -> Unit, finish: () -> Unit,
) {
    val ctx = LocalContext.current
    val info = rememberBattery() ?: return
    val tick = rememberMinuteTick()
    val now = remember(info, tick) { System.currentTimeMillis() }
    val history = remember(now) { History.samples(ctx) }
    val left = estimate(ctx, info, history, now)
    var style by remember { mutableStateOf(prefs.style()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { style = prefs.style() }
    var bigNumber by remember { mutableStateOf(prefs.bigNumber) }
    var help by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(stringResource(R.string.app_name)) {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Add widget to home screen") }, onClick = { menu = false; pinWidget(ctx) })
                    }
                }
            }
        },
        bottomBar = {
            BottomButtons(
                "Designer" to { ctx.startActivity(Intent(ctx, DesignerActivity::class.java)) },
                "Settings" to openSettings,
                "Close" to finish,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            if (release != null) UpdateBanner(release)
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                Stat("Status", info.statusText, Modifier.weight(1f))
                Box(
                    Modifier.padding(horizontal = 12.dp).size(48.dp).clip(CircleShape).background(Panel)
                        .border(1.dp, Line, CircleShape).clickable { help = true },
                    contentAlignment = Alignment.Center,
                ) { Text("?", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                Stat("Time Left", timeLeftText(info, left), Modifier.weight(1f))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                val dial = (maxWidth - 200.dp).coerceIn(120.dp, 280.dp)
                Box(Modifier.size(dial)) {
                    if (bigNumber) BigNumber(info.level, dial) else DialView(style, info, left, dial)
                    Box(Modifier.matchParentSize().clip(CircleShape).clickable { bigNumber = !bigNumber; prefs.bigNumber = bigNumber })
                }
                Stat("Temperature", temperature(info.tempC, fahrenheit), Modifier.align(Alignment.CenterStart).width(92.dp)) {
                    setFahrenheit(!fahrenheit)
                }
                Stat("Voltage", "%.2fV".format(info.volts), Modifier.align(Alignment.CenterEnd).width(92.dp))
            }
            Row {
                Stat("Health", info.healthText, Modifier.weight(1f))
                Spacer(Modifier.width(72.dp))
                Stat("Technology", info.technology, Modifier.weight(1f))
            }
            HistoryGraph(
                history.filter { it.t >= now - prefs.historyDays * DAY }, now, info,
                Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
            )
        }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        confirmButton = { TextButton({ help = false }) { Text("OK") } },
        title = { Text("About these numbers") },
        text = { Text(HELP) },
    )
}

private const val HELP = "Status, temperature, voltage, health and technology come straight from the battery.\n\n" +
    "Time Left is an estimate: on battery, how long until empty; on the charger, how long until full. " +
    "It needs the level to move a couple of percent first and gets better as history builds up.\n\n" +
    "Tap the dial to switch between the clock and a big percentage. Tap the temperature to switch °C / °F. " +
    "Tap the widget on your home screen to open the clock app. Double-tap it to refresh the battery reading.\n\n" +
    "Battery savers and task killers can stop the widget from updating. If that happens, allow unrestricted " +
    "battery use in Settings."

@Composable
private fun UpdateBanner(r: Release) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    Box(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(50))
            .clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Updates.install(ctx, r) } }
                        .onFailure { Toast.makeText(ctx, "Download failed. Try again later.", Toast.LENGTH_LONG).show() }
                    downloading = false
                }
            }
            .padding(vertical = 10.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (downloading) "Downloading ${r.version}…" else "Version ${r.version} is available. Tap to update.",
            color = MaterialTheme.colorScheme.primary, fontSize = 16.sp,
        )
    }
}

private fun temperature(c: Float, f: Boolean) = if (f) "%.0f° F".format(c * 9 / 5 + 32) else "%.1f° C".format(c)

private fun pinWidget(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, DialWidget::class.java), null, null)
    else Toast.makeText(ctx, "Add it from your home screen's widget list", Toast.LENGTH_LONG).show()
}

@Composable
private fun rememberBattery(): BatteryInfo? {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf(BatteryInfo.read(ctx)) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { info = BatteryInfo.from(i) }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }
    return info
}

/** Changes on every wall-clock minute. */
@Composable
private fun rememberMinuteTick(): Long {
    var tick by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            tick++
        }
    }
    return tick
}

/** The real widget views, inflated in-app, so previews match the home screen exactly. */
@Composable
fun DialView(style: DialStyle, info: BatteryInfo, left: Long?, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val views = remember(style, info.level, info.plugged, info.status, left?.div(60_000), size) {
        Dial.views(ctx, style, info, left, size.value, interactive = false)
    }
    AndroidView({ FrameLayout(it) }, modifier.size(size), update = { f ->
        f.removeAllViews()
        f.addView(views.apply(f.context, f))
    })
}

@Composable
private fun BigNumber(level: Int, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.minDimension * 0.05f
            drawArc(
                Color(0xFF333333), -90f, -3.6f * level, false, Offset(w / 2, w / 2),
                Size(this.size.width - w, this.size.height - w), style = Stroke(w),
            )
            drawCircle(Color(0xFF555555), this.size.minDimension / 2 - w * 2)
        }
        Text("$level", color = Color.White, fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() })
    }
}

@Composable
private fun HistoryGraph(samples: List<Sample>, now: Long, info: BatteryInfo, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Color(0xFFDDDDDD), fontSize = 18.sp)
    Column(modifier) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val w = size.width - 64.dp.toPx()
            val pad = 12.dp.toPx()
            val h = size.height - 2 * pad
            fun y(level: Int) = pad + h * (100 - level) / 100f
            for (l in listOf(100, 50, 0)) {
                drawLine(Color(0xFF444444), Offset(0f, y(l)), Offset(w, y(l)), 1.dp.toPx())
                val text = measurer.measure("$l%", label)
                drawText(text, topLeft = Offset(size.width - text.size.width, y(l) - text.size.height / 2))
            }
            if (samples.isEmpty()) return@Canvas
            val start = samples.first().t
            val end = maxOf(now, samples.last().t)
            val span = (end - start).coerceAtLeast(60_000).toFloat()
            fun x(t: Long) = w * (t - start) / span
            val all = samples + Sample(end, info.level, info.plugged)
            for ((a, b) in all.zipWithNext()) {
                if (a.plugged) drawRect(Color(0x2233CC66), Offset(x(a.t), pad), Size(x(b.t) - x(a.t), h))
            }
            val line = Path().apply {
                all.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.t), y(s.level)) else lineTo(x(s.t), y(s.level)) }
            }
            val fill = Path().apply { addPath(line); lineTo(w, y(0)); lineTo(0f, y(0)); close() }
            drawPath(fill, Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF1E1E1E)), startY = pad, endY = y(0)))
            drawPath(line, Color(0xFF9A9A9A), style = Stroke(2.dp.toPx()))
        }
        Text(if (samples.isEmpty()) "No history yet" else ago(now - samples.first().t), style = label)
    }
}

private fun ago(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 60 -> "$m min. ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(prefs: Prefs, fahrenheit: Boolean, setFahrenheit: (Boolean) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = back)
    var days by remember { mutableIntStateOf(prefs.historyDays) }
    var confirmClear by remember { mutableStateOf(false) }
    var unrestricted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    Scaffold(topBar = { TopBar("Settings", back) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            SwitchRow("Fahrenheit", "Show the temperature in °F. Tapping it on the main screen switches too.", fahrenheit, setFahrenheit)
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("History length", fontSize = 18.sp)
                Text("How far back the graph and estimates look", fontSize = 14.sp, color = Muted)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in listOf(1, 3, 7)) FilterChip(
                        days == d, { days = d; prefs.historyDays = d },
                        label = { Text(if (d == 1) "1 day" else "$d days") },
                    )
                }
            }
            ActionRow(
                "Unrestricted battery use",
                if (unrestricted) "Allowed. Widgets keep updating in the background."
                else "Recommended. Stops battery savers from pausing widget updates.",
            ) {
                ctx.startActivity(
                    if (unrestricted) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                )
            }
            ActionRow("Status notification", "Show or hide the ongoing battery notification") {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            ActionRow("Clear history", "Forget recorded battery levels. Estimates start over.") { confirmClear = true }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        confirmButton = {
            TextButton({ History.clear(ctx); DialWidget.update(ctx); confirmClear = false }) { Text("Clear") }
        },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        title = { Text("Clear history?") },
        text = { Text("The graph and time-left estimates start from scratch.") },
    )
}

// Shared look, after the original's dark pill style.

val Panel = Color(0xFF262626)
val Line = Color(0xFF3A3A3A)
val Muted = Color(0xFF9E9E9E)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Color(0xFF29C5F6),
        background = Color(0xFF1A1A1A),
        surface = Color(0xFF1A1A1A),
        surfaceContainer = Color(0xFF2A2A2A),
        surfaceContainerHigh = Color(0xFF2A2A2A),
    ),
    content = content,
)

@Composable
fun TopBar(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = Color(0xFF222222), shadowElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (back != null) IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            else Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 24.sp, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
fun BottomButtons(vararg buttons: Pair<String, () -> Unit>) {
    Surface(color = Color(0xFF2A2A2A)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((label, onClick) in buttons) Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0xFF1F1F1F))
                    .border(1.dp, Line, RoundedCornerShape(50)).clickable(onClick = onClick).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.wrapContentWidth(unbounded = true))
        Box(
            Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(Panel)
                .border(1.dp, Line, RoundedCornerShape(50))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 10.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text(value, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 18.sp)
            Text(summary, fontSize = 14.sp, color = Muted)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp)) {
        Text(title, fontSize = 18.sp)
        Text(summary, fontSize = 14.sp, color = Muted)
    }
}
