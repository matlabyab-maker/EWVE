package com.ewve

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var preview: ImageView
    private lateinit var brightnessShade: View
    private var playerHint: TextView? = null
    private var playControl: TextView? = null
    private var undoControl: TextView? = null
    private var redoControl: TextView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoReady = false

    private val navy = Color.rgb(12, 30, 61)
    private val panel = Color.rgb(17, 39, 75)
    private val yellow = Color.rgb(255, 210, 35)
    private val white = Color.rgb(245, 247, 250)
    private val requestVideo = 410
    private val storyboardFrameCount = 120

    private val videos = mutableListOf<Uri>()
    private var currentVideoIndex = 0
    private val frameIds = (1..storyboardFrameCount).toMutableList()
    private val selectedFrames = mutableSetOf<Int>()
    private val frameTiles = mutableMapOf<Int, FrameLayout>()
    private val frameImageViews = mutableMapOf<Int, ImageView>()
    private val frameBitmaps = mutableMapOf<Int, Bitmap>()
    private var rangeAnchor: Int? = null
    private var storyboardTitle: TextView? = null
    private var storyboardGrid: GridLayout? = null
    private var columnSpinner: Spinner? = null
    @Volatile private var extractionGeneration = 0
    private var extractionDone = 0
    private val processorProgress = intArrayOf(0, 0)
    private var videoDurationMs = 0L
    private var playbackSpeed = 1f
    private var playerVolume = 1f
    private var playerBrightness = 1f
    private var columnCount = 5
    private var videoAspectRatio = 16f / 9f

    private data class EditState(
        val ids: List<Int>,
        val selected: Set<Int>,
        val anchor: Int?
    )
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = Color.BLACK
        buildScreen()
    }

    private fun bg(color: Int, stroke: Int = Color.rgb(65, 83, 111)) =
        GradientDrawable().apply { setColor(color); setStroke(dp(1), stroke) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun button(label: String, size: Float = 15f, color: Int = white,
                       fill: Int = panel, weight: Float = 1f): TextView =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = bg(fill)
            setPadding(dp(3), dp(4), dp(3), dp(4))
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(58), weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
        }

    private fun horizontal(parent: LinearLayout, height: Int = -2): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1,
                if (height == -2) -2 else dp(height))
        }
        parent.addView(row)
        return row
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(3, 12, 30))
            fitsSystemWindows = true
        }
        setContentView(page)

        // Fixed-width toolbar: all eight controls remain visible without horizontal scrolling.
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(58))
        }
        page.addView(topRow)
        val topItems = listOf(
            "Import\nVideos", "Export\nVideo", "Delete", "Tracks",
            "Color\nAnd Light", "Blur\nSharpen\nNoise", "Merge\nJoin", "Video\nText"
        )
        topItems.forEachIndexed { index, title ->
            val b = button(title, 10.5f,
                if (index == 2) Color.rgb(255, 90, 100) else white, navy)
            b.setPadding(dp(1), dp(2), dp(1), dp(2))
            b.layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            topRow.addView(b)
            when (index) {
                0 -> b.setOnClickListener { chooseVideos() }
                2 -> b.setOnClickListener { deleteSelectedFrames() }
                1 -> b.setOnClickListener { toast("خروجی ویدیو در مرحله بعد فعال می‌شود") }
                3 -> b.setOnClickListener { toast("مدیریت ترک‌ها در مرحله بعد فعال می‌شود") }
                else -> b.setOnClickListener { toast("این ابزار هنوز در نسخه اولیه فعال نشده است") }
            }
        }

        val workRow = horizontal(page, 155).apply { gravity = Gravity.CENTER }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(58), -1)
        }
        workRow.addView(left)
        undoControl = button("UnDo", 17f)
        redoControl = button("ReDo", 17f)
        left.addView(undoControl, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        left.addView(redoControl, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        left.addView(button("Cancel", 16f, Color.LTGRAY, Color.rgb(20, 29, 44)).apply {
            setOnClickListener {
                if (video.isPlaying) video.pause()
                video.visibility = View.VISIBLE
                preview.visibility = View.VISIBLE
                if (videos.isNotEmpty()) showPreviewAt(video.currentPosition.toLong(), videos[currentVideoIndex])
                playControl?.text = "Play\n⏯"
            }
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
        })
        undoControl?.setOnClickListener { undo() }
        redoControl?.setOnClickListener { redo() }

        val playColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(55), -1)
        }
        workRow.addView(playColumn)
        val play = button("Play\n⏯", 13f)
        playControl = play
        playColumn.addView(play, LinearLayout.LayoutParams(-1, 0, 1f))
        play.setOnClickListener {
            if (!videoReady) toast("ابتدا ویدیو وارد کنید")
            else if (video.isPlaying) {
                video.pause()
                video.visibility = View.VISIBLE
                preview.visibility = View.VISIBLE
                showPreviewAt(video.currentPosition.toLong(), videos[currentVideoIndex])
                play.text = "Play\n⏯"
            } else {
                preview.visibility = View.GONE
                video.visibility = View.VISIBLE
                video.start()
                play.text = "Pause\n⏸"
            }
        }
        val forward = button("⏩\nForward", 12f)
        val back = button("⏪\nBack", 12f)
        val speed = button("Speed\n1×", 12f)
        listOf(forward, back, speed).forEach {
            playColumn.addView(it, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        forward.setOnClickListener {
            if (videos.size > 1) switchVideo(1)
            else if (videoReady) video.seekTo((video.currentPosition + 10000).coerceAtMost(videoDurationMs.toInt()))
        }
        back.setOnClickListener {
            if (videos.size > 1) switchVideo(-1)
            else if (videoReady) video.seekTo((video.currentPosition - 10000).coerceAtLeast(0))
        }
        speed.setOnClickListener {
            playbackSpeed = when (playbackSpeed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                try { mediaPlayer?.playbackParams = mediaPlayer?.playbackParams?.setSpeed(playbackSpeed)!! }
                catch (_: Exception) {}
            }
            speed.text = "Speed\n${playbackSpeed}×"
        }

        val playerFrame = FrameLayout(this).apply {
            background = bg(Color.BLACK, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        video = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        playerFrame.addView(video)
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        playerFrame.addView(preview)
        brightnessShade = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        playerFrame.addView(brightnessShade)
        playerHint = TextView(this).apply {
            text = "Player\nImport Videos"
            textSize = 20f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            isClickable = false
        }
        playerFrame.addView(playerHint, FrameLayout.LayoutParams(-1, -1))
        installPlayerGestures(playerFrame)
        workRow.addView(playerFrame)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(58), -1)
        }
        workRow.addView(right)
        right.addView(button("Crop", 21f).apply {
            setOnClickListener { toast("برش در مرحله بعد فعال می‌شود") }
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        right.addView(button("Cover\nFrame 🖼", 13f).apply {
            setOnClickListener {
                val bmp = frameBitmaps[1]
                if (bmp != null) { preview.setImageBitmap(bmp); preview.visibility = View.VISIBLE }
                else toast("هنوز فریمی استخراج نشده است")
            }
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })

        val transport = horizontal(page, 38)
        val info = TextView(this).apply {
            text = "UP   VIDEOS 0/0"
            textSize = 13f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = bg(navy)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            setOnClickListener { if (videos.isNotEmpty()) switchVideo(1) }
        }
        transport.addView(info)
        val colsLabel = TextView(this).apply {
            text = "Columns"
            textSize = 11f
            setTextColor(yellow)
            gravity = Gravity.CENTER
        }
        transport.addView(colsLabel, LinearLayout.LayoutParams(dp(52), -1))
        columnSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                (5..15).map { "$it" })
            setSelection(0)
            layoutParams = LinearLayout.LayoutParams(dp(48), -1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    columnCount = position + 5
                    renderStoryboard()
                }
            }
        }
        transport.addView(columnSpinner)
        val selectAll = button("Select All", 12f, Color.DKGRAY, yellow)
        val unselect = button("Un Select", 12f, Color.DKGRAY, yellow)
        listOf(selectAll, unselect).forEach {
            it.layoutParams = LinearLayout.LayoutParams(dp(78), -1).apply {
                setMargins(dp(2), dp(1), dp(2), dp(1))
            }
            transport.addView(it)
        }
        selectAll.setOnClickListener {
            saveUndo()
            selectedFrames.clear(); selectedFrames.addAll(frameIds); rangeAnchor = null
            refreshSelection()
        }
        unselect.setOnClickListener {
            saveUndo()
            selectedFrames.clear(); rangeAnchor = null
            refreshSelection()
        }

        storyboardTitle = TextView(this).apply {
            text = "STORYBOARD • 120 FRAMES • Waiting for video"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = bg(navy)
        }
        page.addView(storyboardTitle)
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.rgb(4, 14, 36))
        }
        storyboardGrid = GridLayout(this).apply {
            columnCount = columnCount
            useDefaultMargins = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        scroll.addView(storyboardGrid)
        page.addView(scroll)
        renderStoryboard()
        updateHistoryButtons()
    }

    private fun installPlayerGestures(frame: FrameLayout) {
        var startY = 0f
        var startValue = 0f
        var mode = 0
        fun attach(area: View, type: Int) {
            area.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        mode = type
                        startValue = if (type == 1) playerBrightness else playerVolume
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (startY - event.rawY) / dp(180).toFloat()
                        val value = (startValue + delta).coerceIn(0f, 1f)
                        if (mode == 1) {
                            playerBrightness = value
                            brightnessShade.alpha = (1f - value) * 0.78f
                        } else {
                            playerVolume = value
                            mediaPlayer?.setVolume(value, value)
                        }
                        playerHint?.text = if (mode == 1) "Light ${(playerBrightness * 100).toInt()}%" else "Sound ${(playerVolume * 100).toInt()}%"
                        playerHint?.visibility = View.VISIBLE
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        playerHint?.visibility = if (videoReady && !video.isPlaying) View.GONE else View.GONE
                        true
                    }
                    else -> true
                }
            }
        }
        val leftArea = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, -1, Gravity.START)
        }
        val rightArea = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, -1, Gravity.END)
        }
        // Split touch surfaces into left/light and right/sound halves.
        frame.post {
            val half = frame.width / 2
            leftArea.layoutParams = FrameLayout.LayoutParams(half, -1, Gravity.START)
            rightArea.layoutParams = FrameLayout.LayoutParams(frame.width - half, -1, Gravity.END)
        }
        attach(leftArea, 1)
        attach(rightArea, 2)
        frame.addView(leftArea)
        frame.addView(rightArea)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun chooseVideos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestVideo)
    }

    @Deprecated("Legacy callback retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestVideo || resultCode != RESULT_OK || data == null) return
        val chosen = mutableListOf<Uri>()
        data.data?.let { chosen.add(it) }
        data.clipData?.let { clip ->
            chosen.clear()
            for (i in 0 until clip.itemCount) chosen.add(clip.getItemAt(i).uri)
        }
        if (chosen.isEmpty()) return
        chosen.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) {}
            if (!videos.contains(uri)) videos.add(uri)
        }
        currentVideoIndex = videos.indexOf(chosen.first()).coerceAtLeast(0)
        loadVideo(videos[currentVideoIndex])
        toast("${videos.size} ویدیو در فهرست قرار گرفت")
    }

    private fun switchVideo(delta: Int) {
        if (videos.isEmpty()) return
        currentVideoIndex = (currentVideoIndex + delta + videos.size) % videos.size
        loadVideo(videos[currentVideoIndex])
    }

    private fun loadVideo(uri: Uri) {
        extractionGeneration++
        val generation = extractionGeneration
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        frameBitmaps.clear()
        selectedFrames.clear()
        frameIds.clear(); frameIds.addAll(1..storyboardFrameCount)
        rangeAnchor = null
        extractionDone = 0
        videoDurationMs = 0L
        videoReady = false
        videoAspectRatio = readDisplayAspectRatio(uri)
        video.visibility = View.VISIBLE
        mediaPlayer = null
        playControl?.text = "Play\n⏯"
        video.stopPlayback()
        preview.setImageDrawable(null)
        preview.visibility = View.VISIBLE
        showPreviewAt(0L, uri, generation)
        playerHint?.text = "Loading video..."
        playerHint?.visibility = View.VISIBLE
        renderStoryboard()
        storyboardTitle?.text = "Preparing video • ${currentVideoIndex + 1}/${videos.size}"
        video.setOnPreparedListener { mp ->
            if (generation != extractionGeneration) return@setOnPreparedListener
            mediaPlayer = mp
            mp.isLooping = false
            mp.setVolume(playerVolume, playerVolume)
            videoReady = true
            videoDurationMs = mp.duration.toLong().coerceAtLeast(1L)
            playControl?.text = "Play\n⏯"
            mp.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    val playerRatio = width.toFloat() / height.toFloat()
                    if (playerRatio in 0.2f..5f) {
                        videoAspectRatio = if (abs(playerRatio - videoAspectRatio) < 0.08f) {
                            playerRatio
                        } else if (abs((1f / playerRatio) - videoAspectRatio) < 0.08f) {
                            1f / playerRatio
                        } else {
                            videoAspectRatio
                        }
                    }
                    renderStoryboard()
                }
                if (!video.isPlaying) video.pause()
            }
            mp.setOnCompletionListener { playControl?.text = "Play\n⏯" }
            playerHint?.visibility = View.GONE
            startTwoFrameProcessors(uri, videoDurationMs, generation)
        }
        video.setOnErrorListener { _, what, extra ->
            if (generation == extractionGeneration) {
                videoReady = false
                playerHint?.text = "Cannot play this video ($what/$extra)"
                playerHint?.visibility = View.VISIBLE
            }
            true
        }
        video.setVideoURI(uri)
    }

    private fun readDisplayAspectRatio(uri: Uri): Float {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rotated = rotation == 90 || rotation == 270
            val displayWidth = if (rotated) height else width
            val displayHeight = if (rotated) width else height
            (displayWidth / displayHeight.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
        } catch (_: Exception) {
            16f / 9f
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun showPreviewAt(positionMs: Long, uri: Uri, expectedGeneration: Int = extractionGeneration) {
        Thread({
            val retriever = MediaMetadataRetriever()
            var bitmap: Bitmap? = null
            try {
                retriever.setDataSource(this, uri)
                bitmap = retriever.getFrameAtTime(positionMs.coerceAtLeast(0L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            runOnUiThread {
                if (expectedGeneration != extractionGeneration) {
                    bitmap?.takeIf { !it.isRecycled }?.recycle()
                    return@runOnUiThread
                }
                bitmap?.let { if (!it.isRecycled) preview.setImageBitmap(it) }
            }
        }, "EWVE-Player-Preview").start()
    }

    private fun saveUndo() {
        undoStack.addLast(EditState(frameIds.toList(), selectedFrames.toSet(), rangeAnchor))
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryButtons()
    }

    private fun applyState(state: EditState) {
        frameIds.clear(); frameIds.addAll(state.ids)
        selectedFrames.clear(); selectedFrames.addAll(state.selected)
        rangeAnchor = state.anchor
        renderStoryboard()
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(EditState(frameIds.toList(), selectedFrames.toSet(), rangeAnchor))
        applyState(undoStack.removeLast())
        updateHistoryButtons()
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(EditState(frameIds.toList(), selectedFrames.toSet(), rangeAnchor))
        applyState(redoStack.removeLast())
        updateHistoryButtons()
    }

    private fun updateHistoryButtons() {
        undoControl?.alpha = if (undoStack.isEmpty()) 0.45f else 1f
        redoControl?.alpha = if (redoStack.isEmpty()) 0.45f else 1f
        undoControl?.isEnabled = undoStack.isNotEmpty()
        redoControl?.isEnabled = redoStack.isNotEmpty()
    }

    private fun deleteSelectedFrames() {
        if (selectedFrames.isEmpty()) { toast("ابتدا اسلایدها را انتخاب کنید"); return }
        saveUndo()
        val deleted = selectedFrames.toSet()
        frameIds.removeAll(deleted)
        selectedFrames.clear()
        rangeAnchor = null
        renderStoryboard()
    }

    private fun refreshSelection() {
        frameTiles.forEach { (id, tile) ->
            val selected = id in selectedFrames
            tile.foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(if (selected) 3 else 1), if (selected) yellow else Color.rgb(55, 70, 112))
            }
        }
        storyboardTitle?.text =
            "STORYBOARD • ${frameIds.size} FRAMES • Selected: ${selectedFrames.size} • Images: ${frameBitmaps.size}/$storyboardFrameCount • ${videos.size} videos"
    }

    private fun renderStoryboard() {
        val grid = storyboardGrid ?: return
        grid.removeAllViews()
        frameTiles.clear(); frameImageViews.clear()
        grid.columnCount = columnCount
        frameIds.forEach { frameId ->
            val tile = FrameLayout(this).apply {
                background = bg(Color.rgb(15, 25, 55))
                isClickable = true; isLongClickable = true
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(20 + (frameId % 3) * 3, 31, 82))
                contentDescription = "Storyboard frame $frameId"
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            tile.addView(image)
            tile.addView(TextView(this).apply {
                text = String.format("%03d", frameId)
                textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(dp(3), dp(1), dp(3), dp(1)); setBackgroundColor(0x99000000.toInt())
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
            })
            tile.setOnClickListener {
                saveUndo()
                rangeAnchor = frameId
                if (!selectedFrames.add(frameId)) selectedFrames.remove(frameId)
                refreshSelection()
            }
            tile.setOnLongClickListener {
                val anchor = rangeAnchor ?: return@setOnLongClickListener false
                val a = frameIds.indexOf(anchor); val b = frameIds.indexOf(frameId)
                if (a >= 0 && b >= 0) {
                    saveUndo()
                    val select = anchor in selectedFrames
                    for (i in min(a, b)..max(a, b)) {
                        if (select) selectedFrames.add(frameIds[i]) else selectedFrames.remove(frameIds[i])
                    }
                }
                rangeAnchor = null
                refreshSelection()
                true
            }
            val gridHorizontalPadding = dp(4)
            val cellMargins = dp(2)
            val availableWidth = (resources.displayMetrics.widthPixels - gridHorizontalPadding - dp(2)).coerceAtLeast(dp(1))
            val tileWidth = ((availableWidth - cellMargins * columnCount) / columnCount).coerceAtLeast(dp(1))
            val tileHeight = (tileWidth / videoAspectRatio).toInt().coerceAtLeast(dp(1))
            val params = GridLayout.LayoutParams().apply {
                width = tileWidth
                height = tileHeight
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            grid.addView(tile, params)
            frameTiles[frameId] = tile
            frameImageViews[frameId] = image
            frameBitmaps[frameId]?.let { image.setImageBitmap(it) }
        }
        refreshSelection()
    }

    private fun startTwoFrameProcessors(uri: Uri, durationMs: Long, generation: Int) {
        extractionDone = 0; processorProgress[0] = 0; processorProgress[1] = 0
        // Interleave frame indices so both workers populate the beginning of the storyboard
        // together instead of one worker filling frames 1-60 while the other fills 61-120.
        Thread({
            extractFrameRange(uri, durationMs, 0, storyboardFrameCount, 2, generation, 0)
        }, "EWVE-Frame-1").start()
        Thread({
            extractFrameRange(uri, durationMs, 1, storyboardFrameCount, 2, generation, 1)
        }, "EWVE-Frame-2").start()
    }

    private fun extractFrameRange(uri: Uri, durationMs: Long, start: Int, end: Int, stride: Int,
                                  generation: Int, processor: Int) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            // Decode storyboard frames at thumbnail resolution where supported instead of
            // decoding every source frame at full video resolution and shrinking it afterward.
            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 16
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 9
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val displayWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
            val displayHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
            val scale = min(1f, min(240f / displayWidth, 150f / displayHeight))
            val decodeWidth = (displayWidth * scale).toInt().coerceAtLeast(1)
            val decodeHeight = (displayHeight * scale).toInt().coerceAtLeast(1)
            for (index in start until end step stride) {
                if (generation != extractionGeneration) break
                val timeUs = durationMs * 1000L * index / (storyboardFrameCount - 1)
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST, decodeWidth, decodeHeight
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                val thumb = frame?.let { makeThumbnail(it) }
                if (frame != null && thumb !== frame && !frame.isRecycled) frame.recycle()
                runOnUiThread {
                    if (generation != extractionGeneration) {
                        if (thumb != null && !thumb.isRecycled) thumb.recycle()
                        return@runOnUiThread
                    }
                    if (thumb != null) {
                        if (index == 0 && thumb.width > 0 && thumb.height > 0) {
                            // The decoded frame is the final authority for the thumbnail's display shape.
                            // Metadata rotation is already applied by the display-ratio helper.
                            val thumbRatio = thumb.width.toFloat() / thumb.height.toFloat()
                            if (thumbRatio in 0.2f..5f && abs(thumbRatio - videoAspectRatio) < 0.08f) {
                                videoAspectRatio = thumbRatio
                                renderStoryboard()
                            }
                        }
                        val id = index + 1
                        frameBitmaps.put(id, thumb)?.let { old ->
                            if (old !== thumb && !old.isRecycled) old.recycle()
                        }
                        frameImageViews[id]?.setImageBitmap(thumb)
                        if (id == 1 && !video.isPlaying && preview.drawable == null) {
                            preview.setImageBitmap(thumb)
                            preview.visibility = View.VISIBLE
                        }
                    }
                    extractionDone++; processorProgress[processor]++
                    storyboardTitle?.text =
                        "Frames ${frameBitmaps.size}/120 • P1 ${processorProgress[0]}/60 • P2 ${processorProgress[1]}/60"
                }
            }
        } catch (e: Exception) {
            runOnUiThread { if (generation == extractionGeneration) storyboardTitle?.text = "Frame extraction: ${e.message}" }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun makeThumbnail(source: Bitmap): Bitmap {
        val maxWidth = 240; val maxHeight = 150
        val scale = min(1f, min(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height))
        if (scale >= 0.999f) return source
        return Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1), true)
    }

    override fun onDestroy() {
        extractionGeneration++
        super.onDestroy()
    }
}
