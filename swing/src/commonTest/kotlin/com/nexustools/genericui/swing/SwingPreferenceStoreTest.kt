package com.nexustools.genericui.swing

import com.nexustools.genericui.component.system.PreferenceStore
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.BinaryPlist
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingPreferenceStoreTest {

    private data class AudioRoutingConfig(
        val outputDevice: String,
        val sampleRateHz: Int,
        val bufferFrames: Int,
        val exclusiveMode: Boolean,
        val channelGains: List<Double>
    )

    @Test
    fun `SwingPreferenceStoreImpl writes per-key binary files and reconstructs arbitrary Kotlin data classes`() {
        val tempDir = Files.createTempDirectory("genericui_swing_prefs_test").toFile()
        try {
            val impl = SwingPreferenceStoreImpl(appName = "EpisodeCaster", customDirectory = tempDir)
            val store = PreferenceStore(appName = "EpisodeCaster")

            val config = AudioRoutingConfig(
                outputDevice = "WASAPI Low-Latency Output",
                sampleRateHz = 48000,
                bufferFrames = 256,
                exclusiveMode = true,
                channelGains = listOf(1.0, 1.0, 0.85, 0.85)
            )

            impl.writeElement("audio_routing", com.nexustools.genericui.component.system.PreferenceObjectCodec.toElement(config))
            val keyFile = impl.fileForKey("audio_routing")
            assertTrue(keyFile.exists(), "Per-key preference file should exist on disk at ${keyFile.absolutePath}")
            if (impl.isMacOsHost) {
                assertTrue(keyFile.name.endsWith(".plist"))
                assertTrue(BinaryPlist.isBinaryPlist(keyFile.readBytes()))
            } else {
                assertTrue(keyFile.name.endsWith(".bson"))
                assertEquals(BinaryJson.TYPE_OBJECT, keyFile.readBytes()[0])
            }

            // Re-open from the same directory to verify disk persistence and reflection reconstruction
            val reopenedImpl = SwingPreferenceStoreImpl(appName = "EpisodeCaster", customDirectory = tempDir)
            val elem = reopenedImpl.readElement("audio_routing")
            val restored = com.nexustools.genericui.component.system.PreferenceObjectCodec.fromElement(
                elem,
                AudioRoutingConfig::class
            )
            assertEquals(config, restored)
            assertTrue(reopenedImpl.keys().contains("audio_routing"))

            reopenedImpl.remove("audio_routing")
            assertFalse(keyFile.exists())
            reopenedImpl.dispose()
            impl.dispose()
            store.dispose()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
