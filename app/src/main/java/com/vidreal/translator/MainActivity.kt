package com.vidreal.translator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CapeoApp() }
    }
}

private fun formatTime(ms: Int): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun CapeoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var previewIsExported by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    var status by remember { mutableStateOf("اختر فيديو للبدء") }
    var stage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var subtitles by remember { mutableStateOf<List<Subtitle>>(emptyList()) }
    var detectedLang by remember { mutableStateOf("") }
    var isArabicVideo by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(CaptionStyle()) }
    var exportResult by remember { mutableStateOf<VideoExporter.ExportResult?>(null) }
    var activeTool by remember { mutableStateOf("video") }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val currentSubtitle = if (previewIsExported) null else
        subtitles.firstOrNull { positionMs in it.startMs until it.endMs }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = videoView?.currentPosition ?: positionMs
            delay(250)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickedUri = uri
        previewUri = uri
        previewIsExported = false
        subtitles = emptyList()
        exportResult = null
        positionMs = 0
        durationMs = 0
        isPlaying = false
        selectedIndex = null
        activeTool = "transcribe"
        status = if (uri != null) "تم اختيار الفيديو. انتقل إلى التفريغ" else "لم يتم اختيار فيديو"
    }

    fun shareExported() {
        val result = exportResult ?: return
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", result.videoFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "شارك الفيديو"))
        } catch (e: Exception) {
            Toast.makeText(context, "تعذرت المشاركة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun startTranscribe() {
        val uri = pickedUri ?: return
        isWorking = true
        progress = null
        stage = "استخراج الصوت"
        status = "جارٍ استخراج الصوت من الفيديو..."
        scope.launch {
            try {
                val copied = withContext(Dispatchers.IO) {
                    val f = File(context.cacheDir, "input_video.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    f
                }
                val wav = withContext(Dispatchers.IO) {
                    AudioExtractor.extractToWav(
                        context, uri, File(context.cacheDir, "audio_16k.wav")
                    )
                }
                val lang = if (isArabicVideo) "ar" else "en"
                stage = "تحميل نموذج التفريغ"
                status = if (lang == "ar") {
                    "جارٍ تحميل نموذج Vosk العربي (~318 ميجابايت، مرة واحدة فقط)..."
                } else {
                    "جارٍ تحميل نموذج Vosk الإنجليزي (~40 ميجابايت، مرة واحدة فقط)..."
                }
                val modelError: String? =
                    if (VoskTranscriber.isModelReady(context, lang)) {
                        null
                    } else {
                        if (!ModelDownloadState.running) {
                            ModelDownloadService.start(context, lang)
                        }
                        while (!ModelDownloadState.done) {
                            val p = ModelDownloadState.progress.value
                            if (p == null) {
                                progress = null
                                status = "جارٍ بدء تحميل نموذج Vosk..."
                            } else if (p.first < 0) {
                                progress = null
                                status = "تم تحميل النموذج، جارٍ فك ضغطه..."
                            } else if (p.second > 0) {
                                progress = p.first.toFloat() / p.second.toFloat()
                                status = "تحميل نموذج Vosk: ${(p.first * 100 / p.second).toInt()}% (${p.first / 1_048_576} من ${p.second / 1_048_576} ميجابايت)..."
                            } else {
                                progress = null
                                status = "تحميل نموذج Vosk: ${p.first / 1_048_576} ميجابايت..."
                            }
                            delay(400)
                        }
                        val err = ModelDownloadState.error
                        ModelDownloadState.reset()
                        err
                    }
                progress = null
                if (modelError != null) {
                    stage = null
                    status = "تعذّر تحميل نموذج Vosk: $modelError"
                    subtitles = listOf(Subtitle(0, 5000, "اكتب الكابشن هنا", "", "ar"))
                } else {
                    stage = "تفريغ الصوت"
                    status = "جارٍ تفريغ الصوت..."
                    val found = withContext(Dispatchers.IO) {
                        VoskTranscriber.transcribe(context, wav, lang)
                    }
                    if (found.isEmpty()) {
                        stage = null
                        status = "لم يتم تفريغ الصوت تلقائياً. تأكد أن لغة الفيديو مطابقة للخيار المحدد، أو أضف كابشن يدوياً."
                        subtitles = listOf(Subtitle(0, 5000, "اكتب الكابشن هنا", "", "ar"))
                    } else {
                        detectedLang = lang
                        stage = "الترجمة إلى العربية"
                        status = "تم التفريغ. جارٍ الترجمة إلى العربية..."
                        val translated = if (lang == "ar") found else Translator.translateToArabic(found)
                        subtitles = translated
                        stage = null
                        status = "جاهز. عدّل النص والتنسيق في الأسفل، وشاهد المعاينة، ثم صدّر."
                        activeTool = "text"
                    }
                }
            } catch (e: Throwable) {
                stage = null
                status = "حدث خطأ (${e.javaClass.simpleName}): ${e.message}"
            } finally {
                progress = null
                isWorking = false
            }
        }
    }

    fun startExport(srtOnly: Boolean) {
        isWorking = true
        progress = null
        stage = if (srtOnly) "حفظ ملف SRT" else "دمج الترجمة في الفيديو"
        status = if (srtOnly) "جارٍ حفظ ملف SRT فقط..." else "جارٍ دمج الترجمة في الفيديو بحجم خط ${style.size}... قد يأخذ وقتاً حسب طول الفيديو."
        scope.launch {
            try {
                if (srtOnly) {
                    val srt = withContext(Dispatchers.IO) {
                        VideoExporter.exportSrtOnly(
                            subtitles,
                            File(context.getExternalFilesDir(null), "VideoTranslator")
                        )
                    }
                    stage = null
                    status = "تم حفظ ملف SRT فقط في: ${srt.parent}/${srt.name}."
                } else {
                    val input = File(context.cacheDir, "input_video.mp4")
                    val result = withContext(Dispatchers.IO) {
                        VideoExporter.export(
                            context, input, subtitles,
                            File(context.getExternalFilesDir(null), "VideoTranslator"),
                            onLog = { log -> scope.launch { status = log } },
                            style = style,
                        )
                    }
                    exportResult = result
                    previewUri = Uri.fromFile(result.videoFile)
                    previewIsExported = true
                    isPlaying = false
                    stage = null
                    status = "تم التصدير بنجاح بحجم خط ${style.size} وخط ${style.font.displayName}. هذه معاينة الفيديو النهائي."
                    activeTool = "share"
                }
            } catch (e: Throwable) {
                stage = null
                status = "فشل التصدير (${e.javaClass.simpleName}): ${e.message}"
            } finally {
                progress = null
                isWorking = false
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8B5CF6),
            secondary = Color(0xFF22D3EE),
            background = Color(0xFF000000),
            surface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFF2A2A2A),
            onBackground = Color.White,
            onSurface = Color.White,
        )
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // ---- CapCut-style top bar: project + Export ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF8B5CF6)),
                    contentAlignment = Alignment.Center
                ) { Text("C", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Capeo", fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Color.White)
                    Text("مشروع ترجمة فيديو", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                }
                Button(
                    onClick = { startExport(false) },
                    enabled = subtitles.isNotEmpty() && !isWorking,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                ) { Text("تصدير") }
            }

            // ---- Preview (top, like CapCut canvas) ----
            // 240dp in 1.10: the preview kept squeezing the settings
            // panel into a thin strip. Shrinking it, plus moving the
            // tools to a bottom icon bar, gives the panel real room.
            Box(
                modifier = Modifier.fillMaxWidth().height(240.dp).background(Color.Black)
            ) {
                if (previewUri != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(previewUri)
                                setOnPreparedListener { mp ->
                                    durationMs = mp.duration
                                    positionMs = 0
                                }
                                setOnCompletionListener {
                                    isPlaying = false
                                    positionMs = 0
                                }
                                videoView = this
                            }
                        },
                        update = { view ->
                            if (view.tag != previewUri.toString()) {
                                view.tag = previewUri.toString()
                                view.setVideoURI(previewUri)
                                view.setOnPreparedListener { mp ->
                                    durationMs = mp.duration
                                    positionMs = 0
                                }
                                videoView = view
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    // Subtitle overlay. key(style, text) is the fix for
                    // the 1.7 bug where changing the font size did not
                    // change the preview: recreating the TextView on
                    // every style change guarantees the new size, font
                    // and colour are applied, instead of relying on an
                    // AndroidView update that could be skipped.
                    if (currentSubtitle != null) {
                        val overlayAlign = when (style.position) {
                            CaptionPosition.BOTTOM -> Alignment.BottomCenter
                            CaptionPosition.MIDDLE -> Alignment.Center
                            CaptionPosition.TOP -> Alignment.TopCenter
                        }
                        // Captions-AI word index: which word is spoken
                        // right now, or the last word already spoken.
                        // Keying on it (not on every position tick)
                        // recreates the overlay only when a new word
                        // pops in, so there is no flicker.
                        val wordIndex = if (style.wordByWord && currentSubtitle!!.words.isNotEmpty()) {
                            val ws = currentSubtitle!!.words
                            ws.indexOfFirst { positionMs in it.startMs..it.endMs }.takeIf { it >= 0 }
                                ?: ws.indexOfLast { it.endMs <= positionMs }.takeIf { it >= 0 }
                        } else null
                        Box(
                            modifier = Modifier.align(overlayAlign).padding(16.dp).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            key(style, currentSubtitle!!.text, wordIndex) {
                                AndroidView(
                                    factory = { ctx ->
                                        TextView(ctx).apply {
                                            gravity = Gravity.CENTER
                                            text = captionsPreviewText(currentSubtitle!!, wordIndex, style)
                                            try {
                                                typeface = android.graphics.Typeface.createFromAsset(
                                                    ctx.assets, "fonts/${style.font.fileName}"
                                                )
                                            } catch (_: Exception) {
                                            }
                                            // 0.75x in 1.10 (was 0.5x): the old
                                            // scale halved every size, so a
                                            // size-64 caption read as only
                                            // 32sp and enlarging it still
                                            // looked small. 0.75x keeps the
                                            // preview honestly large.
                                            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.size * 0.75f)
                                            setTextColor(android.graphics.Color.parseColor("#" + style.color.rgb))
                                            setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                                            setBackgroundColor(
                                                if (style.background == CaptionBackground.BOX)
                                                    android.graphics.Color.parseColor("#80000000")
                                                else android.graphics.Color.TRANSPARENT
                                            )
                                            setPadding(16, 8, 16, 8)
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Empty-state button right on the canvas, so the
                    // choose-video action is never hidden in a panel.
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("لا يوجد فيديو بعد", color = Color(0xFF888888))
                            Button(
                                onClick = { picker.launch("video/*") },
                                modifier = Modifier.height(56.dp).padding(horizontal = 24.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                            ) { Text("📁  اختر فيديو من جوالك", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            // ---- Timeline scrubber + subtitle track ----
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (isPlaying) {
                                videoView?.pause()
                                isPlaying = false
                                positionMs = videoView?.currentPosition ?: positionMs
                            } else {
                                videoView?.start()
                                isPlaying = true
                            }
                        },
                        enabled = previewUri != null
                    ) { Text(if (isPlaying) "⏸" else "▶") }
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                        onValueChange = { v ->
                            val ms = (v * durationMs).toInt()
                            positionMs = ms
                            videoView?.seekTo(ms)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${formatTime(positionMs)} / ${formatTime(durationMs)}", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                }
                if (subtitles.isNotEmpty() && durationMs > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        subtitles.forEachIndexed { index, sub ->
                            val selected = selectedIndex == index
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF8B5CF6) else Color(0xFF333333))
                                    .clickable {
                                        selectedIndex = index
                                        positionMs = sub.startMs.toInt()
                                        videoView?.seekTo(sub.startMs.toInt())
                                        activeTool = "subtitles"
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    sub.text.take(18),
                                    fontSize = 12.sp, color = Color.White, maxLines = 1
                                )
                            }
                        }
                    }
                }
                if (isWorking) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stage ?: "جارٍ العمل...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (progress != null) {
                            LinearProgressIndicator(progress = progress!!, modifier = Modifier.fillMaxWidth())
                            Text("${(progress!! * 100).toInt()}%", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(status, fontSize = 12.sp, color = Color(0xFFAAAAAA))
                    }
                } else {
                    Text(status, fontSize = 12.sp, color = Color(0xFFAAAAAA))
                }
            }

            // ---- Settings panel for the selected tool ----
            // In 1.10 the tools bar no longer sits here: it moved to
            // a bottom icon bar, so this panel gets the full middle
            // of the screen instead of a squeezed strip.
            // Every panel scrolls: in 1.8 the video/transcribe panels
            // were not scrollable, so their buttons and checkbox were
            // clipped when the panel was squeezed by the preview.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A1A)).padding(14.dp)) {
                when (activeTool) {
                    "video" -> Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("اختر الفيديو", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                        Button(
                            onClick = { picker.launch("video/*") },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                        ) {
                            Text("📁  اختر فيديو من جوالك", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        if (pickedUri != null) Text("تم اختيار فيديو. انتقل إلى التفريغ.", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                        if (detectedLang.isNotEmpty()) Text("اللغة المكتشفة: $detectedLang", color = Color.White)
                    }
                    "transcribe" -> Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("تفريغ وترجمة", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                        // Big toggle card instead of a small bare
                        // checkbox: in 1.8 the checkbox looked hidden
                        // in the dark panel. Tapping anywhere on the
                        // card toggles it, and the check square is
                        // large and high-contrast.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isArabicVideo) Color(0xFF3B2A6B) else Color(0xFF2A2A2A))
                                .border(
                                    width = if (isArabicVideo) 2.dp else 1.dp,
                                    color = if (isArabicVideo) Color(0xFF8B5CF6) else Color(0xFF555555),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !isWorking) { isArabicVideo = !isArabicVideo }
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                                        .background(if (isArabicVideo) Color(0xFF8B5CF6) else Color.Transparent)
                                        .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isArabicVideo) Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("الفيديو بالعربية", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("نموذج كبير ~318 ميجابايت، مرة واحدة فقط", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                                }
                            }
                        }
                        Text("بدون هذا الخيار يُفترض الإنجليزية (~40 ميجابايت). التحميل يعمل كخدمة أمامية بإشعار، فيمكنك مغادرة التطبيق وسيستمر.", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                        Button(onClick = { startTranscribe() }, enabled = pickedUri != null && !isWorking, modifier = Modifier.fillMaxWidth()) {
                            Text("تفريغ وترجمة")
                        }
                    }
                    "text" -> Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("تنسيق النص", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                        // Captions-AI preset: word-by-word reveal,
                        // bold sans, yellow highlight, bottom position.
                        Button(
                            onClick = {
                                style = style.copy(
                                    wordByWord = true,
                                    font = CaptionFonts.SANS,
                                    color = CaptionColors.YELLOW,
                                    position = CaptionPosition.BOTTOM,
                                    background = CaptionBackground.OUTLINE,
                                    size = 100,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
                        ) { Text("✨ تطبيق نمط Captions AI", color = Color.Black, fontWeight = FontWeight.Bold) }
                        Text("نمط الكابشن", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { style = style.copy(wordByWord = true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (style.wordByWord) Color(0xFF8B5CF6) else Color(0xFF2A2A2A)
                                )
                            ) { Text("كلمة بكلمة (Captions)") }
                            Button(
                                onClick = { style = style.copy(wordByWord = false) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!style.wordByWord) Color(0xFF8B5CF6) else Color(0xFF2A2A2A)
                                )
                            ) { Text("سطر كامل") }
                        }
                        Text(
                            if (style.wordByWord) "الكلمات تظهر واحدة تلو الأخرى مع تلوين الكلمة الحالية، مثل تطبيق Captions."
                            else "السطر كاملاً يظهر دفعة واحدة طوال مدته.",
                            color = Color(0xFFAAAAAA), fontSize = 15.sp
                        )
                        Text("حجم الخط: ${style.size} — حرّك المزلاج وستتغير المعاينة فوراً", color = Color.White, fontWeight = FontWeight.Bold)
                        Slider(
                            value = style.size.toFloat(),
                            onValueChange = { style = style.copy(size = it.toInt()) },
                            valueRange = 40f..140f
                        )
                        // Live sample, keyed on style so it always reflects the slider.
                        key(style) {
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black).padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        TextView(ctx).apply {
                                            gravity = Gravity.CENTER
                                            text = "معاينة الخط: كيف يمكن أن نتحسن؟"
                                            try {
                                                typeface = android.graphics.Typeface.createFromAsset(
                                                    ctx.assets, "fonts/${style.font.fileName}"
                                                )
                                            } catch (_: Exception) {
                                            }
                                            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.size * 0.75f)
                                            setTextColor(android.graphics.Color.parseColor("#" + style.color.rgb))
                                            setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                                        }
                                    }
                                )
                            }
                        }
                        Text("نوع الخط", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CaptionFonts.ALL.forEach { font ->
                                val selected = style.font.id == font.id
                                Button(
                                    onClick = { style = style.copy(font = font) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF2A2A2A)
                                    )
                                ) { Text(font.displayName) }
                            }
                        }
                        Text("لون الخط: ${style.color.displayName}", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CaptionColors.ALL.forEach { color ->
                                val selected = style.color.id == color.id
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(color.compose)
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) Color.White else Color(0xFF555555),
                                            shape = CircleShape
                                        )
                                        .clickable { style = style.copy(color = color) }
                                )
                            }
                        }
                        Text("موضع الترجمة", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CaptionPosition.values().forEach { pos ->
                                val selected = style.position == pos
                                Button(
                                    onClick = { style = style.copy(position = pos) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF2A2A2A)
                                    )
                                ) { Text(pos.displayName) }
                            }
                        }
                        Text("خلفية الترجمة", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CaptionBackground.values().forEach { bg ->
                                val selected = style.background == bg
                                Button(
                                    onClick = { style = style.copy(background = bg) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF2A2A2A)
                                    )
                                ) { Text(bg.displayName) }
                            }
                        }
                    }
                    "subtitles" -> Column {
                        Text("مراجعة الترجمة", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                        Text("اضغط أي مقطع في الشريط الزمني أعلاه لتعديله هنا.", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        if (subtitles.isEmpty()) {
                            Text("لا توجد ترجمات بعد. ابدأ بالتفريغ أولاً.", color = Color(0xFFAAAAAA))
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(subtitles) { index, sub ->
                                    OutlinedTextField(
                                        value = sub.text,
                                        onValueChange = { newText ->
                                            subtitles = subtitles.toMutableList().also {
                                                it[index] = sub.copy(text = newText)
                                            }
                                        },
                                        label = { Text("${sub.startMs / 1000}s - ${sub.endMs / 1000}s") },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Right)
                                    )
                                }
                            }
                        }
                    }
                    "share" -> Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("المشاركة والتصدير", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                        // Export lives here now (and in the top bar):
                        // the old fixed bottom export bar was removed
                        // in 1.10 to make room for the bottom icon bar.
                        Button(
                            onClick = { startExport(false) },
                            enabled = subtitles.isNotEmpty() && !isWorking,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                        ) { Text("🎬  تصدير فيديو + SRT", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        if (exportResult != null) {
                            Text("تم الحفظ:", color = Color.White)
                            Text("الفيديو: ${exportResult!!.videoFile.name}", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                            Text("الترجمة: ${exportResult!!.srtFile.name}", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                            Button(onClick = { shareExported() }, modifier = Modifier.fillMaxWidth()) {
                                Text("مشاركة الفيديو (إنستغرام / تيك توك / واتساب)")
                            }
                        } else {
                            Text("صدّر الفيديو من الزر أعلاه أو من زر التصدير في الأعلى، ثم شاركه من هنا.", color = Color(0xFFAAAAAA))
                        }
                        Button(onClick = { startExport(true) }, enabled = subtitles.isNotEmpty() && !isWorking, modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير SRT فقط (بدون دمج)")
                        }
                    }
                }
            }

            // ---- Bottom icon bar (CapCut-style) ----
            // The five tools are icons at the very bottom of the app,
            // as requested in 1.10, with a large tap area each. This
            // replaces both the middle tools bar and the fixed bottom
            // export bar, so the settings panel above gets all the
            // middle space instead of a squeezed strip.
            val tools = listOf(
                "video" to "🎬" to "فيديو",
                "transcribe" to "🎙️" to "تفريغ",
                "text" to "✍️" to "نص",
                "subtitles" to "📝" to "ترجمات",
                "share" to "📤" to "مشاركة",
            )
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(vertical = 6.dp),
            ) {
                tools.forEach { (tool, label) ->
                    val (id, icon) = tool
                    val selected = activeTool == id
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color(0xFF2E1A5E) else Color.Transparent)
                            .clickable { activeTool = id }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(icon, fontSize = 24.sp)
                        Text(
                            label,
                            color = if (selected) Color(0xFFC4B5FD) else Color(0xFFAAAAAA),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/**
 * Text for the preview overlay, Captions-AI style: when word-by-word
 * is on, only the words spoken so far are shown, earlier words in
 * white and the current word in the chosen highlight colour (yellow
 * when the chosen colour is white, otherwise it would be invisible).
 * When word-by-word is off, or the segment has no word timings
 * (manual captions), the whole line is shown as before.
 */
private fun captionsPreviewText(
    sub: Subtitle,
    wordIndex: Int?,
    style: CaptionStyle,
): CharSequence {
    if (!style.wordByWord || sub.words.isEmpty() || wordIndex == null) {
        return sub.text
    }
    val highlightRgb = if (style.color.rgb == "FFFFFF") "FFFF00" else style.color.rgb
    val builder = android.text.SpannableStringBuilder()
    sub.words.take(wordIndex + 1).forEachIndexed { i, word ->
        if (i > 0) builder.append(" ")
        val start = builder.length
        builder.append(word.text)
        val colour = if (i == wordIndex) highlightRgb else "FFFFFF"
        builder.setSpan(
            android.text.style.ForegroundColorSpan(
                android.graphics.Color.parseColor("#$colour")
            ),
            start, builder.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return builder
}
