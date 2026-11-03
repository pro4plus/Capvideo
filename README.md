# مترجم الفيديو — Video Translator (Android)

تطبيق أندرويد: ارفع فيديو بأي لغة، فيحوّله إلى كابشن عربي.
إذا كان الفيديو عربياً أصلاً، يخرج كابشن عربي مباشرة.
المخرجات المطلوبة، الاثنان معاً:
1. **فيديو مدمج** — الترجمة محروقة داخل الفيديو نفسه، فتظهر في إنستغرام وتيك توك وواتساب حتى بدون دعم الترجمة.
2. **ملف SRT** — `captions_ar.srt`، يصلح لأي محرر أو منصة.

## كيف يعمل؟

1. **اختر فيديو** من جوالك.
2. **استخراج الصوت** — يحوّل صوت الفيديو إلى WAV بمعدل 16kHz أحادي (AudioExtractor.kt، بمكتبات أندرويد فقط).
3. **التفريغ الصوتي (offline)** — نموذج Vosk على الجهاز، يرجع الكلام مع توقيت كل كلمة، ثم تُجمع الكلمات في جمل قابلة للقراءة.
4. **كشف اللغة والترجمة (offline)** — ML Kit من Google: يكشف اللغة، وإذا لم تكن عربية يترجمها إلى العربية. لا يحتاج مفتاح API ولا خادم. أول ترجمة للغة ما تنزّل نموذجها (بضعة ميجابايت)، ثم تعمل بدون إنترنت.
5. **مراجعة وتعديل** — عدّل أي سطر قبل التصدير. هذه الخطوة هي ضمان الدقة.
6. **تصدير** — ملف SRT + فيديو مدمج بخط Noto Naskh Arabic، عبر FFmpeg/libass، فتتصل حروف العربية بشكل صحيح.

## البناء عبر GitHub Actions (للحصول على APK)

عند دفع المشروع إلى مستودع GitHub، يشغّل ملف `.github/workflows/android.yml` بناءً تلقائياً:
يحمّل خط Noto Naskh Arabic وقت البناء (الخط غير مخزّن في المستودع)، ثم يبني `assembleDebug` ويرفع `app-debug.apk` كـ Artifact باسم `VideoTranslator-debug-apk` من صفحة Actions في المستودع.

## التشغيل في Android Studio (أسرع طريقة للحصول على APK)

1. افتح مجلد `video-translator-app` في Android Studio.
2. انتظر تحميل Gradle، ثم اضغط Run على جوال أندرويد حقيقي أو محاكي.
3. ستحصل على تطبيق "مترجم الفيديو" على جوالك.

## خطوة واحدة للتفريغ التلقائي: نموذج Vosk

التطبيق يشتغل كاملاً للكتابة اليدوية والتصدير بدون هذه الخطوة.
للتفريغ التلقائي من الصوت، أضف مرة واحدة:

1. حمّل ملف Vosk Android AAR من: https://github.com/alphacep/vosk-android
   وضعه في `app/libs/`، ثم أزل التعليق عن سطر `implementation(files("libs/vosk-android.aar"))` في `app/build.gradle.kts`.
2. حمّل النموذج الصغير للغة التي تريدها من https://alphacephei.com/vosk/models/، مثلاً:
   - إنجليزي: `vosk-model-small-en-us-0.15.zip`
   - عربي: `vosk-model-ar-mgb2-0.4.zip`
3. فك الضغط، وانقل المجلد إلى مجلد ملفات التطبيق `models/en/` أو `models/ar/` (التطبيق يقرأ من `filesDir/models/<lang>/`).

الكود في `VoskTranscriber.kt` يحمّل Vosk بالانعكاس (reflection)، لذلك المشروع يُبنى حتى بدون ملف AAR، ويظهر التفريغ التلقائي بمجرد إضافته.

## الملفات المهمة

- `app/src/main/java/com/vidreal/translator/MainActivity.kt` — واجهة التطبيق (Compose، عربية RTL).
- `app/src/main/java/com/vidreal/translator/Subtitle.kt` — بيانات الكابشن + توليد SRT و ASS.
- `app/src/main/java/com/vidreal/translator/AudioExtractor.kt` — استخراج الصوت إلى WAV.
- `app/src/main/java/com/vidreal/translator/VoskTranscriber.kt` — التفريغ الصوتي.
- `app/src/main/java/com/vidreal/translator/Translator.kt` — كشف اللغة والترجمة بـ ML Kit.
- `app/src/main/java/com/vidreal/translator/VideoExporter.kt` — تصدير الفيديو المدمج + SRT.
- `app/src/main/assets/fonts/NotoNaskhArabic-Regular.ttf` — خط العربية للترجمة المدمجة (تم اختياره بعد اختبار، لأن Noto Sans Arabic أسقط حرف "ث" في وسط الكلمة تحت libass، بينما Naskh يعرضه صحيحاً).
- `engine/subtitle_tools.py` — نفس منطق SRT/ASS، للاختبار على الكمبيوتر.
- `demo/` — فيديو تجريبي مدمج + ملف SRT مولّد بنفس المحرك، للتحقق من جودة الخط والتوقيت.

## ملاحظات صراحة

- دقة التفريغ والترجمة تعتمد على وضوح الصوت. لذلك التطبيق يجبرك على مراجعة النص قبل التصدير.
- الترجمة الآلية قد تخطئ في المصطلحات النادرة أو اللهجات الثقيلة؛ المراجعة اليدوية هي الحل، وليست خطوة اختيارية.
- FFmpeg-kit مكتبة كبيرة، فأول بناء قد يأخذ وقتاً أطول من المعتاد.
