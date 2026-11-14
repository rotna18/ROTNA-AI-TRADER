package com.rotna.aitrader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var keyInput: EditText
    private val captureRequest = 701
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("rotna", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 24); gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(TextView(this).apply { text = "ROTNA AI TRADER"; textSize = 25f; setTextColor(0xFF12345A.toInt()) })
        root.addView(TextView(this).apply { text = "Quotex chart visual helper • Manual trading only"; textSize = 14f; setPadding(0, 8, 0, 22) })
        keyInput = EditText(this).apply { hint = "Gemini API key"; singleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(prefs.getString("gemini_key", "") ?: "") }
        root.addView(keyInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply { text = "API key is stored on this device. Never share it. For a public app release, move API calls to your own secure backend."; textSize = 12f; setPadding(0, 6, 0, 18) })
        root.addView(Button(this).apply { text = "SAVE API KEY"; setOnClickListener { prefs.edit().putString("gemini_key", keyInput.text.toString().trim()).apply(); Toast.makeText(this@MainActivity, "Saved on this device", Toast.LENGTH_SHORT).show() } }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply { text = "START SCREEN ANALYSIS"; setOnClickListener { beginCapture() } }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply { text = "STOP ROTNA"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) } }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply { text = "\nHow to use:\n1. Save your Gemini API key.\n2. Allow display-over-other-apps.\n3. Tap Start and approve Android screen capture.\n4. Open Quotex and keep its chart visible.\n5. Tap the ROTNA floating bubble to analyze.\n\nThe app captures the visible screen only after you tap the bubble. It does not read Quotex account data or place trades."; textSize = 14f })
        setContentView(root)
    }
    private fun beginCapture() {
        if (keyInput.text.toString().trim().isEmpty()) { Toast.makeText(this, "Enter Gemini API key first", Toast.LENGTH_LONG).show(); return }
        getSharedPreferences("rotna", MODE_PRIVATE).edit().putString("gemini_key", keyInput.text.toString().trim()).apply()
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); Toast.makeText(this, "Allow display over other apps, then tap Start again", Toast.LENGTH_LONG).show(); return }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }
    @Deprecated("Android screen-capture consent callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply { putExtra("resultCode", resultCode); putExtra("data", data) }
            startForegroundService(service)
            Toast.makeText(this, "ROTNA bubble started. Open Quotex.", Toast.LENGTH_LONG).show()
        }
    }
}
