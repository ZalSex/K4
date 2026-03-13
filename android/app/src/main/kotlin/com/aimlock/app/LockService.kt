package com.aimlock.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.view.*
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.core.app.NotificationCompat
import android.hardware.camera2.CameraManager

class LockService : Service() {

    private val CHANNEL_ID = "aimlock_lock_channel"
    private val NOTIF_ID   = 203

    private var wm: WindowManager? = null
    private var lockView: View?    = null
    private var isLocked           = false

    private var lockText = ""
    private var lockPin  = "1234"
    private var pinInput = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    private var flashRunnable: Runnable? = null
    private var cm: CameraManager?       = null
    private var camId: String?           = null
    private var flashState               = false

    private var volRunnable: Runnable?   = null
    private var am: AudioManager?        = null
    private var pinDotsRef: TextView?    = null

    private var panelWatcher: Runnable?  = null
    private var closeDialogReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Thread {
            try { camId = cm?.cameraIdList?.firstOrNull() } catch (_: Exception) {}
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "lock"
        if (action == "unlock") {
            doUnlock()
            return START_NOT_STICKY
        }
        lockText = intent?.getStringExtra("lockText") ?: ""
        lockPin  = intent?.getStringExtra("lockPin")  ?: "1234"
        pinInput = ""

        getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE).edit()
            .putBoolean("is_locked", true)
            .putString("lock_text", lockText)
            .putString("lock_pin", lockPin)
            .apply()

        stopAll()
        unregisterCloseDialogReceiver()
        registerCloseDialogReceiver()

        mainHandler.post { showLockView() }
        Thread { startSound() }.start()
        mainHandler.postDelayed({ startFlashBlink() }, 300)
        startVolumeGuard()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isLocked) return
        val ri = Intent(this, LockService::class.java).apply {
            putExtra("action", "lock")
            putExtra("lockText", lockText)
            putExtra("lockPin", lockPin)
        }
        val pi = PendingIntent.getService(
            this, 1, ri,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 300, pi)
    }

    private fun registerCloseDialogReceiver() {
        closeDialogReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!isLocked) return
                collapseStatusBar()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction("android.intent.action.SCREEN_ON")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(closeDialogReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(closeDialogReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun unregisterCloseDialogReceiver() {
        try { closeDialogReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        closeDialogReceiver = null
    }

    @Volatile private var isAddingView = false

    private fun showLockView() {
        if (isAddingView) return  // Cegah race condition double addView
        lockView?.let {
            try { wm?.removeViewImmediate(it) } catch (_: Exception) {
                try { wm?.removeView(it) } catch (_: Exception) {}
            }
        }
        lockView = null

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lpFlags = (
            WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            or 0x00000080  // FLAG_SECURE
            or 0x00040000  // FLAG_ALT_FOCUSABLE_IM
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            lpFlags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                preferMinimalPostProcessing = true
        }

        val root = buildRoot()
        lockView = root
        isLocked = true

        isAddingView = true
        try {
            wm!!.addView(root, lp)
            root.post {
                isAddingView = false
                applySystemUIFlags(root)
                root.requestFocus()
            }
        } catch (e: Exception) {
            isAddingView = false
            e.printStackTrace()
        }

        startPanelWatcher()
    }

    @Suppress("DEPRECATION")
    private fun applySystemUIFlags(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                view.windowInsetsController?.let { ctrl ->
                    ctrl.hide(
                        WindowInsets.Type.statusBars()
                        or WindowInsets.Type.navigationBars()
                        or WindowInsets.Type.systemBars()
                        or WindowInsets.Type.displayCutout()
                    )
                    ctrl.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (_: Exception) {}
        }
        // Selalu set flag lama juga untuk semua Android version
        view.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LOW_PROFILE
        )
    }

    private fun startPanelWatcher() {
        panelWatcher?.let { mainHandler.removeCallbacks(it) }
        panelWatcher = object : Runnable {
            private var lostFocusCount = 0

            override fun run() {
                if (!isLocked) return
                val view = lockView ?: run {
                    collapseStatusBar()
                    mainHandler.post { if (isLocked) showLockView() }
                    mainHandler.postDelayed(this, 16)
                    return
                }
                if (!view.hasWindowFocus()) {
                    lostFocusCount++
                    collapseStatusBar()
                    applySystemUIFlags(view)
                    updateOverlayFlags(view)
                    if (lostFocusCount >= 2) {
                        lostFocusCount = 0
                        mainHandler.post {
                            if (isLocked) showLockView()
                        }
                    } else {
                        mainHandler.postDelayed(this, 8)
                    }
                    return
                }
                lostFocusCount = 0
                applySystemUIFlags(view)
                mainHandler.postDelayed(this, 16)
            }
        }
        mainHandler.postDelayed(panelWatcher!!, 50)
    }

    private fun updateOverlayFlags(view: View) {
        try {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
            lp.flags = lp.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            wm?.updateViewLayout(view, lp)
        } catch (_: Exception) {}
    }

    private fun stopPanelWatcher() {
        panelWatcher?.let { mainHandler.removeCallbacks(it) }
        panelWatcher = null
    }

    @Suppress("DEPRECATION")
    private fun collapseStatusBar() {
        try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
        try {
            val sbs = getSystemService("statusbar") ?: return
            val sbClass = Class.forName("android.app.StatusBarManager")
            try {
                sbClass.getMethod("collapsePanels").invoke(sbs)
            } catch (_: Exception) {
                try { sbClass.getMethod("collapse").invoke(sbs) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun buildRoot(): View {
        val dm      = resources.displayMetrics
        val density = dm.density
        val sh      = dm.heightPixels
        val sw      = dm.widthPixels
        val NUMPAD_TOP = sh * 0.42f

        val root = object : FrameLayout(this) {
            private var startY = 0f
            private var startX = 0f
            private val SWIPE_THRESHOLD = 8f * density

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                applySystemUIFlags(this)
                collapseStatusBar()
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = ev.x; startY = ev.y
                        if (ev.y >= NUMPAD_TOP) return super.dispatchTouchEvent(ev)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = Math.abs(ev.y - startY)
                        val dx = Math.abs(ev.x - startX)
                        if (dy > SWIPE_THRESHOLD || dx > SWIPE_THRESHOLD) {
                            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            collapseStatusBar()
                            return true
                        }
                        if (ev.y >= NUMPAD_TOP) return super.dispatchTouchEvent(ev)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (ev.y >= NUMPAD_TOP) return super.dispatchTouchEvent(ev)
                        return true
                    }
                }
                return super.dispatchTouchEvent(ev)
            }

            override fun onInterceptTouchEvent(ev: MotionEvent) = false
            override fun onTouchEvent(ev: MotionEvent) = true

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_APP_SWITCH,
                    KeyEvent.KEYCODE_HOME,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_VOLUME_UP,
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent.KEYCODE_POWER,
                    KeyEvent.KEYCODE_CAMERA,
                    KeyEvent.KEYCODE_SEARCH -> true
                    else -> super.dispatchKeyEvent(event)
                }
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!isLocked) return
                applySystemUIFlags(this)
                requestFocus()
                if (!hasWindowFocus) {
                    collapseStatusBar()
                    // Delay lebih panjang dan cek isAddingView untuk cegah BadTokenException
                    mainHandler.postDelayed({
                        if (isLocked && !isAddingView) showLockView()
                    }, 80)
                }
            }

            override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        windowInsetsController?.let { ctrl ->
                            ctrl.hide(
                                WindowInsets.Type.statusBars()
                                or WindowInsets.Type.navigationBars()
                                or WindowInsets.Type.systemBars()
                            )
                            ctrl.systemBarsBehavior =
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } catch (_: Exception) {}
                }
                return insets
            }
        }

        applySystemUIFlags(root)
        root.setBackgroundColor(Color.BLACK)
        root.isClickable            = true
        root.isFocusable            = true
        root.isFocusableInTouchMode = true
        root.keepScreenOn           = true

        val iconSz = (120 * density).toInt()
        root.addView(
            ImageView(this).apply { setImageBitmap(drawLockBitmap(iconSz)) },
            FrameLayout.LayoutParams(iconSz, iconSz).apply {
                gravity   = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (sh * 0.14f).toInt()
            }
        )

        if (lockText.isNotEmpty()) {
            root.addView(
                TextView(this).apply {
                    text     = lockText
                    textSize = 26f
                    gravity  = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding((32*density).toInt(), 0, (32*density).toInt(), 0)
                    typeface = try {
                        Typeface.createFromAsset(assets, "fonts/Orbitron-Bold.ttf")
                    } catch (_: Exception) { Typeface.DEFAULT_BOLD }
                    setLineSpacing(4f * density, 1f)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity   = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = (sh * 0.14f).toInt() + iconSz + (18*density).toInt()
                }
            )
        }

        val pinDots = TextView(this).apply {
            text     = buildDots()
            textSize = 30f
            gravity  = Gravity.CENTER
            setTextColor(Color.parseColor("#8B5CF6"))
            typeface = Typeface.DEFAULT_BOLD
        }
        pinDotsRef = pinDots

        val centerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }
        centerLayout.addView(pinDots, LinearLayout.LayoutParams(
            (sw * 0.85f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (20*density).toInt() })
        centerLayout.addView(buildNumpad(density))

        root.addView(centerLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity   = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (sh * 0.44f).toInt()
        })

        return root
    }

    private fun buildNumpad(density: Float): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
        }
        listOf(
            listOf("1","2","3"),
            listOf("4","5","6"),
            listOf("7","8","9"),
            listOf("⌫","0","✓")
        ).forEach { row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER
            }
            row.forEach { digit ->
                val btnSz = (72 * density).toInt()
                val m     = (8 * density).toInt()
                val btn   = buildBtn(digit, btnSz, density)
                btn.setOnClickListener { handleDigit(digit) }
                rowView.addView(btn, LinearLayout.LayoutParams(btnSz, btnSz).apply {
                    setMargins(m, m, m, m)
                })
            }
            container.addView(rowView)
        }
        return container
    }

    private fun handleDigit(digit: String) {
        val dots = pinDotsRef ?: return
        when (digit) {
            "⌫" -> { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) }
            "✓" -> {
                if (pinInput == lockPin) { doUnlock(); stopSelf(); return }
                pinInput = ""
                dots.setTextColor(Color.parseColor("#EF4444"))
                mainHandler.postDelayed({
                    if (isLocked) {
                        dots.setTextColor(Color.parseColor("#8B5CF6"))
                        dots.text = buildDots()
                    }
                }, 600)
            }
            else -> {
                if (pinInput.length < lockPin.length.coerceAtLeast(4)) pinInput += digit
            }
        }
        dots.text = buildDots()
    }

    private fun buildDots(): String {
        val max    = lockPin.length.coerceAtLeast(4)
        val filled = "●".repeat(pinInput.length)
        val empty  = "○".repeat((max - pinInput.length).coerceAtLeast(0))
        return "$filled$empty".chunked(1).joinToString("  ")
    }

    private fun buildBtn(digit: String, size: Int, density: Float): TextView {
        val textColor = when (digit) {
            "✓" -> Color.parseColor("#10B981")
            "⌫" -> Color.parseColor("#EF4444")
            else -> Color.WHITE
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        cvs.drawCircle(size/2f, size/2f, size/2f - 1f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111827"); style = Paint.Style.FILL })
        cvs.drawCircle(size/2f, size/2f, size/2f - 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8B5CF6")
                style = Paint.Style.STROKE; strokeWidth = 1.8f * density })
        return TextView(this).apply {
            text        = digit
            textSize    = 20f
            gravity     = Gravity.CENTER
            setTextColor(textColor)
            background  = BitmapDrawable(resources, bmp)
            isClickable = true
            isFocusable = true
            typeface    = try {
                Typeface.createFromAsset(assets, "fonts/Orbitron-Bold.ttf")
            } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        }
    }

    private fun drawLockBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        val red = Color.parseColor("#EF4444")
        cvs.drawRoundRect(RectF(size*.12f, size*.46f, size*.88f, size*.94f),
            size*.10f, size*.10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; style = Paint.Style.FILL })
        cvs.drawArc(RectF(size*.22f, size*.04f, size*.78f, size*.56f), 180f, 180f, false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = red; style = Paint.Style.STROKE
                strokeWidth = size*.12f; strokeCap = Paint.Cap.ROUND })
        cvs.drawCircle(size*.5f, size*.67f, size*.10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL })
        cvs.drawLine(size*.5f, size*.67f, size*.5f, size*.83f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE
                strokeWidth = size*.07f; strokeCap = Paint.Cap.ROUND })
        return bmp
    }

    private fun startSound() {
        stopSound()
        try {
            am?.let { a ->
                for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM)) {
                    try { a.setStreamVolume(stream, a.getStreamMaxVolume(stream), 0) } catch (_: Exception) {}
                }
                try { a.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
            }
            val afd = assets.openFd("sound/jokowi.mp3")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.postDelayed({ Thread { startSoundRetry() }.start() }, 1500)
        }
    }

    private fun startSoundRetry() {
        if (mediaPlayer != null) return
        try {
            val afd = assets.openFd("sound/jokowi.mp3")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopSound() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun startFlashBlink() {
        stopFlashBlink()
        flashState = false
        flashRunnable = object : Runnable {
            override fun run() {
                if (!isLocked) return
                try {
                    if (camId == null) camId = cm?.cameraIdList?.firstOrNull()
                    val id: String = camId ?: run { mainHandler.postDelayed(this, 300); return }
                    flashState = !flashState
                    cm?.setTorchMode(id, flashState)
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.post(flashRunnable!!)
    }

    private fun stopFlashBlink() {
        flashRunnable?.let { mainHandler.removeCallbacks(it) }
        flashRunnable = null
        try { cm?.setTorchMode(camId ?: return, false) } catch (_: Exception) {}
        flashState = false
    }

    private fun startVolumeGuard() {
        stopVolumeGuard()
        volRunnable = object : Runnable {
            override fun run() {
                if (!isLocked) return
                am?.let { a ->
                    for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM)) {
                        try {
                            val max = a.getStreamMaxVolume(stream)
                            if (a.getStreamVolume(stream) < max) a.setStreamVolume(stream, max, 0)
                        } catch (_: Exception) {}
                    }
                    try { a.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
                }
                mainHandler.postDelayed(this, 800)
            }
        }
        mainHandler.post(volRunnable!!)
    }

    private fun stopVolumeGuard() {
        volRunnable?.let { mainHandler.removeCallbacks(it) }
        volRunnable = null
    }

    private fun doUnlock() {
        isLocked   = false
        pinInput   = ""
        pinDotsRef = null
        getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE).edit()
            .putBoolean("is_locked", false).apply()
        stopAll()
        stopPanelWatcher()
        unregisterCloseDialogReceiver()
        mainHandler.post {
            lockView?.let {
                try { wm?.removeViewImmediate(it) } catch (_: Exception) {
                    try { wm?.removeView(it) } catch (_: Exception) {}
                }
            }
            lockView = null
        }
    }

    private fun stopAll() {
        stopFlashBlink()
        stopVolumeGuard()
        stopSound()
    }

    override fun onDestroy() {
        super.onDestroy()
        doUnlock()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }
}
