package com.vidreal.translator

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
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
 */
object VideoExporter {

    data class ExportResult(val videoFile: File, val srtFile: File)

    fun export(
        context: Context,
        inputVideo: File,
        subtitles: List<Subtitle>,
        outputDir: File,
        onLog: (String) -> Unit = {},
    ): ExportResult {
        outputDir.mkdirs()
        val srtFile = File(outputDir, "captions_ar.srt")
        srtFile.writeText(SubtitleFormat.toSrt(subtitles))

        val assFile = File(context.cacheDir, "captions_ar.ass")
        assFile.writeText(SubtitleFormat.toAss(subtitles))

        val fontDir = File(context.cacheDir, "fonts")
        fontDir.mkdirs()
        val fontFile = File(fontDir, "NotoNaskhArabic-Regular.ttf")
        if (!fontFile.exists()) {
            context.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use { input ->
                fontFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val videoOut = File(outputDir, "video_with_arabic_subtitles.mp4")
        // fontsdir + force_style tells libass where the Arabic font is.
        val filter = "subtitles='${assFile.absolutePath}':fontsdir='${fontDir.absolutePath}':force_style='FontName=Noto Naskh Arabic,FontSize=64,PrimaryColour=&HFFFFFF&,OutlineColour=&H80000000&,BorderStyle=1,Outline=3,Alignment=2,MarginV=140'"
        val command = "-y -i \"${inputVideo.absolutePath}\" -vf \"$filter\" -c:a copy \"${videoOut.absolutePath}\""
        onLog("Running ffmpeg: $command")
        val session = FFmpegKit.execute(command)
        val code = session.returnCode
        if (!ReturnCode.isSuccess(code)) {
            val logs = session.allLogsAsString
            throw RuntimeException("FFmpeg failed (code $code). Logs: $logs")
        }
        return ExportResult(videoOut, srtFile)
    }
}
