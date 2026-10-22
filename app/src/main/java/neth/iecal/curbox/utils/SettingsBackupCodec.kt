package neth.iecal.curbox.utils

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.PomodoroState
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.UiHiderConfig
import neth.iecal.curbox.data.models.upgradeLegacyAppGroupConfigs
import neth.iecal.curbox.data.models.upgradeLegacyKeywordGroupConfigs
import neth.iecal.curbox.data.models.upgradeLegacyConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Portable configuration only. No database files, account grants, or device permissions. */
object SettingsBackupCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val FORMAT = "curbox-settings-backup"
    private const val VERSION = 1
    private val gson = Gson()

    fun encode(settings: Settings): ByteArray {
        val portable = portableSettings(settings)
        val root = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            add("settings", gson.toJsonTree(portable))
        }
        return gson.toJson(root).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "Backup is too large" }
        }
    }

    fun decode(input: InputStream): Settings {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= MAX_BYTES - bytes.size()) { "Backup is too large" }
            bytes.write(buffer, 0, count)
        }
        val json = bytes.toString(Charsets.UTF_8.name())
        validateDepth(json)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("format")?.asString == FORMAT) { "Not a Curbox settings backup" }
        require(root.get("version")?.toString() == VERSION.toString()) { "Unsupported backup version" }
        val payload = root.getAsJsonObject("settings") ?: error("Missing settings")
        validateJson(payload)
        val settings = gson.fromJson(payload, Settings::class.java)
        validateSettings(settings)
        return portableSettings(settings).also(::validateSettings)
    }

    fun forDevice(settings: Settings, supportsUiHider: Boolean, supportsGrayscale: Boolean): Settings {
        val portable = portableSettings(settings)
        return portable.copy(
            uiHiderConfig = if (supportsUiHider) portable.uiHiderConfig else UiHiderConfig(),
            grayscaleGroups = if (supportsGrayscale) portable.grayscaleGroups else emptyList()
        )
    }

    private fun portableSettings(settings: Settings): Settings = settings.copy(
        blockedAppGroups = settings.blockedAppGroups.upgradeLegacyAppGroupConfigs().map {
            it.copy(isActive = it.isActive || it.temporarilyDisabledUntilMs > 0L, temporarilyDisabledUntilMs = 0L)
        },
        keywordBlockerConfig = settings.keywordBlockerConfig.upgradeLegacyKeywordGroupConfigs().let { config ->
            config.copy(keywordGroups = config.keywordGroups.map {
                it.copy(isActive = it.isActive || it.temporarilyDisabledUntilMs > 0L, temporarilyDisabledUntilMs = 0L)
            })
        },
        reelBlockerConfig = settings.reelBlockerConfig.upgradeLegacyConfig().let {
            it.copy(isActive = it.isActive || it.temporarilyDisabledUntilMs > 0L, temporarilyDisabledUntilMs = 0L)
        },
        activeManualFocusGroupId = Pair(null, 0L),
        activePomodoroState = PomodoroState(),
        nextWebsiteRecheckTime = 0L,
        serviceProtectionConfig = settings.serviceProtectionConfig.copy(appBlockerLastAliveMs = 0L),
        // Device admin must be granted on this installation before protection can be enabled.
        antiUninstallConfig2 = settings.antiUninstallConfig2.copy(isEnabled = false, unlockRequestedAtMs = 0L),
        settingsChangeDelayConfig2 = settings.settingsChangeDelayConfig2.copy(pendingChanges = emptyList())
    )

    // Bound nesting before handing the document to Gson's parser.
    private fun validateDepth(json: String) {
        var depth = 0
        var inString = false
        var escaped = false
        json.forEach { char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else when (char) {
                '"' -> inString = true
                '{', '[' -> { depth++; require(depth <= 64) { "Backup is too deeply nested" } }
                '}', ']' -> depth--
            }
        }
    }

    private fun validateJson(value: JsonElement, depth: Int = 0) {
        require(depth <= 64) { "Backup is too deeply nested" }
        when {
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (name, child) ->
                if (child.isJsonNull) {
                    require(name in setOf("config", "linkedTimeGroupId", "first")) { "Null settings value" }
                } else validateJson(child, depth + 1)
            }
            value.isJsonArray -> value.asJsonArray.forEach {
                require(!it.isJsonNull) { "Null list entry" }
                validateJson(it, depth + 1)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateSettings(settings: Settings) {
        fun schedule(config: AppTimeConfig) {
            require(config.dailyIntervals.keys.all { it in 0..6 })
            (config.everydayIntervals + config.dailyIntervals.values.flatten()).forEach {
                require(it.startHour in 0..23 && it.startMinute in 0..59)
                require(it.endHour in 0..24 && it.endMinute in 0..59)
                require(it.endHour != 24 || it.endMinute == 0)
            }
        }
        fun limits(uniform: Long, daily: LongArray) {
            require(uniform in 0..Long.MAX_VALUE / 60_000L)
            require(daily.size == 7 && daily.all { it in 0..Long.MAX_VALUE / 60_000L })
        }
        fun warning(config: AppBlockerWarningScreenConfig) {
            require(config.message.isNotEmpty())
            require(config.timeInterval > 0 && config.proceedDelayInSecs >= 0)
            require(config.allowedProceeds >= 0 && config.proceedsTimeWindowMn in 1..Long.MAX_VALUE / 60_000L)
            require(config.qrKeys.values.all { it == -1L || it > 0L })
            require(config.nfcKeys.values.all { it == -1L || it > 0L })
        }
        settings.blockedAppGroups.forEach {
            requireNotNull(it.blockingType)
            it.config?.let { config -> schedule(config.schedule); limits(config.usage.uniformLimit, config.usage.dailyLimits) }
            warning(it.warningScreenConfig)
        }
        settings.keywordBlockerConfig.keywordGroups.forEach {
            requireNotNull(it.blockingType)
            it.config?.let { config -> schedule(config.schedule); limits(config.usage.uniformLimit, config.usage.dailyLimits) }
            warning(it.warningScreenConfig)
        }
        settings.reelBlockerConfig.let {
            requireNotNull(it.blockingType)
            warning(it.warningScreenConfig)
            it.config?.let { config ->
                schedule(AppTimeConfig(config.schedule.isEveryday, config.schedule.everydayIntervals, config.schedule.dailyIntervals))
                limits(config.usage.uniformLimit, config.usage.dailyLimits)
                require(config.reelCount.uniformLimit >= 0)
                require(config.reelCount.dailyLimits.size == 7 && config.reelCount.dailyLimits.all { count -> count >= 0 })
            }
        }
        settings.manualFocusGroups.forEach { requireNotNull(it.blockMode) }
        settings.autoDndGroups.forEach { schedule(it.timeConfig) }
        settings.grayscaleGroups.forEach { schedule(it.timeConfig) }
        requireNotNull(settings.antiUninstallConfig2.mode)
        require(settings.settingsChangeDelayConfig2.delayMinutes in 0..SettingsChangeDelayConfig.MAX_DELAY_MINUTES)
        require(settings.mindfulMessageConfig.textSize.isFinite() && settings.mindfulMessageConfig.textSize > 0f)
        require(settings.reelCounterOverlayConfig.textSize.isFinite() && settings.reelCounterOverlayConfig.textSize > 0f)
        require(settings.mindfulMessageConfig.positionX.isFinite() && settings.mindfulMessageConfig.positionY.isFinite())
        require(settings.reelCounterOverlayConfig.positionX.isFinite() && settings.reelCounterOverlayConfig.positionY.isFinite())
    }
}
