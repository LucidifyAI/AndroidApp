package com.example.eeglabeler.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun WaveformCanvas(
    samples: Flow<FloatArray>,
    sampleRate: Int,
    channelCount: Int,
    modifier: Modifier = Modifier,
    windowSeconds: Float = 10f,
    normWindowSeconds: Float = 2.0f,
    enabledChannels: List<Boolean>? = null,
    drawGreenCursor: Boolean = true
) {
    var sizePx by remember { mutableStateOf(IntSize(0, 0)) }
    val bmpState = remember { mutableStateOf<Bitmap?>(null) }
    val canvasState = remember { mutableStateOf<android.graphics.Canvas?>(null) }

    // IMPORTANT: changes here invalidate Compose drawing
    var redrawTick by remember { mutableIntStateOf(0) }

    var sweepColumn by remember { mutableIntStateOf(0) }
    var sampleCarry by remember { mutableFloatStateOf(0f) }

    var prevX by remember { mutableStateOf(IntArray(channelCount)) }
    var prevY by remember { mutableStateOf(IntArray(channelCount)) }
    var hasPrev by remember { mutableStateOf(BooleanArray(channelCount)) }

    data class NormState(var ring: FloatArray, var head: Int = 0, var count: Int = 0)
    var normStates by remember { mutableStateOf(Array(channelCount) { NormState(FloatArray(1)) }) }

    val latestEnabled by rememberUpdatedState(enabledChannels)

    // queue of incoming samples
    val queue = remember { ArrayDeque<FloatArray>(8192) } // bigger to tolerate BLE bursts

    val paintBlack = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = android.graphics.Color.BLACK
        }
    }
    val paintWhite = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = android.graphics.Color.WHITE
        }
    }
    val paintGreen = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = android.graphics.Color.GREEN
        }
    }

    fun invalidateCanvas() {
        redrawTick++
    }

    fun clearBitmap() {
        canvasState.value?.drawColor(android.graphics.Color.BLACK)
        invalidateCanvas()
    }

    fun clearColumn(x: Int, h: Int) {
        canvasState.value?.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), paintBlack)
    }

    fun drawCursor(x: Int, h: Int) {
        if (!drawGreenCursor) return
        canvasState.value?.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), paintGreen)
    }

    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int) {
        canvasState.value?.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paintWhite)
    }

    fun ensureNormWindow() {
        val want = max(1, (normWindowSeconds * sampleRate).roundToInt())
        for (ch in 0 until channelCount) {
            if (normStates[ch].ring.size != want) normStates[ch] = NormState(FloatArray(want))
        }
    }

    fun pushNorm(ch: Int, v: Float) {
        val st = normStates[ch]
        st.ring[st.head] = v
        st.head = (st.head + 1) % st.ring.size
        st.count = min(st.count + 1, st.ring.size)
    }

    fun minMax(ch: Int): Pair<Float, Float> {
        val st = normStates[ch]
        if (st.count <= 0) return 0f to 0f
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        val n = st.count
        val size = st.ring.size
        var idx = st.head - n
        while (idx < 0) idx += size
        repeat(n) {
            val v = st.ring[idx]
            if (v < mn) mn = v
            if (v > mx) mx = v
            idx++
            if (idx == size) idx = 0
        }
        if (mn == Float.POSITIVE_INFINITY) mn = 0f
        if (mx == Float.NEGATIVE_INFINITY) mx = 0f
        return mn to mx
    }

    fun normalize(ch: Int, v: Float): Float {
        val (mn, mx) = minMax(ch)
        val r = mx - mn
        if (r < 1e-9f) return 0f
        val t = (v - mn) / r
        return (t * 2f - 1f).coerceIn(-1f, 1f)
    }

    fun yForBand(vi: Int, vCount: Int, h: Int, n: Float): Int {
        val segH = max(1, h / max(1, vCount))
        val base = vi * segH
        val segNorm = (n + 1f) * 0.5f
        val yIn = (segNorm * (segH - 1)).roundToInt().coerceIn(0, segH - 1)
        return base + yIn
    }

    // Bitmap (re)create
    LaunchedEffect(sizePx) {
        val w = sizePx.width
        val h = sizePx.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmpState.value = b
        canvasState.value = android.graphics.Canvas(b)
        sweepColumn = 0
        sampleCarry = 0f
        hasPrev = BooleanArray(channelCount)
        prevX = IntArray(channelCount)
        prevY = IntArray(channelCount)
        ensureNormWindow()
        clearBitmap()
    }

    // Force ~60Hz redraw even if no samples (keeps cursor responsive)
    LaunchedEffect(Unit) {
        while (true) {
            invalidateCanvas()
            delay(16)
        }
    }

    // Clear on toggle mask change
    val maskKey = remember(enabledChannels) {
        enabledChannels?.joinToString("") { if (it) "1" else "0" } ?: "all"
    }
    LaunchedEffect(maskKey) {
        clearBitmap()
        for (i in 0 until channelCount) hasPrev[i] = false
    }

    // Collect incoming samples into queue (NOT collectLatest)
    LaunchedEffect(samples) {
        samples.collect { s ->
            if (s.size < channelCount) return@collect
            if (queue.size >= 8192) queue.removeFirst()
            queue.addLast(s)
        }
    }

    // Render loop: consume queue incrementally and invalidate frequently
    LaunchedEffect(sampleRate, sizePx, windowSeconds, normWindowSeconds) {
        val w = sizePx.width
        val h = sizePx.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        if (sampleRate <= 0) return@LaunchedEffect

        ensureNormWindow()

        val secondsPerPixel = windowSeconds / w.toFloat()
        val samplesPerPixel = sampleRate * secondsPerPixel

        while (true) {
            val enabled = latestEnabled
            val visible =
                if (enabled != null && enabled.size >= channelCount)
                    (0 until channelCount).filter { enabled[it] }
                else
                    (0 until channelCount).toList()
            val vCount = max(1, visible.size)

            // Consume some samples each loop.
            // If BLE bursts, this will gradually catch up without “draw whole window at once”.
            var drewAny = false
            var consumed = 0
            val maxConsumePerLoop = 128 // tune 64..512 depending on device

            while (queue.isNotEmpty() && consumed < maxConsumePerLoop) {
                val s = queue.removeFirst()
                consumed++

                for (ch in visible) pushNorm(ch, s[ch])

                sampleCarry += 1f
                while (sampleCarry >= samplesPerPixel) {
                    sampleCarry -= samplesPerPixel

                    val x = sweepColumn
                    clearColumn(x, h)

                    for (vi in visible.indices) {
                        val ch = visible[vi]
                        val n = normalize(ch, s[ch])
                        val y = yForBand(vi, vCount, h, n)

                        if (hasPrev[ch]) drawLine(prevX[ch], prevY[ch], x, y)
                        else {
                            drawLine(x, y, x, y)
                            hasPrev[ch] = true
                        }
                        prevX[ch] = x
                        prevY[ch] = y
                    }

                    sweepColumn++
                    if (sweepColumn >= w) {
                        sweepColumn = 0
                        for (ch in 0 until channelCount) hasPrev[ch] = false
                    }
                    drawCursor(sweepColumn, h)

                    drewAny = true
                }
            }

            if (drewAny) invalidateCanvas()

            // Short sleep; keeps it responsive but doesn’t peg CPU
            delay(2)
        }
    }

    // Display bitmap. Reading redrawTick makes Compose re-draw when it changes.
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = it }
    ) {
        val x99 = redrawTick
        val bmp = bmpState.value ?: return@Canvas
        drawIntoCanvas { c ->
            c.nativeCanvas.drawBitmap(bmp, 0f, 0f, null)
        }
    }
}
