package com.aliiii.faceblur

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { App() }
                }
            }
        }
    }
}

suspend fun process(
    ctx: Context, uri: Uri, s: BlurSettings,
    status: (String) -> Unit, progress: (Float) -> Unit,
): Uri {
    status("آماده‌سازی مدل تشخیص چهره (بار اول دانلود می‌شود)…")
    Detector.ensureModel(ctx)
    status("پیدا کردن و ردیابی چهره‌ها…")
    val tracks = Detector.scan(ctx, uri, s.highAccuracy) { progress(it * 0.5f) }
    status("ساخت ویدیوی نهایی با کیفیت بالا…")
    val out = File(ctx.cacheDir, "blur_${System.currentTimeMillis()}.mp4")
    withContext(Dispatchers.Main) {
        Exporter.export(ctx, uri, tracks, s, out) { progress(0.5f + it * 0.5f) }
    }
    status("ذخیره در گالری…")
    return Exporter.saveToGallery(ctx, out)
}

@Composable
fun ChipRow(title: String, items: List<String>, sel: Int, enabled: Boolean, onSel: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, l ->
            FilterChip(selected = i == sel, onClick = { onSel(i) }, label = { Text(l) }, enabled = enabled)
        }
    }
}

@Composable
fun LabeledSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var mode by remember { mutableIntStateOf(0) }
    var shape by remember { mutableIntStateOf(0) }
    var strength by remember { mutableFloatStateOf(0.9f) }
    var pad by remember { mutableFloatStateOf(0.35f) }
    var feather by remember { mutableFloatStateOf(0.25f) }
    var high by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<Uri?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { uri = u; result = null; status = "ویدیو انتخاب شد" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("سانسور خودکار چهره", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { picker.launch("video/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (uri == null) "انتخاب ویدیو" else "تغییر ویدیو")
        }
        ChipRow("نوع سانسور", listOf("بلور نرم", "موزاییک", "شیشه مات (خشخشی)", "پارازیت", "مشکی"), mode, !busy) { mode = it }
        ChipRow("شکل ناحیه", listOf("بیضی", "دایره", "مربع", "گوشه‌گرد"), shape, !busy) { shape = it }
        LabeledSlider("شدت", strength, 0.3f..1.5f, !busy) { strength = it }
        LabeledSlider("حاشیه اضافه دور صورت", pad, 0f..0.8f, !busy) { pad = it }
        LabeledSlider("نرمی لبه", feather, 0f..0.6f, !busy) { feather = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = high, onCheckedChange = { high = it }, enabled = !busy)
            Spacer(Modifier.width(8.dp))
            Text("دقت بالا برای صورت‌های کوچک و دور (کندتر)")
        }

        if (!busy) {
            Button(
                onClick = {
                    val u = uri ?: return@Button
                    job = scope.launch {
                        busy = true; result = null; progress = 0f
                        try {
                            result = process(ctx, u, BlurSettings(mode, shape, strength, pad, feather, high), { status = it }, { progress = it })
                            status = "تمام شد ✅ در گالری (Movies/FaceBlur) ذخیره شد"
                        } catch (e: CancellationException) {
                            status = "لغو شد"; throw e
                        } catch (e: Exception) {
                            status = "خطا: ${e.message}"
                        } finally { busy = false }
                    }
                },
                enabled = uri != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("شروع پردازش خودکار") }
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("لغو") }
        }
        if (status.isNotEmpty()) Text(status)
        result?.let { r ->
            Button(onClick = {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(r, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("پخش ویدیو") }
        }
    }
}
