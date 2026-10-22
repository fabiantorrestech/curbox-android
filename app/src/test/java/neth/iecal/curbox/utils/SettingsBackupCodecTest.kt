package neth.iecal.curbox.utils

import com.google.gson.JsonParser
import neth.iecal.curbox.data.models.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class SettingsBackupCodecTest {
    @Test
    fun settingsAndCustomRulesRoundTrip() {
        val settings = Settings(
            blockedAppGroups = listOf(AppGroup(
                id = "apps", name = "Evening", selectedPackages = listOf("example.app"),
                isActive = true, config = AppGroupConfig(),
                warningScreenConfig = AppBlockerWarningScreenConfig(message = listOf("Take a break"))
            )),
            keywordBlockerConfig = KeywordBlocker(isActive = true, keywordGroups = listOf(KeywordGroup(
                id = "sites", selectedKeywords = listOf("example.com", "こんにちは"), config = ScheduledUsageConfig()
            ))),
            uiHiderConfig = UiHiderConfig(isActive = true, enabledPresetIds = listOf("ig_feed"), scripts = listOf(
                UiHiderScript(id = "custom", packageName = "example.app", source = "log(\"hello\")")
            )),
            manualFocusGroups = listOf(ManualFocusGroup(groupId = "focus", packages = hashSetOf("example.app"))),
            grayscaleGroups = listOf(GrayscaleGroup(groupId = "gray")),
            isAppUsageTrackingEnabled = false
        )
        val restored = roundTrip(settings)
        assertEquals(settings.blockedAppGroups, restored.blockedAppGroups)
        assertEquals(settings.keywordBlockerConfig, restored.keywordBlockerConfig)
        assertEquals(settings.uiHiderConfig, restored.uiHiderConfig)
        assertEquals(settings.manualFocusGroups, restored.manualFocusGroups)
        assertEquals(settings.grayscaleGroups, restored.grayscaleGroups)
        assertFalse(restored.isAppUsageTrackingEnabled)
    }

    @Test
    fun runtimeSessionsAndDeviceGrantsAreNotRestored() {
        val restored = roundTrip(Settings(
            activeManualFocusGroupId = "focus" to 123456L,
            activePomodoroState = PomodoroState(isActive = true),
            nextWebsiteRecheckTime = 123456L,
            serviceProtectionConfig = ServiceProtectionConfig(isEnabled = true, appBlockerLastAliveMs = 123L),
            antiUninstallConfig2 = AntiUninstallConfig(isEnabled = true, passwordHash = "hash", unlockRequestedAtMs = 123L),
            settingsChangeDelayConfig2 = SettingsChangeDelayConfig(isEnabled = true, delayMinutes = 60,
                pendingChanges = listOf(PendingSettingsChange(field = "APP_GROUPS", newValueJson = "[]")))
        ))
        assertEquals(null to 0L, restored.activeManualFocusGroupId)
        assertFalse(restored.activePomodoroState.isActive)
        assertEquals(0L, restored.nextWebsiteRecheckTime)
        assertEquals(0L, restored.serviceProtectionConfig.appBlockerLastAliveMs)
        assertTrue(restored.serviceProtectionConfig.isEnabled)
        assertFalse(restored.antiUninstallConfig2.isEnabled)
        assertEquals("hash", restored.antiUninstallConfig2.passwordHash)
        assertEquals(0L, restored.antiUninstallConfig2.unlockRequestedAtMs)
        assertTrue(restored.settingsChangeDelayConfig2.pendingChanges.isEmpty())
        assertEquals(60, restored.settingsChangeDelayConfig2.delayMinutes)
    }

    @Test
    fun temporaryPausesDoNotBecomePermanentDisables() {
        val restored = roundTrip(Settings(blockedAppGroups = listOf(
            AppGroup(id = "paused", temporarilyDisabledUntilMs = 100L),
            AppGroup(id = "manual", temporarilyDisabledUntilMs = -1L)
        )))
        assertTrue(restored.blockedAppGroups[0].isActive)
        assertFalse(restored.blockedAppGroups[1].isActive)
        assertTrue(restored.blockedAppGroups.all { it.temporarilyDisabledUntilMs == 0L })
    }

    @Test
    fun unsupportedFeaturesAreRemovedForTheTargetFlavor() {
        val settings = Settings(uiHiderConfig = UiHiderConfig(isActive = true),
            grayscaleGroups = listOf(GrayscaleGroup()))
        val restored = SettingsBackupCodec.forDevice(settings, supportsUiHider = false, supportsGrayscale = false)
        assertFalse(restored.uiHiderConfig.isActive)
        assertTrue(restored.grayscaleGroups.isEmpty())
        assertTrue(SettingsBackupCodec.forDevice(settings, true, true).uiHiderConfig.isActive)
    }

    @Test
    fun defaultsRemainCompatibleWhenFieldsAreMissing() {
        val restored = decode("""{"format":"curbox-settings-backup","version":1,"settings":{}}""")
        assertTrue(restored.blockedAppGroups.isEmpty())
        assertTrue(restored.isAppUsageTrackingEnabled)
    }

    @Test
    fun invalidDocumentsAreRejectedBeforeApplyingSettings() {
        val invalid = listOf(
            "{}", "not json", "[]",
            """{"format":"curbox-settings-backup","version":2,"settings":{}}""",
            document(""""blockedAppGroups":null"""),
            document(""""blockedAppGroups":[null]"""),
            document(""""manualFocusGroups":[{"blockMode":"UNKNOWN"}]"""),
            document(""""reelBlockerConfig":{"config":{"usage":{"dailyLimits":[1]}}}"""),
            document(""""blockedAppGroups":[{"warningScreenConfig":{"message":[]}}]"""),
            document(""""grayscaleGroups":[{"timeConfig":{"everydayIntervals":[{"startHour":99}]}}]""")
        )
        invalid.forEach { json ->
            try {
                decode(json)
                fail("Accepted invalid backup: $json")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun excessivelyNestedAndOversizedInputsAreRejected() {
        try {
            decode("[".repeat(1000) + "]".repeat(1000))
            fail("Accepted excessive nesting")
        } catch (_: IllegalArgumentException) {
        }
        val input = object : InputStream() {
            var remaining = SettingsBackupCodec.MAX_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 32 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                buffer.fill(32, offset, offset + count)
                remaining -= count
                return count
            }
        }
        try {
            SettingsBackupCodec.decode(input)
            fail("Accepted oversized backup")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun backupContainsOnlyTheVersionedSettingsPayload() {
        val root = JsonParser.parseString(String(SettingsBackupCodec.encode(Settings()), Charsets.UTF_8)).asJsonObject
        assertEquals(setOf("format", "version", "settings"), root.keySet())
    }

    private fun document(fields: String) =
        """{"format":"curbox-settings-backup","version":1,"settings":{$fields}}"""
    private fun decode(json: String) = SettingsBackupCodec.decode(json.byteInputStream())
    private fun roundTrip(settings: Settings) = SettingsBackupCodec.decode(SettingsBackupCodec.encode(settings).inputStream())
}
