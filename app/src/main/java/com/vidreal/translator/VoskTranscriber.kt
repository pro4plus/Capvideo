package com.vidreal.translator

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline transcription with Vosk, loaded by reflection.
 *
 * The Vosk library itself now comes from Maven Central
 * (com.alphacephei:vosk-android), so no local AAR is needed. Reflection
 * is kept so a missing library degrades to manual caption entry instead
 * of a crash. The speech model is downloaded in-app on first use by
 * [ensureModel] and stored under filesDir/models/<lang>/, so the user
 * never has to copy files into the app's private folder by hand.
 *
 * Model downloads:
 *  English: vosk-model-small-en-us-0.15.zip (~40 MB)
 *  Arabic:  vosk-model-ar-mgb2-0.4.zip (~318 MB)
 * Both from https://alphacephei.com/vosk/models/
 */
object VoskTranscriber {

    fun isAvailable(): Boolean {
        return try {
            Class.forName("org.vosk.Model")
            Class.forName("org.vosk.Recognizer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    fun modelDir(context: Context, lang: String): File =
        File(context.filesDir, "models/$lang")

    fun modelUrl(lang: String): String? = when (lang) {
        "en" -> "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        "ar" -> "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
        else -> null
    }

    /** True when the extracted model (not just an empty folder) is present. */
    fun isModelReady(context: Context, lang: String): Boolean {
        val dir = modelDir(context, lang)
        return dir.isDirectory && File(dir, "am/final.mdl").isFile
    }

    /** Free space needed to download AND extract the model, with margin.
     *  Verified 2026-11-03: the Arabic zip is 333 MB but extracts to
     *  698 MB, so the phone needs ~1.1 GB free at peak, not 318 MB.
     *  The English zip is 41 MB and extracts to ~100 MB. */
    fun requiredFreeBytes(lang: String): Long = when (lang) {
        "ar" -> 1_150_000_000L
        "en" -> 180_000_000L
        else -> 0L
    }

    fun ensureModel(
        context: Context,
        lang: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean = ensureModelWithError(context, lang, onProgress) == null

    /**
     * Download and extract the model for [lang] on first use.
     * Must be called off the main thread. Returns null on success,
     * or a readable reason on failure — the old version swallowed
     * every reason and the UI could only say "check internet", which
     * is why a storage or interrupted-download failure looked
     * identical to a network failure.
     *
     * [onProgress] is called from the caller's thread with
     * (downloadedBytes, totalBytes); totalBytes is -1 when the size
     * is unknown, and both are -1 while the zip is being extracted,
     * so the UI can always show where the download actually is
     * instead of sitting silently for minutes.
     *
     * Downloads resume: a partial zip from an interrupted attempt is
     * continued with a Range request instead of starting over, and
     * connect/read timeouts turn a stalled connection into a
     * reported failure instead of an endless hang.
     */
    fun ensureModelWithError(
        context: Context,
        lang: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String? {
        if (isModelReady(context, lang)) return null
        val url = modelUrl(lang) ?: return "لغة غير مدعومة: $lang"
        val free = minOf(context.cacheDir.usableSpace, context.filesDir.usableSpace)
        val required = requiredFreeBytes(lang)
        if (free in 1 until required) {
            return "المساحة الحرة غير كافية: المتاح ${free / 1_048_576} ميجابايت، " +
                "والنموذج يحتاج حوالي ${required / 1_048_576} ميجابايت " +
                "(تحميل + فك ضغط). حرّر مساحة ثم أعد المحاولة."
        }
        val dir = modelDir(context, lang)
        val zipFile = File(context.cacheDir, "vosk-model-$lang.zip")
        try {
            val existing = if (zipFile.exists()) zipFile.length() else 0L
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "VideoTranslator/1.5")
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=$existing-")
            }
            connection.connect()
            val code = connection.responseCode
            if (code != 200 && code != 206 && code != 416) {
                return "فشل الاتصال بخادم النماذج: رمز HTTP $code"
            }
            val resumed = code == 206
            if (code != 416 && !resumed && existing > 0) {
                // Server ignored the Range request: start clean.
                zipFile.delete()
            }
            val remaining = connection.contentLengthLong
            val total = if (resumed && remaining > 0) existing + remaining else remaining
            var downloaded = if (resumed) existing else 0L
            var lastReported = -1L
            if (code != 416) {
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(zipFile, resumed).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            // Report at most every ~256 KB, plus at 100%.
                            if (downloaded - lastReported >= 262144 || (total > 0 && downloaded >= total)) {
                                lastReported = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
            } else {
                // 416 = the existing zip is already complete; skip
                // straight to extraction instead of failing.
                downloaded = existing
            }
            if (total > 0 && downloaded < total) {
                return "اكتمل التحميل ناقصاً: وصل $downloaded من $total بايت. " +
                    "أعد المحاولة وسيُستأنف التحميل من حيث توقف."
            }
            onProgress(downloaded, total)
            // Extraction phase: no byte counter is meaningful here.
            onProgress(-1, -1)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            // The zip wraps everything in one top-level folder
            // (vosk-model-.../); strip that first component so Vosk
            // finds am/, conf/ and graph/ directly under models/<lang>/.
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val parts = entry.name.split("/").filter { it.isNotEmpty() }
                        if (parts.size > 1) {
                            val outFile = File(dir, parts.drop(1).joinToString("/"))
                            val canonicalDir = dir.canonicalFile
                            val canonicalOut = outFile.canonicalFile
                            check(canonicalOut.path.startsWith(canonicalDir.path + File.separator)) {
                                "Unsafe zip entry: ${entry.name}"
                            }
                            canonicalOut.parentFile?.mkdirs()
                            canonicalOut.outputStream().use { out -> zip.copyTo(out) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            zipFile.delete()
            return if (isModelReady(context, lang)) {
                null
            } else {
                "النموذج غير مكتمل بعد فك الضغط: ملف am/final.mdl غير موجود."
            }
        } catch (e: Exception) {
            // Delete only the partial extraction, never the zip:
            // keeping it lets the next attempt resume the download.
            try { if (dir.exists()) dir.deleteRecursively() } catch (_: Exception) {}
            return "خطأ أثناء تحميل/فك النموذج (${e.javaClass.simpleName}): ${e.message}"
        }
    }

    fun transcribe(context: Context, wavFile: File, lang: String): List<Subtitle> {
        val modelDir = modelDir(context, lang)
        if (!isModelReady(context, lang) || !isAvailable()) return emptyList()
        return try {
            val modelClass = Class.forName("org.vosk.Model")
            val model = modelClass.getConstructor(String::class.java).newInstance(modelDir.absolutePath)
            val recognizerClass = Class.forName("org.vosk.Recognizer")
            val recognizer = recognizerClass
                .getConstructor(modelClass, Float::class.javaPrimitiveType!!)
                .newInstance(model, 16000f)
            val setWords = recognizerClass.getMethod("setWords", Boolean::class.javaPrimitiveType!!)
            setWords.invoke(recognizer, true)
            val acceptWave = recognizerClass.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.javaPrimitiveType!!)
            val getResult = recognizerClass.getMethod("getResult")
            val getFinalResult = recognizerClass.getMethod("getFinalResult")

            val segments = mutableListOf<Subtitle>()
            val bytes = wavFile.readBytes()
            // Skip the 44-byte WAV header.
            var offset = 44
            val chunk = 4096
            while (offset < bytes.size) {
                val len = minOf(chunk, bytes.size - offset)
                val accepted = acceptWave.invoke(recognizer, bytes.copyOfRange(offset, offset + len), len) as Boolean
                if (accepted) {
                    segments += parseVoskResult(getResult.invoke(recognizer) as String, lang)
                }
                offset += len
            }
            segments += parseVoskResult(getFinalResult.invoke(recognizer) as String, lang)
            segments.filter { it.text.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseVoskResult(json: String, lang: String): List<Subtitle> {
        val out = mutableListOf<Subtitle>()
        try {
            val obj = JSONObject(json)
            val result = obj.optJSONArray("result") ?: return out
            // Group words into sentences: break on a pause longer than 0.5s
            // or after 8 words, so captions stay readable.
            var currentWords = mutableListOf<String>()
            var start = 0.0
            var end = 0.0
            fun flush() {
                if (currentWords.isEmpty()) return
                val text = currentWords.joinToString(" ")
                out += SubtitleFormat.splitLong(
                    text, (start * 1000).toLong(), (end * 1000).toLong(), text, lang
                )
                currentWords = mutableListOf()
            }
            for (i in 0 until result.length()) {
                val w = result.getJSONObject(i)
                val word = w.optString("word")
                val ws = w.optDouble("start")
                val we = w.optDouble("end")
                if (currentWords.isEmpty()) start = ws
                if (currentWords.isNotEmpty() && (ws - end > 0.5 || currentWords.size >= 8)) {
                    flush()
                    start = ws
                }
                currentWords.add(word)
                end = we
            }
            flush()
        } catch (e: Exception) {
            // Ignore a malformed partial result; other chunks still work.
        }
        return out
    }
}
