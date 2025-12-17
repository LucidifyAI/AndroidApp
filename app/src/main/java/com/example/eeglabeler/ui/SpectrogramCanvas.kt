package com.example.eeglabeler.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.math.*

@Composable
fun SpectrogramCanvas(
    samples: Flow<FloatArray>,
    sampleRate: Int,
    channelCount: Int,
    modifier: Modifier = Modifier,
    windowSeconds: Float = 600f,      // 10 minutes across width
    fftSize: Int = 256,               // your chosen FFT size
    maxFreqHz: Float = 60f,           // settable later
    enabledChannels: List<Boolean>? = null
) {
    require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be power of two" }

    var sizePx by remember { mutableStateOf(IntSize(0, 0)) }
    val bmpState = remember { mutableStateOf<Bitmap?>(null) }

    // Force Compose to repaint while bitmap is mutated in coroutines
    var redrawTick by remember { mutableIntStateOf(0) }
    fun invalidate() { redrawTick++ }

    // circular time sweep (column index)
    var colX by remember { mutableIntStateOf(0) }
    var sampleCarry by remember { mutableFloatStateOf(0f) }

    val latestEnabled by rememberUpdatedState(enabledChannels)

    // Per-channel ring buffers for FFT
    var rings by remember { mutableStateOf(Array(channelCount) { FloatArray(fftSize) }) }
    var ringHead by remember { mutableStateOf(IntArray(channelCount)) }
    var ringCount by remember { mutableStateOf(IntArray(channelCount)) }

    // FFT working buffers + tables (shared, reused per channel sequentially)
    var re = remember { FloatArray(fftSize) }
    var im = remember { FloatArray(fftSize) }
    var window = remember { FloatArray(fftSize) }
    var bitrev = remember { IntArray(fftSize) }
    var cosTable = remember { FloatArray(fftSize / 2) }
    var sinTable = remember { FloatArray(fftSize / 2) }

    // One column of pixels (full height)
    var colPixels = remember { IntArray(1) }

    fun initFftTables(n: Int) {
        // Hann window
        for (i in 0 until n) {
            window[i] = (0.5f - 0.5f * cos((2.0 * Math.PI * i) / (n - 1))).toFloat()
        }

        // bit reversal
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            var x = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            bitrev[i] = r
        }

        // twiddle tables
        for (k in 0 until n / 2) {
            val ang = (-2.0 * Math.PI * k / n)
            cosTable[k] = cos(ang).toFloat()
            sinTable[k] = sin(ang).toFloat()
        }
    }

    fun fftInPlace(n: Int) {
        // bit-reversed reorder
        for (i in 0 until n) {
            val j = bitrev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var j = 0
                var k = 0
                while (j < half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]

                    val ur = re[i + j]
                    val ui = im[i + j]
                    val vr = re[i + j + half] * wr - im[i + j + half] * wi
                    val vi = re[i + j + half] * wi + im[i + j + half] * wr

                    re[i + j] = ur + vr
                    im[i + j] = ui + vi
                    re[i + j + half] = ur - vr
                    im[i + j + half] = ui - vi

                    j++
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }

    fun turboLike(t: Float): Int {
        val x = t.coerceIn(0f, 1f)
        fun lerp(a: Int, b: Int, u: Float) = (a + (b - a) * u).roundToInt().coerceIn(0, 255)

        data class RGB(val r: Int, val g: Int, val b: Int)
        val pts = arrayOf(
            0.00f to RGB(0, 0, 0),
            0.20f to RGB(0, 0, 255),
            0.40f to RGB(0, 255, 255),
            0.60f to RGB(0, 255, 0),
            0.80f to RGB(255, 255, 0),
            0.95f to RGB(255, 0, 0),
            1.00f to RGB(255, 255, 255)
        )

        var i = 0
        while (i < pts.size - 1 && x > pts[i + 1].first) i++
        val (x0, c0) = pts[i]
        val (x1, c1) = pts[i + 1]
        val u = if (x1 > x0) ((x - x0) / (x1 - x0)) else 0f

        val r = lerp(c0.r, c1.r, u)
        val g = lerp(c0.g, c1.g, u)
        val b = lerp(c0.b, c1.b, u)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun getRingChronological(ch: Int, dst: FloatArray) {
        val head = ringHead[ch]         // next write
        val count = ringCount[ch]
        val n = dst.size
        val r = rings[ch]

        if (count < n) {
            // not full yet: start at 0
            for (i in 0 until n) dst[i] = r[i]
            return
        }

        // full: oldest is at head
        var idx = head
        for (i in 0 until n) {
            dst[i] = r[idx]
            idx++
            if (idx == n) idx = 0
        }
    }

    fun writeColumnMulti(
        bmp: Bitmap,
        x: Int,
        w: Int,
        h: Int,
        visibleChannels: List<Int>,
        magsDbPerChan: Array<FloatArray>, // [visibleIndex][bin]
        dbMin: Float,
        dbMax: Float
    ) {
        if (colPixels.size != h) colPixels = IntArray(h)
        java.util.Arrays.fill(colPixels, 0xFF000000.toInt())

        val bandCount = max(1, visibleChannels.size)
        val bandH = max(1, h / bandCount)

        for (vi in visibleChannels.indices) {
            val bandTop = vi * bandH
            val bandBottomExclusive = if (vi == bandCount - 1) h else (vi + 1) * bandH
            val thisBandH = max(1, bandBottomExclusive - bandTop)

            val magsDb = magsDbPerChan[vi]
            val bins = magsDb.size

            for (b in 0 until bins) {
                val t = ((magsDb[b] - dbMin) / (dbMax - dbMin)).coerceIn(0f, 1f)
                val c = turboLike(t)

                // map bin -> y-range within this band (fill full band, no gaps)
                val y0 = floor((b.toFloat() / bins) * thisBandH).toInt().coerceIn(0, thisBandH)
                val y1 = floor(((b + 1).toFloat() / bins) * thisBandH).toInt().coerceIn(0, thisBandH)

                for (yy in y0 until max(y0 + 1, y1)) {
                    val y = bandTop + yy
                    if (y in 0 until h) {
                        colPixels[(h - 1) - y] = c
                    }
                }
            }
        }

        bmp.setPixels(colPixels, 0, 1, x, 0, 1, h)
    }

    // Init / reset on size or params change
    LaunchedEffect(sizePx, fftSize, sampleRate, windowSeconds, maxFreqHz, channelCount) {
        val w = sizePx.width
        val h = sizePx.height
        if (w <= 0 || h <= 0) return@LaunchedEffect

        bmpState.value = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(0xFF000000.toInt())
        }
        colX = 0
        sampleCarry = 0f

        // reallocate per-channel state
        rings = Array(channelCount) { FloatArray(fftSize) }
        ringHead = IntArray(channelCount)
        ringCount = IntArray(channelCount)

        // reallocate FFT buffers/tables
        re = FloatArray(fftSize)
        im = FloatArray(fftSize)
        window = FloatArray(fftSize)
        bitrev = IntArray(fftSize)
        cosTable = FloatArray(fftSize / 2)
        sinTable = FloatArray(fftSize / 2)

        initFftTables(fftSize)
        invalidate()
    }

    // Clear on toggle mask change (so layout of bands updates immediately)
    val maskKey = remember(enabledChannels) {
        enabledChannels?.joinToString("") { if (it) "1" else "0" } ?: "all"
    }
    LaunchedEffect(maskKey) {
        bmpState.value?.eraseColor(0xFF000000.toInt())
        colX = 0
        sampleCarry = 0f
        for (ch in 0 until channelCount) {
            ringHead[ch] = 0
            ringCount[ch] = 0
        }
        invalidate()
    }

    // Collect samples and render columns as time advances
    LaunchedEffect(samples, sizePx, fftSize, sampleRate, windowSeconds, maxFreqHz, channelCount) {
        val w = sizePx.width
        val h = sizePx.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val bmp = bmpState.value ?: return@LaunchedEffect
        if (sampleRate <= 0) return@LaunchedEffect

        val secondsPerCol = windowSeconds / w.toFloat()
        val samplesPerCol = sampleRate * secondsPerCol

        val maxBin = min(fftSize / 2, floor(maxFreqHz * fftSize / sampleRate.toFloat()).toInt())
        val bins = max(1, maxBin + 1)

        val temp = FloatArray(fftSize)
        val magsDb = FloatArray(bins)

        val dbMin = -90f
        val dbMax = 0f

        samples.collect { frame ->
            if (frame.size < channelCount) return@collect

            // push every channel sample into its ring (cheap)
            for (ch in 0 until channelCount) {
                val v = frame[ch]
                rings[ch][ringHead[ch]] = v
                ringHead[ch] = (ringHead[ch] + 1) % fftSize
                ringCount[ch] = min(fftSize, ringCount[ch] + 1)
            }

            sampleCarry += 1f

            while (sampleCarry >= samplesPerCol) {
                sampleCarry -= samplesPerCol

                val enabled = latestEnabled
                val visible =
                    if (enabled != null && enabled.size >= channelCount)
                        (0 until channelCount).filter { enabled[it] }
                    else
                        (0 until channelCount).toList()

                // Build mags for each visible channel, then render into stacked bands
                val magsPerChan = Array(max(1, visible.size)) { FloatArray(bins) }

                if (visible.isEmpty()) {
                    // draw blank column if nothing enabled
                    writeColumnMulti(bmp, colX, w, h, listOf(0), magsPerChan, dbMin, dbMax)
                } else {
                    for (vi in visible.indices) {
                        val ch = visible[vi]
                        if (ringCount[ch] < fftSize) {
                            // not enough data yet
                            for (b in 0 until bins) magsPerChan[vi][b] = -120f
                            continue
                        }

                        // time-domain window
                        getRingChronological(ch, temp)
                        for (i in 0 until fftSize) {
                            re[i] = temp[i] * window[i]
                            im[i] = 0f
                        }

                        // FFT
                        fftInPlace(fftSize)

                        // magnitude -> dB for bins
                        for (b in 0 until bins) {
                            val mag2 = re[b] * re[b] + im[b] * im[b]
                            magsDb[b] = (10f * log10(mag2 + 1e-12f))
                        }
                        // copy to this channel’s buffer
                        System.arraycopy(magsDb, 0, magsPerChan[vi], 0, bins)
                    }

                    writeColumnMulti(bmp, colX, w, h, visible, magsPerChan, dbMin, dbMax)
                }

                colX++
                if (colX >= w) colX = 0

                invalidate()
            }
        }
    }

    // Display bitmap; reading redrawTick forces refresh
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
