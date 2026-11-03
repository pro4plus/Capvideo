package com.vidreal.translator

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Offline transcription with Vosk, loaded by reflection.
 *
 * Why reflection? The Vosk Android AAR is distributed from GitHub, not
 * Maven Central, so the project must still compile without it. When the
 * user adds app/libs/vosk-android.aar (see README) and downloads a model,
 * this class finds org.vosk.Model / org.vosk.Recognizer at runtime and
 * returns timed segments. Without them, transcribe() returns an empty
 * list and the UI falls back to manual caption entry.
 *
 * Model downloads (small models, ~40-50 MB each):
 *  English: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
 *  Arabic:  https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip
 * Unzip into the app's filesDir/models/<lang>/ folder.
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

    fun transcribe(context: Context, wavFile: File, lang: String): List<Subtitle> {
        val modelDir = modelDir(context, lang)
        if (!modelDir.exists() || !isAvailable()) return emptyList()
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
