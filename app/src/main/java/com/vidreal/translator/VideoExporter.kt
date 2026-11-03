package com.vidreal.translator

import android.content.Context
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

    fun export(
        context: Context,
        inputVideo: File,
        subtitles: List<Subtitle>,
        outputDir: File,
        onLog: (String) -> Unit = {},
    ): ExportResult {
        require(inputVideo.isFile && inputVideo.length() > 0) {
            "ملف الفيديو غير موجود أو فارغ. أعد اختيار الفيديو ثم حاول التصدير من جديد."
        }
        require(subtitles.isNotEmpty()) { "لا توجد أسطر كابشن للتصدير." }

        val srtFile = exportSrtOnly(subtitles, outputDir)

        val assFile = File(context.cacheDir, "captions_ar.ass")
        assFile.writeText(SubtitleFormat.toAss(subtitles))

        val fontDir = File(context.cacheDir, "fonts")
        fontDir.mkdirs()
        val fontFile = File(fontDir, "NotoNaskhArabic-Regular.ttf")
        if (!fontFile.exists()) {
            try {
                context.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use { input ->
                    fontFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                throw RuntimeException("خط العربية غير موجود في التطبيق، ولا يمكن دمج الترجمة بدونه.", e)
            }
        }
        require(fontFile.length() > 0) { "خط العربية تالف أو فارغ في التطبيق." }

        // Official FFmpegKit requirement on Android, missing until now:
        // Android has no default fontconfig configuration, so libass
        // cannot find the Arabic font from the filter's fontsdir alone.
        // Registering the font directory (with an explicit family ->
        // file mapping) is what makes the subtitles filter find
        // "Noto Naskh Arabic". A failure here must not abort the
        // export by itself: the filter still has fontsdir as fallback.
        try {
            FFmpegKitConfig.setFontDirectoryList(
                context,
                listOf(fontDir.absolutePath, "/system/fonts"),
                mapOf("Noto Naskh Arabic" to "NotoNaskhArabic-Regular.ttf"),
            )
        } catch (e: Exception) {
            onLog("Font directory registration failed: ${e.message}")
        }

        val videoOut = File(outputDir, "video_with_arabic_subtitles.mp4")
        // fontsdir + force_style tells libass where the Arabic font is.
        // Passed as one filter argument (no shell-style quoting), with
        // an explicit encoder and the veryfast preset: the default
        // medium preset re-encodes slowly on phones and is the most
        // likely reason a long export was killed mid-way.
        // The single quotes here are FFmpeg filter syntax (they protect
        // the commas inside force_style), not shell quoting: with an
        // argument array there is no shell to strip them.
        val filter = "subtitles='${assFile.absolutePath}':fontsdir='${fontDir.absolutePath}':force_style='FontName=Noto Naskh Arabic,FontSize=64,PrimaryColour=&HFFFFFF&,OutlineColour=&H80000000&,BorderStyle=1,Outline=3,Alignment=2,MarginV=140'"
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
        if (!ReturnCode.isSuccess(code)) {
            val logs = try {
                session.allLogsAsString
            } catch (e: Exception) {
                "(could not read FFmpeg logs: ${e.message})"
            }
            val tail = if (logs.length > 2000) logs.takeLast(2000) else logs
            throw RuntimeException("فشل دمج الترجمة (رمز $code). آخر سطور السجل:\n$tail")
        }
        require(videoOut.isFile && videoOut.length() > 0) {
            "انتهى الدمج لكن ملف الفيديو الناتج غير موجود أو فارغ."
        }
        return ExportResult(videoOut, srtFile)
    }
}
