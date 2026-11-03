package com.vidreal.translator

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device translation with Google ML Kit. No API key, no server.
 * The first translation in a language pair downloads that pair's model
 * (a few MB) over the internet, then everything works offline.
 */
object Translator {

    /** Maps a BCP-47 code to an ML Kit TranslateLanguage constant. */
    private fun languageCodeToMlKit(code: String): String {
        return when (code.lowercase().take(2)) {
            "ar" -> TranslateLanguage.ARABIC
            "en" -> TranslateLanguage.ENGLISH
            "fr" -> TranslateLanguage.FRENCH
            "es" -> TranslateLanguage.SPANISH
            "de" -> TranslateLanguage.GERMAN
            "it" -> TranslateLanguage.ITALIAN
            "pt" -> TranslateLanguage.PORTUGUESE
            "ru" -> TranslateLanguage.RUSSIAN
            "tr" -> TranslateLanguage.TURKISH
            "hi" -> TranslateLanguage.HINDI
            "ur" -> TranslateLanguage.URDU
            "fa" -> TranslateLanguage.PERSIAN
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            else -> TranslateLanguage.ENGLISH
        }
    }

    /** Returns an ML Kit language code like "en" or "ar", or null. */
    suspend fun identifyLanguage(text: String): String? {
        return suspendCancellableCoroutine { cont ->
            LanguageIdentification.getClient()
                .identifyLanguage(text)
                .addOnSuccessListener { code ->
                    cont.resume(if (code == "und") null else code)
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    /**
     * Translates every subtitle to Arabic, unless it is already Arabic.
     * The original text is kept in [Subtitle.originalText] for review.
     */
    suspend fun translateToArabic(subtitles: List<Subtitle>): List<Subtitle> {
        if (subtitles.isEmpty()) return subtitles
        val sample = subtitles.joinToString(" ") { it.text }
        val source = identifyLanguage(sample) ?: "en"
        if (source == "ar") return subtitles

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(languageCodeToMlKit(source))
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val translator = Translation.getClient(options)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resume(Unit) }
            }
            val out = mutableListOf<Subtitle>()
            for (sub in subtitles) {
                val translated = suspendCancellableCoroutine<String> { cont ->
                    translator.translate(sub.text)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(sub.text) }
                }
                out += sub.copy(text = translated, originalText = sub.text, sourceLanguage = source)
            }
            return out
        } finally {
            translator.close()
        }
    }
}
