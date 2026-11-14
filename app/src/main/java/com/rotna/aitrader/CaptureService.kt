package com.rotna.aitrader

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.util.Base64

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var bubble: TextView? = null
    private var resultView: TextView? = null
    private var windowManager: WindowManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        if (code == Activity.RESULT_OK && data != null && projection == null) startProjection(code, data)
        return START_NOT_STICKY
    }
    private fun createNotification() {
        val id = "rotna_capture"; val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(id, "ROTNA screen analysis", NotificationManager.IMPORTANCE_LOW))
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, id).setContentTitle("ROTNA is ready").setContentText("Tap floating ROTNA bubble to analyze visible chart.").setSmallIcon(android.R.drawable.ic_menu_search).build() else Notification.Builder(this).setContentTitle("ROTNA is ready").setContentText("Tap floating bubble to analyze").setSmallIcon(android.R.drawable.ic_menu_search).build()
        startForeground(41, n)
    }
    private fun startProjection(code: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        val metrics = DisplayMetrics(); @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
        display = projection?.createVirtualDisplay("ROTNA-Chart-Capture", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        showBubble()
    }
    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        bubble = TextView(this).apply { text = "ROTNA\nSCAN"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(18, 12, 18, 12); setBackgroundColor(0xFF12345A.toInt()); setOnClickListener { captureAndAnalyze() } }
        val p = WindowManager.LayoutParams(-2, -2, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 220 }
        windowManager?.addView(bubble, p)
        resultView = TextView(this).apply { text = "ROTNA: READY"; textSize = 14f; setTextColor(Color.WHITE); setPadding(16, 12, 16, 12); setBackgroundColor(0xDD202020.toInt()) }
        val rp = WindowManager.LayoutParams(-2, -2, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 290 }
        windowManager?.addView(resultView, rp)
    }
    private fun captureAndAnalyze() {
        if (busy) return
        busy = true; setResult("ROTNA: CAPTURING…")
        handler.postDelayed({
            try {
                val img = reader?.acquireLatestImage() ?: throw Exception("Screen not ready")
                val pl = img.planes[0]; val buf = pl.buffer; val px = pl.pixelStride; val rowPad = pl.rowStride - px * img.width
                val padded = Bitmap.createBitmap(img.width + rowPad / px, img.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buf); img.close()
                val crop = Bitmap.createBitmap(padded, 0, 0, img.width, img.height); padded.recycle()
                val ratio = minOf(1280f / crop.width, 1f)
                val scaled = Bitmap.createScaledBitmap(crop, (crop.width * ratio).toInt().coerceAtLeast(1), (crop.height * ratio).toInt().coerceAtLeast(1), true); crop.recycle()
                executor.execute { callGemini(scaled) }
            } catch (e: Exception) { busy = false; setResult("WAIT • ${e.message ?: "capture error"}") }
        }, 450)
    }
    private fun callGemini(bitmap: Bitmap) {
        var conn: HttpURLConnection? = null
        try {
            val key = getSharedPreferences("rotna", Context.MODE_PRIVATE).getString("gemini_key", "") ?: ""
            if (key.isBlank()) throw Exception("API key missing")
            val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 78, bytes); bitmap.recycle()
            val b64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            val prompt = """You are ROTNA, a cautious visual chart-reading assistant, not a financial adviser. Look only at the visible trading chart. Ignore unrelated UI. If pair, timeframe, candles or direction are unclear, return WAIT. Do not invent RSI/EMA values if not visibly shown. Assess visible candle structure, trend, momentum, support/resistance and visible indicators. A screenshot cannot establish a reliable probability or guarantee a winning trade. Return exactly: SIGNAL: UP or DOWN or WAIT; REASON: one short sentence; CONFIDENCE: LOW, MEDIUM, or HIGH. If evidence conflicts, candle is forming, chart is obscured, or setup is weak, use WAIT. Informational only; never instruct automatic order placement."""
            val parts = org.json.JSONArray().put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))).put(JSONObject().put("text", prompt))
            val body = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 160))
            conn = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 30000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode; val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream.bufferedReader().use { it.readText() }; if (code !in 200..299) throw Exception("Gemini API error $code: ${raw.take(180)}")
            val answer = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
            val signal = Regex("SIGNAL\\s*:\\s*(UP|DOWN|WAIT)", RegexOption.IGNORE_CASE).find(answer)?.groupValues?.get(1)?.uppercase() ?: "WAIT"
            val reason = Regex("REASON\\s*:\\s*(.*)", RegexOption.IGNORE_CASE).find(answer)?.groupValues?.get(1)?.trim() ?: "Signal not clear"
            val conf = Regex("CONFIDENCE\\s*:\\s*(LOW|MEDIUM|HIGH)", RegexOption.IGNORE_CASE).find(answer)?.groupValues?.get(1)?.uppercase() ?: "LOW"
            setResult("$signal • $conf\n$reason")
        } catch (e: Exception) { setResult("WAIT • ${e.message ?: "analysis failed"}") }
        finally { conn?.disconnect(); busy = false }
    }
    private fun setResult(value: String) { handler.post { resultView?.text = value } }
    override fun onDestroy() {
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { resultView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        display?.release(); reader?.close(); projection?.stop(); executor.shutdownNow(); super.onDestroy()
    }
}
