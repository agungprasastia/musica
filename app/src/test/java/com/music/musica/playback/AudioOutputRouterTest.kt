package com.music.musica.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputRouterTest {

    @Test
    fun classifyFiltersOutScoAndEarpieceAndMapsBuiltInSpeaker() {
        val raw = listOf(
            RawOutputDescriptor(id = 1, productName = "Pixel", deviceType = AudioOutputRouter.TYPE_BUILTIN_EARPIECE),
            RawOutputDescriptor(id = 2, productName = "Pixel", deviceType = AudioOutputRouter.TYPE_BUILTIN_SPEAKER),
            RawOutputDescriptor(id = 3, productName = "AirPods Pro", deviceType = AudioOutputRouter.TYPE_BLUETOOTH_SCO),
            RawOutputDescriptor(id = 4, productName = "AirPods Pro", deviceType = AudioOutputRouter.TYPE_BLUETOOTH_A2DP),
        )

        val result = AudioOutputRouter.classifyAndDeduplicate(raw, phoneLabel = "This phone")

        assertEquals(2, result.size)
        // Connected external route sorts ahead of built-in speaker
        assertEquals("AirPods Pro", result[0].name)
        assertEquals(AudioOutputKind.BUDS, result[0].kind)
        assertEquals(4, result[0].id)

        assertEquals("This phone", result[1].name)
        assertEquals(AudioOutputKind.PHONE, result[1].kind)
        assertTrue(result[1].isBuiltIn)
    }

    @Test
    fun deduplicatesBluetoothByNameAndKindPreferringA2dpOverBle() {
        val raw = listOf(
            RawOutputDescriptor(id = 10, productName = "Galaxy Buds2 Pro", deviceType = AudioOutputRouter.TYPE_BLE_HEADSET),
            RawOutputDescriptor(id = 11, productName = "Galaxy Buds2 Pro", deviceType = AudioOutputRouter.TYPE_BLUETOOTH_A2DP),
            RawOutputDescriptor(id = 12, productName = "JBL Flip 6", deviceType = AudioOutputRouter.TYPE_BLE_SPEAKER),
            RawOutputDescriptor(id = 13, productName = "JBL Flip 6", deviceType = AudioOutputRouter.TYPE_BLUETOOTH_A2DP),
        )

        val result = AudioOutputRouter.classifyAndDeduplicate(raw, phoneLabel = "This phone")

        assertEquals(2, result.size)
        val buds = result.first { it.name == "Galaxy Buds2 Pro" }
        assertEquals(11, buds.id)
        assertEquals(AudioOutputKind.BUDS, buds.kind)

        val speaker = result.first { it.name == "JBL Flip 6" }
        assertEquals(13, speaker.id)
        assertEquals(AudioOutputKind.SPEAKER, speaker.kind)
    }

    @Test
    fun infersCarHeadphonesUsbWiredAndHdmiAndResolvesActiveDevice() {
        val raw = listOf(
            RawOutputDescriptor(id = 1, productName = "Phone", deviceType = AudioOutputRouter.TYPE_BUILTIN_SPEAKER),
            RawOutputDescriptor(id = 2, productName = "Toyota Multimedia", deviceType = AudioOutputRouter.TYPE_BLUETOOTH_A2DP),
            RawOutputDescriptor(id = 3, productName = "WH-1000XM5", deviceType = AudioOutputRouter.TYPE_BLUETOOTH_A2DP),
            RawOutputDescriptor(id = 4, productName = "USB-C DAC", deviceType = AudioOutputRouter.TYPE_USB_HEADSET),
            RawOutputDescriptor(id = 5, productName = "HDMI TV", deviceType = AudioOutputRouter.TYPE_HDMI_EARC),
            RawOutputDescriptor(id = 6, productName = "Wired", deviceType = AudioOutputRouter.TYPE_WIRED_HEADPHONES),
        )

        val result = AudioOutputRouter.classifyAndDeduplicate(raw, phoneLabel = "Perangkat ini")

        assertEquals(AudioOutputKind.CAR, result.first { it.id == 2 }.kind)
        assertEquals(AudioOutputKind.HEADPHONES, result.first { it.id == 3 }.kind)
        assertEquals(AudioOutputKind.USB, result.first { it.id == 4 }.kind)
        assertEquals(AudioOutputKind.HDMI, result.first { it.id == 5 }.kind)
        assertEquals(AudioOutputKind.WIRED, result.first { it.id == 6 }.kind)

        // Explicit preferred id wins over default external preference
        assertEquals(1, AudioOutputRouter.resolveActiveDevice(result, preferredDeviceId = 1)?.id)
        // Missing/stale preferred id falls back to highest-priority external route
        assertEquals(2, AudioOutputRouter.resolveActiveDevice(result, preferredDeviceId = 999)?.id)
        // Empty list returns null cleanly
        assertNull(AudioOutputRouter.resolveActiveDevice(emptyList(), preferredDeviceId = null))
    }
}
