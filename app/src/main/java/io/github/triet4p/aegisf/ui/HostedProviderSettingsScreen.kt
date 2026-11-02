package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProviderFailure
import io.github.triet4p.aegisf.provider.HostedProviderPresets
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.provider.SavedHostedProvider
import io.github.triet4p.aegisf.provider.HostedReasoningEffort
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostedProviderSettingsScreen(
    store: HostedProviderSettingsStore,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var storeLoadFailed by remember { mutableStateOf(false) }
    var profiles by remember {
        mutableStateOf(
            runCatching { store.load() }
                .onFailure { storeLoadFailed = true }
                .getOrDefault(emptyList()),
        )
    }
    var selectedPresetId by remember { mutableStateOf(HostedProviderPresets.all.first().id) }
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var profileName by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var maxOutputTokens by remember { mutableStateOf(HostedProviderPresets.all.first().maxOutputTokens.toString()) }
    var reasoningEffort by remember { mutableStateOf(HostedProviderPresets.all.first().reasoningEffort) }
    var parallelToolCalls by remember {
        mutableStateOf(HostedProviderPresets.all.first().capabilities.supportsParallelToolCalls)
    }
    var model by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val preset = HostedProviderPresets.byId(selectedPresetId)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("HOSTED PROVIDER") },
                navigationIcon = { TextButton(onClick = onNavigateUp) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hosted agent", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Requests use the selected HTTPS endpoint and may send locally masked context. " +
                    "Masking reduces direct values but is not anonymity; remaining context may still identify you. " +
                    "Review the endpoint and provider terms before sending. Manual entry, notification intake and local Laya remain available offline.",
            )
            if (storeLoadFailed) {
                Text(
                    "Secure provider settings could not be opened. Existing profiles are preserved; " +
                        "saving or deleting is disabled until secure storage becomes available.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            profiles.forEach { profile ->
                Text(
                    if (store.active()?.id == profile.id) "ACTIVE · ${profile.name} · ${profile.model}"
                    else "${profile.name} · ${profile.model}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("${profile.protocol} · ${profile.endpoint} · credential stored securely")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        editingProfileId = profile.id
                        selectedPresetId = HostedProviderPresets.all.firstOrNull {
                            it.protocol == profile.protocol &&
                                it.model == profile.model &&
                                it.endpoint == profile.endpoint
                        }?.id ?: HostedProviderPresets.all.firstOrNull {
                            it.protocol == profile.protocol
                        }?.id ?: HostedProviderPresets.all.first().id
                        profileName = profile.name
                        endpoint = profile.endpoint
                        model = profile.model
                        credential = ""
                        maxOutputTokens = profile.maxOutputTokens.toString()
                        reasoningEffort = profile.reasoningEffort
                        parallelToolCalls = profile.parallelToolCalls ?: false
                        status = "Enter a replacement credential to edit this profile."
                    }) { Text("Replace") }
                    OutlinedButton(onClick = {
                        runCatching { store.activate(profile.id) }
                            .onSuccess { status = "Active provider changed. New runs use this profile; active runs remain unchanged." }
                            .onFailure { status = "Provider could not be selected." }
                    }) { Text("Use provider") }
                    OutlinedButton(onClick = {
                        runCatching { store.delete(profile.id) }
                            .onSuccess { profiles = store.load(); status = "Provider profile deleted." }
                            .onFailure { status = "Could not delete settings." }
                    }) { Text("Delete") }
                }
            }
            Text("ADD OR REPLACE PROFILE", style = MaterialTheme.typography.labelLarge)
            HostedProviderPresets.all.forEach { option ->
                OutlinedButton(
                    onClick = {
                        selectedPresetId = option.id
                        endpoint = option.endpoint
                        model = option.model
                        maxOutputTokens = option.maxOutputTokens.toString()
                        reasoningEffort = option.reasoningEffort
                        parallelToolCalls = option.capabilities.supportsParallelToolCalls
                        if (profileName.isBlank()) profileName = option.label
                        status = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (option.id == selectedPresetId) "Selected · ${option.label}" else option.label) }
            }
            OutlinedTextField(profileName, { profileName = it }, Modifier.fillMaxWidth(), label = { Text("Profile name") }, singleLine = true)
            OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS endpoint") }, singleLine = true)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true)
            OutlinedTextField(
                value = maxOutputTokens,
                onValueChange = { maxOutputTokens = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Maximum output tokens") },
                singleLine = true,
            )
            OutlinedTextField(
                credential, { credential = it }, Modifier.fillMaxWidth(),
                label = { Text(if (preset.protocol.name.startsWith("GEMINI")) "Google API key" else "Bearer credential") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
            )
            Text("Reasoning budget: ${reasoningEffort?.name ?: "not configured"}")
            if (preset.capabilities.supportsReasoningBudget) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { reasoningEffort = null }) { Text("No reasoning budget") }
                    HostedReasoningEffort.values().forEach { effort ->
                        TextButton(onClick = { reasoningEffort = effort }) { Text(effort.name) }
                    }
                }
            }
            if (preset.capabilities.supportsParallelToolCalls) {
                Row {
                    Checkbox(
                        checked = parallelToolCalls,
                        onCheckedChange = { parallelToolCalls = it },
                    )
                    Text("Allow parallel tool calls")
                }
            }
            Text("Generation limit: ${preset.maxOutputTokens} output tokens. Protocol capabilities are declared per verified preset; custom models need a supported capability declaration.")
            Button(
                onClick = {
                    val candidate = runCatching {
                        require(profileName.isNotBlank()) { "Enter a profile name." }
                        require(credential.isNotBlank()) { "Enter a credential or replace the saved profile securely." }
                        preset.copy(
                            endpoint = endpoint,
                            model = model,
                            maxOutputTokens = maxOutputTokens.toInt(),
                            reasoningEffort = reasoningEffort,
                        ).profile(
                            name = profileName.trim(),
                            credential = credential,
                            maxOutputTokens = maxOutputTokens.toInt(),
                            reasoningEffort = reasoningEffort,
                            parallelToolCalls = if (preset.capabilities.supportsParallelToolCalls) {
                                parallelToolCalls
                            } else {
                                null
                            },
                        )
                    }.getOrElse {
                        status = it.message ?: "Provider settings are invalid."
                        return@Button
                    }
                    busy = true
                    status = "Checking the configured endpoint with a synthetic probe…"
                    scope.launch {
                    val result = try {
                        withContext(Dispatchers.IO) { validateConnection(candidate) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        "No network connection. Check that the device is online and retry."
                    }
                    busy = false
                        if (result == "Connection verified.") {
                            val saved = candidate.copy(
                                id = editingProfileId ?: (preset.id + "-" + UUID.randomUUID().toString()),
                            )
                            val next = profiles.filterNot { it.id == saved.id } + saved
                            runCatching { store.save(next) }
                                .onSuccess {
                                    profiles = next
                                    editingProfileId = null
                                    credential = ""
                                    status = "Connection verified. Provider settings saved on this device."
                                }
                                .onFailure { status = "Settings could not be saved securely." }
                        } else {
                            status = "$result No profile was changed."
                        }
                    }
                },
                enabled = !busy && !storeLoadFailed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator() else Text("Validate connection and save")
            }
            status?.let { Text(it, color = if (it.startsWith("Connection verified")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }
}

private suspend fun validateConnection(profile: SavedHostedProvider): String {
    val config = profile.validatedConfig("settings-${UUID.randomUUID()}")
    val adapter = io.github.triet4p.aegisf.provider.HostedAdapters.create(config, UrlConnectionHttpTransport())
    val conversation = adapter.begin(
        systemInstruction = "This is a connection check. Reply with the single word READY.",
        userInstruction = "Reply READY.",
    )
    return when (val result = adapter.generate(conversation)) {
        is HostedGenerationResult.Turn -> "Connection verified."
        is HostedGenerationResult.ProviderFailure -> when (val failure = result.failure) {
            is HostedProviderFailure.Authentication -> "Credential rejected (HTTP ${failure.status}). Replace it and retry."
            is HostedProviderFailure.Account -> "Provider account or billing needs attention (HTTP ${failure.status})."
            is HostedProviderFailure.RateLimited -> "Provider is rate limited (HTTP ${failure.status}). Wait and retry."
            is HostedProviderFailure.Transport -> "Provider is offline or unreachable. Check network and retry."
            is HostedProviderFailure.Unavailable -> "Provider is temporarily unavailable (HTTP ${failure.status}). Retry later."
            is HostedProviderFailure.Rejected -> if (failure.status == 404) {
                "The configured model or endpoint is unavailable (HTTP 404). Check the model ID and route."
            } else {
                "Provider rejected the configured model or generation settings (HTTP ${failure.status})."
            }
            is HostedProviderFailure.Redirected -> "Endpoint redirected the request; credentials were not forwarded."
            is HostedProviderFailure.PrivacyBlocked -> "Provider response was refused by the local privacy boundary."
            is HostedProviderFailure.ModelMismatch -> "Provider returned a different model than configured."
            is HostedProviderFailure.ProviderState, is HostedProviderFailure.InvalidResponse ->
                "Provider response was not valid for the configured protocol."
        }
    }
}
