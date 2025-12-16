package com.example.eeglabeler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.example.eeglabeler.ui.PatchPacketParser
import com.example.eeglabeler.ui.WaveformCanvas
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding

class MainActivity : ComponentActivity() {
    private lateinit var perms: PermissionGate
    private lateinit var ble: CgxBleClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms = PermissionGate(this)
        ble = CgxBleClient(this)
        perms.requestBlePermissions()

        setContent { App(ble = ble) }
    }
}

@Composable
fun App(ble: CgxBleClient) {
    LocalContext.current // keep if you later need context-based checks

    var batteryText by remember { mutableStateOf("...") }
    var signalText by remember { mutableStateOf("...") }
    var connected by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("⏸ Idle") }

    // EEG channel UI state
    val channelLabels = remember {
        listOf("Fp1_d", "Fp2_d", "F7_d", "F8_d", "T3_d", "T4_d", "O1_d", "O2_d")
    }
    val channelEnabled = remember {
        mutableStateListOf(true, true, true, true, true, true, true, true)
    }

    // Stream samples directly to the renderer (oscilloscope-style)
    val sampleFlow = remember {
        MutableSharedFlow<FloatArray>(
            replay = 0,
            extraBufferCapacity = 8192,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }

    // Start scanning once (you can add a reconnect button later)
    LaunchedEffect(Unit) {
        connected = false
        scanning = true
        statusText = "🔍 Scanning..."
        ble.stopScan()
        ble.disconnect()
        ble.startScan()
    }

    // Auto-connect when scanning
    LaunchedEffect(scanning, connected) {
        if (scanning && !connected) {
            ble.scanHits.collect { h ->
                if ((h.name ?: "").startsWith("PEEG")) {
                    ble.stopScan()
                    scanning = false
                    ble.connect(h.address)
                }
            }
        }
    }

    // Connection events
    LaunchedEffect(Unit) {
        ble.events.collect { event ->
            if (event == "connected") {
                connected = true
                scanning = false
                statusText = "🟢 Connected"
            }
        }
    }

    // Data stream
    LaunchedEffect(Unit) {
        ble.notifyBytes.collect { bytes ->
            PatchPacketParser.tryParse(bytes)?.let { parsed ->
                // Copy to avoid any reuse/mutation issues
                sampleFlow.tryEmit(parsed.channels.copyOf())

                batteryText = "${parsed.batteryVoltage?.let { String.format("%.2f", it) }} V"
                signalText = "Fp1_d: ${String.format("%.2f", parsed.channels[0])}"
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp)
    ) {
        Text("Status: $statusText", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Battery: $batteryText", style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(8.dp))

        // Compact waveform channel toggles
        val chipWidth = 40.dp
        val chipHeight = 24.dp
        val chipSpacing = 2.dp

        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(chipSpacing),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(channelLabels) { i, label ->
                    FilterChip(
                        selected = channelEnabled[i],
                        onClick = { channelEnabled[i] = !channelEnabled[i] },
                        modifier = Modifier
                            .width(chipWidth)
                            .height(chipHeight),
                        label = {
                            Text(
                                text = label.replace("_d", "").take(2),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Half-height waveform (so you can add spectrogram below)
        WaveformCanvas(
            samples = sampleFlow,
            sampleRate = 250,
            channelCount = 8,
            windowSeconds = 10f,
            normWindowSeconds = 2f,
            enabledChannels = channelEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )

        // Spectrogram will go here later (with its own toggles above it)
    }
}
