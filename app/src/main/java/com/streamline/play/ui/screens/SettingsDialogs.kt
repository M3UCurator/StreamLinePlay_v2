package com.streamline.play.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.model.SourceKind
import com.streamline.play.data.settings.Parental
import com.streamline.play.ui.components.DialogHeader
import com.streamline.play.ui.components.DialogOption
import com.streamline.play.ui.components.PillChip
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.components.TvDialog
import com.streamline.play.ui.components.TvTextField
import com.streamline.play.ui.components.rememberInitialFocus
import com.streamline.play.ui.theme.Cinema
import java.util.UUID

data class PickerState(
    val title: String,
    val subtitle: String?,
    val options: List<Pair<String, String?>>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
)

data class TextEditorState(
    val title: String,
    val label: String,
    val initial: String,
    val placeholder: String,
    val onSave: (String) -> Unit,
)

@Composable
fun OptionPickerDialog(
    state: PickerState,
    onDismiss: () -> Unit,
) {
    val initialFocus = rememberInitialFocus()
    TvDialog(onDismiss = onDismiss, width = 620.dp) {
        DialogHeader(title = state.title, subtitle = state.subtitle)
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(state.options) { index, option ->
                DialogOption(
                    label = option.first,
                    supporting = option.second,
                    selected = index == state.selected,
                    onClick = {
                        state.onSelect(index)
                        onDismiss()
                    },
                    focusRequester = if (index == state.selected.coerceIn(0, state.options.lastIndex)) initialFocus else null,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        TvButton(text = "Cancel", onClick = onDismiss, primary = false)
    }
}

@Composable
fun TextEditorDialog(
    state: TextEditorState,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(state.initial) }
    val focus = rememberInitialFocus()
    TvDialog(onDismiss = onDismiss, width = 720.dp) {
        DialogHeader(title = state.title)
        TvTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = state.placeholder,
            label = state.label,
            focusRequester = focus,
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "Save",
                onClick = {
                    state.onSave(value)
                    onDismiss()
                },
                primary = true,
            )
            TvButton(text = "Cancel", onClick = onDismiss, primary = false)
        }
    }
}

@Composable
fun SourceEditorDialog(
    original: SourceConfig?,
    onDismiss: () -> Unit,
    onSave: (SourceConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val generatedId = remember { UUID.randomUUID().toString() }
    val sourceId = original?.id ?: generatedId
    var kind by remember(sourceId) { mutableStateOf(original?.kind ?: SourceKind.M3U_URL) }
    var name by remember(sourceId) { mutableStateOf(original?.name.orEmpty()) }
    var url by remember(sourceId) { mutableStateOf(original?.url.orEmpty()) }
    var username by remember(sourceId) { mutableStateOf(original?.username.orEmpty()) }
    var password by remember(sourceId) { mutableStateOf(original?.password.orEmpty()) }
    var mac by remember(sourceId) { mutableStateOf(original?.mac.orEmpty()) }
    var epgUrl by remember(sourceId) { mutableStateOf(original?.epgUrl.orEmpty()) }
    val nameFocus = rememberInitialFocus()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            url = uri.toString()
            if (name.isBlank()) {
                name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Local playlist"
            }
        }
    }

    val valid = when (kind) {
        SourceKind.M3U_URL -> name.isNotBlank() && (url.startsWith("http://", true) || url.startsWith("https://", true))
        SourceKind.M3U_FILE -> name.isNotBlank() && url.isNotBlank()
        SourceKind.XTREAM -> name.isNotBlank() && url.startsWith("http", true) && username.isNotBlank() && password.isNotBlank()
        SourceKind.STALKER -> name.isNotBlank() && url.startsWith("http", true) && mac.isNotBlank()
    }

    TvDialog(onDismiss = onDismiss, width = 780.dp) {
        DialogHeader(
            title = if (original == null) "Add source" else "Edit source",
            subtitle = "Sources load concurrently and merge into one cinematic catalog.",
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(SourceKind.entries.size) { index ->
                val candidate = SourceKind.entries[index]
                PillChip(
                    text = candidate.label,
                    selected = kind == candidate,
                    onClick = { kind = candidate },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvTextField(
                value = name,
                onValueChange = { name = it },
                label = "Display name",
                placeholder = "Living room playlist",
                focusRequester = nameFocus,
            )
            when (kind) {
                SourceKind.M3U_URL -> {
                    TvTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "Playlist URL",
                        placeholder = "https://provider.example/playlist.m3u8",
                    )
                }

                SourceKind.M3U_FILE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TvButton(
                            text = if (url.isBlank()) "Choose M3U file" else "Change file",
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            primary = false,
                        )
                        Text(
                            text = url.ifBlank { "No file selected" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cinema.TextMuted,
                            modifier = Modifier.width(440.dp),
                        )
                    }
                }

                SourceKind.XTREAM -> {
                    TvTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "Portal URL",
                        placeholder = "http://provider.example:8080",
                    )
                    TvTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Username",
                        placeholder = "username",
                    )
                    TvTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        placeholder = "password",
                    )
                }

                SourceKind.STALKER -> {
                    TvTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "Portal URL",
                        placeholder = "http://portal.example/c/",
                    )
                    TvTextField(
                        value = mac,
                        onValueChange = { mac = it },
                        label = "MAC address",
                        placeholder = "00:1A:79:XX:XX:XX",
                    )
                }
            }
            TvTextField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                label = "EPG URL (optional)",
                placeholder = "https://provider.example/xmltv.php",
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvButton(
                text = if (original == null) "Add source" else "Save changes",
                onClick = {
                    onSave(
                        (original ?: SourceConfig(id = sourceId)).copy(
                            kind = kind,
                            name = name.trim(),
                            url = url.trim(),
                            username = username.trim(),
                            password = password,
                            mac = mac.trim(),
                            epgUrl = epgUrl.trim(),
                        ),
                    )
                },
                primary = true,
                enabled = valid,
            )
            TvButton(text = "Cancel", onClick = onDismiss, primary = false)
            Spacer(Modifier.weight(1f))
            if (onDelete != null) {
                TvButton(
                    text = "Remove",
                    onClick = onDelete,
                    primary = false,
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = rememberInitialFocus()
    TvDialog(onDismiss = onDismiss, width = 560.dp) {
        DialogHeader(title = title)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Cinema.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "Cancel",
                onClick = onDismiss,
                primary = false,
                focusRequester = cancelFocus,
            )
            TvButton(
                text = confirmLabel,
                onClick = onConfirm,
                primary = true,
            )
        }
    }
}

enum class PinMode { SET, VERIFY }

/**
 * D-pad numeric PIN pad. SET asks for the code twice; VERIFY compares the salted hash.
 * The caller owns dismissal so a verified flow can chain into another dialog.
 */
@Composable
fun PinDialog(
    title: String,
    mode: PinMode,
    onConfirmed: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    expectedHash: String = "",
) {
    var pin by remember(title, mode) { mutableStateOf("") }
    var firstPin by remember(title, mode) { mutableStateOf("") }
    var confirming by remember(title, mode) { mutableStateOf(false) }
    var error by remember(title, mode) { mutableStateOf<String?>(null) }
    val firstKey = rememberInitialFocus()

    fun press(digit: Char) {
        if (pin.length >= PIN_LENGTH) return
        error = null
        pin += digit
    }

    fun submit() {
        if (pin.length != PIN_LENGTH) {
            error = "Enter all $PIN_LENGTH digits"
            return
        }
        when {
            mode == PinMode.VERIFY -> {
                if (expectedHash.isBlank()) {
                    error = "No PIN is configured"
                } else if (Parental.hashPin(pin) == expectedHash) {
                    onConfirmed(pin)
                } else {
                    error = "Incorrect PIN"
                    pin = ""
                }
            }
            !confirming -> {
                firstPin = pin
                pin = ""
                confirming = true
                error = null
            }
            pin == firstPin -> onConfirmed(firstPin)
            else -> {
                error = "PINs do not match"
                pin = ""
                firstPin = ""
                confirming = false
            }
        }
    }

    TvDialog(onDismiss = onDismiss, width = 520.dp) {
        DialogHeader(
            title = title,
            subtitle = when {
                confirming -> "Re-enter the same PIN to confirm"
                mode == PinMode.VERIFY -> subtitle ?: "Enter your $PIN_LENGTH-digit PIN"
                else -> subtitle ?: "Choose a $PIN_LENGTH-digit PIN for adult content"
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(PIN_LENGTH) { index ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (index < pin.length) Cinema.SteelBright else Cinema.SurfaceHigh)
                        .border(1.dp, Cinema.Border, CircleShape),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (confirming) "Confirm" else "PIN",
                style = MaterialTheme.typography.labelLarge,
                color = Cinema.TextMuted,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.Live,
            )
        }
        Spacer(Modifier.height(18.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        keys.chunked(3).forEachIndexed { rowIndex, rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowKeys.forEachIndexed { columnIndex, digit ->
                    TvButton(
                        text = digit,
                        onClick = { press(digit.first()) },
                        modifier = Modifier.width(96.dp),
                        primary = false,
                        focusRequester = if (rowIndex == 0 && columnIndex == 0) firstKey else null,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(
                text = "Clear",
                onClick = { pin = ""; error = null },
                modifier = Modifier.width(96.dp),
                primary = false,
            )
            TvButton(
                text = "0",
                onClick = { press('0') },
                modifier = Modifier.width(96.dp),
                primary = false,
            )
            TvButton(
                text = "Del",
                onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1); error = null },
                modifier = Modifier.width(96.dp),
                primary = false,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = if (confirming) "Confirm PIN" else "Continue",
                onClick = ::submit,
                primary = true,
                enabled = pin.length == PIN_LENGTH,
            )
            TvButton(text = "Cancel", onClick = onDismiss, primary = false)
        }
    }
}

private const val PIN_LENGTH = 4

@Composable
fun GroupLockDialog(
    groups: List<String>,
    selected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember(groups, selected) { mutableStateOf(selected) }
    val firstFocus = rememberInitialFocus()
    TvDialog(onDismiss = onDismiss, width = 640.dp) {
        DialogHeader(
            title = "Locked groups",
            subtitle = "Selected groups disappear from Live, Guide and Home while the PIN session is locked.",
        )
        if (groups.isEmpty()) {
            Text(
                text = "No groups in the catalog yet. Load a playlist first.",
                style = MaterialTheme.typography.bodyLarge,
                color = Cinema.TextMuted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(groups) { index, group ->
                    DialogOption(
                        label = group,
                        supporting = if (Parental.looksAdult(group)) "Adult content" else null,
                        selected = group in current,
                        onClick = {
                            current = if (group in current) current - group else current + group
                        },
                        focusRequester = if (index == 0) firstFocus else null,
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "Save groups",
                onClick = { onSave(current) },
                primary = true,
            )
            TvButton(text = "Cancel", onClick = onDismiss, primary = false)
        }
    }
}

@Composable
fun GlobalEpgDialog(
    urls: List<String>,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldFocus = rememberInitialFocus()
    TvDialog(onDismiss = onDismiss, width = 780.dp) {
        DialogHeader(
            title = "Global EPG guides",
            subtitle = "XMLTV .xml or gzipped .xml.gz feeds shared by every loaded playlist.",
        )
        TvTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            label = "Guide URL",
            placeholder = "https://example.com/guide.xml.gz",
            focusRequester = fieldFocus,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "Add guide",
                onClick = {
                    val clean = draft.trim()
                    when {
                        !(clean.startsWith("http://", true) || clean.startsWith("https://", true)) ->
                            error = "Enter a full http:// or https:// URL"

                        urls.any { it.equals(clean, ignoreCase = true) } ->
                            error = "This guide is already linked"

                        else -> {
                            onChange(urls + clean)
                            draft = ""
                            error = null
                        }
                    }
                },
                primary = true,
            )
            TvButton(text = "Done", onClick = onDismiss, primary = false)
        }
        error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.Live,
            )
        }
        Spacer(Modifier.height(16.dp))
        if (urls.isEmpty()) {
            Text(
                text = "No global guides yet. Playlist headers (url-tvg, x-tvg-url, tvg-url) are still parsed automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextMuted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(urls) { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cinema.TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        TvButton(
                            text = "Remove",
                            onClick = { onChange(urls.filterNot { it == url }) },
                            primary = false,
                        )
                    }
                }
            }
        }
    }
}
