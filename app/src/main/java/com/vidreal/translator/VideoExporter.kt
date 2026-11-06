package com.vidreal.translator

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Exports two things, exactly as the user asked:
 *  1. An .srt file with the final Arabic captions.
 *  2. A copy of the video with those captions burned in (hard subtitles),
 *     so the Arabic text shows on Instagram / TikTok / WhatsApp even
 *     when the player has no subtitle support.
 *
 * Burning uses ffmpeg-kit with libass. The ASS file points at the
 * bundled Noto Naskh Arabic font, so Arabic letters join correctly.
 *
 * Crash-safety notes (added after a user-reported export crash):
 *  - The FFmpeg command is passed via executeWithArguments, not as
 *    one parsed string, so quotes/commas in the subtitle filter can
 *    no longer break FFmpegKit's command parser and kill the app.
 *  - Pre-checks fail with a readable message (missing video, empty
 *    captions, missing font) instead of reaching FFmpeg at all.
 *  - FFmpeg failure logs are truncated: the full log can be megabytes
 *    and showing it all in the UI is itself a crash risk.
 *  - [exportSrtOnly] never touches FFmpeg, so the user always has a
 *    working SRT fallback even if burning fails on their device.
 */
object VideoExporter {

    data class ExportResult(val videoFile: File, val srtFile: File)

    fun exportSrtOnly(
        subtitles: List<Subtitle>,
        outputDir: File,
    ): File {
        require(subtitles.isNotEmpty()) { "لا توجد أسطر كابشن للتصدير." }
        outputDir.mkdirs()
        val srtFile = File(outputDir, "captions_ar.srt")
        srtFile.writeText(SubtitleFormat.toSrt(subtitles))
        return srtFile
    }

    /**
     * Real display size of the input video, for the ASS PlayRes (1.11).
     * Rotation is honoured: a 1080x1920 file stored rotated 90 degrees
     * displays as 1920x1080. Any failure falls back to the old 1080x1920,
     * so export never breaks on an unreadable header — it just behaves
     * exactly as 1.10 did.
     */
    private fun readVideoDimensions(inputVideo: File): Pair<Int, Int> {
        val fallback = 1080 to 1920
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputVideo.absolutePath)
            readDimensions(retriever, fallback)
        } catch (_: Exception) {
            fallback
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    /** Same as [readVideoDimensions] but for a content Uri, so the
     *  preview can scale its caption to the real video size (1.13).
     *  Rotation is honoured the same way; any failure falls back to
     *  1080x1920. */
    fun displaySize(context: Context, uri: Uri): Pair<Int, Int> {
        val fallback = 1080 to 1920
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            readDimensions(retriever, fallback)
        } catch (_: Exception) {
            fallback
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun readDimensions(retriever: MediaMetadataRetriever, fallback: Pair<Int, Int>): Pair<Int, Int> {
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w == null || h == null || w <= 0 || h <= 0) return fallback
        return if (rotation == 90 || rotation == 270) h to w else w to h
    }

    fun export(
        context: Context,
        inputVideo: File,
        subtitles: List<Subtitle>,
        outputDir: File,
        onLog: (String) -> Unit = {},
        style: CaptionStyle = CaptionStyle(),
    ): ExportResult {
        require(inputVideo.isFile && inputVideo.length() > 0) {
            "ملف الفيديو غير موجود أو فارغ. أعد اختيار الفيديو ثم حاول التصدير من جديد."
        }
        require(subtitles.isNotEmpty()) { "لا توجد أسطر كابشن للتصدير." }

        val srtFile = exportSrtOnly(subtitles, outputDir)

        // 1.11: write the real video size into the ASS PlayRes instead of
        // the old fixed 1080x1920, so libass no longer shrinks the font
        // on horizontal video (see SubtitleFormat.toAss).
        val (playResX, playResY) = readVideoDimensions(inputVideo)
        onLog("Video size ${playResX}x${playResY} — subtitles sized for it")
        val assFile = File(context.cacheDir, "captions_ar.ass")
        assFile.writeText(SubtitleFormat.toAss(subtitles, style, playResX, playResY))

        val fontDir = File(context.cacheDir, "fonts")
        fontDir.mkdirs()
        // Copy every bundled Arabic font, not just the selected one:
        // fontconfig scans the whole directory, and the user may
        // change fonts between exports without reinstalling.
        for (font in CaptionFonts.ALL) {
            val f = File(fontDir, font.fileName)
            if (!f.exists() || f.length() == 0L) {
                try {
                    context.assets.open("fonts/${font.fileName}").use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                    // A missing extra font is not fatal; the selected
                    // font below is checked strictly.
                }
            }
        }
        val fontFile = File(fontDir, style.font.fileName)
        require(fontFile.exists() && fontFile.length() > 0) {
            "خط ${style.font.displayName} غير موجود في التطبيق، ولا يمكن دمج الترجمة بدونه."
        }

        // Official FFmpegKit requirement on Android, missing until now:
        // Android has no default fontconfig configuration, so libass
        // cannot find the Arabic font from the filter's fontsdir alone.
        // Registering the font directories is what makes the subtitles
        // filter find "Noto Naskh Arabic".
        //
        // CRITICAL, learned from the 1.5 "boxes" bug: do NOT pass a
        // font name map here. FFmpegKitConfig turns each map entry
        // into a fontconfig <match> rule that rewrites the requested
        // family to the map value. We used to map
        // "Noto Naskh Arabic" -> "NotoNaskhArabic-Regular.ttf", but
        // that value is a FILE name, not a family name, so fontconfig
        // rewrote the request to a family that does not exist, libass
        // fell back to a font without Arabic glyphs, and the burned
        // subtitles came out as boxes (reproduced locally: the same
        // ASS + font renders perfect Arabic without that rule, and
        // fails font selection with it). With an empty map, fontconfig
        // scans the registered directories and finds the real family
        // "Noto Naskh Arabic" from the bundled TTF, whose family name
        // was verified to be exactly that.
        // A failure here must not abort the export by itself: the
        // filter still has fontsdir as fallback.
        try {
            FFmpegKitConfig.setFontDirectoryList(
                context,
                listOf(fontDir.absolutePath, "/system/fonts"),
                emptyMap(),
            )
        } catch (e: Exception) {
            onLog("Font directory registration failed: ${e.message}")
        }

        val videoOut = File(outputDir, "video_with_arabic_subtitles.mp4")
        // fontsdir + force_style tells libass where the Arabic font is.
        // Every value here comes from the user's review-screen style,
        // so the exported video matches the preview exactly.
        // The single quotes here are FFmpeg filter syntax (they protect
        // the commas inside force_style), not shell quoting: with an
        // argument array there is no shell to strip them.
        // MarginV here must match the scaled value in the ASS style:
        // force_style overrides the style line, so an unscaled 140 would
        // undo the proportional bottom margin on horizontal video.
        val filter = "subtitles='${assFile.absolutePath}':fontsdir='${fontDir.absolutePath}':force_style='FontName=${style.font.familyName},FontSize=${style.size},PrimaryColour=${style.assColor()},OutlineColour=&H80000000&,BorderStyle=${style.background.assBorderStyle},Outline=3,Alignment=${style.position.assAlignment},MarginV=${SubtitleFormat.scaledMarginV(style, playResY)}'"
        val arguments = arrayOf(
            "-y",
            "-i", inputVideo.absolutePath,
            "-vf", filter,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "23",
            "-c:a", "copy",
            videoOut.absolutePath,
        )
        onLog("Running ffmpeg (veryfast preset)")
        val session = FFmpegKit.executeWithArguments(arguments)
        val code = session.returnCode
        val allLogs = try {
            session.allLogsAsString
        } catch (e: Exception) {
            "(could not read FFmpeg logs: ${e.message})"
        }
        // libass reports which font it actually used in "fontselect"
        // lines. A wrong font is a silent success for FFmpeg (it still
        // writes a video) but a visible failure for the user (boxes),
        // so check it explicitly: if libass selected a font and it is
        // not one of the bundled Arabic fonts, fail loudly with that
        // line instead of delivering a boxes video as if it had worked.
        // 1.13: this used to hardcode Noto Naskh Arabic, so choosing
        // any other font (or a preset that used Sans) always failed
        // export with a confusing message even when libass had found
        // the right font. Now it accepts any bundled family.
        val acceptedFamilies = CaptionFonts.ALL.map {
            it.familyName.replace(" ", "").lowercase()
        }
        val fontLines = allLogs.lines().filter { it.contains("fontselect") }
        val wrongFont = fontLines.firstOrNull { line ->
            line.contains("failed to find") ||
                (line.contains("->") && acceptedFamilies.none { fam ->
                    line.replace(" ", "").lowercase().contains(fam)
                })
        }
        if (!ReturnCode.isSuccess(code)) {
            val tail = if (allLogs.length > 2000) allLogs.takeLast(2000) else allLogs
            val fontInfo = if (fontLines.isNotEmpty()) "\nسطور الخط:\n" + fontLines.joinToString("\n") else ""
            throw RuntimeException("فشل دمج الترجمة (رمز $code). آخر سطور السجل:\n$tail$fontInfo")
        }
        if (wrongFont != null) {
            throw RuntimeException(
                "تم الدمج لكن بخط غير عربي، فستظهر الترجمة مربعات. " +
                    "الخط المختار: $wrongFont. أعد المحاولة بعد حذف بيانات التطبيق، " +
                    "أو أرسل هذه الرسالة للتشخيص."
            )
        }
        require(videoOut.isFile && videoOut.length() > 0) {
            "انتهى الدمج لكن ملف الفيديو الناتج غير موجود أو فارغ."
        }
        return ExportResult(videoOut, srtFile)
    }
}
