package com.drdisagree.colorblendr.ui.compose.screens.systemui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.colorblendr.R
import com.drdisagree.colorblendr.data.domain.PreviewController
import com.drdisagree.colorblendr.ui.compose.components.AppToolbar
import com.drdisagree.colorblendr.utils.app.SystemUtil
import com.drdisagree.colorblendr.utils.samsung.SamsungShizukuPaletteBridge
import com.drdisagree.colorblendr.utils.systemui.SystemUiCapabilityAnalysis
import com.drdisagree.colorblendr.utils.systemui.SystemUiCapabilityProbe
import com.drdisagree.colorblendr.utils.systemui.SystemUiCommandResult
import com.drdisagree.colorblendr.utils.systemui.SystemUiInjectionLabActions
import com.drdisagree.colorblendr.utils.systemui.SystemUiLabOperationResult
import com.drdisagree.colorblendr.utils.systemui.SystemUiProbeReport
import com.drdisagree.colorblendr.utils.systemui.SystemUiProbeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SystemUiInjectionLabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val samsungLogs by SamsungShizukuPaletteBridge.diagnosticLog.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf<SystemUiProbeReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var showApplyConfirm by rememberSaveable { mutableStateOf(false) }
    var showRevertConfirm by rememberSaveable { mutableStateOf(false) }
    var operation by remember { mutableStateOf<SystemUiLabOperationResult?>(null) }
    var actionLog by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun runOperation(action: suspend () -> SystemUiLabOperationResult) {
        running = true
        scope.launch {
            try {
                operation = action()
                actionLog = actionLog + operation!!.toLogLine()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operation = SystemUiLabOperationResult(
                    SystemUiProbeStatus.FAILED,
                    "SystemUI lab",
                    error.message ?: error.javaClass.simpleName,
                    stderr = error.javaClass.simpleName
                )
                actionLog = actionLog + operation!!.toLogLine()
            } finally {
                running = false
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = buildString {
                report?.let { append(SystemUiCapabilityAnalysis.export(it, actionLog)) }
                if (report == null) appendLine("ColorBlendr SystemUI Injection Lab: no capability probe has been run.")
                if (samsungLogs.isNotEmpty()) {
                    appendLine()
                    appendLine("Samsung engine trace")
                    samsungLogs.forEach(::appendLine)
                }
            }
            scope.launch(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
            }
        }
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text(stringResource(R.string.systemui_lab_apply)) },
            text = { Text(stringResource(R.string.systemui_lab_apply_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    runOperation { SystemUiInjectionLabActions(context).apply() }
                }) { Text(stringResource(R.string.systemui_lab_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
    if (showRevertConfirm) {
        AlertDialog(
            onDismissRequest = { showRevertConfirm = false },
            title = { Text(stringResource(R.string.systemui_lab_revert)) },
            text = { Text(stringResource(R.string.systemui_lab_revert_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRevertConfirm = false
                    runOperation { SystemUiInjectionLabActions(context).revert() }
                }) { Text(stringResource(R.string.systemui_lab_revert)) }
            },
            dismissButton = {
                TextButton(onClick = { showRevertConfirm = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppToolbar(title = stringResource(R.string.systemui_lab_title), showBackButton = true, lifted = scroll.value > 0)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LabCard { Text(stringResource(R.string.systemui_lab_warning), style = MaterialTheme.typography.bodyMedium) }

            Text(stringResource(R.string.systemui_lab_capabilities), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LabCard {
                Text("Device: " + (report?.device ?: "Not probed"), style = MaterialTheme.typography.bodyMedium)
                Text("Shizuku: " + (report?.shizukuState ?: "Not probed"), style = MaterialTheme.typography.bodyMedium)
                val oneUi = report?.commands?.firstOrNull { it.command == "getprop ro.build.version.oneui" }
                if (oneUi != null) Text("One UI: " + oneUi.stdout.ifBlank { "not reported" }, style = MaterialTheme.typography.bodyMedium)
                val uid = report?.commands?.firstOrNull { it.command == "id" }?.stdout?.let(SystemUiCapabilityAnalysis::parseUid)
                Text("UID: " + (uid?.toString() ?: "unknown"), style = MaterialTheme.typography.bodyMedium)
                val rish = report?.commands?.firstOrNull { it.command == "command -v rish" }
                Text("Rish executable: " + when {
                    rish == null -> "not probed"
                    SystemUiCapabilityAnalysis.classify(rish) == SystemUiProbeStatus.SUCCESS -> rish.stdout
                    else -> "not available in the Shizuku shell"
                }, style = MaterialTheme.typography.bodyMedium)
                report?.let { current ->
                    listOf(
                        "Overlay service" to "cmd overlay list --user",
                        "Android overlay target" to "cmd overlay dump android",
                        "SystemUI overlay target" to "cmd overlay dump com.android.systemui"
                    ).forEach { (label, marker) ->
                        val result = current.commands.firstOrNull { it.command.contains(marker) }
                        Text(label + ": " + (result?.let(SystemUiCapabilityAnalysis::classify)?.toString() ?: "not probed"))
                    }
                    val fabricate = current.commands.firstOrNull { it.command == "cmd overlay fabricate --help" }
                    Text("Fabricated overlays: " + (fabricate?.let(SystemUiCapabilityAnalysis::classify)?.toString() ?: "not probed"))
                    val theme = current.commands.firstOrNull { it.command.contains("get secure theme_customization_overlay_packages") }
                    Text("Theme customization: " + (theme?.let(SystemUiCapabilityAnalysis::classify)?.toString() ?: "not probed"))
                    val accent = current.commands.firstOrNull { it.command.contains("get system accent_color") }
                    Text("Legacy system accent setting: " + (accent?.let(SystemUiCapabilityAnalysis::classify)?.toString() ?: "not probed"))
                    Text("SystemUI mode: " + if (SamsungShizukuPaletteBridge.isSamsungDevice()) "Samsung firmware engine available for explicit apply" else "Android fallback only")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        scope.launch {
                            report = SystemUiCapabilityProbe().run()
                            running = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (running) "Working…" else stringResource(R.string.systemui_lab_probe), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    enabled = !running && report != null,
                    onClick = { exporter.launch("colorblendr-systemui-diagnostic.txt") },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.systemui_lab_export), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }

            if (report == null) {
                LabCard { Text(stringResource(R.string.systemui_lab_no_probe)) }
            } else {
                Text(stringResource(R.string.systemui_lab_preview), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PalettePreview(report!!)
                Text(stringResource(R.string.systemui_lab_commands), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                report!!.commands.forEach { CommandCard(it) }
            }

            Text(stringResource(R.string.systemui_lab_strategies), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            operation?.let { op ->
                LabCard {
                    Text(op.status.toString(), color = statusColor(op.status), fontWeight = FontWeight.Bold)
                    Text(op.strategy, style = MaterialTheme.typography.titleSmall)
                    Text(op.detail, style = MaterialTheme.typography.bodySmall)
                    if (op.stdout.isNotBlank()) Text("stdout: " + op.stdout.take(600), style = MaterialTheme.typography.bodySmall)
                    if (op.stderr.isNotBlank()) Text("stderr: " + op.stderr.take(600), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(enabled = !running, onClick = { showApplyConfirm = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.systemui_lab_apply), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(enabled = !running, onClick = { showRevertConfirm = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.systemui_lab_revert), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            actionLog.takeLast(10).forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PalettePreview(report: SystemUiProbeReport) {
    val isDark = SystemUtil.isDarkMode
    val preview = remember { PreviewController.buildPreviewColors() }
    val map = if (isDark) preview.darkMap else preview.lightMap
    val palette = if (isDark) preview.paletteDark else preview.paletteLight
    val background = map["system_background_" + (if (isDark) "dark" else "light")] ?: palette[3][if (isDark) 1 else 11]
    val surface = map["system_surface_" + (if (isDark) "dark" else "light")] ?: palette[3][if (isDark) 2 else 10]
    val swatches = mutableListOf(
        "accent1" to palette[0][7], "accent2" to palette[1][7], "accent3" to palette[2][7],
        "neutral1" to palette[3][7], "neutral2" to palette[4][7], "background" to background, "surface" to surface
    )
    listOf(
        "QS active" to "com.android.systemui:color/qs_tile_round_background_on",
        "QS inactive" to "com.android.systemui:color/qs_tile_round_background_off"
    ).forEach { (label, resource) ->
        val command = report.commands.firstOrNull { it.command.endsWith(resource) }
        val resolved = command?.stdout?.let { Regex("#([0-9a-fA-F]{8})").find(it)?.groupValues?.get(1) }
        resolved?.toLongOrNull(16)?.toInt()?.let { swatches += (label to it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (name, color) -> ColorSwatch(name, color, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ColorSwatch(name: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        color = Color(value),
        contentColor = if (android.graphics.Color.luminance(value) < 0.45f) Color.White else Color.Black,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.height(72.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text("#" + value.toUInt().toString(16).takeLast(6).uppercase(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CommandCard(command: SystemUiCommandResult) {
    val status = SystemUiCapabilityAnalysis.classify(command)
    LabCard {
        Text(command.command, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(status.toString() + " · exit " + command.exitCode, style = MaterialTheme.typography.labelSmall, color = statusColor(status))
        val output = command.stdout.ifBlank { command.stderr }
        if (output.isNotBlank()) Text(output.take(900), style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun statusColor(status: SystemUiProbeStatus): Color = when (status) {
    SystemUiProbeStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    SystemUiProbeStatus.BLOCKED, SystemUiProbeStatus.FAILED -> MaterialTheme.colorScheme.error
    SystemUiProbeStatus.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
    SystemUiProbeStatus.NEEDS_REBOOT, SystemUiProbeStatus.NEEDS_SYSTEMUI_RESTART -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun LabCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
