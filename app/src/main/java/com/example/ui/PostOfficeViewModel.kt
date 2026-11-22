package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.adhocctl.GameDb
import com.example.core.adhocctl.GameDbCodec
import com.example.core.common.LogCategory
import com.example.core.common.LogEntry
import com.example.core.common.ServerConfig
import com.example.core.common.ServerConfigCodec
import com.example.core.common.ServerLogger
import com.example.service.ConfigStorage
import com.example.service.PostOfficeCoordinator
import com.example.service.PostOfficeForegroundService
import com.example.service.ServerRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.TreeMap
import java.util.TreeSet

enum class AppDestination {
    DASHBOARD,
    ROOMS,
    CONFIG,
    LOGS
}

data class ConfigEditorUiState(
    val configDraft: ServerConfig = ServerConfig(),
    val configDirty: Boolean = false,
    val configValidationMessage: String? = null,
    val configValidationIsError: Boolean = false,
    val gameDbDraft: GameDb = GameDb(),
    val gameDbDirty: Boolean = false,
    val gameDbValidationMessage: String? = null,
    val gameDbValidationIsError: Boolean = false
)

data class LogFilterUiState(
    val selectedCategory: LogCategory? = null,
    val searchQuery: String = "",
    val autoScroll: Boolean = true
)

class PostOfficeViewModel(application: Application) : AndroidViewModel(application) {
    val runtimeState: StateFlow<ServerRuntimeState> = PostOfficeCoordinator.state
    val logEntries: StateFlow<List<LogEntry>> = ServerLogger.logs

    private val _selectedDestination = MutableStateFlow(AppDestination.DASHBOARD)
    val selectedDestination: StateFlow<AppDestination> = _selectedDestination.asStateFlow()

    private val _editorState = MutableStateFlow(ConfigEditorUiState())
    val editorState: StateFlow<ConfigEditorUiState> = _editorState.asStateFlow()

    private val _logFilter = MutableStateFlow(LogFilterUiState())
    val logFilter: StateFlow<LogFilterUiState> = _logFilter.asStateFlow()

    private val _gplLicenseText = MutableStateFlow("")
    val gplLicenseText: StateFlow<String> = _gplLicenseText.asStateFlow()

    private val _ignoringBatteryOptimizations = MutableStateFlow(false)
    val ignoringBatteryOptimizations: StateFlow<Boolean> = _ignoringBatteryOptimizations.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    init {
        val ctx = getApplication<Application>()
        PostOfficeCoordinator.refreshStaticState(ctx)
        refreshBatteryOptimizationStatus()
        loadEditorFromDisk()
        viewModelScope.launch(Dispatchers.IO) {
            _gplLicenseText.value = ConfigStorage.readGplLicenseText(ctx)
        }
    }

    fun refreshBatteryOptimizationStatus() {
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = try {
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        } catch (_: Exception) {
            false
        }
        _ignoringBatteryOptimizations.value = ignoring
    }

    fun selectDestination(destination: AppDestination) {
        _selectedDestination.value = destination
    }

    fun startServer() {
        PostOfficeForegroundService.startServer(getApplication())
    }

    fun stopServer() {
        PostOfficeForegroundService.stopServer(getApplication())
    }

    fun restartServer() {
        PostOfficeForegroundService.restartServer(getApplication())
    }

    fun setWakeLockEnabled(enabled: Boolean) {
        PostOfficeForegroundService.setWakeLockEnabled(getApplication(), enabled)
    }

    fun refreshLocalAddresses() {
        PostOfficeCoordinator.refreshStaticState(getApplication())
    }

    fun loadEditorFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val cfgRaw = ConfigStorage.readConfigRaw(ctx)
            val cfgParsed = ServerConfigCodec.parseFromJsonString(cfgRaw)
            val dbRaw = ConfigStorage.readGameDbRaw(ctx)
            val dbParsed = GameDbCodec.parseFromJsonString(dbRaw)
            _editorState.value = ConfigEditorUiState(
                configDraft = cfgParsed.config,
                configDirty = false,
                configValidationMessage = cfgParsed.parseError,
                configValidationIsError = cfgParsed.parseError != null,
                gameDbDraft = dbParsed.gameDb,
                gameDbDirty = false,
                gameDbValidationMessage = dbParsed.errorMessage,
                gameDbValidationIsError = !dbParsed.isValid
            )
        }
    }

    fun updateConfigVisual(updated: ServerConfig) {
        _editorState.value = _editorState.value.copy(
            configDraft = updated,
            configDirty = true,
            configValidationMessage = null,
            configValidationIsError = false
        )
    }

    fun saveConfig() {
        val current = _editorState.value
        val serialized = ServerConfigCodec.toJsonString(current.configDraft)
        val parsed = ServerConfigCodec.parseFromJsonString(serialized)
        if (parsed.parseError != null) {
            _editorState.value = current.copy(
                configValidationMessage = parsed.parseError,
                configValidationIsError = true
            )
            emitSnackbar("Cannot save config.json: ${parsed.parseError}")
            return
        }
        val normalizedJson = ServerConfigCodec.toJsonString(parsed.config)
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            ConfigStorage.writeConfigRaw(ctx, normalizedJson)
            PostOfficeCoordinator.reloadConfigAndGameDbNow(ctx)
            val warning = when {
                parsed.forcedKeys.isNotEmpty() -> "Saved with forced rules: ${parsed.forcedKeys.joinToString(", ")}"
                parsed.defaultedKeys.isNotEmpty() -> "Saved with defaults for: ${parsed.defaultedKeys.joinToString(", ")}"
                else -> null
            }
            _editorState.value = _editorState.value.copy(
                configDraft = parsed.config,
                configDirty = false,
                configValidationMessage = warning,
                configValidationIsError = false
            )
            emitSnackbar(warning ?: "Saved config.json")
        }
    }

    fun resetConfigToDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val raw = ConfigStorage.resetConfigToDefault(ctx)
            val parsed = ServerConfigCodec.parseFromJsonString(raw)
            PostOfficeCoordinator.reloadConfigAndGameDbNow(ctx)
            _editorState.value = _editorState.value.copy(
                configDraft = parsed.config,
                configDirty = false,
                configValidationMessage = null,
                configValidationIsError = false
            )
            emitSnackbar("Reset config.json to defaults")
        }
    }

    fun importConfigFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (text.isNullOrBlank()) {
                    emitSnackbar("Selected file is empty")
                    return@launch
                }
                val parsed = ServerConfigCodec.parseFromJsonString(text)
                if (parsed.parseError != null) {
                    emitSnackbar("Import failed: ${parsed.parseError}")
                    return@launch
                }
                val normalized = ServerConfigCodec.toJsonString(parsed.config)
                ConfigStorage.writeConfigRaw(ctx, normalized)
                PostOfficeCoordinator.reloadConfigAndGameDbNow(ctx)
                _editorState.value = _editorState.value.copy(
                    configDraft = parsed.config,
                    configDirty = false,
                    configValidationMessage = null,
                    configValidationIsError = false
                )
                emitSnackbar("Imported config.json")
            } catch (e: Exception) {
                emitSnackbar("Import error: ${e.message}")
            }
        }
    }

    fun exportConfigToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val content = ServerConfigCodec.toJsonString(_editorState.value.configDraft)
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                emitSnackbar("Exported config.json")
            } catch (e: Exception) {
                emitSnackbar("Export error: ${e.message}")
            }
        }
    }

    fun addOrUpdateGameName(gameId: String, title: String) {
        val cleanId = gameId.trim().uppercase()
        val cleanTitle = title.trim()
        if (cleanId.isEmpty() || cleanTitle.isEmpty()) {
            emitSnackbar("Game ID and title are required")
            return
        }
        val currentDb = _editorState.value.gameDbDraft
        val updatedNames = TreeMap(currentDb.names)
        updatedNames[cleanId] = cleanTitle
        applyGameDbVisual(currentDb.copy(names = updatedNames))
    }

    fun removeGameName(gameId: String) {
        val currentDb = _editorState.value.gameDbDraft
        val updatedNames = TreeMap(currentDb.names)
        updatedNames.remove(gameId)
        applyGameDbVisual(currentDb.copy(names = updatedNames))
    }

    fun addOrUpdateCrosslink(fromId: String, toId: String) {
        val cleanFrom = fromId.trim().uppercase()
        val cleanTo = toId.trim().uppercase()
        if (cleanFrom.isEmpty() || cleanTo.isEmpty() || cleanFrom == cleanTo) {
            emitSnackbar("Enter two distinct Game IDs for a crosslink")
            return
        }
        val currentDb = _editorState.value.gameDbDraft
        val updatedLinks = TreeMap(currentDb.crosslinks)
        updatedLinks[cleanFrom] = cleanTo
        val candidate = currentDb.copy(crosslinks = updatedLinks)
        val validated = GameDbCodec.parseFromJsonString(GameDbCodec.toJsonString(candidate))
        if (!validated.isValid) {
            emitSnackbar(validated.errorMessage ?: "Recursive crosslink is not supported")
            return
        }
        applyGameDbVisual(candidate)
    }

    fun removeCrosslink(fromId: String) {
        val currentDb = _editorState.value.gameDbDraft
        val updatedLinks = TreeMap(currentDb.crosslinks)
        updatedLinks.remove(fromId)
        applyGameDbVisual(currentDb.copy(crosslinks = updatedLinks))
    }

    fun addCodQuirkGame(gameId: String) {
        val cleanId = gameId.trim().uppercase()
        if (cleanId.isEmpty()) {
            emitSnackbar("Game ID is required")
            return
        }
        val currentDb = _editorState.value.gameDbDraft
        val updatedQuirks = TreeSet(currentDb.codQuirkGames)
        updatedQuirks.add(cleanId)
        applyGameDbVisual(currentDb.copy(codQuirkGames = updatedQuirks))
    }

    fun removeCodQuirkGame(gameId: String) {
        val currentDb = _editorState.value.gameDbDraft
        val updatedQuirks = TreeSet(currentDb.codQuirkGames)
        updatedQuirks.remove(gameId)
        applyGameDbVisual(currentDb.copy(codQuirkGames = updatedQuirks))
    }

    private fun applyGameDbVisual(updated: GameDb) {
        _editorState.value = _editorState.value.copy(
            gameDbDraft = updated,
            gameDbDirty = true,
            gameDbValidationMessage = null,
            gameDbValidationIsError = false
        )
    }

    fun saveGameDb() {
        val current = _editorState.value
        val serialized = GameDbCodec.toJsonString(current.gameDbDraft)
        val parsed = GameDbCodec.parseFromJsonString(serialized)
        if (!parsed.isValid) {
            _editorState.value = current.copy(
                gameDbValidationMessage = parsed.errorMessage,
                gameDbValidationIsError = true
            )
            emitSnackbar("Cannot save game_db.json: ${parsed.errorMessage}")
            return
        }
        val normalized = GameDbCodec.toJsonString(parsed.gameDb)
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            ConfigStorage.writeGameDbRaw(ctx, normalized)
            PostOfficeCoordinator.reloadConfigAndGameDbNow(ctx)
            _editorState.value = _editorState.value.copy(
                gameDbDraft = parsed.gameDb,
                gameDbDirty = false,
                gameDbValidationMessage = null,
                gameDbValidationIsError = false
            )
            emitSnackbar("Saved game_db.json and reloaded live")
        }
    }

    fun resetGameDbToDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val raw = ConfigStorage.resetGameDbToDefault(ctx)
            val parsed = GameDbCodec.parseFromJsonString(raw)
            PostOfficeCoordinator.reloadConfigAndGameDbNow(ctx)
            _editorState.value = _editorState.value.copy(
                gameDbDraft = parsed.gameDb,
                gameDbDirty = false,
                gameDbValidationMessage = null,
                gameDbValidationIsError = false
            )
            emitSnackbar("Reset game_db.json to defaults")
        }
    }

    fun importGameDbFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (text.isNullOrBlank()) {
                    emitSnackbar("Selected file is empty")
                    return@launch
                }
                val parsed = GameDbCodec.parseFromJsonString(text)
                if (!parsed.isValid) {
                    emitSnackbar("Import failed: ${parsed.errorMessage}")
                    return@launch
                }
                val normalized = GameDbCodec.toJsonString(parsed.gameDb)
                ConfigStorage.writeGameDbRaw(ctx, normalized)
                PostOfficeCoordinator.reloadConfigAndGameDbNow(ctx)
                _editorState.value = _editorState.value.copy(
                    gameDbDraft = parsed.gameDb,
                    gameDbDirty = false,
                    gameDbValidationMessage = null,
                    gameDbValidationIsError = false
                )
                emitSnackbar("Imported game_db.json")
            } catch (e: Exception) {
                emitSnackbar("Import error: ${e.message}")
            }
        }
    }

    fun exportGameDbToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val content = GameDbCodec.toJsonString(_editorState.value.gameDbDraft)
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                emitSnackbar("Exported game_db.json")
            } catch (e: Exception) {
                emitSnackbar("Export error: ${e.message}")
            }
        }
    }

    fun setLogCategory(category: LogCategory?) {
        _logFilter.value = _logFilter.value.copy(selectedCategory = category)
    }

    fun setLogSearchQuery(query: String) {
        _logFilter.value = _logFilter.value.copy(searchQuery = query)
    }

    fun toggleLogAutoScroll() {
        _logFilter.value = _logFilter.value.copy(autoScroll = !_logFilter.value.autoScroll)
    }

    fun clearLogs() {
        ServerLogger.clear()
        emitSnackbar("Logs cleared")
    }

    fun exportLogsToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val text = ServerLogger.exportText()
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                emitSnackbar("Exported server logs")
            } catch (e: Exception) {
                emitSnackbar("Log export error: ${e.message}")
            }
        }
    }

    fun notifyCopied(label: String) {
        emitSnackbar("Copied $label")
    }

    private fun emitSnackbar(message: String) {
        _snackbarMessages.tryEmit(message)
    }
}
