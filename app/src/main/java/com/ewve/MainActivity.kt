package com.ewve

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var video: VideoView
    private var playerHint: TextView? = null
    private var playControl: TextView? = null
    private var videoReady = false

    private val navy = Color.rgb(12, 30, 61)
    private val panel = Color.rgb(17, 39, 75)
    private val yellow = Color.rgb(255, 210, 35)
    private val white = Color.rgb(245, 247, 250)
    private val requestVideo = 410
    private val storyboardFrameCount = 120

    private val frameIds = (1..storyboardFrameCount).toMutableList()
    private val selectedFrames = mutableSetOf<Int>()
    private val frameTiles = mutableMapOf<Int, FrameLayout>()
    private val frameImageViews = mutableMapOf<Int, ImageView>()
    private val frameBitmaps = mutableMapOf<Int, Bitmap>()
    private var rangeAnchor: Int? = null
    private var storyboardTitle: TextView? = null
    private var storyboardGrid: GridLayout? = null
    @Volatile private var extractionGeneration = 0
    private var extractionDone = 0
    private val processorProgress = intArrayOf(0, 0)
    private var videoDurationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = Color.BLACK
        buildScreen()
    }

    private fun bg(color: Int, stroke: Int = Color.rgb(65, 83, 111)): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun button(
        label: String,
        size: Float = 15f,
        color: Int = white,
        fill: Int = panel,
        weight: Float = 1f
    ): TextView = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = bg(fill)
        setPadding(dp(3), dp(5), dp(3), dp(5))
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, dp(58), weight).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        }
    }

    private fun horizontal(parent: LinearLayout, height: Int = -2): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (height == -2) LinearLayout.LayoutParams.WRAP_CONTENT else dp(height)
            )
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

        val topScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, dp(58))
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topScroll.addView(topRow)
        page.addView(topScroll)

        var deleteButton: TextView? = null
        listOf(
            "Import\nVideos", "Export\nVideo", "Delete", "Tracks",
            "Color\nAnd Light", "Blur\nSharpen\nNoise", "Merge\nJoin", "Video\nText"
        ).forEachIndexed { index, title ->
            val b = button(
                title,
                if (index == 1 || index == 3) 15f else 14f,
                if (index == 2) Color.rgb(255, 90, 100) else white,
                navy
            )
            b.layoutParams = LinearLayout.LayoutParams(
                dp(if (index == 4 || index == 5) 105 else 92), dp(58)
            )
            topRow.addView(b)
            if (index == 0) b.setOnClickListener { chooseVideo() }
            if (index == 2) deleteButton = b
        }

        val workRow = horizontal(page, 155).apply { gravity = Gravity.CENTER }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(58), -1)
        }
        workRow.addView(left)
        listOf("UnDo", "ReDo", "Cancel").forEach {
            left.addView(button(it, 17f, Color.GRAY, Color.rgb(20, 29, 44)).apply {
                alpha = 0.55f
                isEnabled = false
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
            })
        }

        val playColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(55), -1)
        }
        workRow.addView(playColumn)
        listOf("Play\n⏯", "⏩\nForward", "⏪\nBack", "Speed\nPlay").forEach { label ->
            val isPlay = label.startsWith("Play")
            val b = button(
                label, 13f,
                if (isPlay) white else Color.GRAY,
                if (isPlay) panel else Color.rgb(20, 29, 44)
            )
            b.alpha = if (isPlay) 1f else 0.45f
            b.isEnabled = isPlay
            b.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            playColumn.addView(b)
            if (isPlay) {
                playControl = b
                b.setOnClickListener {
                    if (!videoReady) {
                        Toast.makeText(this, "ابتدا یک ویدیو وارد کنید", Toast.LENGTH_SHORT).show()
                    } else if (video.isPlaying) {
                        video.pause()
                        b.text = "Play\n⏯"
                    } else {
                        video.start()
                        b.text = "Pause\n⏸"
                    }
                }
            }
        }

        val playerFrame = FrameLayout(this).apply {
            background = bg(Color.BLACK, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        video = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        playerFrame.addView(video)
        playerHint = TextView(this).apply {
            text = "Player\nImport Videos"
            textSize = 24f
            setTextColor(Color.rgb(255, 65, 75))
            gravity = Gravity.CENTER
            rotation = -12f
            isClickable = false
        }
        playerFrame.addView(playerHint, FrameLayout.LayoutParams(-1, -1))
        workRow.addView(playerFrame)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(58), -1)
        }
        workRow.addView(right)
        right.addView(button("Crop", 23f, Color.GRAY, Color.rgb(20, 29, 44)).apply {
            alpha = 0.45f
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        right.addView(button("Cover\nFrame 🖼", 14f, Color.GRAY, Color.rgb(20, 29, 44)).apply {
            alpha = 0.45f
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })

        val transport = horizontal(page, 32)
        val info = TextView(this).apply {
            text = "UP     ALL VIEW      Time Lines   00:05"
            textSize = 15f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = bg(navy)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        transport.addView(info)

        var selectAllButton: TextView? = null
        var unselectButton: TextView? = null
        listOf("Select All", "Un Select", "Search\nBy Photo").forEachIndexed { index, label ->
            val b = button(
                label, 13f,
                if (index == 2) Color.rgb(135, 95, 65) else Color.DKGRAY,
                if (index == 2) Color.rgb(255, 225, 175) else yellow
            )
            b.layoutParams = LinearLayout.LayoutParams(
                dp(if (index == 2) 86 else 82), -1
            ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            transport.addView(b)
            if (index == 0) selectAllButton = b
            if (index == 1) unselectButton = b
            if (index == 2) {
                b.alpha = 0.45f
                b.isEnabled = false
            }
        }

        storyboardTitle = TextView(this).apply {
            text = "STORYBOARD  •  120 FRAMES  •  Waiting for video"
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
            columnCount = 5
            useDefaultMargins = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        scroll.addView(storyboardGrid)
        page.addView(scroll)

        selectAllButton?.setOnClickListener {
            selectedFrames.clear()
            selectedFrames.addAll(frameIds)
            rangeAnchor = null
            refreshSelection()
        }
        unselectButton?.setOnClickListener {
            selectedFrames.clear()
            rangeAnchor = null
            refreshSelection()
        }
        deleteButton?.setOnClickListener {
            if (selectedFrames.isNotEmpty()) {
                val deleted = selectedFrames.toList()
                frameIds.removeAll(selectedFrames)
                selectedFrames.clear()
                rangeAnchor = null
                deleted.forEach { id ->
                    frameImageViews[id]?.setImageDrawable(null)
                    frameBitmaps.remove(id)?.let { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    frameImageViews.remove(id)
                    frameTiles.remove(id)
                }
                renderStoryboard()
            } else {
                Toast.makeText(this, "ابتدا اسلایدها را انتخاب کنید", Toast.LENGTH_SHORT).show()
            }
        }

        renderStoryboard()
    }

    private fun refreshSelection() {
        frameTiles.forEach { (frameId, tile) ->
            val selected = frameId in selectedFrames
            tile.foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(if (selected) 3 else 1), if (selected) yellow else Color.rgb(55, 70, 112))
            }
        }
        val extracted = frameBitmaps.size
        storyboardTitle?.text = buildString {
            append("STORYBOARD  •  ${frameIds.size} FRAMES  •  Selected: ${selectedFrames.size}")
            append("  •  Images: $extracted/$storyboardFrameCount")
            if (rangeAnchor != null) append("  •  Start: ${rangeAnchor!!.toString().padStart(3, '0')} (hold second)")
        }
    }

    private fun renderStoryboard() {
        val grid = storyboardGrid ?: return
        grid.removeAllViews()
        frameTiles.clear()
        frameImageViews.clear()

        frameIds.forEach { frameId ->
            val tile = FrameLayout(this).apply {
                background = bg(Color.rgb(15, 25, 55))
                isClickable = true
                isLongClickable = true
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(20 + (frameId % 3) * 3, 31, 82))
                contentDescription = "Storyboard frame $frameId"
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            tile.addView(image)

            val number = TextView(this).apply {
                text = String.format("%03d", frameId)
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(1), dp(3), dp(1))
                setBackgroundColor(0x99000000.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                )
            }
            tile.addView(number)

            tile.setOnClickListener {
                // First tap sets the endpoint and toggles its selection state.
                rangeAnchor = frameId
                if (!selectedFrames.add(frameId)) selectedFrames.remove(frameId)
                refreshSelection()
            }
            tile.setOnLongClickListener {
                val anchor = rangeAnchor
                if (anchor == null) {
                    Toast.makeText(this, "ابتدا اسلاید اول را لمس کنید", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    val firstIndex = frameIds.indexOf(anchor)
                    val secondIndex = frameIds.indexOf(frameId)
                    if (firstIndex >= 0 && secondIndex >= 0) {
                        // The first endpoint's post-tap state chooses select or deselect.
                        val selectRange = anchor in selectedFrames
                        val low = min(firstIndex, secondIndex)
                        val high = max(firstIndex, secondIndex)
                        for (index in low..high) {
                            val id = frameIds[index]
                            if (selectRange) selectedFrames.add(id) else selectedFrames.remove(id)
                        }
                    }
                    rangeAnchor = null
                    refreshSelection()
                    true
                }
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(76)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            grid.addView(tile, params)
            frameTiles[frameId] = tile
            frameImageViews[frameId] = image
            frameBitmaps[frameId]?.let { image.setImageBitmap(it) }
        }
        refreshSelection()
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, requestVideo)
    }

    @Deprecated("Legacy callback retained for compatibility with the starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestVideo && resultCode == RESULT_OK) {
            data?.data?.let { uri -> loadVideo(uri) }
        }
    }

    private fun loadVideo(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not grant persistable permissions.
        }

        extractionGeneration++
        val generation = extractionGeneration
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        frameBitmaps.clear()
        selectedFrames.clear()
        frameIds.clear()
        frameIds.addAll(1..storyboardFrameCount)
        rangeAnchor = null
        extractionDone = 0
        videoDurationMs = 0L
        videoReady = false
        playControl?.text = "Play\n⏯"
        video.stopPlayback()
        renderStoryboard()
        storyboardTitle?.text = "STORYBOARD  •  Preparing video and two frame processors..."
        playerHint?.visibility = View.VISIBLE

        video.setOnPreparedListener { mediaPlayer ->
            if (generation != extractionGeneration) return@setOnPreparedListener
            mediaPlayer.isLooping = false
            videoReady = true
            videoDurationMs = mediaPlayer.duration.toLong().coerceAtLeast(1L)
            playControl?.text = "Play\n⏯"
            mediaPlayer.setOnCompletionListener { playControl?.text = "Play\n⏯" }
            playerHint?.visibility = View.GONE
            startTwoFrameProcessors(uri, videoDurationMs, generation)
        }
        video.setOnErrorListener { _, what, extra ->
            if (generation == extractionGeneration) {
                videoReady = false
                playerHint?.visibility = View.VISIBLE
                storyboardTitle?.text = "Video load error ($what / $extra)"
            }
            true
        }
        video.setVideoURI(uri)
    }

    /** Two independent retrievers process the first and second halves concurrently. */
    private fun startTwoFrameProcessors(uri: Uri, durationMs: Long, generation: Int) {
        extractionDone = 0
        processorProgress[0] = 0
        processorProgress[1] = 0
        refreshSelection()
        storyboardTitle?.text = "STORYBOARD  •  P1: 001–060  |  P2: 061–120  •  Starting..."

        val midpoint = storyboardFrameCount / 2
        val firstProcessor = Thread {
            extractFrameRange(uri, durationMs, 0, midpoint, generation, 0)
        }.apply { name = "Storyboard-Frame-Processor-1" }
        val secondProcessor = Thread {
            extractFrameRange(uri, durationMs, midpoint, storyboardFrameCount, generation, 1)
        }.apply { name = "Storyboard-Frame-Processor-2" }

        firstProcessor.start()
        secondProcessor.start()
    }

    private fun extractFrameRange(
        uri: Uri,
        durationMs: Long,
        startIndex: Int,
        endIndexExclusive: Int,
        generation: Int,
        processorIndex: Int
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            for (index in startIndex until endIndexExclusive) {
                if (generation != extractionGeneration) break
                val timeUs = if (storyboardFrameCount <= 1) 0L else
                    (durationMs * 1000L * index) / (storyboardFrameCount - 1)
                val original = retriever.getFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST
                )
                val thumbnail = original?.let { makeThumbnail(it) }
                if (original != null && thumbnail !== original && !original.isRecycled) original.recycle()

                runOnUiThread {
                    if (generation == extractionGeneration) {
                        if (thumbnail != null) {
                            val frameId = index + 1
                            if (frameId in frameIds) {
                                frameBitmaps.put(frameId, thumbnail)?.let { old ->
                                    if (old !== thumbnail && !old.isRecycled) old.recycle()
                                }
                                frameImageViews[frameId]?.setImageBitmap(thumbnail)
                            } else if (!thumbnail.isRecycled) {
                                thumbnail.recycle()
                            }
                        }
                        extractionDone++
                        processorProgress[processorIndex]++
                        updateExtractionStatus()
                    } else if (thumbnail != null && !thumbnail.isRecycled) {
                        thumbnail.recycle()
                    }
                }
            }
        } catch (error: Exception) {
            runOnUiThread {
                if (generation == extractionGeneration) {
                    storyboardTitle?.text = "Frame extraction issue (processor ${processorIndex + 1}): ${error.message ?: "unknown"}"
                }
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun makeThumbnail(source: Bitmap): Bitmap {
        // Keep thumbnails small to avoid excessive memory use on Android devices.
        val maxWidth = 240
        val maxHeight = 150
        val scale = min(
            1f,
            min(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
        )
        if (scale >= 0.999f) return source
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun updateExtractionStatus() {
        val count = frameBitmaps.size
        val p1 = processorProgress[0]
        val p2 = processorProgress[1]
        val state = if (extractionDone >= storyboardFrameCount) "Complete" else "Running"
        storyboardTitle?.text =
            "STORYBOARD • Images: $count/$storyboardFrameCount • P1: $p1/60 • P2: $p2/60 • $state"
    }

    override fun onDestroy() {
        extractionGeneration++
        super.onDestroy()
    }
}
