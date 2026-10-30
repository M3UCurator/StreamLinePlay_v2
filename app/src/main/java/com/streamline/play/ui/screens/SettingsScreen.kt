package com.streamline.play.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamline.play.BuildConfig
import com.streamline.play.data.model.AspectMode
import com.streamline.play.data.model.BitrateLimit
import com.streamline.play.data.model.BufferPreset
import com.streamline.play.data.model.DecoderMode
import com.streamline.play.data.model.EpgRefreshMode
import com.streamline.play.data.model.ExternalPlayerApp
import com.streamline.play.data.model.NetworkProfile
import com.streamline.play.data.model.PlaybackEngineType
import com.streamline.play.data.model.QualityPresets
import com.streamline.play.data.model.ResolutionCap
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.model.SourceKind
import com.streamline.play.data.model.UserAgentPreset
import com.streamline.play.data.settings.Parental
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.components.FocusableSurface
import com.streamline.play.ui.components.SectionHeader
import com.streamline.play.ui.components.SettingRow
import com.streamline.play.ui.components.Tag
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.components.TvSlider
import com.streamline.play.ui.components.rememberInitialFocus
import com.streamline.play.ui.components.requestFocusReliably
import com.streamline.play.ui.theme.Cinema
import com.streamline.play.util.asClock
import com.streamline.play.util.formatBytes
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

enum class SettingsSection(val title: String, val icon: ImageVector) {
    PLAYBACK("Playback", Icons.Rounded.PlayArrow),
    RESOLUTION("Resolution & bitrate", Icons.Rounded.HighQuality),
    BUFFER("Buffer & latency", Icons.Rounded.Memory),
    PROFILE("Network profile", Icons.Rounded.SettingsEthernet),
    SECURITY("Security", Icons.Rounded.Security),
    NETWORK("Network", Icons.Rounded.Wifi),
    APPEARANCE("Appearance", Icons.Rounded.Palette),
    SOURCES("Sources", Icons.Rounded.LiveTv),
    CACHE("Cache", Icons.Rounded.Storage),
    ABOUT("About", Icons.Rounded.Info),
}

private data class PinRequest(
    val mode: PinMode,
    val title: String,
    val subtitle: String?,
    val expectedHash: String,
    val onConfirmed: (String) -> Unit,
)

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    initialSection: SettingsSection = SettingsSection.PLAYBACK,
    onSectionChanged: (SettingsSection) -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val allGroups by vm.allGroups.collectAsStateWithLifecycle()
    val parentalUnlocked by vm.parentalUnlocked.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val epgUpdated by vm.epgLastRefreshMs.collectAsStateWithLifecycle()
    val epgRefreshing by vm.epgRefreshing.collectAsStateWithLifecycle()
    val epgMessage by vm.epgMessage.collectAsStateWithLifecycle()

    var section by remember { mutableStateOf(initialSection) }
    var picker by remember { mutableStateOf<PickerState?>(null) }
    var textEditor by remember { mutableStateOf<TextEditorState?>(null) }
    var editingSource by remember { mutableStateOf<SourceConfig?>(null) }
    var addingSource by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<SourceConfig?>(null) }
    var pinRequest by remember { mutableStateOf<PinRequest?>(null) }
    var groupLockOpen by remember { mutableStateOf(false) }
    var epgEditorOpen by remember { mutableStateOf(false) }
    val railFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val railState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val index = SettingsSection.entries.indexOf(initialSection)
        if (index > 0) railState.scrollToItem(index)
        delay(140)
        railFocus.requestFocusReliably()
    }

    fun startSetPin(title: String, subtitle: String?) {
        pinRequest = PinRequest(
            mode = PinMode.SET,
            title = title,
            subtitle = subtitle,
            expectedHash = "",
            onConfirmed = { raw ->
                vm.updateSettings {
                    it.copy(parentalLockEnabled = true, parentalPinHash = Parental.hashPin(raw))
                }
                vm.lockParental()
                pinRequest = null
            },
        )
    }

    fun verifyPin(title: String, subtitle: String?, action: (String) -> Unit) {
        pinRequest = PinRequest(
            mode = PinMode.VERIFY,
            title = title,
            subtitle = subtitle,
            expectedHash = settings.parentalPinHash,
            onConfirmed = { pin ->
                pinRequest = null
                action(pin)
            },
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxSize()
                .padding(start = 32.dp, top = 24.dp, end = 20.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = Cinema.TextPrimary,
            )
            Text(
                text = "Tune the player for your TV box",
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextMuted,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                state = railState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(SettingsSection.entries, key = { it.name }) { candidate ->
                    RailItem(
                        section = candidate,
                        selected = section == candidate,
                        onClick = {
                            section = candidate
                            onSectionChanged(candidate)
                        },
                        focusRequester = if (candidate == initialSection) railFocus else null,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            Tag(text = "v${BuildConfig.VERSION_NAME} · armeabi-v7a", modifier = Modifier.padding(bottom = 24.dp))
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            key(section) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 40.dp, top = 26.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        SectionHeader(
                            title = section.title,
                            subtitle = sectionSubtitle(section),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    when (section) {
                        SettingsSection.PLAYBACK -> playbackSection(vm, settings) { picker = it }
                        SettingsSection.RESOLUTION -> resolutionSection(vm, settings) { picker = it }
                        SettingsSection.BUFFER -> bufferSection(vm, settings)
                        SettingsSection.PROFILE -> profileSection(vm, settings)
                        SettingsSection.SECURITY -> securitySection(
                            vm = vm,
                            settings = settings,
                            parentalUnlocked = parentalUnlocked,
                            allGroups = allGroups,
                            onSetPin = { startSetPin(it.first, it.second) },
                            onVerify = { title, subtitle, action -> verifyPin(title, subtitle, action) },
                            onOpenGroupLock = { groupLockOpen = true },
                        )

                        SettingsSection.NETWORK -> networkSection(
                            vm = vm,
                            settings = settings,
                            onPicker = { picker = it },
                            onTextEditor = { textEditor = it },
                        )
                        SettingsSection.APPEARANCE -> appearanceSection(
                            vm = vm,
                            settings = settings,
                            onPicker = { picker = it },
                        )

                        SettingsSection.SOURCES -> {
                            sourceSection(
                                vm = vm,
                                sources = sources,
                                settings = settings,
                                epgUpdated = epgUpdated,
                                epgRefreshing = epgRefreshing,
                                epgMessage = epgMessage,
                                onAdd = { addingSource = true },
                                onEdit = { editingSource = it },
                                onPicker = { picker = it },
                                onManageEpg = { epgEditorOpen = true },
                            )
                        }

                        SettingsSection.CACHE -> cacheSection(
                            vm = vm,
                            settings = settings,
                            cacheSizeText = formatBytes(cacheSize),
                            onClear = { confirmClear = true },
                        )

                        SettingsSection.ABOUT -> aboutSection()
                    }
                }
            }
        }
    }

    picker?.let { state ->
        OptionPickerDialog(state = state, onDismiss = { picker = null })
    }
    textEditor?.let { state ->
        TextEditorDialog(state = state, onDismiss = { textEditor = null })
    }
    if (addingSource) {
        SourceEditorDialog(
            original = null,
            onDismiss = { addingSource = false },
            onSave = { source ->
                vm.addSource(source)
                addingSource = false
            },
            onDelete = null,
        )
    }
    editingSource?.let { source ->
        SourceEditorDialog(
            original = source,
            onDismiss = { editingSource = null },
            onSave = { updated ->
                vm.updateSource(updated)
                editingSource = null
            },
            onDelete = { confirmRemove = source },
        )
    }
    confirmRemove?.let { source ->
        ConfirmDialog(
            title = "Remove source?",
            message = "“${source.name}” and its channels will disappear from the catalog until it is added again.",
            confirmLabel = "Remove",
            onConfirm = {
                vm.removeSource(source.id)
                confirmRemove = null
                editingSource = null
            },
            onDismiss = { confirmRemove = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear app cache?",
            message = "Cached EPG data and temporary stream buffers will be deleted. Playlists and settings are kept.",
            confirmLabel = "Clear cache",
            onConfirm = {
                vm.clearCacheNow()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
    pinRequest?.let { request ->
        PinDialog(
            title = request.title,
            mode = request.mode,
            subtitle = request.subtitle,
            expectedHash = request.expectedHash,
            onConfirmed = request.onConfirmed,
            onDismiss = { pinRequest = null },
        )
    }
    if (groupLockOpen) {
        GroupLockDialog(
            groups = if (settings.parentalAutoLockAdult) {
                allGroups.filterNot { Parental.looksAdult(it) }
            } else {
                allGroups
            },
            selected = settings.parentalLockedGroups,
            onSave = { selection ->
                vm.updateSettings { it.copy(parentalLockedGroups = selection) }
                groupLockOpen = false
            },
            onDismiss = { groupLockOpen = false },
        )
    }
    if (epgEditorOpen) {
        GlobalEpgDialog(
            urls = settings.globalEpgUrls,
            onChange = { updated ->
                vm.updateSettings { it.copy(globalEpgUrls = updated) }
                vm.refreshEpg()
            },
            onDismiss = { epgEditorOpen = false },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playbackSection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
    onPicker: (PickerState) -> Unit,
) {
    item {
        SettingRow(
            label = "Playback engine",
            value = settings.engine.label,
            caption = settings.engine.description,
            onClick = {
                onPicker(
                    PickerState(
                        title = "Playback engine",
                        subtitle = "MPV is handed to an installed MPV app; Media3 and VLC are embedded.",
                        options = PlaybackEngineType.entries.map { it.label to it.description },
                        selected = PlaybackEngineType.entries.indexOf(settings.engine),
                        onSelect = { index ->
                            vm.updateSettings { it.copy(engine = PlaybackEngineType.entries[index]) }
                        },
                    ),
                )
            },
        )
    }
    item {
        SettingRow(
            label = "External player app",
            value = settings.externalPlayer.label,
            caption = if (settings.externalPlayer.packageName == null) {
                "Native chooser over every installed media app · position is handed over"
            } else {
                "Direct handoff to ${settings.externalPlayer.label}"
            },
            onClick = {
                onPicker(
                    PickerState(
                        title = "External player app",
                        subtitle = "The native chooser lists every installed app that opens video. " +
                            "Direct targets are shortcuts for common players.",
                        options = ExternalPlayerApp.entries.map { it.label to it.description },
                        selected = ExternalPlayerApp.entries.indexOf(settings.externalPlayer),
                        onSelect = { index ->
                            vm.updateSettings { it.copy(externalPlayer = ExternalPlayerApp.entries[index]) }
                        },
                    ),
                )
            },
        )
    }
    item {
        SettingRow(
            label = "Decoder mode",
            value = settings.decoderMode.label,
            caption = settings.decoderMode.detail,
            onClick = {
                onPicker(
                    PickerState(
                        title = "Decoder mode",
                        subtitle = "Hardware profiles reduce CPU load on 32-bit TV boxes.",
                        options = DecoderMode.entries.map { it.label to it.detail },
                        selected = DecoderMode.entries.indexOf(settings.decoderMode),
                        onSelect = { index ->
                            vm.updateSettings { it.copy(decoderMode = DecoderMode.entries[index]) }
                        },
                    ),
                )
            },
        )
    }
    item {
        SettingRow(
            label = "Audio pass-through",
            value = if (settings.audioPassthrough) "On" else "Off",
            caption = "Bitstream Dolby/DTS through the FFmpeg extension",
            onClick = { vm.updateSettings { it.copy(audioPassthrough = !it.audioPassthrough) } },
        )
    }
    item {
        SectionHeader(
            title = "D-pad remote",
            subtitle = "Every card and action is reachable with UP, DOWN, LEFT and RIGHT",
        )
    }
    item {
        Text(
            text = "Focus wraps rows and grids, sliders react to LEFT/RIGHT, and CENTER activates. " +
                "BACK returns to the previous screen; on the home screen two BACK presses exit the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextMuted,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resolutionSection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
    onPicker: (PickerState) -> Unit,
) {
    item {
        SettingRow(
            label = "Max stream resolution",
            value = settings.resolutionCap.label,
            caption = "Caps ABR video selection in Media3 and libVLC",
            onClick = {
                onPicker(
                    PickerState(
                        title = "Maximum resolution",
                        subtitle = "Limits adaptive streams to the selected video size.",
                        options = ResolutionCap.entries.map { it.label to null },
                        selected = ResolutionCap.entries.indexOf(settings.resolutionCap),
                        onSelect = { index ->
                            vm.updateSettings { it.copy(resolutionCap = ResolutionCap.entries[index]) }
                        },
                    ),
                )
            },
        )
    }
    item {
        val tuning = settings.bufferTuning
        TvSlider(
            label = "Bandwidth / bitrate limit",
            valueText = BitrateLimit.label(settings.bitrateLimitKbps),
            fraction = bitrateFraction(settings.bitrateLimitKbps),
            caption = "Forces adaptive HLS/IPTV (.m3u8) to a tier. " +
                "Effective ceiling ${BitrateLimit.label(settings.bitrateLimitKbps)} · buffer ${tuning.targetBytes / (1024 * 1024)} MB",
            startLabel = "Auto",
            endLabel = "${BitrateLimit.MAX_KBPS / 1000} Mbps",
            onStep = { delta ->
                vm.updateSettings { it.copy(bitrateLimitKbps = stepBitrate(it.bitrateLimitKbps, delta)) }
            },
            onTapFraction = { fraction ->
                vm.updateSettings { it.copy(bitrateLimitKbps = fractionToBitrate(fraction)) }
            },
        )
    }
    item {
        SectionHeader(
            title = "Quality guidance",
            subtitle = "Desired quality · target resolution · recommended · safe margin",
        )
    }
    QualityPresets.all.forEach { preset ->
        item(key = "preset-${preset.name}") {
            SettingRow(
                label = "${preset.name} · ${preset.target}",
                value = "${formatKbps(preset.safeKbps)} Kbps",
                caption = preset.summary,
                onClick = {
                    vm.updateSettings {
                        it.copy(
                            resolutionCap = preset.resolution,
                            bitrateLimitKbps = preset.safeKbps,
                        )
                    }
                },
            )
        }
    }
    item {
        Text(
            text = "Tap a tier to apply its resolution and safe bitrate together. " +
                "SD 800–1,500 Kbps · safe 1,500 · HD 1,500–3,000 · safe 3,000 · " +
                "Full HD 3,000–6,000 · safe 6,000 · 4K 8,000 Kbps.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextMuted,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bufferSection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
) {
    item {
        val presets = BufferPreset.entries
        val index = presets.indexOf(settings.bufferPreset).coerceAtLeast(0)
        val tuning = settings.bufferTuning
        TvSlider(
            label = "Video buffer size",
            valueText = settings.bufferPreset.label,
            fraction = if (presets.size > 1) index / (presets.lastIndex.toFloat()) else 0f,
            caption = "${tuning.targetBytes / (1024 * 1024)} MB ceiling · start ${tuning.minBufferMs} ms · " +
                "max ${tuning.maxBufferMs / 1000} s · rebuffer ${tuning.rebufferMs} ms",
            startLabel = "Small · default",
            endLabel = "High · 60 MB",
            onStep = { delta ->
                val next = (index + delta).coerceIn(0, presets.lastIndex)
                vm.updateSettings { it.copy(bufferPreset = presets[next]) }
            },
            onTapFraction = { fraction ->
                val next = (fraction * presets.lastIndex).roundToInt().coerceIn(0, presets.lastIndex)
                vm.updateSettings { it.copy(bufferPreset = presets[next]) }
            },
        )
    }
    item {
        SectionHeader(title = "Buffer presets", subtitle = "Start time stays between 500 ms and 2 s")
    }
    BufferPreset.entries.forEach { preset ->
        item(key = "buffer-${preset.name}") {
            val effective = settings.copy(bufferPreset = preset).bufferTuning
            SettingRow(
                label = preset.label,
                value = "${preset.targetBytes / (1024 * 1024)} MB",
                caption = "${preset.minBufferMs} ms start · ${preset.maxBufferMs / 1000} s max · " +
                    "effective ${effective.targetBytes / (1024 * 1024)} MB",
                onClick = { vm.updateSettings { it.copy(bufferPreset = preset) } },
            )
        }
    }
    item {
        Text(
            text = "Small is the default: 10 MB ceiling, 1 s start. Medium doubles the lookahead, " +
                "High reaches the 60 MB maximum for unstable providers.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextMuted,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.profileSection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
) {
    item {
        Text(
            text = "Profiles reshape the network buffer on top of the chosen buffer size. " +
                "The profile wins when it needs a safer ceiling.",
            style = MaterialTheme.typography.bodyLarge,
            color = Cinema.TextSecondary,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
    NetworkProfile.entries.forEach { profile ->
        item(key = "profile-${profile.name}") {
            SettingRow(
                label = profile.label,
                value = if (settings.networkProfile == profile) "Active" else "Use",
                caption = profile.summary,
                onClick = { vm.updateSettings { it.copy(networkProfile = profile) } },
            )
        }
    }
    item {
        val tuning = settings.bufferTuning
        SettingRow(
            label = "Effective tuning",
            value = "${tuning.targetBytes / (1024 * 1024)} MB",
            caption = "${tuning.minBufferMs} ms start · ${tuning.maxBufferMs / 1000} s max · " +
                "playback ${tuning.playbackBufferMs} ms · rebuffer ${tuning.rebufferMs} ms",
            onClick = {},
        )
    }
    item {
        Text(
            text = "Low RAM (Android 4+) keeps a steady 8 MB ceiling. Ultra low latency live is tuned for " +
                "sports with near real-time playback. Aggressive buffer keeps a large lookahead to survive dropouts.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextMuted,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.securitySection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
    parentalUnlocked: Boolean,
    allGroups: List<String>,
    onSetPin: (Pair<String, String?>) -> Unit,
    onVerify: (String, String?, (String) -> Unit) -> Unit,
    onOpenGroupLock: () -> Unit,
) {
    val autoGroups = if (settings.parentalLockEnabled && settings.parentalAutoLockAdult) {
        allGroups.filter { Parental.looksAdult(it) }
    } else {
        emptyList()
    }
    val lockedCount = (settings.parentalLockedGroups + autoGroups).size
    item {
        SettingRow(
            label = "Parental lock",
            value = if (settings.parentalLockEnabled) "On" else "Off",
            caption = if (settings.hasParentalPin) {
                "PIN configured · ${if (parentalUnlocked) "session unlocked" else "locked"}"
            } else {
                "Create a 4-digit PIN to hide adult content"
            },
            onClick = {
                when {
                    settings.parentalLockEnabled -> onVerify(
                        "Disable parental lock",
                        "Enter the PIN to turn the lock off",
                    ) {
                        vm.updateSettings { s -> s.copy(parentalLockEnabled = false) }
                        vm.lockParental()
                    }

                    settings.hasParentalPin -> {
                        vm.updateSettings { it.copy(parentalLockEnabled = true) }
                        vm.lockParental()
                    }

                    else -> onSetPin("Set parental PIN" to null)
                }
            },
        )
    }
    if (settings.parentalLockEnabled) {
        item {
            SettingRow(
                label = "Session",
                value = if (parentalUnlocked) "Unlocked" else "Locked",
                caption = "Unlock reveals adult groups until the app closes",
                onClick = {
                    if (parentalUnlocked) {
                        vm.lockParental()
                    } else {
                        onVerify("Unlock adult content", "Enter the PIN to reveal locked groups") { pin ->
                            vm.unlockParental(pin)
                        }
                    }
                },
            )
        }
        item {
            SettingRow(
                label = "Lock adult content automatically",
                value = if (settings.parentalAutoLockAdult) "On" else "Off",
                caption = "${autoGroups.size} groups detected by keyword (adult, xxx, 18+, …)",
                onClick = { vm.updateSettings { it.copy(parentalAutoLockAdult = !it.parentalAutoLockAdult) } },
            )
        }
        item {
            SettingRow(
                label = "Locked groups",
                value = "$lockedCount groups",
                caption = settings.parentalLockedGroups.joinToString(", ").ifBlank { "Choose groups to hide" },
                onClick = onOpenGroupLock,
            )
        }
        item {
            SettingRow(
                label = "Change PIN",
                value = "Update",
                caption = "Verify the current PIN, then choose a new one",
                onClick = {
                    onVerify("Verify current PIN", "Confirm the PIN before changing it") {
                        onSetPin("Choose a new PIN" to "Enter the new 4-digit PIN")
                    }
                },
            )
        }
    }
    item {
        SectionHeader(title = "App exit", subtitle = "Protect against accidental remote presses")
    }
    item {
        SettingRow(
            label = "Double-press BACK to exit",
            value = if (settings.confirmExit) "On" else "Off",
            caption = if (settings.confirmExit) {
                "On the home screen, BACK twice within 2 seconds quits"
            } else {
                "A single BACK press exits the app from the home screen"
            },
            onClick = { vm.updateSettings { it.copy(confirmExit = !it.confirmExit) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.networkSection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
    onPicker: (PickerState) -> Unit,
    onTextEditor: (TextEditorState) -> Unit,
) {
    item {
        SettingRow(
            label = "User-Agent preset",
            value = settings.userAgentPreset.label,
            caption = settings.userAgent,
            onClick = {
                onPicker(
                    PickerState(
                        title = "User-Agent preset",
                        subtitle = "Spoof a known player signature to bypass strict CDN filters.",
                        options = UserAgentPreset.entries.map {
                            it.label to it.value.ifBlank { "Enter a custom value" }
                        },
                        selected = UserAgentPreset.entries.indexOf(settings.userAgentPreset),
                        onSelect = { index ->
                            vm.updateSettings { it.copy(userAgentPreset = UserAgentPreset.entries[index]) }
                        },
                    ),
                )
            },
        )
    }
    if (settings.userAgentPreset == UserAgentPreset.CUSTOM) {
        item {
            SettingRow(
                label = "Custom User-Agent",
                value = settings.customUserAgent.ifBlank { "Not set" },
                onClick = {
                    onTextEditor(
                        TextEditorState(
                            title = "Custom User-Agent",
                            label = "User-Agent string",
                            initial = settings.customUserAgent,
                            placeholder = "TiviMate/5.0.4 (Linux; Android 9; Android TV)",
                            onSave = { value ->
                                vm.updateSettings { it.copy(customUserAgent = value.trim()) }
                            },
                        ),
                    )
                },
            )
        }
    }
    item {
        SettingRow(
            label = "Referer header",
            value = settings.referer.ifBlank { "None" },
            caption = "Required by some provider CDNs",
            onClick = {
                onTextEditor(
                    TextEditorState(
                        title = "Referer header",
                        label = "Referer",
                        initial = settings.referer,
                        placeholder = "https://provider.example/",
                        onSave = { value -> vm.updateSettings { it.copy(referer = value.trim()) } },
                    ),
                )
            },
        )
    }
    item {
        SettingRow(
            label = "Origin header",
            value = settings.origin.ifBlank { "None" },
            onClick = {
                onTextEditor(
                    TextEditorState(
                        title = "Origin header",
                        label = "Origin",
                        initial = settings.origin,
                        placeholder = "https://provider.example",
                        onSave = { value -> vm.updateSettings { it.copy(origin = value.trim()) } },
                    ),
                )
            },
        )
    }
    item {
        SettingRow(
            label = "Authorization header",
            value = if (settings.authorization.isBlank()) "None" else "Configured",
            caption = "Injected into Media3, VLC and external player requests",
            onClick = {
                onTextEditor(
                    TextEditorState(
                        title = "Authorization header",
                        label = "Authorization value",
                        initial = settings.authorization,
                        placeholder = "Bearer …",
                        onSave = { value -> vm.updateSettings { it.copy(authorization = value.trim()) } },
                    ),
                )
            },
        )
    }
    item {
        TvButton(
            text = "Clear custom headers",
            onClick = {
                vm.updateSettings {
                    it.copy(referer = "", origin = "", authorization = "", customUserAgent = "")
                }
            },
            primary = false,
        )
    }
    item {
        Text(
            text = "Playlist headers from #EXTVLCOPT and |User-Agent=…&Referer=… pipes override these defaults per channel.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextMuted,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appearanceSection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
    onPicker: (PickerState) -> Unit,
) {
    item {
        SettingRow(
            label = "Aspect ratio",
            value = settings.preferredAspect.label,
            caption = "Default surface fit for video playback",
            onClick = {
                onPicker(
                    PickerState(
                        title = "Aspect ratio",
                        subtitle = "Used as the starting mode for every stream.",
                        options = AspectMode.entries.map { it.label to null },
                        selected = AspectMode.entries.indexOf(settings.preferredAspect),
                        onSelect = { index ->
                            vm.updateSettings { it.copy(preferredAspect = AspectMode.entries[index]) }
                        },
                    ),
                )
            },
        )
    }
    item {
        SettingRow(
            label = "Channel sort",
            value = settings.channelSort.label,
            caption = "Order used by Live and the TV Guide",
            onClick = {
                onPicker(
                    PickerState(
                        title = "Channel sort",
                        subtitle = "Playlist order follows the provider sequence.",
                        options = com.streamline.play.data.model.ChannelSortMode.entries.map { it.label to null },
                        selected = com.streamline.play.data.model.ChannelSortMode.entries.indexOf(settings.channelSort),
                        onSelect = { index ->
                            vm.updateSettings {
                                it.copy(
                                    channelSort = com.streamline.play.data.model.ChannelSortMode.entries[index],
                                )
                            }
                        },
                    ),
                )
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sourceSection(
    vm: AppViewModel,
    sources: List<SourceConfig>,
    settings: com.streamline.play.data.settings.AppSettings,
    epgUpdated: Long,
    epgRefreshing: Boolean,
    epgMessage: String?,
    onAdd: () -> Unit,
    onEdit: (SourceConfig) -> Unit,
    onPicker: (PickerState) -> Unit,
    onManageEpg: () -> Unit,
) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "Add source",
                onClick = onAdd,
                icon = Icons.Rounded.Add,
                primary = true,
            )
            TvButton(
                text = "Reload playlists",
                onClick = { vm.reloadPlaylists() },
                icon = Icons.Rounded.Refresh,
                primary = false,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
    items(sources, key = { it.id }) { source ->
        SettingRow(
            label = source.name,
            value = source.kind.label,
            caption = sourceSummary(source),
            icon = Icons.Rounded.ChevronRight,
            onClick = { onEdit(source) },
        )
    }
    item {
        SectionHeader(
            title = "Global EPG",
            subtitle = "XMLTV guides shared by every loaded playlist",
        )
    }
    item {
        SettingRow(
            label = "Global EPG guides",
            value = when (settings.globalEpgUrls.size) {
                0 -> "None"
                1 -> "1 guide"
                else -> "${settings.globalEpgUrls.size} guides"
            },
            caption = "Supports .xml and gzipped .xml.gz links · tap to add or remove",
            icon = Icons.Rounded.ChevronRight,
            onClick = onManageEpg,
        )
    }
    item {
        SettingRow(
            label = "EPG refresh",
            value = settings.epgRefreshMode.label,
            caption = "Last update ${if (epgUpdated > 0) epgUpdated.asClock() else "never"}",
            onClick = {
                onPicker(
                    PickerState(
                        title = "EPG refresh",
                        subtitle = "Automatic mode refreshes every 24 hours or when guides change.",
                        options = EpgRefreshMode.entries.map { it.label to null },
                        selected = EpgRefreshMode.entries.indexOf(settings.epgRefreshMode),
                        onSelect = { index ->
                            vm.updateSettings { it.copy(epgRefreshMode = EpgRefreshMode.entries[index]) }
                        },
                    ),
                )
            },
        )
    }
    item {
        SettingRow(
            label = "Refresh EPG now",
            value = if (epgRefreshing) "Working…" else "Run",
            caption = epgMessage ?: "Fetches XMLTV schedules for all channels and playlists",
            icon = Icons.Rounded.Refresh,
            onClick = { vm.refreshEpg() },
        )
    }
    item {
        Text(
            text = "Sources are loaded concurrently. M3U, Xtream and MAG portals are merged into one catalog. " +
                "Every playlist is linked to the global guides above; tvg-id, tvg-name and channel names " +
                "map XMLTV programmes onto Live TV.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextMuted,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 12.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.cacheSection(
    vm: AppViewModel,
    settings: com.streamline.play.data.settings.AppSettings,
    cacheSizeText: String,
    onClear: () -> Unit,
) {
    item {
        SettingRow(
            label = "Cache footprint",
            value = cacheSizeText,
            caption = "EPG snapshots and stream buffers",
            icon = Icons.Rounded.Refresh,
            onClick = { vm.refreshCacheSize() },
        )
    }
    item {
        SettingRow(
            label = "Clear cache now",
            value = "Wipe",
            caption = "Deletes cached EPG data and buffered stream files",
            onClick = onClear,
        )
    }
    item {
        SettingRow(
            label = "Clear cache on exit",
            value = if (settings.clearCacheOnExit) "On" else "Off",
            caption = "Runs automatically when the app closes",
            onClick = { vm.updateSettings { it.copy(clearCacheOnExit = !it.clearCacheOnExit) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.aboutSection() {
    item {
        SettingRow(label = "App version", value = BuildConfig.VERSION_NAME, onClick = {})
    }
    item {
        SettingRow(
            label = "Player stack",
            value = "Media3 1.9 · libVLC 3.7",
            caption = "Jellyfin FFmpeg decoder for audio passthrough",
            onClick = {},
        )
    }
    item {
        SettingRow(
            label = "Target ABI",
            value = "armeabi-v7a",
            caption = "Optimized for 32-bit Android TV boxes and leanback remotes",
            onClick = {},
        )
    }
    item {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = "StreamLine Play",
                style = MaterialTheme.typography.titleMedium,
                color = Cinema.TextPrimary,
            )
            Text(
                text = "Cinema-grade IPTV for Android TV. D-pad first, low-latency, and built to keep old boxes cool.",
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "MPV has no embeddable 32-bit library on Maven, so the MPV engine profile hands the stream " +
                    "to an installed MPV app (is.xyz.mpv).",
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextMuted,
            )
        }
    }
}

@Composable
private fun RailItem(
    section: SettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        restingColor = if (selected) Cinema.SteelDeep.copy(alpha = 0.32f) else Color.Transparent,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = if (selected) Cinema.SteelDeep else Color.Transparent,
        focusedBorder = Cinema.SteelBlue,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        focusRequester = focusRequester,
    ) { focused ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = when {
                    focused -> Cinema.SteelBright
                    selected -> Cinema.SteelBlue
                    else -> Cinema.TextMuted
                },
                modifier = Modifier.width(22.dp),
            )
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) Cinema.TextPrimary else Cinema.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun sectionSubtitle(section: SettingsSection): String = when (section) {
    SettingsSection.PLAYBACK -> "Engine, decoder and audio"
    SettingsSection.RESOLUTION -> "Cap resolution and force a bitrate tier"
    SettingsSection.BUFFER -> "Buffer presets from Small to the 60 MB maximum"
    SettingsSection.PROFILE -> "Low RAM, ultra low latency or aggressive buffering"
    SettingsSection.SECURITY -> "Parental PIN and double-press exit"
    SettingsSection.NETWORK -> "Player signature and HTTP headers"
    SettingsSection.APPEARANCE -> "Aspect ratio and channel sorting"
    SettingsSection.SOURCES -> "Playlists, global EPG guides and refresh"
    SettingsSection.CACHE -> "Storage footprint and cleanup"
    SettingsSection.ABOUT -> "Build and player stack"
}

private fun sourceSummary(source: SourceConfig): String = when (source.kind) {
    SourceKind.M3U_URL -> source.url.ifBlank { "No playlist URL" }
    SourceKind.M3U_FILE -> source.url.ifBlank { "No file selected" }
    SourceKind.XTREAM -> "${source.url.ifBlank { "No portal URL" }} · ${source.username.ifBlank { "no user" }}"
    SourceKind.STALKER -> "${source.url.ifBlank { "No portal URL" }} · ${source.mac.ifBlank { "no MAC" }}"
}

private fun formatKbps(kbps: Int): String = String.format(Locale.US, "%,d", kbps)

private fun bitrateFraction(kbps: Int): Float =
    if (kbps <= BitrateLimit.AUTO) {
        0f
    } else {
        ((kbps - BitrateLimit.MIN_KBPS).toFloat() / (BitrateLimit.MAX_KBPS - BitrateLimit.MIN_KBPS))
            .coerceIn(0f, 1f)
    }

private fun fractionToBitrate(fraction: Float): Int {
    if (fraction <= 0.02f) return BitrateLimit.AUTO
    val raw = BitrateLimit.MIN_KBPS + fraction * (BitrateLimit.MAX_KBPS - BitrateLimit.MIN_KBPS)
    return ((raw / BitrateLimit.STEP_KBPS).roundToInt() * BitrateLimit.STEP_KBPS)
        .coerceIn(BitrateLimit.MIN_KBPS, BitrateLimit.MAX_KBPS)
}

private fun stepBitrate(kbps: Int, delta: Int): Int {
    if (kbps <= BitrateLimit.AUTO) {
        return if (delta > 0) BitrateLimit.MIN_KBPS else BitrateLimit.AUTO
    }
    val next = kbps + delta * BitrateLimit.STEP_KBPS
    return if (next < BitrateLimit.MIN_KBPS) BitrateLimit.AUTO else next.coerceAtMost(BitrateLimit.MAX_KBPS)
}
