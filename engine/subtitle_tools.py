"""
Core subtitle engine, mirrored by the Android app (Subtitle.kt).

Functions:
  - to_srt(subtitles) -> str
  - to_ass(subtitles) -> str  (Arabic font, libass shaping)
  - burn_subtitles(input_video, ass_path, output_video, font_dir)

Subtitle dict format: {"start_ms": int, "end_ms": int, "text": str}
This script is used to test the exact SRT/ASS logic on desktop
before it ships in the Android app.
"""
import subprocess
import sys
from pathlib import Path


def srt_time(ms: int) -> str:
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, rest = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{rest:03d}"


def ass_time(ms: int) -> str:
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, cs = divmod(rem, 1000)
    return f"{h}:{m:02d}:{s:02d}.{cs // 10:02d}"


def to_srt(subtitles) -> str:
    out = []
    for i, sub in enumerate(sorted(subtitles, key=lambda x: x["start_ms"]), 1):
        out.append(str(i))
        out.append(f"{srt_time(sub['start_ms'])} --> {srt_time(sub['end_ms'])}")
        out.append(sub["text"].strip())
        out.append("")
    return "\n".join(out)


def to_ass(subtitles) -> str:
    header = """[Script Info]
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
"""
    lines = [header]
    for sub in sorted(subtitles, key=lambda x: x["start_ms"]):
        safe = sub["text"].strip().replace("\n", "\\N")
        lines.append(
            f"Dialogue: 0,{ass_time(sub['start_ms'])},{ass_time(sub['end_ms'])},Arabic,,0,0,0,,{safe}"
        )
    return "\n".join(lines)


def burn_subtitles(input_video: Path, ass_path: Path, output_video: Path, font_dir: Path):
    filt = (
        f"subtitles='{ass_path}':fontsdir='{font_dir}'"
        ":force_style='FontName=Noto Naskh Arabic,FontSize=64,PrimaryColour=&HFFFFFF&,OutlineColour=&H80000000&,BorderStyle=1,Outline=3,Alignment=2,MarginV=140'"
    )
    cmd = [
        "ffmpeg", "-y", "-i", str(input_video),
        "-vf", filt, "-c:a", "copy", str(output_video),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stderr[-3000:], file=sys.stderr)
        raise SystemExit(f"ffmpeg failed with code {result.returncode}")
    return output_video


if __name__ == "__main__":
    demo = [
        {"start_ms": 0, "end_ms": 4000, "text": "هل كنت تعرف؟ الأخطبوط له ثلاثة قلوب"},
        {"start_ms": 4000, "end_ms": 9000, "text": "قلبان يضخان الدم إلى الخياشيم، وقلب يضخه إلى الجسم"},
    ]
    print(to_srt(demo))
    print("---ASS---")
    print(to_ass(demo))
