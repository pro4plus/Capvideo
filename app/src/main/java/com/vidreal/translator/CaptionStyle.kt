package com.vidreal.translator

import androidx.compose.ui.graphics.Color

/**
 * Everything the user styles in the Capeo review screen, and exactly
 * what gets burned into the exported video. One object travels from
 * the preview overlay to the ASS file, so what you see in the preview
 * is what you get in the saved video.
 */
data class CaptionFont(
    val id: String,
    val displayName: String,
    val familyName: String,
    val fileName: String,
    val sample: String = "مرحباً بالعالم",
)

data class CaptionColor(
    val id: String,
    val displayName: String,
    /** RGB hex, e.g. "FFFFFF". */
    val rgb: String,
    val compose: Color,
)

enum class CaptionPosition(val displayName: String, val assAlignment: Int) {
    BOTTOM("أسفل", 2),
    MIDDLE("وسط", 5),
    TOP("أعلى", 8),
}

enum class CaptionBackground(val displayName: String, val assBorderStyle: Int) {
    OUTLINE("حدود فقط", 1),
    BOX("صندوق شفاف", 3),
}

data class CaptionStyle(
    val font: CaptionFont = CaptionFonts.NASKH,
    /** Font size in script pixels, as used by libass (64 = old default). */
    val size: Int = 64,
    val color: CaptionColor = CaptionColors.WHITE,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val background: CaptionBackground = CaptionBackground.OUTLINE,
) {
    /** ASS colour in &HAABBGGRR form (alpha 00 = opaque). */
    fun assColor(): String {
        val r = rgbPart(0)
        val g = rgbPart(2)
        val b = rgbPart(4)
        return "&H00$b$g$r"
    }

    private fun rgbPart(start: Int): String =
        color.rgb.substring(start, start + 2)

    fun marginV(): Int = when (position) {
        CaptionPosition.BOTTOM -> 140
        CaptionPosition.MIDDLE -> 0
        CaptionPosition.TOP -> 140
    }
}

object CaptionFonts {
    val NASKH = CaptionFont("naskh", "نسخ", "Noto Naskh Arabic", "NotoNaskhArabic-Regular.ttf")
    val SANS = CaptionFont("sans", "سانس", "Noto Sans Arabic", "NotoSansArabic-Regular.ttf")
    val AMIRI = CaptionFont("amiri", "أميري", "Amiri", "Amiri-Regular.ttf")
    val ALL = listOf(NASKH, SANS, AMIRI)
}

object CaptionColors {
    val WHITE = CaptionColor("white", "أبيض", "FFFFFF", Color.White)
    val YELLOW = CaptionColor("yellow", "أصفر", "FFFF00", Color(0xFFFFEB3B))
    val CYAN = CaptionColor("cyan", "سماوي", "00FFFF", Color(0xFF00E5FF))
    val GREEN = CaptionColor("green", "أخضر", "00FF88", Color(0xFF00E676))
    val PINK = CaptionColor("pink", "وردي", "FF66AA", Color(0xFFFF4081))
    val ORANGE = CaptionColor("orange", "برتقالي", "FF8800", Color(0xFFFF9100))
    val ALL = listOf(WHITE, YELLOW, CYAN, GREEN, PINK, ORANGE)
}
