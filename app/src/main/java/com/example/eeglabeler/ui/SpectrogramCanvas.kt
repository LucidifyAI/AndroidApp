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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.math.*

@Composable
fun SpectrogramCanvas(
    samples: Flow<FloatArray>,
    sampleRate: Int,
    channelCount: Int,
    modifier: Modifier = Modifier,

    // time range shown across width
    windowSeconds: Float = 600f, // 10 minutes

    // FFT parameters
    fftSize: Int = 256,

    // display range (settable later)
    maxFreqHz: Float = 60f,

    // which channel(s) are enabled for spectrogram; first enabled is displayed
    enabledChannels: List<Boolean>? = null
) {
    require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be power of two" }

    var sizePx by remember { mutableStateOf(IntSize(0, 0)) }
    val bmpState = remember { mutableStateOf<Bitmap?>(null) }

    // forces Compose to repaint the Canvas while we mutate the bitmap off-thread
    var redrawTick by remember { mutableIntStateOf(0) }
    fun invalidate() { redrawTick++ }

    // circular column sweep
    var colX by remember { mutableIntStateOf(0) }
    var sampleCarry by remember { mutableFloatStateOf(0f) }

    val latestEnabled by rememberUpdatedState(enabledChannels)

    // ring buffer for the selected channel (fftSize samples)
    val ring = remember { FloatArray(256) } // resized on init below
    var ringHead by remember { mutableIntStateOf(0) } // next write index
    var ringCount by remember { mutableIntStateOf(0) } // up to fftSize

    // FFT working buffers
    var re = remember { FloatArray(256) }
    var im = remember { FloatArray(256) }
    var window = remember { FloatArray(256) }
    var bitrev = remember { IntArray(256) }
    var cosTable = remember { FloatArray(256 / 2) }
    var sinTable = remember { FloatArray(256 / 2) }

    // Column pixels (1 column wide)
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

    fun pickSpectrogramChannel(): Int {
        val e = latestEnabled
        if (e == null || e.size < channelCount) return 0
        for (i in 0 until channelCount) if (e[i]) return i
        return 0
    }

    fun getRingChronological(dst: FloatArray, n: Int) {
        // ringHead is next write; oldest is ringHead when full
        val start = if (ringCount < n) 0 else ringHead
        var idx = start
        for (i in 0 until n) {
            dst[i] = ring[idx]
            idx++
            if (idx == n) idx = 0
        }
    }

    fun writeColumn(
        bmp: Bitmap,
        x: Int,
        h: Int,
        magsDb: FloatArray, // length bins
    ) {
        if (colPixels.size != h) colPixels = IntArray(h)

        // normalize within this column (simple, robust)
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (v in magsDb) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val range = max(1e-6f, mx - mn)

        // Clear column to black then draw bins as vertical “heat”
        // Map bins 0..B-1 to y bottom..top
        java.util.Arrays.fill(colPixels, 0xFF000000.toInt())

        val bins = magsDb.size
        for (b in 0 until bins) {
            val t = ((magsDb[b] - mn) / range).coerceIn(0f, 1f)
            val inten = (t * 255f).roundToInt().coerceIn(0, 255)
            val c = (0xFF shl 24) or (inten shl 16) or (inten shl 8) or inten

            val y = (h - 1) - ((b.toFloat() / (bins - 1).toFloat()) * (h - 1)).roundToInt()
            if (y in 0 until h) colPixels[y] = c
        }

        bmp.setPixels(colPixels, 0, 1, x, 0, 1, h)
    }

    // bitmap init on size change
    LaunchedEffect(sizePx, fftSize, sampleRate, windowSeconds, maxFreqHz) {
        val w = sizePx.width
        val h = sizePx.height
        if (w <= 0 || h <= 0) return@LaunchedEffect

        bmpState.value = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(0xFF000000.toInt())
        }
        colX = 0
        sampleCarry = 0f

        // resize FFT buffers if needed
        re = FloatArray(fftSize)
        im = FloatArray(fftSize)
        window = FloatArray(fftSize)
        bitrev = IntArray(fftSize)
        cosTable = FloatArray(fftSize / 2)
        sinTable = FloatArray(fftSize / 2)

        // ring buffer sized to fftSize
        // (use existing `ring` array name by copying into it)
        // easiest: recreate via reflection-free approach:
        for (i in ring.indices) ring[i] = 0f
        ringHead = 0
        ringCount = 0

        initFftTables(fftSize)
        invalidate()
    }

    // clear on enabled mask change so the “selected channel” switch is obvious
    val maskKey = remember(enabledChannels) {
        enabledChannels?.joinToString("") { if (it) "1" else "0" } ?: "all"
    }
    LaunchedEffect(maskKey) {
        bmpState.value?.eraseColor(0xFF000000.toInt())
        colX = 0
        sampleCarry = 0f
        ringHead = 0
        ringCount = 0
        invalidate()
    }

    // collect samples: push selected channel into ring; render columns as time advances
    LaunchedEffect(samples, sizePx, fftSize, sampleRate, windowSeconds, maxFreqHz) {
        val w = sizePx.width
        val h = sizePx.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val bmp = bmpState.value ?: return@LaunchedEffect
        if (sampleRate <= 0) return@LaunchedEffect

        val secondsPerCol = windowSeconds / w.toFloat()
        val samplesPerCol = sampleRate * secondsPerCol

        val maxBin = min(fftSize / 2, floor(maxFreqHz * fftSize / sampleRate.toFloat()).toInt())
        val bins = max(1, maxBin + 1)
        val magsDb = FloatArray(bins)

        val temp = FloatArray(fftSize)

        samples.collect { frame ->
            if (frame.size < channelCount) return@collect
            val ch = pickSpectrogramChannel()
            val v = frame[ch]

            // push into ring
            // NOTE: ring array was created fixed at 256; ensure fftSize is 256 per your requirement
            ring[ringHead] = v
            ringHead = (ringHead + 1) % fftSize
            ringCount = min(fftSize, ringCount + 1)

            sampleCarry += 1f

            // for 10-min view, columns advance slowly; still update exactly as samples accrue
            while (sampleCarry >= samplesPerCol) {
                sampleCarry -= samplesPerCol

                if (ringCount < fftSize) {
                    // not enough data yet; still advance a blank column so time starts moving
                    writeColumn(bmp, colX, h, FloatArray(bins) { -120f })
                } else {
                    // build chronological window, apply window function
                    getRingChronological(temp, fftSize)
                    for (i in 0 until fftSize) {
                        re[i] = temp[i] * window[i]
                        im[i] = 0f
                    }

                    fftInPlace(fftSize)

                    // magnitude -> dB (log)
                    for (b in 0 until bins) {
                        val mag2 = re[b] * re[b] + im[b] * im[b]
                        // add epsilon to avoid -inf
                        magsDb[b] = (10f * log10(mag2 + 1e-12f))
                    }

                    writeColumn(bmp, colX, h, magsDb)
                }

                colX++
                if (colX >= w) colX = 0

                invalidate()
            }
        }
    }

    // display bitmap; redrawTick forces refresh
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
