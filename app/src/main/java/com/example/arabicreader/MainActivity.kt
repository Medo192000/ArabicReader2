package com.example.arabicreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var resultText: EditText
    private lateinit var statusText: TextView
    private lateinit var switchOnline: SwitchCompat
    private lateinit var apiKeyInput: EditText

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voices: List<Voice> = emptyList()
    private var voiceIndex = 0
    private var cameraUri: Uri? = null
    private val busy = AtomicBoolean(false)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) process(uri)
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { process(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)
        statusText = findViewById(R.id.statusText)
        switchOnline = findViewById(R.id.switchOnline)
        apiKeyInput = findViewById(R.id.apiKeyInput)

        apiKeyInput.setText(prefs.getString("api_key", ""))
        switchOnline.isChecked = prefs.getBoolean("online", false)
        apiKeyInput.visibility = if (switchOnline.isChecked) View.VISIBLE else View.GONE
        switchOnline.setOnCheckedChangeListener { _, checked ->
            apiKeyInput.visibility = if (checked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("online", checked).apply()
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickFile.launch(arrayOf("image/*", "application/pdf"))
        }
        findViewById<Button>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<Button>(R.id.btnSpeak).setOnClickListener { speakText() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { tts?.stop() }
        findViewById<Button>(R.id.btnVoice).setOnClickListener { changeVoice() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("text", resultText.text.toString()))
            toast("تم النسخ")
        }

        tts = TextToSpeech(this, this)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------- الكاميرا ----------------

    private fun openCamera() {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "photo.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraUri = uri
        takePhoto.launch(uri)
    }

    // ---------------- معالجة الصورة أو الـ PDF ----------------

    private fun process(uri: Uri) {
        if (!busy.compareAndSet(false, true)) {
            toast("استنى لما القراءة الحالية تخلص")
            return
        }
        val online = switchOnline.isChecked
        val key = apiKeyInput.text.toString().trim()
        prefs.edit().putString("api_key", key).apply()
        if (online && key.isEmpty()) {
            toast("اكتب مفتاح OCR.space أو اقفل الأونلاين")
            busy.set(false)
            return
        }
        resultText.setText("")

        Thread {
            var tess: TessBaseAPI? = null
            try {
                if (!online) {
                    setStatus("جاري تجهيز محرك القراءة...")
                    tess = initTess()
                }
                val mime = contentResolver.getType(uri) ?: ""
                val isPdf = mime == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")

                if (isPdf) {
                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("تعذر فتح الملف")
                    val renderer = PdfRenderer(pfd)
                    try {
                        val n = renderer.pageCount
                        for (i in 0 until n) {
                            setStatus("جاري قراءة صفحة ${i + 1} من $n ...")
                            val page = renderer.openPage(i)
                            val bmp: Bitmap
                            try {
                                val scale = 2200f / page.width
                                val w = (page.width * scale).toInt()
                                val h = (page.height * scale).toInt()
                                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            } finally {
                                page.close()
                            }
                            val text = recognize(bmp, tess, key)
                            bmp.recycle()
                            appendText(text)
                        }
                    } finally {
                        renderer.close()
                        pfd.close()
                    }
                } else {
                    setStatus("جاري تحميل الصورة...")
                    val bmp = loadImage(uri)
                    setStatus("جاري قراءة النص...")
                    val text = recognize(bmp, tess, key)
                    bmp.recycle()
                    appendText(text)
                }
                runOnUiThread {
                    val len = resultText.text.length
                    statusText.text = if (len == 0) "ماطلعش نص. جرّب صورة أوضح." else "تمت القراءة ($len حرف)"
                }
            } catch (e: Throwable) {
                setStatus("حصل خطأ: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                try { tess?.recycle() } catch (_: Throwable) {}
                busy.set(false)
            }
        }.start()
    }

    private fun recognize(bmp: Bitmap, tess: TessBaseAPI?, key: String): String {
        return if (tess == null) {
            ocrOnline(bmp, key)
        } else {
            val pre = preprocess(bmp)
            tess.setImage(pre)
            val t = tess.getUTF8Text() ?: ""
            pre.recycle()
            t
        }
    }

    // ---------------- الأوفلاين: Tesseract ----------------

    private fun initTess(): TessBaseAPI {
        val dir = File(filesDir, "ocr")
        val tessdata = File(dir, "tessdata").apply { mkdirs() }
        val f = File(tessdata, "ara.traineddata")
        if (!f.exists() || f.length() == 0L) {
            assets.open("ara.traineddata").use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
        }
        val api = TessBaseAPI()
        if (!api.init(dir.absolutePath, "ara")) {
            api.recycle()
            throw IllegalStateException("تعذر تشغيل محرك القراءة")
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        return api
    }

    private fun loadImage(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxSide = max(info.size.width, info.size.height)
            if (maxSide > 4000) {
                val s = 4000f / maxSide
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
    }

    // تحويل لرمادي + رفع التباين + ضبط الحجم (بيحسّن دقة القراءة)
    private fun preprocess(src: Bitmap): Bitmap {
        val maxSide = max(src.width, src.height)
        val scale = when {
            maxSide < 1500 -> 2f
            maxSide > 3600 -> 3600f / maxSide
            else -> 1f
        }
        val base = if (scale != 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        else src

        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val contrast = 1.4f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, shift,
                    0f, contrast, 0f, 0f, shift,
                    0f, 0f, contrast, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(base, 0f, 0f, paint)
        if (base !== src) base.recycle()
        return out
    }

    // ---------------- الأونلاين: OCR.space ----------------

    private fun ocrOnline(bmp: Bitmap, key: String): String {
        var scale = 1f
        var bytes: ByteArray
        while (true) {
            val b = if (scale == 1f) bmp else Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            )
            val bos = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            if (b !== bmp) b.recycle()
            if (bos.size() <= 1_000_000 || scale < 0.3f) {
                bytes = bos.toByteArray()
                break
            }
            scale *= 0.8f
        }
        val b64 = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = "apikey=" + enc(key) +
            "&language=ara&OCREngine=1&scale=true&isOverlayRequired=false" +
            "&base64Image=" + enc(b64)

        val conn = URL("https://api.ocr.space/parse/image").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 90000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(resp)
        if (json.optBoolean("IsErroredOnProcessing")) {
            val msg = json.optJSONArray("ErrorMessage")?.optString(0) ?: json.optString("ErrorMessage")
            throw IllegalStateException(msg)
        }
        val results = json.optJSONArray("ParsedResults") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until results.length()) {
            sb.append(results.getJSONObject(i).optString("ParsedText")).append("\n")
        }
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- الصوت (TTS) ----------------

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            toast("محرك الصوت مش شغال")
            return
        }
        val r = engine.setLanguage(Locale("ar"))
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        loadVoices()
        if (!ttsReady) {
            statusText.text = "لازم تحمّل صوت عربي (اضغط: تغيير الصوت)"
        }
    }

    private fun loadVoices() {
        val engine = tts ?: return
        val all = try { engine.voices?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() }
        voices = all.filter { it.locale.language == "ar" && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
        if (voices.isEmpty()) return
        val saved = prefs.getString("voice", null)
        val idx = voices.indexOfFirst { it.name == saved }
        val female = voices.indexOfFirst { it.name.lowercase().contains("female") }
        voiceIndex = if (idx >= 0) idx else if (female >= 0) female else 0
        engine.voice = voices[voiceIndex]
        ttsReady = true
    }

    private fun changeVoice() {
        val engine = tts ?: return
        loadVoices()
        if (voices.isEmpty()) {
            toast("مفيش صوت عربي أوفلاين متحمّل. هفتحلك إعدادات الصوت")
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Throwable) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
            return
        }
        voiceIndex = (voiceIndex + 1) % voices.size
        val v = voices[voiceIndex]
        engine.voice = v
        prefs.edit().putString("voice", v.name).apply()
        statusText.text = "الصوت ${voiceIndex + 1} من ${voices.size}"
        engine.stop()
        engine.speak("مرحباً، هذا هو الصوت الحالي. هل يعجبك؟", TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun speakText() {
        val engine = tts ?: return
        val text = resultText.text.toString().trim()
        if (text.isEmpty()) {
            toast("مفيش نص يتقرا")
            return
        }
        if (!ttsReady) {
            toast("لازم تحمّل صوت عربي الأول")
            return
        }
        engine.stop()
        val chunks = splitForSpeech(text, 1500)
        chunks.forEachIndexed { i, c ->
            engine.speak(c, TextToSpeech.QUEUE_ADD, null, "chunk$i")
        }
    }

    private fun splitForSpeech(text: String, limit: Int): List<String> {
        val pieces = text.split(Regex("(?<=[.!؟?\\n])"))
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            var piece = p
            while (piece.length > limit) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                out.add(piece.substring(0, limit))
                piece = piece.substring(limit)
            }
            if (cur.length + piece.length > limit) {
                out.add(cur.toString()); cur.setLength(0)
            }
            cur.append(piece)
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out.filter { it.isNotBlank() }
    }

    // ---------------- أدوات مساعدة ----------------

    private fun setStatus(s: String) = runOnUiThread { statusText.text = s }

    private fun appendText(t: String) = runOnUiThread {
        val clean = t.trim()
        if (clean.isNotEmpty()) resultText.append(clean + "\n\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
