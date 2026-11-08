package com.hercules.vision

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground screen-capture service. Only draws visual hints: NO game-input automation.
 */
class CrosshairService : Service() {
    companion object {
        const val ACTION_START = "com.hercules.vision.START"
        const val ACTION_STOP = "com.hercules.vision.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_CAPTURE_DATA = "captureData"
        private const val CHANNEL = "vision_capture"
        private const val NOTIFICATION_ID = 2002
    }

    private val prefs by lazy { getSharedPreferences("vision", MODE_PRIVATE) }
    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastAnalyzed = 0L
    private var trackedX = Float.NaN
    private var trackedY = Float.NaN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Відстеження маркерів", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, CrosshairService::class.java).apply { action = ACTION_STOP }
        val pending = PendingIntent.getService(
            this, 91, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("HERCULES VISION v0.2")
            .setContentText("Візуальне відстеження екрана активне")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Зупинити", pending)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val captureData = intent.getParcelableExtra<Intent>(EXTRA_CAPTURE_DATA)
        if (resultCode != Activity.RESULT_OK || captureData == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTIFICATION_ID, notification())

            showOverlay()

            thread = HandlerThread("VisionFrames").also { it.start() }
            val frameHandler = Handler(thread!!.looper)
            val captureManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = captureManager.getMediaProjection(resultCode, captureData)
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))

            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            val width = max(1, point.x)
            val height = max(1, point.y)
            val density = resources.displayMetrics.densityDpi
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val frame = try { source.acquireLatestImage() } catch (_: Exception) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalyzed >= 115L) {
                        lastAnalyzed = now
                        val matches = VisionProcessor.findRedMarkers(frame, prefs.getInt("sensitivity", 60))
                        val selected = chooseTarget(matches, frame.width, frame.height)
                        overlay?.post { overlay?.updateMarker(selected, frame.width, frame.height) }
                    }
                } catch (_: Exception) {
                    overlay?.post { overlay?.updateMarker(null, frame.width, frame.height) }
                } finally {
                    frame.close()
                }
            }, frameHandler)
            display = p.createVirtualDisplay(
                "HerculesVisionCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, frameHandler
            )
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun chooseTarget(
        markers: List<VisionProcessor.Marker>, width: Int, height: Int
    ): VisionProcessor.Marker? {
        if (markers.isEmpty()) {
            trackedX = Float.NaN
            trackedY = Float.NaN
            return null
        }
        val cx = width / 2f
        val cy = height / 2f
        val fov = prefs.getInt("fov", 85).coerceIn(0, 100)
        val radius = min(width, height) * (0.18f + fov / 100f * 0.65f)
        val available = markers.filter { val d = hypot(it.x - cx, it.y - cy); d <= radius && d >= min(width, height) * 0.08f }
        if (available.isEmpty()) return null
        val preferredX = if (trackedX.isNaN()) cx else trackedX
        val preferredY = if (trackedY.isNaN()) cy else trackedY
        val choice = available.minByOrNull {
            val d = hypot(it.x - preferredX, it.y - preferredY)
            d - min(it.samples, 15) * 1.4f
        } ?: return null
        trackedX = choice.x
        trackedY = choice.y
        return choice
    }

    private fun showOverlay() {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Allow touches to pass to the game under Android 12+ untrusted-touch rules.
            alpha = 0.78f
        }
        overlay = OverlayView().also { wm.addView(it, params) }
    }

    private inner class OverlayView : View(this@CrosshairService) {
        private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(74, 255, 144)
            strokeWidth = 4.5f
            style = Paint.Style.STROKE
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private var hasMarker = false
        private var markerX = 0f
        private var markerY = 0f
        private var hintX = 0f
        private var hintY = 0f
        private var initialized = false

        fun updateMarker(m: VisionProcessor.Marker?, frameWidth: Int, frameHeight: Int) {
            if (m == null || width == 0 || height == 0) {
                hasMarker = false
                initialized = false
                invalidate()
                return
            }
            val mx = m.x / frameWidth * width
            val my = m.y / frameHeight * height
            val offset = when (prefs.getInt("aim_point", 1)) {
                0 -> 0.045f
                2 -> 0.22f
                else -> 0.14f
            }
            val goalX = mx
            val goalY = (my + height * offset).coerceIn(0f, height.toFloat())
            val smoothing = prefs.getInt("smoothing", 40).coerceIn(0, 100)
            val step = 1f - smoothing / 100f * 0.92f
            if (!initialized) {
                hintX = goalX; hintY = goalY; initialized = true
            } else {
                hintX += (goalX - hintX) * step
                hintY += (goalY - hintY) * step
            }
            markerX = mx
            markerY = my
            hasMarker = true
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val opacity = prefs.getInt("alpha", 85).coerceIn(15, 100)
            green.alpha = opacity * 255 / 100
            markerPaint.alpha = opacity * 255 / 100
            textPaint.alpha = opacity * 255 / 100

            val midX = width / 2f
            val midY = height / 2f
            val r = min(width, height) * (0.18f + prefs.getInt("fov", 85) / 100f * 0.65f)
            markerPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 12f), 0f)
            canvas.drawCircle(midX, midY, r, markerPaint)
            markerPaint.pathEffect = null

            if (!hasMarker) {
                textPaint.color = Color.WHITE
                canvas.drawText("VISION: ПОШУК ЧЕРВОНИХ МАРКЕРІВ", 32f, 56f, textPaint)
                return
            }
            canvas.drawCircle(markerX, markerY, 18f, markerPaint)
            canvas.drawLine(markerX, markerY + 19f, hintX, hintY - 20f, markerPaint)
            val crossRadius = 27f
            canvas.drawLine(hintX - crossRadius, hintY, hintX - 9f, hintY, green)
            canvas.drawLine(hintX + 9f, hintY, hintX + crossRadius, hintY, green)
            canvas.drawLine(hintX, hintY - crossRadius, hintX, hintY - 9f, green)
            canvas.drawLine(hintX, hintY + 9f, hintX, hintY + crossRadius, green)
            canvas.drawCircle(hintX, hintY, 3f, green)
            textPaint.color = Color.rgb(74, 255, 144)
            canvas.drawText("VISION: МАРКЕР ЗНАЙДЕНО", 32f, 56f, textPaint)
        }
    }

    override fun onDestroy() {
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        overlay = null
        projection = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }
}
