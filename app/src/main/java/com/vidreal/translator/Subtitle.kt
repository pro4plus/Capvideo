package com.vidreal.translator

import java.util.Locale
import kotlin.math.roundToLong

/**
 * One timed caption line. Times are in milliseconds.
 * [text] is always the text that will be shown in the final video.
 * [originalText] keeps the transcribed text before translation, for review.
 */
/** One spoken word with its own timing, from Vosk. This is what
 *  makes the Captions-AI style possible: words appear one by one
 *  as they are spoken, instead of a whole line appearing at once. */
data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class Subtitle(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val originalText: String = text,
    val sourceLanguage: String = "unknown",
    val words: List<Word> = emptyList(),
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
     * 1.13: re-tested 2026-11-06 with two Sans file versions, bold and
     * non-bold: still tofu boxes. Almarai and Tajawal fail the same way
     * under libass. Only Naskh and Amiri are verified to render Arabic
     * correctly when burned, so only those two are offered in the app.
     *
     * PlayRes must be the REAL video size (1.11 fix): libass scales every
     * script-unit size by video height / PlayResY. With the old fixed
     * 1080x1920, a horizontal 1920x1080 video shrank the font to
     * 1080/1920 = 56% (size 100 looked like 56, max 140 like 79), which
     * is why enlarging the font never seemed to help on horizontal video.
     * Vertical video was unaffected because its height really is 1920.
     */
    /** MarginV scaled to the real video height, so the caption sits the
     *  same fraction up from the bottom on any video size (140 at 1920
     *  height, ~79 at 1080). Default PlayResY keeps the old value. */
    fun scaledMarginV(style: CaptionStyle, playResY: Int): Int =
        (style.marginV() * playResY / 1920).coerceAtLeast(0)
    /** Split a translated line into words spread evenly over its
     *  segment. Used when the caption language differs from the
     *  spoken language, so English word timings cannot be reused for
     *  Arabic words: the reveal is still word-by-word, just evenly
     *  spaced instead of speech-synced. */
    fun distributeWords(text: String, startMs: Long, endMs: Long): List<Word> {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val total = (endMs - startMs).coerceAtLeast(parts.size * 100L)
        return parts.mapIndexed { i, w ->
            val s = startMs + (total * i / parts.size)
            val e = startMs + (total * (i + 1) / parts.size)
            Word(w, s, e)
        }
    }

    fun toAss(
        subtitles: List<Subtitle>,
        style: CaptionStyle = CaptionStyle(),
        playResX: Int = 1080,
        playResY: Int = 1920,
    ): String {
        // The style the user picked in the Capeo review screen is the
        // style burned in: font family, size, colour, position and
        // background all come from it, so preview and export match.
        // Captions-AI mode: base words are white, the currently
        // spoken word is the chosen colour (yellow when the chosen
        // colour itself is white, otherwise it would be invisible).
        val baseColour = if (style.wordByWord) "&H00FFFFFF" else style.assColor()
        val highlightColour = if (style.wordByWord) {
            if (style.color.rgb == "FFFFFF") "&H0000FFFF" else style.assColor()
        } else style.assColor()
        // Karaoke uses the ASS karaoke colours the other way round:
        // PrimaryColour is the already-sung part, SecondaryColour the
        // not-yet-sung part, so the line starts white and lights up
        // word by word as the \k timers pass.
        val primaryColour = if (style.karaoke) highlightColour else baseColour
        val secondaryColour = if (style.karaoke) baseColour else "&H000000FF"
        val backColour = "&H80000000"
        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: $playResX
            PlayResY: $playResY
            WrapStyle: 0
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Arabic, ${style.font.familyName}, ${style.size}, $primaryColour, $secondaryColour, &H80000000, $backColour, -1, 0, 0, 0, 100, 100, 0, 0, ${style.background.assBorderStyle}, 3, 0, ${style.position.assAlignment}, 80, 80, ${scaledMarginV(style, playResY)}, 1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()
        val out = StringBuilder(header).append("\n")
        subtitles.sortedBy { it.startMs }.forEach { sub ->
            if (style.karaoke && sub.words.isNotEmpty()) {
                // Karaoke: one dialogue for the whole line. Each word
                // carries a \k tag with its duration in centiseconds,
                // so libass lights words up as they are spoken while
                // the full line stays visible. Uppercase (IMPACT never
                // sets karaoke, but a manual combination could) is
                // applied to the words too.
                val words = if (style.uppercase) {
                    sub.words.map { it.copy(text = it.text.uppercase()) }
                } else sub.words
                val text = words.joinToString(" ") { word ->
                    val durCs = ((word.endMs - word.startMs) / 10).coerceAtLeast(1L)
                    "{\\k$durCs}${word.text}"
                }
                out.append("Dialogue: 0,")
                    .append(assTime(sub.startMs)).append(",")
                    .append(assTime(sub.endMs)).append(",Arabic,,0,0,0,,")
                    .append(text.trim()).append("\n")
            } else if (style.wordByWord && sub.words.isNotEmpty()) {
                // Captions-AI reveal: one dialogue per word, showing
                // all words spoken so far, with the newest word in
                // the highlight colour. Each dialogue lasts until the
                // next word starts, so words pop in as they are spoken.
                val words = if (style.uppercase) {
                    sub.words.map { it.copy(text = it.text.uppercase()) }
                } else sub.words
                words.forEachIndexed { i, word ->
                    val start = word.startMs
                    val end = if (i + 1 < words.size) words[i + 1].startMs else sub.endMs
                    val before = words.take(i).joinToString(" ") { it.text }
                    val text = if (before.isEmpty()) {
                        "{\\c$highlightColour&}${word.text}"
                    } else {
                        "$before {\\c$highlightColour&}${word.text}"
                    }
                    out.append("Dialogue: 0,")
                        .append(assTime(start)).append(",")
                        .append(assTime(end)).append(",Arabic,,0,0,0,,")
                        .append(text.trim()).append("\n")
                }
            } else {
                val shown = if (style.uppercase) sub.text.uppercase() else sub.text
                val safe = shown.trim().replace("\n", "\\N")
                out.append("Dialogue: 0,")
                    .append(assTime(sub.startMs)).append(",")
                    .append(assTime(sub.endMs)).append(",Arabic,,0,0,0,,")
                    .append(safe).append("\n")
            }
        }
        return out.toString()
    }

    /**
     * 1.13: give every line a minimum time on screen, so captions stop
     * disappearing before they can be read. Vosk lines last only as
     * long as the speech (a fast 6-word line can be ~1.5s), and with
     * word-by-word the full line is only visible at the very end of
     * that. Each line is stretched to at least ~90ms per character
     * (2.2s minimum, 4.5s maximum), then clamped to 80ms before the
     * next line starts so lines never overlap. Word timings are left
     * alone on purpose: words still pop in as spoken, then the whole
     * line stays readable until the stretched end. Applied after
     * transcription/translation, so preview and export share it.
     */
    fun normalizeTimings(subtitles: List<Subtitle>): List<Subtitle> {
        if (subtitles.isEmpty()) return subtitles
        val sorted = subtitles.sortedBy { it.startMs }
        return sorted.mapIndexed { i, sub ->
            val target = (sub.text.length * 90L).coerceIn(2200L, 4500L)
            var end = maxOf(sub.endMs, sub.startMs + target)
            val nextStart = sorted.getOrNull(i + 1)?.startMs
            if (nextStart != null) end = minOf(end, nextStart - 80L)
            if (end <= sub.startMs + 300L) end = sub.endMs
            sub.copy(endMs = end)
        }
    }

    /** Split a long recognised segment into readable lines, max ~32 chars. */
    fun splitLong(text: String, startMs: Long, endMs: Long, original: String, lang: String): List<Subtitle> {
        val words = text.trim().split(Regex("\\s+"))
        if (text.length <= 32) return listOf(Subtitle(startMs, endMs, text, original, lang))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.length + word.length + 1 > 32 && current.isNotEmpty()) {
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
