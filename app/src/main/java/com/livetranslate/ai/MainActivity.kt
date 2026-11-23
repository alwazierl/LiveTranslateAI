package com.livetranslate.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private class Lang(val label: String, val ml: String, val tag: String)

    private val arabic = Lang("العربية", TranslateLanguage.ARABIC, "ar")
    private val foreign = listOf(
        Lang("English", TranslateLanguage.ENGLISH, "en-US"),
        Lang("Français", TranslateLanguage.FRENCH, "fr-FR"),
        Lang("Español", TranslateLanguage.SPANISH, "es-ES"),
        Lang("Deutsch", TranslateLanguage.GERMAN, "de-DE"),
        Lang("Türkçe", TranslateLanguage.TURKISH, "tr-TR"),
        Lang("Italiano", TranslateLanguage.ITALIAN, "it-IT")
    )
    private var foreignIndex = 0
    private var arabicToForeign = false // false: اللغة الأخرى -> العربية ، true: العربية -> اللغة الأخرى

    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var ready = false
    private var busy = false

    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var translated: TextView
    private lateinit var startButton: Button
    private lateinit var directionButton: Button
    private lateinit var langButton: Button

    private fun srcLang(): Lang = if (arabicToForeign) arabic else foreign[foreignIndex]
    private fun dstLang(): Lang = if (arabicToForeign) foreign[foreignIndex] else arabic

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this, this)
        prepareTranslator()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 20)
        } else setupRecognizer()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            gravity = Gravity.TOP
            setBackgroundColor(0xFF101B19.toInt())
        }
        fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }
        root.addView(text("LIVE TRANSLATE AI", 24f, 0xFFFFFFFF.toInt(), true))
        subtitle = text("", 15f, 0xFFB9D8CF.toInt())
        root.addView(subtitle)
        status = text("جارٍ تجهيز الترجمة…", 14f, 0xFF9CCC65.toInt())
        root.addView(status)

        directionButton = Button(this)
        directionButton.setOnClickListener {
            stopListening()
            arabicToForeign = !arabicToForeign
            refreshLabels()
            prepareTranslator()
        }
        root.addView(directionButton)

        langButton = Button(this)
        langButton.setOnClickListener {
            stopListening()
            foreignIndex = (foreignIndex + 1) % foreign.size
            refreshLabels()
            prepareTranslator()
        }
        root.addView(langButton)

        root.addView(text("ما سمعه الهاتف", 14f, 0xFF9CCC65.toInt(), true))
        heard = text("ستظهر الجملة المسموعة هنا…", 19f, 0xFFFFFFFF.toInt())
        root.addView(heard)
        root.addView(text("الترجمة", 14f, 0xFF9CCC65.toInt(), true))
        translated = text("ستظهر الترجمة هنا…", 22f, 0xFFFFFFFF.toInt(), true)
        root.addView(translated)

        startButton = Button(this)
        startButton.text = "ابدأ الاستماع"
        startButton.setOnClickListener { if (listening) stopListening() else startListening() }
        root.addView(startButton)

        root.addView(text("صِل السماعات بالهاتف. سيخرج صوت الترجمة عبر جهاز الصوت المحدد في أندرويد.", 13f, 0xFFB0BEC5.toInt()))
        root.addView(text("اختر الاتجاه واللغة الأخرى بالأزرار أعلاه، ثم ابدأ الاستماع.", 12f, 0xFFFFCC80.toInt()))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF101B19.toInt())
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)
        refreshLabels()
    }

    private fun refreshLabels() {
        val other = foreign[foreignIndex].label
        if (arabicToForeign) {
            subtitle.text = "العربية  →  $other"
            directionButton.text = "الاتجاه: أنا أتكلم بالعربية ← $other  •  اضغط للتبديل"
        } else {
            subtitle.text = "$other  →  العربية"
            directionButton.text = "الاتجاه: أسمع $other ← العربية  •  اضغط للتبديل"
        }
        langButton.text = "اللغة الأخرى: $other  •  اضغط للتغيير"
    }

    private fun prepareTranslator() {
        ready = false
        translator?.close()
        val src = srcLang()
        val dst = dstLang()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src.ml)
            .setTargetLanguage(dst.ml)
            .build()
        val t = Translation.getClient(options)
        translator = t
        status.text = "جارٍ تنزيل نموذج الترجمة (${src.label} ← ${dst.label})…"
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (translator === t) {
                    ready = true
                    status.text = "جاهز. اضغط ابدأ الاستماع."
                }
            }
            .addOnFailureListener {
                if (translator === t) {
                    status.text = "تعذر تنزيل نموذج الترجمة. تحقق من الإنترنت."
                }
            }
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "خدمة التعرف على الكلام غير متاحة على هذا الهاتف."; return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "أستمع الآن… تحدث بـ ${srcLang().label}." }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { status.text = "أعالج الجملة…" }
            override fun onError(error: Int) {
                if (listening && !busy) {
                    status.text = "إعادة الاتصال بالاستماع…"
                    window.decorView.postDelayed({ beginRecognition() }, 700)
                }
            }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!phrase.isNullOrBlank()) {
                    busy = true
                    translatePhrase(phrase)
                } else if (listening) {
                    window.decorView.postDelayed({ beginRecognition() }, 350)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
    }

    private fun startListening() {
        if (!ready) { Toast.makeText(this, "انتظر حتى يكتمل تنزيل نموذج الترجمة", Toast.LENGTH_SHORT).show(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 20); return
        }
        if (recognizer == null) setupRecognizer()
        listening = true
        busy = false
        startButton.text = "إيقاف الاستماع"
        beginRecognition()
    }

    private fun beginRecognition() {
        if (!listening || busy) return
        val tag = srcLang().tag
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { }
    }

    private fun translatePhrase(phrase: String) {
        heard.text = phrase
        status.text = "جارٍ الترجمة…"
        val t = translator
        if (t == null) { finishCycle(); return }
        t.translate(phrase)
            .addOnSuccessListener { result ->
                translated.text = result
                status.text = "جارٍ النطق…"
                speak(result)
            }
            .addOnFailureListener {
                status.text = "فشلت الترجمة. تحقق من الإنترنت."
                finishCycle()
            }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) { finishCycle(); return }
        val dst = dstLang()
        val r = engine.setLanguage(Locale.forLanguageTag(dst.tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.text = "صوت اللغة ${dst.label} غير مثبت في الهاتف. ثبّته من إعدادات تحويل النص إلى كلام."
            finishCycle()
            return
        }
        val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "live-translation")
        if (code == TextToSpeech.ERROR) finishCycle()
    }

    private fun finishCycle() {
        busy = false
        if (listening) {
            status.text = "تمت الترجمة • يمكنك الاستمرار في الحديث"
            window.decorView.postDelayed({ beginRecognition() }, 300)
        }
    }

    private fun stopListening() {
        listening = false
        busy = false
        tts?.stop()
        try { recognizer?.cancel() } catch (_: Exception) { }
        startButton.text = "ابدأ الاستماع"
        status.text = "تم إيقاف الاستماع."
    }

    override fun onInit(result: Int) {
        ttsReady = result == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { }
                override fun onDone(utteranceId: String?) { runOnUiThread { finishCycle() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { runOnUiThread { finishCycle() } }
            })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) setupRecognizer()
        else status.text = "يجب السماح باستخدام الميكروفون لتشغيل الترجمة."
    }

    override fun onDestroy() {
        listening = false
        recognizer?.destroy(); recognizer = null
        translator?.close(); translator = null
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }
}
