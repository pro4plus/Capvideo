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

    /**
     * Download and extract the model for [lang] on first use.
     * Must be called off the main thread. Returns true when the model
     * is ready to transcribe with. A failed download cleans up the
     * partial model so the next attempt starts fresh.
     */
    fun ensureModel(context: Context, lang: String): Boolean {
        if (isModelReady(context, lang)) return true
        val url = modelUrl(lang) ?: return false
        val dir = modelDir(context, lang)
        val zipFile = File(context.cacheDir, "vosk-model-$lang.zip")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "VideoTranslator/1.1")
            connection.connect()
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }
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
            return isModelReady(context, lang)
        } catch (e: Exception) {
            try { if (dir.exists()) dir.deleteRecursively() } catch (_: Exception) {}
            try { if (zipFile.exists()) zipFile.delete() } catch (_: Exception) {}
            return false
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
