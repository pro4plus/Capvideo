package com.vidreal.translator

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoTranslatorApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTranslatorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("اختر فيديو من جوالك للبدء") }
    var isWorking by remember { mutableStateOf(false) }
    var subtitles by remember { mutableStateOf<List<Subtitle>>(emptyList()) }
    var detectedLang by remember { mutableStateOf("") }
    var exportResult by remember { mutableStateOf<VideoExporter.ExportResult?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoUri = uri
        subtitles = emptyList()
        exportResult = null
        status = if (uri != null) "تم اختيار الفيديو. اضغط: تفريغ وترجمة" else "لم يتم اختيار فيديو"
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("مترجم الفيديو") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "ارفع فيديو بأي لغة، وسيحوّله التطبيق إلى كابشن عربي. " +
                        "وإذا كان الفيديو عربياً أصلاً، سيخرج لك كابشن عربي مباشرة.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = { picker.launch("video/*") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("اختر فيديو") }

                Text("الحالة: $status", style = MaterialTheme.typography.bodyMedium)

                if (detectedLang.isNotEmpty()) {
                    Text("اللغة المكتشفة: $detectedLang", style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    enabled = videoUri != null && !isWorking,
                    onClick = {
                        val uri = videoUri ?: return@Button
                        isWorking = true
                        status = "جارٍ استخراج الصوت وتفريغه..."
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
                                // Try English first, then Arabic, if no model is set.
                                // The user can add a model for any language in models/<lang>/.
                                var found = VoskTranscriber.transcribe(context, wav, "en")
                                var lang = "en"
                                if (found.isEmpty()) {
                                    found = VoskTranscriber.transcribe(context, wav, "ar")
                                    if (found.isNotEmpty()) lang = "ar"
                                }
                                if (found.isEmpty()) {
                                    status = "لم يتم تفريغ الصوت تلقائياً (نموذج Vosk غير مثبّت بعد). " +
                                        "أضف كابشن يدوياً بالأسفل، أو ثبّت النموذج من الشرح في README، ثم أعد المحاولة."
                                    subtitles = listOf(
                                        Subtitle(0, 5000, "اكتب الكابشن هنا", "", "ar")
                                    )
                                } else {
                                    detectedLang = lang
                                    status = "تم التفريغ. جارٍ الترجمة إلى العربية..."
                                    val translated = if (lang == "ar") found else Translator.translateToArabic(found)
                                    subtitles = translated
                                    status = "جاهز للمراجعة. عدّل أي سطر قبل التصدير، ثم اضغط تصدير."
                                }
                            } catch (e: Exception) {
                                status = "حدث خطأ: ${e.message}"
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("تفريغ وترجمة") }

                if (isWorking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (subtitles.isNotEmpty()) {
                    Text("راجع وعدّل الترجمة:", style = MaterialTheme.typography.titleMedium)
                    // Editing list, kept in a fixed-height scrollable area.
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
                            status = "جارٍ تصدير الفيديو المدمج + ملف SRT..."
                            scope.launch {
                                try {
                                    val input = File(context.cacheDir, "input_video.mp4")
                                    val result = withContext(Dispatchers.IO) {
                                        VideoExporter.export(
                                            context, input, subtitles,
                                            File(context.getExternalFilesDir(null), "VideoTranslator")
                                        ) { log -> status = log }
                                    }
                                    exportResult = result
                                    status = "تم التصدير بنجاح."
                                } catch (e: Exception) {
                                    status = "فشل التصدير: ${e.message}"
                                } finally {
                                    isWorking = false
                                }
                            }
                        },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("تصدير: فيديو مدمج + SRT") }
                }

                exportResult?.let { result ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("تم الحفظ في مجلد التطبيق:")
                            Text("الفيديو: ${result.videoFile.name}")
                            Text("الترجمة: ${result.srtFile.name}")
                            Text("المسار: ${result.videoFile.parent}")
                            Button(
                                onClick = {
                                    Toast.makeText(context, "شارك الملف من مدير الملفات", Toast.LENGTH_LONG).show()
                                }
                            ) { Text("تم، افتح مجلد الملفات") }
                        }
                    }
                }

                Text(
                    "ملاحظة: الترجمة تعمل بدون خادم وبدون مفتاح API. " +
                        "التفريغ الصوتي يحتاج نموذج Vosk لمرة واحدة (انظر README)، " +
                        "وبعده يعمل التطبيق كاملاً على الجهاز.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
