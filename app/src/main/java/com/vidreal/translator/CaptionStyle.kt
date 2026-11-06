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

/**
 * Caption presets, from the public Captions feature page studied in
 * Clean Room with craft-anything (see captions-clean-room-study-v1.12.md):
 * "Choose from styles like animated word-by-word captions, highlights or
 * bold stacks. Or, build your own style to match custom fonts and colors."
 * Each preset is one tap that sets font, colour, background and timing
 * mode together; the user can still tweak every control afterwards.
 * Preset names are generic style types, not Captions' trademarked
 * template names (Cove, Energy, ...), and no Captions font or asset
 * is used: Capeo keeps its own bundled Arabic fonts.
 */
enum class CaptionPreset(val displayName: String, val description: String) {
    SPOTLIGHT("Spotlight", "كلمة بكلمة، الكلمة الحالية صفراء والباقي أبيض."),
    IMPACT("Impact", "صندوق أسود وأحرف كبيرة، للعناوين القوية."),
    KARAOKE("Karaoke", "السطر كاملاً ظاهر، والكلمات تُضاء مع الصوت."),
    MINIMAL("Minimal", "سطر كامل هادئ بلا تأثيرات."),
}

data class CaptionStyle(
    val font: CaptionFont = CaptionFonts.NASKH,
    /** Font size in script pixels, as used by libass (64 = old default). */
    val size: Int = 64,
    val color: CaptionColor = CaptionColors.WHITE,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val background: CaptionBackground = CaptionBackground.OUTLINE,
    /** Captions-AI mode: words appear one by one as spoken, with the
     *  current word highlighted. When false, whole lines appear at once. */
    val wordByWord: Boolean = true,
    /** The preset these settings came from; manual tweaks keep it as
     *  a starting point, they do not clear it. */
    val preset: CaptionPreset = CaptionPreset.SPOTLIGHT,
    /** IMPACT: render the caption in upper case (Latin letters only;
     *  Arabic has no case, so Arabic text is unchanged). */
    val uppercase: Boolean = false,
    /** KARAOKE: one dialogue per line with ASS \k tags, so the whole
     *  line is visible and words light up as they are spoken, instead
     *  of popping in one by one. */
    val karaoke: Boolean = false,
) {
    /** One tap: return the style this preset stands for. Size and
     *  position are left as the user set them, so applying a preset
     *  never jumps the font size or moves the caption. */
    fun withPreset(preset: CaptionPreset): CaptionStyle = when (preset) {
        // 1.13: presets use NASKH, not SANS. Noto Sans Arabic was
        // verified twice (Nov 3 and Nov 6, two file versions, bold and
        // non-bold) to drop Arabic glyphs as tofu boxes under libass,
        // and the 1.12 presets using it made every preset export fail
        // the font check. Naskh bold is the safe Arabic look; Amiri is
        // the second verified font, selectable in the font picker.
        CaptionPreset.SPOTLIGHT -> copy(
            preset = preset, font = CaptionFonts.NASKH,
            color = CaptionColors.YELLOW, background = CaptionBackground.OUTLINE,
            wordByWord = true, uppercase = false, karaoke = false,
        )
        CaptionPreset.IMPACT -> copy(
            preset = preset, font = CaptionFonts.NASKH,
            color = CaptionColors.YELLOW, background = CaptionBackground.BOX,
            wordByWord = true, uppercase = true, karaoke = false,
        )
        CaptionPreset.KARAOKE -> copy(
            preset = preset, font = CaptionFonts.NASKH,
            color = CaptionColors.YELLOW, background = CaptionBackground.OUTLINE,
            wordByWord = true, uppercase = false, karaoke = true,
        )
        CaptionPreset.MINIMAL -> copy(
            preset = preset, font = CaptionFonts.NASKH,
            color = CaptionColors.WHITE, background = CaptionBackground.OUTLINE,
            wordByWord = false, uppercase = false, karaoke = false,
        )
    }
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
    val AMIRI = CaptionFont("amiri", "أميري", "Amiri", "Amiri-Regular.ttf")
    // 1.13: SANS was removed from the picker on purpose. Noto Sans
    // Arabic renders fine in Android's preview but drops Arabic glyphs
    // as tofu boxes when libass burns it into the video (verified
    // twice locally, and Almarai/Tajawal fail the same way). Offering
    // it meant preview and export disagreed. Only fonts verified to
    // render Arabic correctly under libass are offered: Naskh and
    // Amiri (Amiri verified 2026-11-06, frame test_Amiri_frame7s.png).
    val ALL = listOf(NASKH, AMIRI)
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
