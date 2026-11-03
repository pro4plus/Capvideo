package com.vidreal.translator

import java.util.Locale
import kotlin.math.roundToLong

/**
 * One timed caption line. Times are in milliseconds.
 * [text] is always the text that will be shown in the final video.
 * [originalText] keeps the transcribed text before translation, for review.
 */
data class Subtitle(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val originalText: String = text,
    val sourceLanguage: String = "unknown",
)

object SubtitleFormat {

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val rest = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, rest)
    }

    private fun assTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val cs = (ms % 1000) / 10
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    /** Standard SRT file, readable by Instagram, YouTube, VLC and every editor. */
    fun toSrt(subtitles: List<Subtitle>): String {
        val out = StringBuilder()
        subtitles.sortedBy { it.startMs }.forEachIndexed { index, sub ->
            out.append(index + 1).append("\n")
            out.append(srtTime(sub.startMs)).append(" --> ").append(srtTime(sub.endMs)).append("\n")
            out.append(sub.text.trim()).append("\n\n")
        }
        return out.toString()
    }

    /**
     * ASS file for burning subtitles with ffmpeg/libass.
     * Arabic shaping is done by libass + the bundled Noto Naskh Arabic font,
     * so letters join correctly in the exported video. The text itself is
     * stored in logical order; libass renders it right-to-left.
     * Note: Noto Sans Arabic was tested and dropped a glyph for "ث" in
     * medial position under libass; Noto Naskh Arabic renders it correctly.
     */
    fun toAss(subtitles: List<Subtitle>): String {
        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1080
            PlayResY: 1920
            WrapStyle: 2
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Arabic, Noto Naskh Arabic, 64, &H00FFFFFF, &H000000FF, &H80000000, &H80000000, -1, 0, 0, 0, 100, 100, 0, 0, 1, 3, 0, 2, 80, 80, 140, 1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()
        val out = StringBuilder(header).append("\n")
        subtitles.sortedBy { it.startMs }.forEach { sub ->
            val safe = sub.text.trim().replace("\n", "\\N")
            out.append("Dialogue: 0,")
                .append(assTime(sub.startMs)).append(",")
                .append(assTime(sub.endMs)).append(",Arabic,,0,0,0,,")
                .append(safe).append("\n")
        }
        return out.toString()
    }

    /** Split a long recognised segment into readable lines, max ~42 chars. */
    fun splitLong(text: String, startMs: Long, endMs: Long, original: String, lang: String): List<Subtitle> {
        val words = text.trim().split(Regex("\\s+"))
        if (text.length <= 42) return listOf(Subtitle(startMs, endMs, text, original, lang))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.length + word.length + 1 > 42 && current.isNotEmpty()) {
                lines.add(current.toString().trim())
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(word)
        }
        if (current.isNotEmpty()) lines.add(current.toString().trim())
        val total = (endMs - startMs).coerceAtLeast(500)
        return lines.mapIndexed { i, line ->
            val s = startMs + (total * i / lines.size)
            val e = startMs + (total * (i + 1) / lines.size)
            Subtitle(s, e, line, original, lang)
        }
    }

    fun durationMsToSeconds(ms: Long): Double = (ms / 1000.0).roundToLong().toDouble()
}
