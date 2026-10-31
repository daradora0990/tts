package com.antigravity.ttsreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle text selection (ACTION_PROCESS_TEXT) or share sheet (ACTION_SEND)
        if (handleIncomingTextIntent(intent)) {
            return
        }

        buildDashboardUi()
        checkPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingTextIntent(intent)
    }

    private fun handleIncomingTextIntent(intent: Intent?): Boolean {
        if (intent == null) return false

        var capturedText: String? = null
        if (Intent.ACTION_PROCESS_TEXT == intent.action) {
            val readonly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            capturedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        } else if (Intent.ACTION_SEND == intent.action && "text/plain" == intent.type) {
            capturedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }

        if (!capturedText.isNullOrBlank()) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала включите разрешение поверх окон!", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
                return false
            }

            val serviceIntent = Intent(this, FloatingTtsService::class.java).apply {
                action = FloatingTtsService.ACTION_LOAD_TEXT
                putExtra(FloatingTtsService.EXTRA_TEXT, capturedText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Immediately return focus back to the current browser/app
            finish()
            return true
        }
        return false
    }

    private fun checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun buildDashboardUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            setBackgroundColor(0xFF121212.toInt())
        }

        val titleView = TextView(this).apply {
            text = "Word-by-Word Floating TTS"
            textSize = 22f
            setTextColor(0xFFFFD700.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(titleView)

        val descView = TextView(this).apply {
            text = "• Выделите текст в браузере -> Нажмите «Читать в ТТС»\n" +
                    "• Или используйте кнопку [Запустить оверлей] ниже\n" +
                    "• Оверлей можно перемещать и сворачивать в мини-полоску"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 48)
            setLineSpacing(12f, 1f)
        }
        rootLayout.addView(descView)

        val testInput = EditText(this).apply {
            hint = "Введите или вставьте текст для теста..."
            setHintTextColor(0xFF777777.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(32, 24, 32, 24)
            setText("Топографиялық картада, масштабы мен шартты белгілері бар.")
        }
        rootLayout.addView(testInput)

        val btnStart = Button(this).apply {
            text = "▶ Запустить оверлей с текстом"
            setBackgroundColor(0xFF007ACC.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 24, 0, 24)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    requestOverlayPermission()
                    return@setOnClickListener
                }
                val text = testInput.text.toString().trim()
                val serviceIntent = Intent(this@MainActivity, FloatingTtsService::class.java).apply {
                    action = FloatingTtsService.ACTION_LOAD_TEXT
                    putExtra(FloatingTtsService.EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                finish()
            }
        }
        val paramsBtn = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 32 }
        rootLayout.addView(btnStart, paramsBtn)

        val btnStop = Button(this).apply {
            text = "⏹ Закрыть оверлей"
            setBackgroundColor(0xFF2D2D30.toInt())
            setTextColor(0xFFE0E0E0.toInt())
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingTtsService::class.java))
                Toast.makeText(this@MainActivity, "Оверлей закрыт", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(btnStop, paramsBtn)

        setContentView(rootLayout)
    }
}
