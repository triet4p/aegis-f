package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.provider.HostedProviderFailure
import io.github.triet4p.aegisf.provider.HostedProviderPreset
import io.github.triet4p.aegisf.provider.HostedProviderPresets
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedReasoningEffort
import io.github.triet4p.aegisf.provider.MIN_EXPLICIT_OUTPUT_TOKENS
import io.github.triet4p.aegisf.provider.SavedHostedProvider
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import io.github.triet4p.aegisf.ui.components.AegisChoice
import io.github.triet4p.aegisf.ui.components.AegisChoiceSheet
import io.github.triet4p.aegisf.ui.components.AegisDiscardChangesDialog
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * One hosted provider configuration destination.
 *
 * The screen owns one task: keep the saved provider profiles and edit one of them. Profiles are
 * a homogeneous list; protocol, preset, endpoint, model, credential and output controls belong to
 * that same task, so they are one focused form rather than a second destination. A save tests
 * the configured endpoint first and stores the profile only after a verified answer; a failed or
 * offline test changes no profile and keeps every typed value. The stored credential is never
 * rendered: editing a profile can keep it by leaving the credential field blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostedProviderSettingsScreen(
    store: HostedProviderSettingsStore,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    networkAvailable: Boolean = true,
    transport: HttpTransport = UrlConnectionHttpTransport(),
) {
    val baseStrings = LocalAegisStrings.current
    val strings = remember(baseStrings.language) { settingsStrings(baseStrings.language) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var profiles by remember { mutableStateOf<List<SavedHostedProvider>?>(null) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var storeFailed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

    var draft by remember { mutableStateOf(blankProviderDraft()) }
    var initialDraft by remember { mutableStateOf(blankProviderDraft()) }
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var editingCredential by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var pendingDiscardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deletingProfile by remember { mutableStateOf<SavedHostedProvider?>(null) }
    var openProtocolSheet by remember { mutableStateOf(false) }
    var openPresetSheet by remember { mutableStateOf(false) }
    var openReasoningSheet by remember { mutableStateOf(false) }

    suspend fun reloadProfiles() {
        try {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            val active = withContext(Dispatchers.IO) { store.active()?.id }
            profiles = loaded
            activeProfileId = active
            storeFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storeFailed = true
            profiles = emptyList()
        }
    }

    LaunchedEffect(store, reloadToken) { reloadProfiles() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadToken += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val dirty = draft != initialDraft
    fun requestClose() {
        if (busy) return
        if (dirty) {
            pendingDiscardAction = { onNavigateUp() }
            showDiscard = true
        } else {
            onNavigateUp()
        }
    }

    fun startNewProfile() {
        val blank = blankProviderDraft()
        draft = blank
        initialDraft = blank
        editingProfileId = null
        editingCredential = null
        status = null
        statusIsError = false
    }

    fun editProfile(profile: SavedHostedProvider) {
        val preset = HostedProviderPresets.all.firstOrNull {
            it.protocol == profile.protocol &&
                it.model == profile.model &&
                it.endpoint == profile.endpoint
        } ?: HostedProviderPresets.all.firstOrNull { it.protocol == profile.protocol }
            ?: HostedProviderPresets.all.first()
        val loaded = ProviderDraft(
            presetId = preset.id,
            protocol = profile.protocol,
            name = profile.name,
            endpoint = profile.endpoint,
            model = profile.model,
            maxOutputTokens = profile.maxOutputTokens?.toString().orEmpty(),
            reasoningEffort = profile.reasoningEffort,
            parallelToolCalls = profile.parallelToolCalls ?: false,
            credential = "",
        )
        draft = loaded
        initialDraft = loaded
        editingProfileId = profile.id
        editingCredential = profile.credential
        status = strings.providerReplaceNotice
        statusIsError = false
    }

    fun requestNewProfile() {
        if (busy) return
        if (dirty) {
            pendingDiscardAction = { startNewProfile() }
            showDiscard = true
        } else {
            startNewProfile()
        }
    }

    fun requestEditProfile(profile: SavedHostedProvider) {
        if (busy) return
        if (dirty) {
            pendingDiscardAction = { editProfile(profile) }
            showDiscard = true
        } else {
            editProfile(profile)
        }
    }

    fun applyPreset(preset: HostedProviderPreset) {
        draft = draft.copy(
            presetId = preset.id,
            protocol = preset.protocol,
            endpoint = preset.endpoint,
            model = preset.model,
            maxOutputTokens = preset.maxOutputTokens?.toString().orEmpty(),
            reasoningEffort = preset.reasoningEffort,
            parallelToolCalls = preset.capabilities.supportsParallelToolCalls,
            name = draft.name.ifBlank { preset.label },
        )
    }

    BackHandler { requestClose() }

    val selectedPreset = HostedProviderPresets.all.firstOrNull { it.id == draft.presetId }
        ?: HostedProviderPresets.all.first()
    val protocolPresets = HostedProviderPresets.all.filter { it.protocol == draft.protocol }
    val supportsReasoning = selectedPreset.capabilities.supportsReasoningBudget
    val supportsParallel = selectedPreset.capabilities.supportsParallelToolCalls

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.providerTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::requestClose, enabled = !busy) {
                        AegisIcon(
                            glyph = AegisGlyph.ARROW_BACK,
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 22.dp,
                            modifier = Modifier.semantics {
                                contentDescription = strings.back
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = strings.providerTestBeforeSave,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            val trimmedName = draft.name.trim()
                            val trimmedEndpoint = draft.endpoint.trim()
                            val trimmedModel = draft.model.trim()
                            val outputText = draft.maxOutputTokens.trim()
                            val outputBudget = outputText.toIntOrNull()
                            val localError = when {
                                !networkAvailable -> strings.providerTestNoNetwork
                                storeFailed -> strings.providerStoreUnavailable
                                trimmedName.isEmpty() -> strings.providerProfileNameRequired
                                trimmedEndpoint.isEmpty() -> strings.providerEndpointRequired
                                !trimmedEndpoint.startsWith("https://", ignoreCase = true) ->
                                    strings.providerEndpointRequired
                                trimmedModel.isEmpty() -> strings.providerModelRequired
                                outputText.isNotEmpty() &&
                                    (outputBudget == null ||
                                        outputBudget < MIN_EXPLICIT_OUTPUT_TOKENS) ->
                                    strings.providerOutputInvalid(MIN_EXPLICIT_OUTPUT_TOKENS)
                                draft.credential.isBlank() && editingCredential.isNullOrBlank() ->
                                    strings.providerCredentialRequired
                                else -> null
                            }
                            if (localError != null) {
                                status = localError
                                statusIsError = true
                                return@Button
                            }
                            val candidate = try {
                                selectedPreset.copy(
                                    endpoint = trimmedEndpoint,
                                    model = trimmedModel,
                                ).profile(
                                    name = trimmedName,
                                    credential = draft.credential.ifBlank {
                                        editingCredential.orEmpty()
                                    },
                                    maxOutputTokens = outputBudget,
                                    reasoningEffort = if (supportsReasoning) {
                                        draft.reasoningEffort
                                    } else {
                                        null
                                    },
                                    parallelToolCalls = if (supportsParallel) {
                                        draft.parallelToolCalls
                                    } else {
                                        null
                                    },
                                )
                            } catch (_: Exception) {
                                status = strings.providerSaveFailed
                                statusIsError = true
                                return@Button
                            }
                            busy = true
                            status = strings.providerTestingStatus
                            statusIsError = false
                            scope.launch {
                                val check = try {
                                    withContext(Dispatchers.IO) {
                                        validateConnection(candidate, transport)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    ConnectionCheck.Transport
                                }
                                if (check != ConnectionCheck.Verified) {
                                    busy = false
                                    status = connectionMessage(check, strings)
                                    statusIsError = true
                                    return@launch
                                }
                                val savedId = editingProfileId
                                    ?: (selectedPreset.id + "-" + UUID.randomUUID().toString())
                                val saved = candidate.copy(id = savedId)
                                val next = profiles.orEmpty().filterNot { it.id == savedId } + saved
                                try {
                                    withContext(Dispatchers.IO) { store.save(next) }
                                    profiles = next
                                    editingProfileId = null
                                    editingCredential = null
                                    val cleared = draft.copy(credential = "")
                                    draft = cleared
                                    initialDraft = cleared
                                    busy = false
                                    status = strings.providerConnectionVerified
                                    statusIsError = false
                                    focusManager.clearFocus()
                                    activeProfileId = withContext(Dispatchers.IO) {
                                        store.active()?.id
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    busy = false
                                    status = strings.providerSaveFailed
                                    statusIsError = true
                                }
                            }
                        },
                        enabled = !busy && !storeFailed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("provider-save"),
                    ) {
                        if (busy) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp))
                                Text(strings.providerTesting)
                            }
                        } else {
                            Text(strings.providerSave)
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.providerPurpose,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (storeFailed) {
                Text(
                    text = strings.providerStoreUnavailable,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = strings.providerProfilesSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                profiles == null -> Text(
                    text = strings.loading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profiles.orEmpty().isEmpty() -> Text(
                    text = strings.providerNoProfiles,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.orEmpty().forEach { profile ->
                        ProviderProfileCard(
                            profile = profile,
                            strings = strings,
                            active = activeProfileId == profile.id,
                            enabled = !busy && !storeFailed,
                            onUse = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.activate(profile.id) }
                                        activeProfileId = profile.id
                                        status = strings.providerActivated
                                        statusIsError = false
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        status = strings.providerActivateFailed
                                        statusIsError = true
                                    }
                                }
                            },
                            onEdit = { requestEditProfile(profile) },
                            onDelete = { deletingProfile = profile },
                        )
                    }
                }
            }
            if (profiles.orEmpty().isNotEmpty() || editingProfileId != null) {
                TextButton(
                    onClick = ::requestNewProfile,
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("provider-new"),
                ) {
                    Text(strings.providerNewProfile)
                }
            }

            HorizontalDivider()

            Text(
                text = strings.providerProfileSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    ProviderChoiceRow(
                        title = strings.providerProtocolLabel,
                        value = strings.protocolName(draft.protocol),
                        onClick = { openProtocolSheet = true },
                        modifier = Modifier.testTag("provider-protocol"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ProviderChoiceRow(
                        title = strings.providerPresetLabel,
                        value = selectedPreset.label,
                        onClick = { openPresetSheet = true },
                        modifier = Modifier.testTag("provider-preset"),
                    )
                    if (supportsReasoning) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ProviderChoiceRow(
                            title = strings.providerReasoningLabel,
                            value = strings.reasoningName(draft.reasoningEffort),
                            onClick = { openReasoningSheet = true },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider-name"),
                label = { Text(strings.providerProfileNameLabel) },
                placeholder = { Text(strings.providerProfileNamePlaceholder) },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.endpoint,
                onValueChange = { draft = draft.copy(endpoint = it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider-endpoint"),
                label = { Text(strings.providerEndpointLabel) },
                singleLine = true,
                supportingText = { Text(strings.providerEndpointDisclosure) },
            )
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider-model"),
                label = { Text(strings.providerModelLabel) },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.credential,
                onValueChange = { draft = draft.copy(credential = it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider-credential"),
                label = { Text(strings.providerCredentialLabel) },
                placeholder = {
                    Text(
                        if (editingProfileId != null) {
                            strings.providerCredentialPlaceholderKeep
                        } else {
                            strings.providerCredentialPlaceholderNew
                        },
                    )
                },
                supportingText = {
                    if (editingProfileId != null) {
                        Text(strings.providerCredentialStored)
                    }
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.maxOutputTokens,
                onValueChange = { value ->
                    draft = draft.copy(maxOutputTokens = value.filter(Char::isDigit))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider-output"),
                label = { Text(strings.providerOutputLabel) },
                supportingText = { Text(strings.providerOutputHelp(MIN_EXPLICIT_OUTPUT_TOKENS)) },
                singleLine = true,
            )
            if (supportsParallel) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("provider-parallel"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = draft.parallelToolCalls,
                        onCheckedChange = { draft = draft.copy(parallelToolCalls = it) },
                    )
                    Text(text = strings.providerParallelLabel)
                }
            }
            Text(
                text = strings.providerMaskingDisclosure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .testTag("provider-status"),
                )
            }
        }
    }

    if (openProtocolSheet) {
        AegisChoiceSheet(
            title = strings.providerProtocolSheet,
            options = HostedProtocol.entries.map { protocol ->
                AegisChoice(protocol, strings.protocolName(protocol))
            },
            selected = draft.protocol,
            onSelect = { protocol ->
                val first = HostedProviderPresets.all.firstOrNull { it.protocol == protocol }
                if (first != null) applyPreset(first)
            },
            onDismiss = { openProtocolSheet = false },
        )
    }
    if (openPresetSheet) {
        AegisChoiceSheet(
            title = strings.providerPresetSheet,
            options = protocolPresets.map { preset -> AegisChoice(preset, preset.label) },
            selected = selectedPreset,
            onSelect = { preset -> applyPreset(preset) },
            onDismiss = { openPresetSheet = false },
        )
    }
    if (openReasoningSheet) {
        AegisChoiceSheet<HostedReasoningEffort?>(
            title = strings.providerReasoningSheet,
            options = buildList {
                add(AegisChoice<HostedReasoningEffort?>(null, strings.providerReasoningNone))
                HostedReasoningEffort.entries.forEach { effort ->
                    add(AegisChoice<HostedReasoningEffort?>(effort, strings.reasoningName(effort)))
                }
            },
            selected = draft.reasoningEffort,
            onSelect = { effort -> draft = draft.copy(reasoningEffort = effort) },
            onDismiss = { openReasoningSheet = false },
        )
    }

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(strings.providerDeleteTitle) },
            text = { Text(strings.providerDeleteBody(profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.delete(profile.id) }
                                deletingProfile = null
                                if (editingProfileId == profile.id) startNewProfile()
                                reloadToken += 1
                                status = strings.providerProfileDeleted
                                statusIsError = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                deletingProfile = null
                                status = strings.providerProfileDeleteFailed
                                statusIsError = true
                            }
                        }
                    },
                    modifier = Modifier.testTag("provider-delete-confirm"),
                ) {
                    Text(
                        text = strings.providerDeleteConfirm,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) { Text(strings.cancel) }
            },
        )
    }

    if (showDiscard) {
        AegisDiscardChangesDialog(
            onKeepEditing = {
                showDiscard = false
                pendingDiscardAction = null
            },
            onDiscard = {
                showDiscard = false
                val action = pendingDiscardAction
                pendingDiscardAction = null
                action?.invoke() ?: onNavigateUp()
            },
        )
    }
}

/**
 * One protocol/preset row: the current value sits under its label so a long protocol or preset
 * name cannot squeeze the label into a vertical strip. Tapping opens the focused choice sheet.
 */
@Composable
private fun ProviderChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AegisIcon(
            glyph = AegisGlyph.CHEVRON_RIGHT,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
    }
}

@Composable
private fun ProviderProfileCard(
    profile: SavedHostedProvider,
    strings: SettingsStrings,
    active: Boolean,
    enabled: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider-profile-${profile.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = strings.providerActiveBadge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Text(
                text = strings.providerProfileSummary(
                    strings.protocolName(profile.protocol),
                    profile.model,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = profile.endpoint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!active) {
                    TextButton(
                        onClick = onUse,
                        enabled = enabled,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("provider-use-${profile.id}"),
                    ) { Text(strings.providerUse) }
                }
                TextButton(
                    onClick = onEdit,
                    enabled = enabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("provider-edit-${profile.id}"),
                ) { Text(strings.providerEdit) }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("provider-delete-${profile.id}"),
                ) {
                    Text(strings.providerDelete, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** One provider profile's editable values; the credential is never part of a saved snapshot. */
private data class ProviderDraft(
    val presetId: String,
    val protocol: HostedProtocol,
    val name: String,
    val endpoint: String,
    val model: String,
    val maxOutputTokens: String,
    val reasoningEffort: HostedReasoningEffort?,
    val parallelToolCalls: Boolean,
    val credential: String,
)

private fun blankProviderDraft(): ProviderDraft {
    val preset = HostedProviderPresets.all.first()
    return ProviderDraft(
        presetId = preset.id,
        protocol = preset.protocol,
        name = "",
        endpoint = preset.endpoint,
        model = preset.model,
        maxOutputTokens = preset.maxOutputTokens?.toString().orEmpty(),
        reasoningEffort = preset.reasoningEffort,
        parallelToolCalls = preset.capabilities.supportsParallelToolCalls,
        credential = "",
    )
}

private sealed interface ConnectionCheck {
    data object Verified : ConnectionCheck
    data class Auth(val status: Int) : ConnectionCheck
    data class Account(val status: Int) : ConnectionCheck
    data class RateLimited(val status: Int) : ConnectionCheck
    data object Transport : ConnectionCheck
    data class Unavailable(val status: Int) : ConnectionCheck
    data class Rejected(val status: Int) : ConnectionCheck
    data object Redirected : ConnectionCheck
    data object PrivacyBlocked : ConnectionCheck
    data object ModelMismatch : ConnectionCheck
    data object Invalid : ConnectionCheck
}

private fun connectionMessage(check: ConnectionCheck, strings: SettingsStrings): String =
    when (check) {
        ConnectionCheck.Verified -> strings.providerConnectionVerified
        is ConnectionCheck.Auth -> strings.providerTestAuth(check.status)
        is ConnectionCheck.Account -> strings.providerTestAccount(check.status)
        is ConnectionCheck.RateLimited -> strings.providerTestRateLimited(check.status)
        ConnectionCheck.Transport -> strings.providerTestTransport
        is ConnectionCheck.Unavailable -> strings.providerTestUnavailable(check.status)
        is ConnectionCheck.Rejected -> if (check.status == 404) {
            strings.providerTestRejectedModel(check.status)
        } else {
            strings.providerTestRejectedSettings(check.status)
        }
        ConnectionCheck.Redirected -> strings.providerTestRedirected
        ConnectionCheck.PrivacyBlocked -> strings.providerTestPrivacyBlocked
        ConnectionCheck.ModelMismatch -> strings.providerTestModelMismatch
        ConnectionCheck.Invalid -> strings.providerTestInvalid
    }

private suspend fun validateConnection(
    profile: SavedHostedProvider,
    transport: HttpTransport,
): ConnectionCheck {
    val config = profile.validatedConfig("settings-${UUID.randomUUID()}")
    val adapter = HostedAdapters.create(config, transport)
    val conversation = adapter.begin(
        systemInstruction = "This is a connection check. Reply with the single word READY.",
        userInstruction = "Reply READY.",
    )
    return when (val result = adapter.generate(conversation)) {
        is HostedGenerationResult.Turn -> ConnectionCheck.Verified
        is HostedGenerationResult.ProviderFailure -> when (val failure = result.failure) {
            is HostedProviderFailure.Authentication -> ConnectionCheck.Auth(failure.status)
            is HostedProviderFailure.Account -> ConnectionCheck.Account(failure.status)
            is HostedProviderFailure.RateLimited -> ConnectionCheck.RateLimited(failure.status)
            is HostedProviderFailure.Transport -> ConnectionCheck.Transport
            is HostedProviderFailure.Unavailable -> ConnectionCheck.Unavailable(failure.status)
            is HostedProviderFailure.Rejected -> ConnectionCheck.Rejected(failure.status)
            is HostedProviderFailure.Redirected -> ConnectionCheck.Redirected
            is HostedProviderFailure.PrivacyBlocked -> ConnectionCheck.PrivacyBlocked
            is HostedProviderFailure.ModelMismatch -> ConnectionCheck.ModelMismatch
            is HostedProviderFailure.ProviderState,
            is HostedProviderFailure.InvalidResponse,
            -> ConnectionCheck.Invalid
        }
    }
}
