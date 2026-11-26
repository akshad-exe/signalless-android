package com.signalless.relay

import com.signalless.app.util.AppConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvertisementCodecTest {
    @Test
    fun `decodes eight byte peer id as lowercase hex`() {
        val serviceData = byteArrayOf(
            0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(),
            0xE5.toByte(), 0xF6.toByte(), 0x07, 0x18,
        )

        assertEquals("a1b2c3d4e5f60718", AdvertisementCodec.peerIdFromServiceData(serviceData))
    }

    @Test
    fun `ignores trailing service data bytes`() {
        val serviceData = ByteArray(12) { 0x11 }

        assertEquals("1111111111111111", AdvertisementCodec.peerIdFromServiceData(serviceData))
    }

    @Test
    fun `rejects missing service data`() {
        assertNull(AdvertisementCodec.peerIdFromServiceData(null))
    }

    @Test
    fun `rejects service data shorter than eight bytes`() {
        assertNull(AdvertisementCodec.peerIdFromServiceData(ByteArray(7) { 0x01 }))
    }

    @Test
    fun `encodes peer id into eight bytes`() {
        val expected = byteArrayOf(
            0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(),
            0xE5.toByte(), 0xF6.toByte(), 0x07, 0x18,
        )

        assertArrayEquals(expected, AdvertisementCodec.serviceDataForPeerId("a1b2c3d4e5f60718"))
    }

    @Test
    fun `round trips uppercase peer id`() {
        val encoded = AdvertisementCodec.serviceDataForPeerId("A1B2C3D4E5F60718")

        assertEquals("a1b2c3d4e5f60718", AdvertisementCodec.peerIdFromServiceData(encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects peer id with the wrong length`() {
        AdvertisementCodec.serviceDataForPeerId("a1b2c3d4")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non hex peer id`() {
        AdvertisementCodec.serviceDataForPeerId("a1b2c3d4e5f6071z")
    }

    @Test
    fun `service uuid matches the protocol constant`() {
        assertEquals(
            AppConstants.Mesh.Gatt.SERVICE_UUID.toString().lowercase(),
            AdvertisementCodec.SERVICE_UUID.toString(),
        )
    }
}
