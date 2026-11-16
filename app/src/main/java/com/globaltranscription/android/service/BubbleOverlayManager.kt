package com.globaltranscription.android.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.globaltranscription.android.R
import kotlin.math.abs
import kotlin.math.hypot

class BubbleOverlayManager(
    private val context: Context,
    private val onAction: (String) -> Unit,
    private val onOpenApp: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var bubbleWindowRoot: FrameLayout? = null
    private var bubbleView: FrameLayout? = null
    private var bubbleBackground: GradientDrawable? = null
    private var bubbleBadge: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelWindowRoot: FrameLayout? = null
    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusDot: View? = null
    private var titleText: TextView? = null
    private var subtitleScrollView: MaxHeightScrollView? = null
    private var subtitleText: TextView? = null
    private var statsText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var primaryBtn: TextView? = null
    private var secondaryBtn: TextView? = null

    private var dismissView: FrameLayout? = null
    private var dismissCircle: FrameLayout? = null
    private var dismissCircleBg: GradientDrawable? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var isSnappedToDismiss = false

    private var isShowing = false
    private var isExpanded = false
    private var openedFromRightSide = true
    private var isTranscriptExpanded = false
    private var lastTranscriptKey: String? = null
    private var lastState = SessionState()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoMinimizeRunnable = Runnable {
        if (isShowing && isExpanded) {
            setExpanded(false, animate = true)
        }
    }

    fun show() {
        if (isShowing) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot draw overlays; permission not granted")
            return
        }

        buildViews()
        val metrics = screenMetrics()
        val initialY = (metrics.heightPixels * 0.30f).toInt()

        val bParams = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS_ACTIVE_WINDOW,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - dp(56) - dp(8)
            y = initialY
        }
        bubbleParams = bParams

        val pParams = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS_INACTIVE_WINDOW,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - dp(260) - dp(8)).coerceAtLeast(dp(8))
            y = initialY
        }
        panelParams = pParams

        val dParams = WindowManager.LayoutParams(
            dp(64),
            dp(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS_INACTIVE_WINDOW,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - dp(64)) / 2
            y = metrics.heightPixels - dp(64) - dp(DISMISS_BOTTOM_MARGIN_DP)
        }
        dismissParams = dParams

        try {
            windowManager.addView(panelWindowRoot, pParams)
            windowManager.addView(bubbleWindowRoot, bParams)
            windowManager.addView(dismissView, dParams)
            isShowing = true
            isExpanded = false
            openedFromRightSide = true
            bubbleView?.alpha = 1f
            bubbleView?.scaleX = 1f
            bubbleView?.scaleY = 1f
            panelView?.alpha = 0f
            panelView?.scaleX = 0.75f
            panelView?.scaleY = 0.75f
            updateState(lastState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding bubble overlay", e)
            removeAllViewsSafely()
        }
    }

    fun hide() {
        mainHandler.removeCallbacks(autoMinimizeRunnable)
        panelView?.animate()?.cancel()
        bubbleView?.animate()?.cancel()
        dismissView?.animate()?.cancel()
        dismissCircle?.animate()?.cancel()
        if (!isShowing) return
        removeAllViewsSafely()
    }

    private fun removeAllViewsSafely() {
        try {
            bubbleWindowRoot?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing bubble overlay", e)
        }
        try {
            panelWindowRoot?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing panel overlay", e)
        }
        try {
            dismissView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing dismiss overlay", e)
        }
        bubbleWindowRoot = null
        bubbleView = null
        panelWindowRoot = null
        panelView = null
        dismissView = null
        dismissCircle = null
        dismissCircleBg = null
        bubbleParams = null
        panelParams = null
        dismissParams = null
        isShowing = false
        isExpanded = false
        isSnappedToDismiss = false
    }

    fun updateState(state: SessionState) {
        lastState = state
        if (!isShowing) return

        val expandKey = when (state.status) {
            RecorderStatus.TRANSCRIBED -> "T:${state.transcriptText}"
            RecorderStatus.ERROR -> "E:${state.errorMessage}"
            else -> null
        }
        if (expandKey != lastTranscriptKey) {
            isTranscriptExpanded = false
            lastTranscriptKey = expandKey
        }

        val accentColor = statusAccentColor(state.status)
        bubbleBackground?.setColor(accentColor)
        (statusDot?.background as? GradientDrawable)?.setColor(accentColor)

        when (state.status) {
            RecorderStatus.IDLE -> {
                bubbleBadge?.visibility = View.GONE
                titleText?.text = "Ready"
                subtitleText?.text = "Tap Start to record voice"
                subtitleScrollView?.visibility = View.VISIBLE
                statsText?.visibility = View.GONE
                progressBar?.visibility = View.GONE
                applyTranscriptExpansion(state.status)

                configureButton(
                    button = primaryBtn,
                    label = "Start Recording",
                    bgColor = COLOR_BLUE,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_START_RECORDING
                )
                configureButton(secondaryBtn, "", COLOR_GRAY, visible = false, action = null)
            }

            RecorderStatus.RECORDING -> {
                val time = NotificationHelper.formatDuration(state.elapsedMs)
                bubbleBadge?.text = time
                bubbleBadge?.visibility = View.VISIBLE
                titleText?.text = "Recording • $time"
                subtitleText?.text = "Microphone active"
                subtitleScrollView?.visibility = View.VISIBLE
                statsText?.visibility = View.GONE
                progressBar?.visibility = View.GONE
                applyTranscriptExpansion(state.status)

                configureButton(
                    button = primaryBtn,
                    label = "Pause",
                    bgColor = COLOR_AMBER,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_PAUSE_RECORDING
                )
                configureButton(
                    button = secondaryBtn,
                    label = "End",
                    bgColor = COLOR_RED,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_END_RECORDING
                )
            }

            RecorderStatus.PAUSED -> {
                val time = NotificationHelper.formatDuration(state.elapsedMs)
                bubbleBadge?.text = "II"
                bubbleBadge?.visibility = View.VISIBLE
                titleText?.text = "Paused • $time"
                subtitleText?.text = "Resume or end recording"
                subtitleScrollView?.visibility = View.VISIBLE
                statsText?.visibility = View.GONE
                progressBar?.visibility = View.GONE
                applyTranscriptExpansion(state.status)

                configureButton(
                    button = primaryBtn,
                    label = "Resume",
                    bgColor = COLOR_GREEN,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_RESUME_RECORDING
                )
                configureButton(
                    button = secondaryBtn,
                    label = "End",
                    bgColor = COLOR_RED,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_END_RECORDING
                )
            }

            RecorderStatus.RECORDED -> {
                val time = NotificationHelper.formatDuration(state.elapsedMs)
                bubbleBadge?.text = "✓"
                bubbleBadge?.visibility = View.VISIBLE
                titleText?.text = "Recorded • $time"
                val fileName = state.savedFileDisplayName ?: "Saved audio"
                subtitleText?.text = "Saved: $fileName"
                subtitleScrollView?.visibility = View.VISIBLE
                statsText?.visibility = View.GONE
                progressBar?.visibility = View.GONE
                applyTranscriptExpansion(state.status)

                configureButton(
                    button = primaryBtn,
                    label = "New",
                    bgColor = COLOR_SLATE_BTN,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_START_RECORDING
                )
                configureButton(
                    button = secondaryBtn,
                    label = "Transcribe",
                    bgColor = COLOR_BLUE,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY
                )
            }

            RecorderStatus.TRANSCRIBING -> {
                bubbleBadge?.text = "..."
                bubbleBadge?.visibility = View.VISIBLE
                titleText?.text = "Transcribing"
                subtitleText?.text = state.statusDetail.ifBlank { "Cleaning speech & uploading..." }
                subtitleScrollView?.visibility = View.VISIBLE
                statsText?.visibility = View.GONE
                progressBar?.visibility = View.VISIBLE
                applyTranscriptExpansion(state.status)

                configureButton(
                    button = primaryBtn,
                    label = "Cancel",
                    bgColor = COLOR_RED,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_CANCEL_TRANSCRIBE
                )
                configureButton(secondaryBtn, "", COLOR_GRAY, visible = false, action = null)
            }

            RecorderStatus.TRANSCRIBED -> {
                bubbleBadge?.text = "TXT"
                bubbleBadge?.visibility = View.VISIBLE
                titleText?.text = "Transcribed • Copied"
                val preview = state.transcriptText.ifBlank { "(No speech words recognized)" }
                subtitleText?.text = preview
                subtitleScrollView?.visibility = View.VISIBLE
                if (state.speechCleanupStats.isNotBlank()) {
                    statsText?.text = state.speechCleanupStats
                    statsText?.visibility = View.VISIBLE
                } else {
                    statsText?.visibility = View.GONE
                }
                progressBar?.visibility = View.GONE
                applyTranscriptExpansion(state.status)

                configureButton(
                    button = primaryBtn,
                    label = "New",
                    bgColor = COLOR_SLATE_BTN,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_START_RECORDING
                )
                configureButton(
                    button = secondaryBtn,
                    label = "Copy",
                    bgColor = COLOR_GREEN,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY
                )
            }

            RecorderStatus.ERROR -> {
                bubbleBadge?.text = "!"
                bubbleBadge?.visibility = View.VISIBLE
                titleText?.text = "Issue"
                subtitleText?.text = state.errorMessage ?: "Transcription failed"
                subtitleScrollView?.visibility = View.VISIBLE
                statsText?.visibility = View.GONE
                progressBar?.visibility = View.GONE
                applyTranscriptExpansion(state.status)

                configureButton(
                    button = primaryBtn,
                    label = "New",
                    bgColor = COLOR_SLATE_BTN,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_START_RECORDING
                )
                configureButton(
                    button = secondaryBtn,
                    label = "Transcribe",
                    bgColor = COLOR_BLUE,
                    visible = true,
                    action = TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        // 1. Collapsed Floating Bubble Window (56dp x 56dp)
        val bRoot = FrameLayout(context)
        val bubbleWrapper = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
        }

        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_BLUE)
            setStroke(dp(2), Color.WHITE)
        }
        bubbleBackground = circleBg

        val circle = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
            background = circleBg
            elevation = 0f
            outlineProvider = null
        }

        val micIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic_notification)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        }
        circle.addView(micIcon)
        bubbleWrapper.addView(circle)

        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#CC0F172A"))
        }
        val badge = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = badgeBg
            setPadding(dp(5), dp(1), dp(5), dp(1))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        }
        bubbleBadge = badge
        bubbleWrapper.addView(badge)

        bubbleWrapper.setOnTouchListener(createBubbleDragTouchListener())
        bubbleView = bubbleWrapper
        bRoot.addView(bubbleWrapper)
        bubbleWindowRoot = bRoot

        // 2. Expanded Control Panel Window (260dp x WRAP_CONTENT)
        val pRoot = FrameLayout(context)
        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#F50F172A"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
        val panel = object : LinearLayout(context) {
            private var dragStartX = 0
            private var dragStartY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var isPanelDragging = false
            private var startedInScrollableText = false

            override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
                if (isExpanded) {
                    resetAutoMinimizeTimer()
                }
                return super.dispatchTouchEvent(ev)
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (!isExpanded) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val pParams = this@BubbleOverlayManager.panelParams
                        dragStartX = pParams?.x ?: 0
                        dragStartY = pParams?.y ?: 0
                        touchStartX = ev.rawX
                        touchStartY = ev.rawY
                        isPanelDragging = false

                        val sv = subtitleScrollView
                        val canScroll = isTranscriptExpanded &&
                            sv != null &&
                            sv.visibility == View.VISIBLE &&
                            ev.x >= sv.left && ev.x <= sv.right &&
                            ev.y >= sv.top && ev.y <= sv.bottom &&
                            (sv.canScrollVertically(-1) || sv.canScrollVertically(1))
                        startedInScrollableText = canScroll
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - touchStartX
                        val dy = ev.rawY - touchStartY
                        if (startedInScrollableText && abs(dy) > abs(dx)) {
                            return false
                        }
                        if (!isPanelDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isPanelDragging = true
                            showDismissTarget()
                            return true
                        }
                        return isPanelDragging
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = isPanelDragging
                        isPanelDragging = false
                        startedInScrollableText = false
                        if (wasDragging) {
                            hideDismissTarget()
                        }
                        return false
                    }
                }
                return false
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (!isExpanded) return false
                val pParams = this@BubbleOverlayManager.panelParams ?: return false
                val pWindowRoot = this@BubbleOverlayManager.panelWindowRoot ?: return false

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = pParams.x
                        dragStartY = pParams.y
                        touchStartX = ev.rawX
                        touchStartY = ev.rawY
                        isPanelDragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - touchStartX
                        val dy = ev.rawY - touchStartY
                        if (!isPanelDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isPanelDragging = true
                            showDismissTarget()
                        }
                        if (isPanelDragging) {
                            val viewW = maxOf(width, dp(260))
                            val viewH = maxOf(height, dp(150))
                            updateDragWithDismissSnap(
                                windowRoot = pWindowRoot,
                                innerView = this,
                                activeParams = pParams,
                                rawDesiredX = dragStartX + dx.toInt(),
                                rawDesiredY = dragStartY + dy.toInt(),
                                viewW = viewW,
                                viewH = viewH,
                                touchRawX = ev.rawX,
                                touchRawY = ev.rawY
                            )
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val wasDragging = isPanelDragging
                        val shouldDismiss = wasDragging && isSnappedToDismiss
                        isPanelDragging = false
                        startedInScrollableText = false
                        hideDismissTarget()
                        if (shouldDismiss) {
                            onAction(TranscriptionForegroundService.ACTION_STOP_SERVICE)
                        } else if (wasDragging) {
                            openedFromRightSide = pParams.x + dp(130) >= screenMetrics().widthPixels / 2
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        isPanelDragging = false
                        startedInScrollableText = false
                        hideDismissTarget()
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            alpha = 0f
            orientation = LinearLayout.VERTICAL
            background = panelBg
            elevation = 0f
            outlineProvider = null
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(260), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // Header Row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val dotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_BLUE)
        }
        val dot = View(context).apply {
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
        }
        statusDot = dot
        header.addView(dot)

        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onOpenApp() }
        }
        titleText = title
        header.addView(title)

        val collapseBtn = TextView(context).apply {
            text = "—"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(6), dp(2))
            setOnClickListener { setExpanded(false, animate = true) }
        }
        header.addView(collapseBtn)
        panel.addView(header)

        // Scrollable Subtitle / Transcript Preview (~11 rows max when expanded)
        val scrollContainer = MaxHeightScrollView(context, dp(195)).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val subtitle = TextView(context).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(4), 0)
            setOnClickListener { toggleTranscriptExpansion() }
        }
        subtitleText = subtitle
        scrollContainer.addView(
            subtitle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        subtitleScrollView = scrollContainer
        panel.addView(scrollContainer)

        val stats = TextView(context).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        statsText = stats
        panel.addView(stats)

        // Progress Spinner (only during transcription)
        val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply {
                bottomMargin = dp(10)
            }
        }
        progressBar = spinner
        panel.addView(spinner)

        // Action Buttons Row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btn1 = createPillButton().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        primaryBtn = btn1
        btnRow.addView(btn1)

        val btn2 = createPillButton().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        secondaryBtn = btn2
        btnRow.addView(btn2)

        panel.addView(btnRow)
        panelView = panel
        pRoot.addView(panel)
        panelWindowRoot = pRoot

        // 3. Subtle Bottom Dismiss Target ('✕')
        val dismissRoot = FrameLayout(context).apply {
            visibility = View.GONE
        }
        val dCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_DISMISS_IDLE)
            setStroke(dp(1), Color.parseColor("#80FFFFFF"))
        }
        dismissCircleBg = dCircleBg

        val dCircle = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER)
            background = dCircleBg
            elevation = 0f
            outlineProvider = null
        }
        val dIcon = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        dCircle.addView(dIcon)
        dismissCircle = dCircle
        dismissRoot.addView(dCircle)
        dismissView = dismissRoot
    }

    private fun createPillButton(): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(COLOR_BLUE)
        }
        return TextView(context).apply {
            background = bg
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
    }

    private fun configureButton(
        button: TextView?,
        label: String,
        bgColor: Int,
        visible: Boolean,
        action: String?
    ) {
        if (button == null) return
        button.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            button.setOnClickListener(null)
            return
        }
        button.text = label
        (button.background as? GradientDrawable)?.setColor(bgColor)
        button.setOnClickListener {
            resetAutoMinimizeTimer()
            if (action != null) {
                onAction(action)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleDragTouchListener(): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (isExpanded) return false
                val bParams = bubbleParams ?: return false
                val bWindowRoot = bubbleWindowRoot ?: return false
                val bubble = bubbleView ?: return false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = bParams.x
                        startY = bParams.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        isDragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                            showDismissTarget()
                        }
                        if (isDragging) {
                            updateDragWithDismissSnap(
                                windowRoot = bWindowRoot,
                                innerView = bubble,
                                activeParams = bParams,
                                rawDesiredX = startX + dx.toInt(),
                                rawDesiredY = startY + dy.toInt(),
                                viewW = dp(56),
                                viewH = dp(56),
                                touchRawX = event.rawX,
                                touchRawY = event.rawY
                            )
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            setExpanded(true, animate = true)
                        } else {
                            val shouldDismiss = isSnappedToDismiss
                            hideDismissTarget()
                            if (shouldDismiss) {
                                onAction(TranscriptionForegroundService.ACTION_STOP_SERVICE)
                            } else {
                                snapBubbleToEdge()
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            hideDismissTarget()
                            snapBubbleToEdge()
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun showDismissTarget() {
        val dView = dismissView ?: return
        val dParams = dismissParams ?: return
        val metrics = screenMetrics()
        dParams.x = (metrics.widthPixels - dp(64)) / 2
        dParams.y = metrics.heightPixels - dp(64) - dp(DISMISS_BOTTOM_MARGIN_DP)
        try {
            windowManager.updateViewLayout(dView, dParams)
        } catch (_: Exception) {
        }

        dView.animate().cancel()
        dView.animate().withEndAction(null)
        dView.animate().setListener(null)
        if (dView.visibility != View.VISIBLE) {
            dView.visibility = View.VISIBLE
            dView.alpha = 0f
            dView.scaleX = 0.7f
            dView.scaleY = 0.7f
        }
        dView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(110L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    private fun hideDismissTarget() {
        val wasSnapped = isSnappedToDismiss
        isSnappedToDismiss = false
        dismissCircleBg?.setColor(COLOR_DISMISS_IDLE)
        dismissCircle?.animate()?.cancel()
        dismissCircle?.scaleX = 1f
        dismissCircle?.scaleY = 1f

        if (wasSnapped) {
            if (isExpanded) {
                panelView?.animate()?.cancel()
                panelView?.alpha = 1f
                panelView?.scaleX = 1f
                panelView?.scaleY = 1f
            } else {
                bubbleView?.animate()?.cancel()
                bubbleView?.alpha = 1f
                bubbleView?.scaleX = 1f
                bubbleView?.scaleY = 1f
            }
        }

        val dView = dismissView ?: return
        if (dView.visibility != View.VISIBLE) return
        dView.animate().cancel()
        dView.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(90L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                dView.visibility = View.GONE
            }
            .start()
    }

    private fun updateDragWithDismissSnap(
        windowRoot: View,
        innerView: View,
        activeParams: WindowManager.LayoutParams,
        rawDesiredX: Int,
        rawDesiredY: Int,
        viewW: Int,
        viewH: Int,
        touchRawX: Float,
        touchRawY: Float
    ) {
        val metrics = screenMetrics()
        val targetCenterX = metrics.widthPixels / 2
        val targetCenterY = metrics.heightPixels - dp(DISMISS_BOTTOM_MARGIN_DP) - dp(32)

        val draggedCenterX = rawDesiredX + viewW / 2.0
        val draggedCenterY = rawDesiredY + viewH / 2.0
        val draggedBottomY = rawDesiredY + viewH - dp(24).toDouble()

        val distCenter = hypot(draggedCenterX - targetCenterX, draggedCenterY - targetCenterY)
        val distBottom = hypot(draggedCenterX - targetCenterX, draggedBottomY - targetCenterY)
        val distTouch = hypot((touchRawX - targetCenterX).toDouble(), (touchRawY - targetCenterY).toDouble())
        val withinSnap = minOf(distCenter, minOf(distBottom, distTouch)) <= dp(88)

        if (withinSnap) {
            activeParams.x = (targetCenterX - viewW / 2)
                .coerceIn(0, (metrics.widthPixels - viewW).coerceAtLeast(0))
            activeParams.y = targetCenterY - viewH / 2
            if (!isSnappedToDismiss) {
                isSnappedToDismiss = true
                triggerSnapVibration(innerView)
                dismissCircleBg?.setColor(COLOR_DISMISS_ACTIVE)
                dismissCircle?.animate()?.cancel()
                dismissCircle?.animate()
                    ?.scaleX(1.18f)
                    ?.scaleY(1.18f)
                    ?.setDuration(80L)
                    ?.setInterpolator(DecelerateInterpolator(1.5f))
                    ?.start()

                innerView.animate().cancel()
                innerView.pivotX = viewW / 2f
                innerView.pivotY = viewH / 2f
                if (innerView === panelView) {
                    innerView.animate()
                        .alpha(0.65f)
                        .scaleX(0.82f)
                        .scaleY(0.82f)
                        .setDuration(80L)
                        .start()
                } else {
                    innerView.animate()
                        .alpha(0.65f)
                        .setDuration(80L)
                        .start()
                }
            }
        } else {
            activeParams.x = rawDesiredX.coerceIn(0, (metrics.widthPixels - viewW).coerceAtLeast(0))
            activeParams.y = rawDesiredY.coerceIn(0, (metrics.heightPixels - viewH - dp(16)).coerceAtLeast(0))
            if (isSnappedToDismiss) {
                isSnappedToDismiss = false
                dismissCircleBg?.setColor(COLOR_DISMISS_IDLE)
                dismissCircle?.animate()?.cancel()
                dismissCircle?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(80L)
                    ?.setInterpolator(DecelerateInterpolator(1.5f))
                    ?.start()

                innerView.animate().cancel()
                innerView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(80L)
                    .start()
            }
        }

        try {
            windowManager.updateViewLayout(windowRoot, activeParams)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerSnapVibration(activeView: View) {
        try {
            activeView.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Exception) {
        }
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val effect = VibrationEffect.createOneShot(24L, 120)
                vibrator.vibrate(effect, attrs)
            }
        } catch (_: Exception) {
        }
    }

    private fun resetAutoMinimizeTimer() {
        mainHandler.removeCallbacks(autoMinimizeRunnable)
        if (isShowing && isExpanded) {
            mainHandler.postDelayed(autoMinimizeRunnable, AUTO_MINIMIZE_DELAY_MS)
        }
    }

    private fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        val bRoot = bubbleWindowRoot ?: return
        val pRoot = panelWindowRoot ?: return
        val bubble = bubbleView ?: return
        val panel = panelView ?: return
        val bParams = bubbleParams ?: return
        val pParams = panelParams ?: return
        val metrics = screenMetrics()

        if (expanded) {
            openedFromRightSide = bParams.x + dp(28) >= metrics.widthPixels / 2
            isExpanded = true

            val targetW = dp(260)
            val targetH = panel.height.takeIf { it > 0 } ?: run {
                panel.measure(
                    View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                panel.measuredHeight.takeIf { it > 0 } ?: dp(145)
            }
            val maxPanelX = (metrics.widthPixels - targetW - dp(8)).coerceAtLeast(dp(8))
            val maxPanelY = (metrics.heightPixels - targetH - dp(48)).coerceAtLeast(dp(8))

            pParams.x = if (openedFromRightSide) {
                (bParams.x + dp(56) - targetW).coerceIn(dp(8), maxPanelX)
            } else {
                bParams.x.coerceIn(dp(8), maxPanelX)
            }
            pParams.y = bParams.y.coerceIn(dp(8), maxPanelY)
            pParams.flags = FLAGS_ACTIVE_WINDOW
            bParams.flags = FLAGS_INACTIVE_WINDOW

            try {
                windowManager.updateViewLayout(pRoot, pParams)
                windowManager.updateViewLayout(bRoot, bParams)
            } catch (_: Exception) {
            }

            panel.animate().cancel()
            bubble.animate().cancel()
            panel.animate().withEndAction(null)
            bubble.animate().withEndAction(null)

            if (animate) {
                // Mic bubble shrinks and fades out in place
                bubble.pivotX = dp(28).toFloat()
                bubble.pivotY = dp(28).toFloat()
                bubble.animate()
                    .scaleX(0.4f)
                    .scaleY(0.4f)
                    .alpha(0f)
                    .setDuration(120L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()

                // Record window grows and fades in in place
                panel.pivotX = targetW / 2f
                panel.pivotY = targetH / 2f
                panel.scaleX = 0.75f
                panel.scaleY = 0.75f
                panel.alpha = 0f
                panel.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(130L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            } else {
                bubble.scaleX = 0.4f
                bubble.scaleY = 0.4f
                bubble.alpha = 0f

                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.alpha = 1f
            }

            resetAutoMinimizeTimer()
        } else {
            mainHandler.removeCallbacks(autoMinimizeRunnable)
            if (!isExpanded && panel.alpha == 0f) {
                return
            }

            isExpanded = false
            val placeOnRight = openedFromRightSide
            val bubbleSize = dp(56)
            val maxBubbleX = (metrics.widthPixels - bubbleSize - dp(8)).coerceAtLeast(dp(8))
            val maxBubbleY = (metrics.heightPixels - bubbleSize - dp(48)).coerceAtLeast(dp(8))

            bParams.x = if (placeOnRight) {
                (pParams.x + dp(260) - bubbleSize).coerceIn(dp(8), maxBubbleX)
            } else {
                pParams.x.coerceIn(dp(8), maxBubbleX)
            }
            bParams.y = pParams.y.coerceIn(dp(8), maxBubbleY)
            bParams.flags = FLAGS_ACTIVE_WINDOW
            pParams.flags = FLAGS_INACTIVE_WINDOW

            try {
                windowManager.updateViewLayout(bRoot, bParams)
                windowManager.updateViewLayout(pRoot, pParams)
            } catch (_: Exception) {
            }

            panel.animate().cancel()
            bubble.animate().cancel()
            panel.animate().withEndAction(null)
            bubble.animate().withEndAction(null)

            if (animate) {
                // Record window shrinks and fades out in place
                panel.pivotX = (panel.width.takeIf { it > 0 } ?: dp(260)) / 2f
                panel.pivotY = (panel.height.takeIf { it > 0 } ?: dp(145)) / 2f
                panel.animate()
                    .scaleX(0.75f)
                    .scaleY(0.75f)
                    .alpha(0f)
                    .setDuration(120L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()

                // Mic bubble grows and fades in in place (already positioned at the end of the record window)
                bubble.pivotX = dp(28).toFloat()
                bubble.pivotY = dp(28).toFloat()
                bubble.scaleX = 0.4f
                bubble.scaleY = 0.4f
                bubble.alpha = 0f
                bubble.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(130L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            } else {
                panel.scaleX = 0.75f
                panel.scaleY = 0.75f
                panel.alpha = 0f

                bubble.scaleX = 1f
                bubble.scaleY = 1f
                bubble.alpha = 1f
            }
        }
    }

    private fun toggleTranscriptExpansion() {
        resetAutoMinimizeTimer()
        val status = lastState.status
        if (status != RecorderStatus.TRANSCRIBED && status != RecorderStatus.ERROR) return
        if (!isTranscriptExpanded && !isSubtitleTruncated()) return

        isTranscriptExpanded = !isTranscriptExpanded
        applyTranscriptExpansion(status)
        subtitleScrollView?.post { clampPanelPositionAfterResize() }
    }

    private fun isSubtitleTruncated(): Boolean {
        val tv = subtitleText ?: return false
        val layout = tv.layout ?: return true
        val lines = layout.lineCount
        if (lines <= 0) return false
        return layout.getEllipsisCount(lines - 1) > 0
    }

    private fun applyTranscriptExpansion(status: RecorderStatus) {
        val tv = subtitleText ?: return
        val allowExpand = status == RecorderStatus.TRANSCRIBED || status == RecorderStatus.ERROR
        if (allowExpand && isTranscriptExpanded) {
            tv.maxLines = Int.MAX_VALUE
            tv.ellipsize = null
        } else {
            tv.maxLines = 3
            tv.ellipsize = TextUtils.TruncateAt.END
            subtitleScrollView?.scrollTo(0, 0)
        }
    }

    private fun clampPanelPositionAfterResize() {
        val pRoot = panelWindowRoot ?: return
        val panel = panelView ?: return
        val pParams = panelParams ?: return
        val metrics = screenMetrics()
        val actualHeight = panel.height.takeIf { it > 0 } ?: return
        val maxY = (metrics.heightPixels - actualHeight - dp(48)).coerceAtLeast(dp(8))
        if (pParams.y > maxY) {
            pParams.y = maxY
            try {
                windowManager.updateViewLayout(pRoot, pParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun snapBubbleToEdge() {
        val bParams = bubbleParams ?: return
        val bRoot = bubbleWindowRoot ?: return
        val metrics = screenMetrics()
        val viewWidth = dp(56)
        val center = bParams.x + viewWidth / 2
        val targetX = if (center < metrics.widthPixels / 2) {
            dp(8)
        } else {
            (metrics.widthPixels - viewWidth - dp(8)).coerceAtLeast(dp(8))
        }
        openedFromRightSide = center >= metrics.widthPixels / 2

        ValueAnimator.ofInt(bParams.x, targetX).apply {
            duration = 110L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                bParams.x = anim.animatedValue as Int
                try {
                    if (isShowing) {
                        windowManager.updateViewLayout(bRoot, bParams)
                    }
                } catch (_: Exception) {
                }
            }
            start()
        }
    }

    private fun statusAccentColor(status: RecorderStatus): Int {
        return when (status) {
            RecorderStatus.IDLE -> COLOR_BLUE
            RecorderStatus.RECORDING -> COLOR_RED
            RecorderStatus.PAUSED -> COLOR_AMBER
            RecorderStatus.RECORDED -> COLOR_TEAL
            RecorderStatus.TRANSCRIBING -> COLOR_INDIGO
            RecorderStatus.TRANSCRIBED -> COLOR_GREEN
            RecorderStatus.ERROR -> COLOR_RED
        }
    }

    private fun screenMetrics(): DisplayMetrics {
        return context.resources.displayMetrics
    }

    private fun dp(value: Int): Int {
        return (value * density).toInt()
    }

    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, cappedHeightSpec)
        }
    }

    companion object {
        private const val TAG = "BubbleOverlayManager"
        private const val AUTO_MINIMIZE_DELAY_MS = 6000L
        private const val DISMISS_BOTTOM_MARGIN_DP = 36

        private const val FLAGS_ACTIVE_WINDOW =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        private const val FLAGS_INACTIVE_WINDOW =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        private val COLOR_BLUE = Color.parseColor("#2563EB")
        private val COLOR_RED = Color.parseColor("#DC2626")
        private val COLOR_AMBER = Color.parseColor("#D97706")
        private val COLOR_GREEN = Color.parseColor("#16A34A")
        private val COLOR_TEAL = Color.parseColor("#0D9488")
        private val COLOR_INDIGO = Color.parseColor("#4F46E5")
        private val COLOR_SLATE_BTN = Color.parseColor("#334155")
        private val COLOR_GRAY = Color.parseColor("#64748B")
        private val COLOR_DISMISS_IDLE = Color.parseColor("#880F172A")
        private val COLOR_DISMISS_ACTIVE = Color.parseColor("#E6DC2626")
    }
}
