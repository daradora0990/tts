package com.antigravity.ttsreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatingTtsService : Service() {

    companion object {
        const val ACTION_LOAD_TEXT = "com.antigravity.ttsreader.ACTION_LOAD_TEXT"
        const val EXTRA_TEXT = "extra_text"
        private const val CHANNEL_ID = "tts_floating_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    }

    data class Chunk(val start: Int, val end: Int, val text: String)

    // Android System Services
    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null

    // Coroutine Scope & Network
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val prefetchSemaphore = Semaphore(4)
    private val inProgressDownloads = ConcurrentHashMap.newKeySet<String>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // App State
    private var rawText: String = ""
    private var chunks: List<Chunk> = emptyList()
    private var currentIndex: Int = 0
    private var chunkSizeWords: Int = 2 // 1, 2, or 3
    private var voiceId: String = "kk-KZ-AigulNeural" // Default Kazakh
    private var speakPunctuation: Boolean = true
    private var punctLanguage: String = "KZ" // "KZ" or "RU"
    private var isExpanded: Boolean = true

    // UI Elements
    private lateinit var rootContainer: FrameLayout
    private lateinit var expandedView: LinearLayout
    private lateinit var minimizedView: LinearLayout

    // Expanded View components
    private lateinit var chunkTextView: TextView
    private lateinit var counterTextView: TextView
    private lateinit var btnChunkSize: TextView
    private lateinit var btnVoiceToggle: TextView
    private lateinit var btnPunctToggle: TextView

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FloatingTtsService:Playback")

        startForegroundNotification()
        initOverlayViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOAD_TEXT) {
            val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
            if (text.isNotBlank()) {
                loadNewText(text)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseMediaPlayer()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (::rootContainer.isInitialized) {
            windowManager.removeView(rootContainer)
        }
    }

    // ---------------------------------------------------------------------
    // FOREGROUND NOTIFICATION
    // ---------------------------------------------------------------------
    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Активный виджет плавающего озвучивания текста"
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TTS Плавающий Оверлей")
            .setContentText("Нажмите для открытия настроек читалки")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------
    // OVERLAY VIEWS & TOUCH DRAG ARCHITECTURE
    // ---------------------------------------------------------------------
    private fun initOverlayViews() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 260
        }

        rootContainer = FrameLayout(this)
        buildExpandedUi()
        buildMinimizedUi()

        rootContainer.addView(expandedView)
        rootContainer.addView(minimizedView)

        setExpandedState(true)
        windowManager.addView(rootContainer, windowParams)
        setupDragListener()
    }

    private fun setExpandedState(expanded: Boolean) {
        isExpanded = expanded
        if (expanded) {
            expandedView.visibility = View.VISIBLE
            minimizedView.visibility = View.GONE
        } else {
            expandedView.visibility = View.GONE
            minimizedView.visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun makeCardBackground(radiusDp: Float, bgColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp,
                resources.displayMetrics
            )
            setColor(bgColor)
            setStroke(dp(1), 0x44FFFFFF)
        }
    }

    // --- State 1: Expanded Mini-Window (~260dp x 160dp) ---
    private fun buildExpandedUi() {
        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBackground(16f, 0xF0181818.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(270), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragHandle = TextView(this).apply {
            text = "⠿"
            textSize = 18f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, dp(6), 0)
        }
        topBar.addView(dragHandle)

        btnChunkSize = createSmallBadge("${chunkSizeWords}w") {
            chunkSizeWords = if (chunkSizeWords >= 3) 1 else chunkSizeWords + 1
            btnChunkSize.text = "${chunkSizeWords}w"
            rebuildChunks()
        }
        topBar.addView(btnChunkSize)

        btnVoiceToggle = createSmallBadge(if (voiceId.startsWith("kk")) "KZ" else "RU") {
            voiceId = if (voiceId.startsWith("kk")) "ru-RU-SvetlanaNeural" else "kk-KZ-AigulNeural"
            btnVoiceToggle.text = if (voiceId.startsWith("kk")) "KZ" else "RU"
            // Re-trigger synthesis for active chunk
            speakCurrentChunk()
        }
        topBar.addView(btnVoiceToggle)

        val btnPaste = createSmallBadge("📥 Вставить") {
            readClipboardAndLoad()
        }
        topBar.addView(btnPaste)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer)

        val btnMinimize = createSmallIcon("—") {
            setExpandedState(false)
        }
        topBar.addView(btnMinimize)

        val btnClose = createSmallIcon("✕") {
            stopSelf()
        }
        topBar.addView(btnClose)

        expandedView.addView(topBar)

        // Center Area: Text & Counter
        val centerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }

        counterTextView = TextView(this).apply {
            text = "0 / 0"
            textSize = 11f
            setTextColor(0xFF9CDCFE.toInt())
            gravity = Gravity.CENTER
        }
        centerCard.addView(counterTextView)

        chunkTextView = TextView(this).apply {
            text = "Выделите текст или нажмите 📥"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFD700.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        centerCard.addView(chunkTextView)

        expandedView.addView(centerCard)

        // Bottom bar: Controls
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        val btnPrev = createControlButton("⏮") { previousChunk() }
        val btnRepeat = createControlButton("🔄") { repeatCurrentChunk() }
        val btnNext = createControlButton("⏭") { nextChunk() }

        btnPunctToggle = createSmallBadge(if (speakPunctuation) "Знаки: ВКЛ" else "Знаки: ВЫКЛ") {
            speakPunctuation = !speakPunctuation
            btnPunctToggle.text = if (speakPunctuation) "Знаки: ВКЛ" else "Знаки: ВЫКЛ"
            speakCurrentChunk()
        }

        bottomBar.addView(btnPrev)
        bottomBar.addView(btnRepeat)
        bottomBar.addView(btnNext)
        bottomBar.addView(btnPunctToggle)

        expandedView.addView(bottomBar)
    }

    // --- State 2: Minimized Micro-Pill (~160dp x 44dp) ---
    private fun buildMinimizedUi() {
        minimizedView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeCardBackground(22f, 0xEE1A1A1A.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(168), dp(44))
        }

        val pillPrev = createPillButton("⏮") { previousChunk() }
        val pillRepeat = createPillButton("🔄") { repeatCurrentChunk() }
        val pillNext = createPillButton("⏭") { nextChunk() }
        val pillExpand = createPillButton("⤢") { setExpandedState(true) }

        minimizedView.addView(pillPrev)
        minimizedView.addView(pillRepeat)
        minimizedView.addView(pillNext)
        minimizedView.addView(pillExpand)
    }

    private fun createSmallBadge(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            background = makeCardBackground(6f, 0xFF2D2D30.toInt())
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5) }
            setOnClickListener { onClick() }
        }
    }

    private fun createSmallIcon(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { onClick() }
        }
    }

    private fun createControlButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            background = makeCardBackground(8f, 0xFF2D2D30.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun createPillButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    // --- Touch Dragging & Smooth Edge Snapping ---
    private fun setupDragListener() {
        val touchListener = object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams.x
                        initialY = windowParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return false // allow child buttons to receive click
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            windowParams.x = (initialX + dx).toInt()
                            windowParams.y = (initialY + dy).toInt()
                            windowManager.updateViewLayout(rootContainer, windowParams)
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            val metrics = resources.displayMetrics
                            val screenWidth = metrics.widthPixels
                            val viewWidth = rootContainer.width
                            val snapX = if (windowParams.x + viewWidth / 2 < screenWidth / 2) {
                                dp(10)
                            } else {
                                screenWidth - viewWidth - dp(10)
                            }
                            windowParams.x = snapX
                            windowManager.updateViewLayout(rootContainer, windowParams)
                            return true
                        }
                    }
                }
                return false
            }
        }

        rootContainer.setOnTouchListener(touchListener)
    }

    // ---------------------------------------------------------------------
    // TEXT CAPTURE: CLIPBOARD PASTE METHOD
    // ---------------------------------------------------------------------
    private fun readClipboardAndLoad() {
        try {
            // Temporarily remove FLAG_NOT_FOCUSABLE to allow clipboard reading on Android 10+
            windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(rootContainer, windowParams)

            rootContainer.postDelayed({
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
                        if (text.isNotEmpty()) {
                            loadNewText(text)
                            Toast.makeText(this, "Текст вставлен!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore clipboard security exceptions
                } finally {
                    // Restore FLAG_NOT_FOCUSABLE
                    windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    windowManager.updateViewLayout(rootContainer, windowParams)
                }
            }, 80)
        } catch (e: Exception) {
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(rootContainer, windowParams)
        }
    }

    // ---------------------------------------------------------------------
    // SMART CHUNKING & TEXT PROCESSING
    // ---------------------------------------------------------------------
    private fun loadNewText(text: String) {
        rawText = text.trim()
        rebuildChunks()
        currentIndex = 0
        updateUiForCurrentChunk()
        speakCurrentChunk()
        prefetchUpcomingChunks(0, 8)
    }

    private fun rebuildChunks() {
        if (rawText.isBlank()) {
            chunks = emptyList()
            return
        }

        val wordRegex = Regex("\\S+")
        val tokens = wordRegex.findAll(rawText).map { match ->
            Chunk(match.range.first, match.range.last + 1, match.value)
        }.toList()

        val sentenceBoundary = Regex("""[.!?…;:]+["»')\]]*$""")
        val result = mutableListOf<Chunk>()
        val currTokens = mutableListOf<Chunk>()

        for (token in tokens) {
            currTokens.add(token)
            val isSentenceEnd = sentenceBoundary.containsMatchIn(token.text)
            if (currTokens.size >= chunkSizeWords || isSentenceEnd) {
                val startChar = currTokens.first().start
                val endChar = currTokens.last().end
                result.add(Chunk(startChar, endChar, rawText.substring(startChar, endChar)))
                currTokens.clear()
            }
        }

        if (currTokens.isNotEmpty()) {
            val startChar = currTokens.first().start
            val endChar = currTokens.last().end
            result.add(Chunk(startChar, endChar, rawText.substring(startChar, endChar)))
        }

        chunks = result
        if (currentIndex >= chunks.size) {
            currentIndex = (chunks.size - 1).coerceAtLeast(0)
        }
        updateUiForCurrentChunk()
    }

    private fun updateUiForCurrentChunk() {
        if (chunks.isEmpty()) {
            chunkTextView.text = "Нет текста"
            counterTextView.text = "0 / 0"
            return
        }
        val chunk = chunks[currentIndex]
        chunkTextView.text = chunk.text
        counterTextView.text = "${currentIndex + 1} / ${chunks.size}"
    }

    // ---------------------------------------------------------------------
    // SPOKEN PUNCTUATION & KAZAKH STRESS
    // ---------------------------------------------------------------------
    private fun prepareSpokenText(originalText: String): String {
        var text = originalText.trim()
        if (text.isEmpty()) return "."

        if (speakPunctuation) {
            val isKz = punctLanguage == "KZ" || voiceId.startsWith("kk")
            val dashWord = if (isKz) " сызықша " else " тире "

            // Standalone dashes (keep compound words like әдіс-тәсілдері intact)
            text = text.replace(Regex("[\u2013\u2014]"), " $dashWord ")
            text = text.replace(Regex("""(?<=\s)-(?=\s)|(?<=\s)-|-(?=\s)"""), " $dashWord ")

            if (isKz) {
                text = text.replace("(", " жақша ашылды ")
                text = text.replace(")", " жақша жабылды ")
                text = text.replace(Regex("""["«»]"""), " тырнақша ")
                text = text.replace(":", " қос нүкте ")
                text = text.replace(";", " нүктелі үтір ")
                text = text.replace("?", " сұрақ белгісі ")
                text = text.replace("!", " леп белгісі ")
                text = text.replace(",", " үтір ")
                text = text.replace("…", " көп нүкте ")
                text = text.replace(Regex("""(?<!\d)\.|\.(?!\d)"""), " нүкте ")
            } else {
                text = text.replace("(", " скобка открывается ")
                text = text.replace(")", " скобка закрывается ")
                text = text.replace(Regex("""["«»]"""), " кавычки ")
                text = text.replace(":", " двоеточие ")
                text = text.replace(";", " точка с запятой ")
                text = text.replace("?", " вопросительный знак ")
                text = text.replace("!", " восклицательный знак ")
                text = text.replace(",", " запятая ")
                text = text.replace("…", " многоточие ")
                text = text.replace(Regex("""(?<!\d)\.|\.(?!\d)"""), " точка ")
            }
            text = text.replace(Regex("""\s+"""), " ").trim()
        } else {
            text = text.replace(Regex("""\s+"""), " ").trim()
        }

        // Kazakh Stress Fix: Always append a trailing period '.' for declarative intonation
        return text.trimEnd('.', ' ') + "."
    }

    // ---------------------------------------------------------------------
    // CHUNK NAVIGATION
    // ---------------------------------------------------------------------
    private fun nextChunk() {
        if (chunks.isEmpty()) return
        if (currentIndex < chunks.size - 1) {
            currentIndex++
            updateUiForCurrentChunk()
            speakCurrentChunk()
            prefetchUpcomingChunks(currentIndex + 1, currentIndex + 9)
        }
    }

    private fun previousChunk() {
        if (chunks.isEmpty()) return
        if (currentIndex > 0) {
            currentIndex--
            updateUiForCurrentChunk()
            speakCurrentChunk()
        }
    }

    private fun repeatCurrentChunk() {
        if (chunks.isEmpty()) return
        speakCurrentChunk()
    }

    // ---------------------------------------------------------------------
    // AUDIO SYNTHESIS & PLAYBACK (CACHE + MEDIAPLAYER)
    // ---------------------------------------------------------------------
    private fun getCacheFile(spokenText: String, voice: String): File {
        val cacheDir = File(cacheDir, "tts_cache").apply { mkdirs() }
        val rawKey = "${spokenText.lowercase()}_${voice}_0%"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(rawKey.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.mp3")
    }

    private fun speakCurrentChunk() {
        if (chunks.isEmpty() || currentIndex !in chunks.indices) return
        val chunk = chunks[currentIndex]
        val spokenText = prepareSpokenText(chunk.text)
        val file = getCacheFile(spokenText, voiceId)

        if (file.exists() && file.length() > 0) {
            playAudioFile(file)
            return
        }

        // Synthesize in background
        serviceScope.launch {
            acquireWakeLockTransiently()
            try {
                synthesizeToCache(spokenText, voiceId, file)
                if (file.exists() && file.length() > 0) {
                    playAudioFile(file)
                }
            } finally {
                releaseWakeLockIfNeeded()
            }
        }
    }

    private fun prefetchUpcomingChunks(fromIndex: Int, toIndex: Int) {
        val start = fromIndex.coerceAtLeast(0)
        val end = toIndex.coerceAtMost(chunks.size)
        if (start >= end) return

        serviceScope.launch(Dispatchers.IO) {
            for (i in start until end) {
                val chunk = chunks[i]
                val spoken = prepareSpokenText(chunk.text)
                val file = getCacheFile(spoken, voiceId)
                if (!file.exists() || file.length() == 0L) {
                    prefetchSemaphore.acquire()
                    try {
                        synthesizeToCache(spoken, voiceId, file)
                    } finally {
                        prefetchSemaphore.release()
                    }
                }
            }
        }
    }

    private suspend fun synthesizeToCache(text: String, voice: String, targetFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            val key = targetFile.absolutePath
            if (!inProgressDownloads.add(key)) return@withContext false

            val tempFile = File.createTempFile("tts_", ".tmp", cacheDir)
            try {
                val audioBytes = fetchEdgeTtsAudio(text, voice)
                if (audioBytes.isNotEmpty()) {
                    FileOutputStream(tempFile).use { it.write(audioBytes) }
                    tempFile.renameTo(targetFile)
                    return@withContext true
                }
                false
            } catch (e: Exception) {
                false
            } finally {
                if (tempFile.exists()) tempFile.delete()
                inProgressDownloads.remove(key)
            }
        }
    }

    // --- Microsoft Edge TTS WebSocket Protocol ---
    private suspend fun fetchEdgeTtsAudio(text: String, voice: String): ByteArray {
        return withContext(Dispatchers.IO) {
            val outputStream = ByteArrayOutputStream()
            val deferredResult = kotlinx.coroutines.CompletableDeferred<ByteArray>()

            val request = Request.Builder()
                .url(WSS_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .build()

            val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val configMessage = "Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    webSocket.send(configMessage)

                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val escapedText = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    val ssml = "X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n" +
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                            "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                            escapedText +
                            "</prosody></voice></speak>"
                    webSocket.send(ssml)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val byteArr = bytes.toByteArray()
                    if (byteArr.size >= 2) {
                        val headerLen = (byteArr[0].toInt() and 0xFF shl 8) or (byteArr[1].toInt() and 0xFF)
                        if (byteArr.size > 2 + headerLen) {
                            outputStream.write(byteArr, 2 + headerLen, byteArr.size - (2 + headerLen))
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, "Done")
                        deferredResult.complete(outputStream.toByteArray())
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    deferredResult.complete(outputStream.toByteArray())
                }
            })

            try {
                deferredResult.await()
            } catch (e: Exception) {
                ByteArray(0)
            } finally {
                webSocket.cancel()
            }
        }
    }

    private fun playAudioFile(file: File) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                releaseMediaPlayer()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(this@FloatingTtsService, Uri.fromFile(file))
                    setOnCompletionListener {
                        releaseWakeLockIfNeeded()
                    }
                    setOnErrorListener { _, _, _ ->
                        releaseWakeLockIfNeeded()
                        false
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                releaseWakeLockIfNeeded()
            }
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        } finally {
            mediaPlayer = null
        }
    }

    private fun acquireWakeLockTransiently() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10000) // 10s max safety timeout
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun releaseWakeLockIfNeeded() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
