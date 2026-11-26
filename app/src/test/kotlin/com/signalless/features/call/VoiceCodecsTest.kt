package com.signalless.app.features.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCodecsTest {
    private val aac = CallCodec.AAC_LC.mask
    private val both = CallCodec.OPUS.mask or aac

    @Test
    fun `opus is chosen only when both sides have it`() {
        assertEquals(CallCodec.OPUS, VoiceCodecs.negotiate(both, both))
        assertEquals(CallCodec.AAC_LC, VoiceCodecs.negotiate(both, aac))
        assertEquals(CallCodec.AAC_LC, VoiceCodecs.negotiate(aac, both))
        // Unknown future bits are ignored.
        assertEquals(CallCodec.AAC_LC, VoiceCodecs.negotiate(both, aac or 0x80))
        assertNull(VoiceCodecs.negotiate(both, 0x80))
    }
}
