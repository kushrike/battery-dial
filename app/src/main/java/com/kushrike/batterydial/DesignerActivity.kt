package com.kushrike.batterydial

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.ImageDecoder
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/** Edits the default style from the app, or one widget's style when the launcher opens it for that widget. */
class DesignerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            .takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        // Backing out of a new widget's setup keeps the widget (in the default look) instead of dropping it.
        if (widgetId != null) setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val prefs = Prefs(this)
        setContent {
            AppTheme {
                Designer(
                    initial = prefs.style(widgetId),
                    apply = { prefs.setStyle(widgetId, it); DialWidget.update(this); finish() },
                    cancel = ::finish,
                )
            }
        }
    }
}

@Composable
private fun Designer(initial: DialStyle, apply: (DialStyle) -> Unit, cancel: () -> Unit) {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(initial) }
    var level by remember { mutableFloatStateOf(80f) }
    var charging by remember { mutableStateOf(true) }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var presets by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) wallpaper = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                d.setTargetSampleSize(max(1, info.size.width / 1080))
            }.asImageBitmap()
        }.getOrNull() ?: wallpaper
    }
    val colors = listOf(
        Triple("Low Color", style.low) { c: Int -> style.copy(low = c) },
        Triple("Mid Color", style.mid) { c: Int -> style.copy(mid = c) },
        Triple("High Color", style.high) { c: Int -> style.copy(high = c) },
        Triple("Text Color", style.text) { c: Int -> style.copy(text = c) },
        Triple("Background", style.background) { c: Int -> style.copy(background = c) },
        Triple("Border", style.border) { c: Int -> style.copy(border = c) },
        Triple("Empty Ring", style.track) { c: Int -> style.copy(track = c) },
    )
    val preview = BatteryInfo(
        level.roundToInt(),
        if (charging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
        plugged = charging,
    )

    Scaffold(
        topBar = { TopBar("Widget Designer") },
        bottomBar = { BottomButtons("Apply" to { apply(style) }, "Style" to { presets = true }, "Cancel" to cancel) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Box(
                Modifier.fillMaxWidth().height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF6FB7E0), Color(0xFF0B4F8A)))),
                contentAlignment = Alignment.Center,
            ) {
                wallpaper?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                DialView(style, preview, if (charging) 131 * 60_000L else 262 * 60_000L, 220.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp).size(52.dp).clip(CircleShape)
                        .background(Color(0xDD303030)).clickable { pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_image), "Preview background") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SliderBox("Percentage preview", level, 0f..100f) { level = it }
                SliderBox("Thickness", style.thickness, 0.02f..0.5f) { style = style.copy(thickness = it) }
                SliderBox("Mid color position", style.midPos, 0.01f..0.99f) { style = style.copy(midPos = it) }
                SliderBox("Ring spacing", style.gap, 0f..0.3f) { style = style.copy(gap = it) }
                SliderBox("Text size", style.textScale, 0.6f..1.4f) { style = style.copy(textScale = it) }
                colors.chunked(3).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { i, (label, value) -> ColorChip(label, value, Modifier.weight(1f)) { editing = r * 3 + i } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                SwitchRow("Use 24-hour format", "Show the widget time in 24 hour format instead of AM/PM", style.use24h) {
                    style = style.copy(use24h = it)
                }
                SwitchRow("Show date", "Display the date under the time", style.showDate) { style = style.copy(showDate = it) }
                SwitchRow("Short date", "17 Nov 2020 instead of Thursday, Nov 17", style.shortDate) { style = style.copy(shortDate = it) }
                SwitchRow("Show time left", "Estimated time until empty, or until full while charging", style.showTimeLeft) {
                    style = style.copy(showTimeLeft = it)
                }
                SwitchRow("Show percentage", "Battery level under the clock", style.showPercent) { style = style.copy(showPercent = it) }
                SwitchRow("Text shadow", "Helps text stand out on bright backgrounds", style.shadow) { style = style.copy(shadow = it) }
                SwitchRow("Preview charging", "See the widget as it looks on the charger", charging) { charging = it }
            }
        }
    }
    editing?.let { i -> ColorPicker(colors[i].second, onDismiss = { editing = null }) { style = colors[i].third(it) } }
    if (presets) PresetPicker(onDismiss = { presets = false }) { style = it; presets = false }
}

@Composable
private fun SliderBox(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, fontSize = 18.sp)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(Panel).padding(horizontal = 16.dp)) {
            Slider(value, onChange, valueRange = range)
        }
    }
}

@Composable
private fun ColorChip(label: String, color: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Panel).border(1.dp, Line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Swatch(color, Modifier.size(32.dp))
    }
}

/** A color over a checkerboard, so transparency shows. */
@Composable
private fun Swatch(color: Int, modifier: Modifier) {
    Canvas(modifier.clip(CircleShape).border(1.dp, Color(0x55FFFFFF), CircleShape)) {
        val cell = 5.dp.toPx()
        for (i in 0..(size.width / cell).toInt()) for (j in 0..(size.height / cell).toInt()) {
            drawRect(if ((i + j) % 2 == 0) Color.LightGray else Color.White, Offset(i * cell, j * cell), Size(cell, cell))
        }
        drawRect(Color(color))
    }
}

@Composable
private fun ColorPicker(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    var a by remember { mutableFloatStateOf(AndroidColor.alpha(initial) / 255f) }
    val color = AndroidColor.HSVToColor((a * 255).roundToInt(), floatArrayOf(h, s, v))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ onPick(color); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(color, Modifier.size(44.dp))
                Spacer(Modifier.width(16.dp))
                Text("#%08X".format(color))
            }
        },
        text = {
            Column {
                Text("Hue"); Slider(h, { h = it }, valueRange = 0f..360f)
                Text("Saturation"); Slider(s, { s = it })
                Text("Brightness"); Slider(v, { v = it })
                Text("Opacity"); Slider(a, { a = it })
            }
        },
    )
}

@Composable
private fun PresetPicker(onDismiss: () -> Unit, onPick: (DialStyle) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text("Styles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((name, s) in PRESETS) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(s) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val thumb = remember(s) { Dial.ring(s, 75, 144).asImageBitmap() }
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF2F6FA8)), contentAlignment = Alignment.Center) {
                        Image(thumb, null, Modifier.size(46.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(name, fontSize = 18.sp)
                }
            }
        },
    )
}
