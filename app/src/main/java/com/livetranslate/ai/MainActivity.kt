package com.livetranslate.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.*
import com.google.mlkit.common.model.DownloadConditions
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var ready = false
    private var outputMode = "ar" // ar = Modern Standard Arabic; darija = experimental label only
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var translated: TextView
    private lateinit var startButton: Button
    private lateinit var modeButton: Button

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
        root.addView(text("English (US)  →  Arabic / Darija", 15f, 0xFFB9D8CF.toInt()))
        status = text("جارٍ تجهيز الترجمة…", 14f, 0xFF9CCC65.toInt())
        root.addView(status)
        modeButton = Button(this).apply { text = "لغة الإخراج: العربية الفصحى  •  اضغط للتبديل"; setOnClickListener {
            outputMode = if (outputMode == "ar") "darija" else "ar"
            text = if (outputMode == "ar") "لغة الإخراج: العربية الفصحى  •  اضغط للتبديل" else "لغة الإخراج: الدارجة (تجريبية)  •  اضغط للتبديل"
            Toast.makeText(this@MainActivity, "ملاحظة: نموذج الترجمة الحالي ينتج العربية؛ دعم الدارجة يحتاج طبقة تحويل متخصصة لاحقاً.", Toast.LENGTH_LONG).show()
        } }
        root.addView(modeButton)
        root.addView(text("ما سمعه الهاتف", 14f, 0xFF9CCC65.toInt(), true))
        heard = text("ستظهر الجملة الإنجليزية هنا…", 19f, 0xFFFFFFFF.toInt())
        root.addView(heard)
        root.addView(text("الترجمة", 14f, 0xFF9CCC65.toInt(), true))
        translated = text("ستظهر الترجمة هنا…", 22f, 0xFFFFFFFF.toInt(), true)
        root.addView(translated)
        startButton = Button(this).apply { text = "ابدأ الاستماع"; setOnClickListener { if (listening) stopListening() else startListening() } }
        root.addView(startButton)
        root.addView(text("صِل سماعات Oraimo بالهاتف. سيخرج صوت الترجمة عبر جهاز الصوت المحدد في أندرويد.", 13f, 0xFFB0BEC5.toInt()))
        root.addView(text("الإصدار الأول: محادثة مباشرة. المكالمات والفيديو تحتاج وحدات إضافية وصلاحيات منفصلة.", 12f, 0xFFFFCC80.toInt()))
        setContentView(root)
    }

    private fun prepareTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        translator = Translation.getClient(options)
        status.text = "جارٍ تنزيل نموذج الترجمة العربية المجاني…"
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener { ready = true; status.text = "جاهز. صِل سماعات Oraimo وابدأ." }
            ?.addOnFailureListener { status.text = "تعذر تنزيل نموذج الترجمة. تحقق من الإنترنت." }
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "خدمة التعرف على الكلام غير متاحة على هذا الهاتف."; return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "أستمع الآن… تحدث بالإنجليزية." }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { status.text = "أعالج الجملة…" }
            override fun onError(error: Int) {
                if (listening) { status.text = "إعادة الاتصال بالاستماع…"; window.decorView.postDelayed({ beginRecognition() }, 700) }
            }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!phrase.isNullOrBlank()) translatePhrase(phrase)
                if (listening) window.decorView.postDelayed({ beginRecognition() }, 350)
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
        listening = true; startButton.text = "إيقاف الاستماع"; beginRecognition()
    }

    private fun beginRecognition() {
        if (!listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { }
    }

    private fun translatePhrase(phrase: String) {
        runOnUiThread { heard.text = phrase; status.text = "جارٍ الترجمة…" }
        translator?.translate(phrase)?.addOnSuccessListener { result ->
            runOnUiThread {
                translated.text = result
                status.text = "تمت الترجمة • يمكنك الاستمرار في الحديث"
                tts?.language = Locale("ar")
                tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "live-translation")
            }
        }?.addOnFailureListener { runOnUiThread { status.text = "فشلت الترجمة. تحقق من اتصال الإنترنت." } }
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) { }
        startButton.text = "ابدأ الاستماع"; status.text = "تم إيقاف الاستماع."
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) tts?.language = Locale("ar")
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
