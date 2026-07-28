package com.ideliver.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideliver.capture.AccessibilityAccess
import com.ideliver.capture.DumpExporter
import com.ideliver.capture.EventLog
import com.ideliver.capture.NotificationAccess
import com.ideliver.data.MileageKind
import com.ideliver.data.MileageReading
import com.ideliver.data.MileageRepository
import com.ideliver.data.SettingsStore
import com.ideliver.model.RuleSettings
import com.ideliver.mileage.MileageCaptureActivity
import com.ideliver.overlay.OverlayAccess
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d · HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * Stateful entry point: reads permission status (refreshing on resume) and
 * observes the live [EventLog] feed.
 */
@Composable
fun SettingsRoute() {
    val context = LocalContext.current
    val activity = context as Activity

    // Ask for notification permission (Android 13+) so the dash-start/end odometer
    // prompts can appear. Harmless on older versions.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored; the prompt just won't show if denied */ }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var listenerEnabled by remember { mutableStateOf(NotificationAccess.isEnabled(context)) }
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityAccess.isEnabled(context)) }
    var overlayEnabled by remember { mutableStateOf(OverlayAccess.isGranted(context)) }

    // Re-check on every resume so returning from system settings reflects reality.
    LifecycleResumeEffect(Unit) {
        listenerEnabled = NotificationAccess.isEnabled(context)
        accessibilityEnabled = AccessibilityAccess.isEnabled(context)
        overlayEnabled = OverlayAccess.isGranted(context)
        EventLog.ensureLoaded(context)
        onPauseOrDispose { }
    }

    val events by EventLog.entries.collectAsStateWithLifecycle()

    val readingsFlow = remember { MileageRepository(context).readings }
    val readings by readingsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    SettingsScreen(
        listenerEnabled = listenerEnabled,
        accessibilityEnabled = accessibilityEnabled,
        overlayEnabled = overlayEnabled,
        events = events,
        readings = readings,
        onEnableClick = { activity.startActivity(NotificationAccess.settingsIntent(context)) },
        onEnableAccessibilityClick = { activity.startActivity(AccessibilityAccess.settingsIntent()) },
        onEnableOverlayClick = { activity.startActivity(OverlayAccess.settingsIntent(context)) },
        onExportClick = {
            val count = DumpExporter.export(activity)
            if (count == 0) {
                Toast.makeText(context, "No fixtures captured yet", Toast.LENGTH_SHORT).show()
            }
        },
        onClearLog = { EventLog.clear(context) },
        onCaptureStart = { activity.startActivity(MileageCaptureActivity.intent(context, MileageKind.START)) },
        onCaptureEnd = { activity.startActivity(MileageCaptureActivity.intent(context, MileageKind.END)) },
    )
}

@Composable
private fun SettingsScreen(
    listenerEnabled: Boolean,
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    events: List<EventLog.Entry>,
    readings: List<MileageReading>,
    onEnableClick: () -> Unit,
    onEnableAccessibilityClick: () -> Unit,
    onEnableOverlayClick: () -> Unit,
    onExportClick: () -> Unit,
    onClearLog: () -> Unit,
    onCaptureStart: () -> Unit,
    onCaptureEnd: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        // One LazyColumn for the whole screen so the log can grow arbitrarily
        // without a nested-scroll conflict.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
        ) {
            item {
                Text("IDeliver", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                Text(
                    text = "Phase 1 — fixture collection",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                LogHeader(count = events.size, onClearLog = onClearLog)
            }
            if (events.isEmpty()) {
                item {
                    Text(
                        text = "No events yet. Go online — offers will appear here as they arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(events) { entry ->
                    EventRow(entry)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item { VoiceCard() }
            item { RulesCard() }
            item {
                MileageCard(
                    readings = readings,
                    onCaptureStart = onCaptureStart,
                    onCaptureEnd = onCaptureEnd,
                )
            }
            item {
                NotificationAccessCard(enabled = listenerEnabled, onEnableClick = onEnableClick)
            }
            item {
                AccessibilityAccessCard(
                    enabled = accessibilityEnabled,
                    onEnableClick = onEnableAccessibilityClick,
                )
            }
            item {
                SimpleAccessCard(
                    title = "Suggestion overlay",
                    enabledText = "On. A pay/accept card floats over offers (taps pass through to DoorDash).",
                    disabledText = "Off. Grant “display over other apps” to show the recommendation card during offers.",
                    enabled = overlayEnabled,
                    enabledLabel = "Open overlay settings",
                    disabledLabel = "Enable suggestion overlay",
                    onEnableClick = onEnableOverlayClick,
                )
            }
            item { ExportCard(onExportClick = onExportClick) }
        }
    }
}

@Composable
private fun LogHeader(count: Int, onClearLog: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Event log",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            TextButton(onClick = onClearLog) { Text("Clear") }
        }
    }
}

@Composable
private fun EventRow(entry: EventLog.Entry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = TIME_FORMAT.format(entry.at),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = entry.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SimpleAccessCard(
    title: String,
    enabledText: String,
    disabledText: String,
    enabled: Boolean,
    enabledLabel: String,
    disabledLabel: String,
    onEnableClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (enabled) enabledText else disabledText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onEnableClick, modifier = Modifier.fillMaxWidth()) {
                Text(if (enabled) enabledLabel else disabledLabel)
            }
        }
    }
}

@Composable
private fun VoiceCard() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var enabled by remember { mutableStateOf(store.voiceEnabled()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Voice announcements", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Speaks each offer's miles, minutes, and accept/reject — hands-free.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; store.setVoiceEnabled(it) },
            )
        }
    }
}

@Composable
private fun RulesCard() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val initial = remember { store.load() }

    var homeBase by remember { mutableStateOf(initial.homeBase.orEmpty()) }
    var radius by remember {
        mutableStateOf(if (initial.radiusMiles > 0) trimNum(initial.radiusMiles) else "")
    }
    var maxTime by remember {
        mutableStateOf(if (initial.maxMinutes > 0) initial.maxMinutes.toString() else "")
    }
    var baseFloor by remember { mutableStateOf(trimNum(initial.baseFloorCents / 100.0)) }
    var basePerMile by remember { mutableStateOf(trimNum(initial.basePerMileCents / 100.0)) }
    var deadhead by remember { mutableStateOf(trimNum(initial.deadheadFactor)) }
    var minPerHour by remember { mutableStateOf(if (initial.minDollarsPerHour > 0) trimNum(initial.minDollarsPerHour) else "") }
    var minPerMile by remember { mutableStateOf(if (initial.minDollarsPerMile > 0) trimNum(initial.minDollarsPerMile) else "") }
    var platinumTarget by remember { mutableStateOf(initial.platinumTargetPercent.toString()) }
    var byTimeRate by remember { mutableStateOf(trimNum(initial.byTimeHourlyCents / 100.0)) }

    fun persist() {
        store.save(
            RuleSettings(
                homeBase = homeBase.ifBlank { null },
                radiusMiles = radius.toDoubleOrNull() ?: 0.0,
                maxMinutes = maxTime.toIntOrNull() ?: 0,
                baseFloorCents = ((baseFloor.toDoubleOrNull() ?: 2.0) * 100).toInt(),
                basePerMileCents = ((basePerMile.toDoubleOrNull() ?: 0.15) * 100).toInt(),
                deadheadFactor = deadhead.toDoubleOrNull() ?: 1.0,
                minDollarsPerHour = minPerHour.toDoubleOrNull() ?: 0.0,
                minDollarsPerMile = minPerMile.toDoubleOrNull() ?: 0.0,
                platinumTargetPercent = platinumTarget.toIntOrNull() ?: 70,
                byTimeHourlyCents = ((byTimeRate.toDoubleOrNull() ?: 13.0) * 100).toInt(),
            ),
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Rules", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Offers over a limit are flagged DECLINE. Leave a number blank or 0 for “no limit”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = homeBase,
                onValueChange = { homeBase = it; persist() },
                label = { Text("Home base (address or lat,lng)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = radius,
                onValueChange = { radius = it; persist() },
                label = { Text("Max radius (miles)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxTime,
                onValueChange = { maxTime = it; persist() },
                label = { Text("Max total time (minutes)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseFloor,
                onValueChange = { baseFloor = it; persist() },
                label = { Text("Base pay floor ($)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = basePerMile,
                onValueChange = { basePerMile = it; persist() },
                label = { Text("Base pay per mile ($)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "By-order tip estimate = total − promo − base, where base = floor + per-mile × miles. Tune both to your market.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = deadhead,
                onValueChange = { deadhead = it; persist() },
                label = { Text("Deadhead factor (empty return)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minPerHour,
                    onValueChange = { minPerHour = it; persist() },
                    label = { Text("Min $/hr") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minPerMile,
                    onValueChange = { minPerMile = it; persist() },
                    label = { Text("Min $/mi") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "True-cost $/hr and $/mi count the unpaid empty return: return miles = deadhead × delivery miles (1 = full empty drive back, 0 = none). Offers under Min $/hr or Min $/mi are flagged DECLINE (0/blank = no limit).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = platinumTarget,
                onValueChange = { platinumTarget = it; persist() },
                label = { Text("Acceptance-rate floor % (Platinum)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Reads your acceptance rate off the Ratings screen. Near this floor, a DECLINE softens to MARGINAL so you don't lose Platinum. Default 70 (some markets 80).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = byTimeRate,
                onValueChange = { byTimeRate = it; persist() },
                label = { Text("Earn-by-time rate ($/hr)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Your market's earn-by-time guarantee. After a dash, open the DoorDash earnings screen and the log will show whether earn-by-time would've paid more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Note: home-base radius is compared against the offer's total trip miles for now — true distance-from-home needs the dropoff location, which isn't available yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun trimNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@Composable
private fun MileageCard(
    readings: List<MileageReading>,
    onCaptureStart: () -> Unit,
    onCaptureEnd: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Odometer mileage", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Photograph the dashboard; the number is read on-device and you confirm it before it saves.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCaptureStart, modifier = Modifier.weight(1f)) {
                    Text("Capture start")
                }
                OutlinedButton(onClick = onCaptureEnd, modifier = Modifier.weight(1f)) {
                    Text("Capture end")
                }
            }
            if (readings.isEmpty()) {
                Text(
                    text = "No readings yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                readings.take(6).forEach { r ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val kind = if (r.kind == MileageKind.START) "Start" else "End"
                    Text(
                        text = "$kind · ${formatMiles(r.odometerMiles)} mi · ${TIME_FORMAT.format(r.capturedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun formatMiles(miles: Double): String =
    if (miles % 1.0 == 0.0) miles.toLong().toString() else miles.toString()

@Composable
private fun AccessibilityAccessCard(
    enabled: Boolean,
    onEnableClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Offer-screen reader", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (enabled) {
                    "Enabled. When an offer arrives, the screen is read (read-only) to capture pay and distance."
                } else {
                    "Off. Notifications don't include pay — grant accessibility so the offer screen can be read. Read-only; never taps the app."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onEnableClick, modifier = Modifier.fillMaxWidth()) {
                Text(if (enabled) "Open accessibility settings" else "Enable offer-screen reader")
            }
        }
    }
}

@Composable
private fun NotificationAccessCard(
    enabled: Boolean,
    onEnableClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Notification access", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (enabled) {
                    "Enabled. The capture harness is recording DoorDash and Uber offers."
                } else {
                    "Off. Grant notification access so the harness can read incoming offers."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onEnableClick, modifier = Modifier.fillMaxWidth()) {
                Text(if (enabled) "Open notification access settings" else "Enable notification access")
            }
        }
    }
}

@Composable
private fun ExportCard(
    onExportClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Captured fixtures", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Share the recorded JSONL files. Everything stays on device until you send it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onExportClick, modifier = Modifier.fillMaxWidth()) {
                Text("Export fixtures")
            }
        }
    }
}
