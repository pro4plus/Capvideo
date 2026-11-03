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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapeoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- State: video, subtitles, style, work ----
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var previewIsExported by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    var status by remember { mutableStateOf("اختر فيديو من جوالك للبدء") }
    var stage by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var subtitles by remember { mutableStateOf<List<Subtitle>>(emptyList()) }
    var detectedLang by remember { mutableStateOf("") }
    var isArabicVideo by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(CaptionStyle()) }
    var exportResult by remember { mutableStateOf<VideoExporter.ExportResult?>(null) }

    val currentSubtitle = if (previewIsExported) null else
        subtitles.firstOrNull { positionMs in it.startMs until it.endMs }

    // Poll playback position while playing, so the overlay follows the video.
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
        status = if (uri != null) "تم اختيار الفيديو. اضغط: تفريغ وترجمة" else "لم يتم اختيار فيديو"
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

    val step = when {
        pickedUri == null -> 1
        subtitles.isEmpty() -> 2
        else -> 3
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8B5CF6),
            secondary = Color(0xFF22D3EE),
            tertiary = Color(0xFF22D3EE),
            background = Color(0xFF0F0F1A),
            surface = Color(0xFF1C1C2E),
            surfaceVariant = Color(0xFF2A2A44),
            onBackground = Color.White,
            onSurface = Color.White,
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF8B5CF6)),
                                contentAlignment = Alignment.Center
                            ) { Text("C", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Capeo", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                                Text("مترجم ومحرر فيديو", fontSize = 12.sp, color = Color(0xFFB0B0D0))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF141423))
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ---- Steps ----
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("١  الفيديو", "٢  التفريغ", "٣  المراجعة والتصدير").forEachIndexed { i, label ->
                        val active = step == i + 1
                        val doneStep = step > i + 1
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    when {
                                        active -> Color(0xFF8B5CF6)
                                        doneStep -> Color(0xFF2E7D5B)
                                        else -> Color(0xFF2A2A44)
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text(label, fontSize = 13.sp, color = Color.White) }
                    }
                }

                // ---- Working / progress banner: always visible while working ----
                if (isWorking) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A44))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stage ?: "جارٍ العمل...", fontWeight = FontWeight.Bold)
                            if (progress != null) {
                                LinearProgressIndicator(progress = progress!!, modifier = Modifier.fillMaxWidth())
                                Text("${(progress!! * 100).toInt()}%", fontSize = 13.sp, color = Color(0xFFB0B0D0))
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(status, fontSize = 13.sp, color = Color(0xFFB0B0D0))
                        }
                    }
                } else {
                    Text("الحالة: $status", fontSize = 14.sp, color = Color(0xFFD0D0E8))
                }

                // ---- 1. Video + preview player ----
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("١  الفيديو", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Button(
                            onClick = { picker.launch("video/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("اختر فيديو من جوالك") }

                        if (previewUri != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(380.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                            ) {
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
                                        // Switch the player source only when the URI actually changed.
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
                                // Styled subtitle overlay: exactly the style
                                // that will be burned into the export.
                                if (currentSubtitle != null) {
                                    val overlayAlign = when (style.position) {
                                        CaptionPosition.BOTTOM -> Alignment.BottomCenter
                                        CaptionPosition.MIDDLE -> Alignment.Center
                                        CaptionPosition.TOP -> Alignment.TopCenter
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(overlayAlign)
                                            .padding(16.dp)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AndroidView(
                                            factory = { ctx -> TextView(ctx).apply { gravity = Gravity.CENTER } },
                                            update = { tv ->
                                                tv.text = currentSubtitle!!.text
                                                try {
                                                    tv.typeface = android.graphics.Typeface.createFromAsset(
                                                        ctxAssets(context), "fonts/${style.font.fileName}"
                                                    )
                                                } catch (_: Exception) {
                                                }
                                                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (style.size * 0.36f))
                                                tv.setTextColor(android.graphics.Color.parseColor("#" + style.color.rgb))
                                                tv.setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                                                tv.setBackgroundColor(
                                                    if (style.background == CaptionBackground.BOX)
                                                        android.graphics.Color.parseColor("#80000000")
                                                    else android.graphics.Color.TRANSPARENT
                                                )
                                                tv.setPadding(16, 8, 16, 8)
                                            }
                                        )
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = {
                                    if (isPlaying) {
                                        videoView?.pause()
                                        isPlaying = false
                                        positionMs = videoView?.currentPosition ?: positionMs
                                    } else {
                                        videoView?.start()
                                        isPlaying = true
                                    }
                                }) { Text(if (isPlaying) "إيقاف مؤقت" else "تشغيل") }
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
                                Text(
                                    "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                                    fontSize = 12.sp, color = Color(0xFFB0B0D0)
                                )
                            }
                            if (previewIsExported) {
                                Text("هذه معاينة الفيديو المُصدَّر بالترجمة المدمجة.", fontSize = 13.sp, color = Color(0xFF22D3EE))
                            }
                        }
                    }
                }

                // ---- 2. Transcribe / translate ----
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("٢  التفريغ والترجمة", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (detectedLang.isNotEmpty()) {
                            Text("اللغة المكتشفة: $detectedLang", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isArabicVideo,
                                onCheckedChange = { isArabicVideo = it },
                                enabled = !isWorking
                            )
                            Text(
                                "الفيديو بالعربية (يحمّل نموذجاً كبيراً ~318 ميجابايت مرة واحدة فقط)",
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            "بدون هذا الخيار، يُفترض أن الفيديو بالإنجليزية ويُحمَّل نموذجه الصغير (~40 ميجابايت مرة واحدة فقط). التحميل يعمل كخدمة أمامية بإشعار، فيمكنك مغادرة التطبيق وسيستمر.",
                            fontSize = 13.sp, color = Color(0xFFB0B0D0)
                        )
                        Button(
                            enabled = pickedUri != null && !isWorking,
                            onClick = {
                                val uri = pickedUri ?: return@Button
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
                                            subtitles = listOf(
                                                Subtitle(0, 5000, "اكتب الكابشن هنا", "", "ar")
                                            )
                                        } else {
                                            stage = "تفريغ الصوت"
                                            status = "جارٍ تفريغ الصوت..."
                                            val found = withContext(Dispatchers.IO) {
                                                VoskTranscriber.transcribe(context, wav, lang)
                                            }
                                            if (found.isEmpty()) {
                                                stage = null
                                                status = "لم يتم تفريغ الصوت تلقائياً. تأكد أن لغة الفيديو مطابقة للخيار المحدد، أو أضف كابشن يدوياً بالأسفل."
                                                subtitles = listOf(
                                                    Subtitle(0, 5000, "اكتب الكابشن هنا", "", "ar")
                                                )
                                            } else {
                                                detectedLang = lang
                                                stage = "الترجمة إلى العربية"
                                                status = "تم التفريغ. جارٍ الترجمة إلى العربية..."
                                                val translated = if (lang == "ar") found else Translator.translateToArabic(found)
                                                subtitles = translated
                                                stage = null
                                                status = "جاهز للمراجعة. عدّل النص والتنسيق، وشاهد المعاينة، ثم صدّر."
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
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("تفريغ وترجمة") }
                    }
                }

                // ---- 3. Style + review + export ----
                if (subtitles.isNotEmpty()) {
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("٣  تنسيق الترجمة ومعاينتها", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("ما تراه في معاينة الفيديو أعلاه هو ما سيُدمج في الفيديو المُصدَّر.", fontSize = 13.sp, color = Color(0xFFB0B0D0))

                            Text("حجم الخط: ${style.size}", fontWeight = FontWeight.Bold)
                            Slider(
                                value = style.size.toFloat(),
                                onValueChange = { style = style.copy(size = it.toInt()) },
                                valueRange = 32f..96f
                            )

                            Text("نوع الخط", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CaptionFonts.ALL.forEach { font ->
                                    val selected = style.font.id == font.id
                                    Button(
                                        onClick = { style = style.copy(font = font) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF2A2A44)
                                        )
                                    ) { Text(font.displayName) }
                                }
                            }

                            Text("لون الخط", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CaptionColors.ALL.forEach { color ->
                                    val selected = style.color.id == color.id
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(color.compose)
                                            .border(
                                                width = if (selected) 3.dp else 1.dp,
                                                color = if (selected) Color.White else Color(0xFF555577),
                                                shape = CircleShape
                                            )
                                            .clickable { style = style.copy(color = color) }
                                    )
                                }
                            }
                            Text("اللون المختار: ${style.color.displayName}", fontSize = 13.sp, color = Color(0xFFB0B0D0))

                            Text("موضع الترجمة", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CaptionPosition.values().forEach { pos ->
                                    val selected = style.position == pos
                                    Button(
                                        onClick = { style = style.copy(position = pos) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF2A2A44)
                                        )
                                    ) { Text(pos.displayName) }
                                }
                            }

                            Text("خلفية الترجمة", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CaptionBackground.values().forEach { bg ->
                                    val selected = style.background == bg
                                    Button(
                                        onClick = { style = style.copy(background = bg) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF2A2A44)
                                        )
                                    ) { Text(bg.displayName) }
                                }
                            }

                            Text("راجع وعدّل الترجمة:", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            LazyColumn(
                                modifier = Modifier.height(320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(subtitles) { index, sub ->
                                    OutlinedTextField(
                                        value = sub.text,
                                        onValueChange = { newText ->
                                            subtitles = subtitles.toMutableList().also {
                                                it[index] = sub.copy(text = newText)
                                            }
                                        },
                                        label = {
                                            Text("${sub.startMs / 1000}s - ${sub.endMs / 1000}s")
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Right)
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    isWorking = true
                                    progress = null
                                    stage = "دمج الترجمة في الفيديو"
                                    status = "جارٍ دمج الترجمة في الفيديو... قد يأخذ وقتاً حسب طول الفيديو، لا تغلق التطبيق."
                                    scope.launch {
                                        try {
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
                                            status = "تم التصدير بنجاح. هذه معاينة الفيديو النهائي بالترجمة المدمجة."
                                        } catch (e: Throwable) {
                                            stage = null
                                            status = "فشل التصدير (${e.javaClass.simpleName}): ${e.message}"
                                        } finally {
                                            progress = null
                                            isWorking = false
                                        }
                                    }
                                },
                                enabled = !isWorking,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("تصدير: فيديو مدمج + SRT") }

                            Button(
                                onClick = {
                                    isWorking = true
                                    stage = "حفظ ملف SRT"
                                    status = "جارٍ حفظ ملف SRT فقط..."
                                    scope.launch {
                                        try {
                                            val srt = withContext(Dispatchers.IO) {
                                                VideoExporter.exportSrtOnly(
                                                    subtitles,
                                                    File(context.getExternalFilesDir(null), "VideoTranslator")
                                                )
                                            }
                                            stage = null
                                            status = "تم حفظ ملف SRT فقط في: ${srt.parent}/${srt.name}."
                                        } catch (e: Throwable) {
                                            stage = null
                                            status = "فشل حفظ SRT (${e.javaClass.simpleName}): ${e.message}"
                                        } finally {
                                            isWorking = false
                                        }
                                    }
                                },
                                enabled = !isWorking,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("تصدير SRT فقط (بدون دمج)") }
                        }
                    }
                }

                if (exportResult != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A2F))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("تم الحفظ:", fontWeight = FontWeight.Bold)
                            Text("الفيديو: ${exportResult!!.videoFile.name}")
                            Text("الترجمة: ${exportResult!!.srtFile.name}")
                            Text("المسار: ${exportResult!!.videoFile.parent}", fontSize = 13.sp, color = Color(0xFFB0B0D0))
                            Button(onClick = { shareExported() }, modifier = Modifier.fillMaxWidth()) {
                                Text("مشاركة الفيديو (إنستغرام / تيك توك / واتساب)")
                            }
                            Button(
                                onClick = {
                                    Toast.makeText(context, "شارك الملف من مدير الملفات", Toast.LENGTH_LONG).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("تم، افتح مجلد الملفات") }
                        }
                    }
                }

                Text(
                    "ملاحظة: الترجمة تعمل بدون خادم وبدون مفتاح API. " +
                        "التفريغ الصوتي يحمّل نموذج Vosk داخل التطبيق مرة واحدة فقط عند أول استخدام، " +
                        "وبعده يعمل التطبيق كاملاً على الجهاز بدون إنترنت.",
                    fontSize = 13.sp, color = Color(0xFFB0B0D0)
                )
                Text(
                    "تشخيص: معمارية الجهاز ${android.os.Build.SUPPORTED_ABIS.joinToString()}، " +
                        "مساحة حرة ${context.filesDir.usableSpace / 1_048_576} ميجابايت. " +
                        "النموذج العربي يحتاج ~1100 ميجابايت حرة وقت التحميل والفك.",
                    fontSize = 12.sp, color = Color(0xFF8888AA)
                )
            }
        }
    }
}

private fun ctxAssets(context: android.content.Context) = context.assets
