package com.hmgcarapp.fuelwise

import com.hmgcarapp.fuelwise.ui.voiceLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLabelTest {
    @Test fun noDuplicatePrefix() { assertEquals("음성 안내만", voiceLabel("음성 안내만")) }
    @Test fun addsPrefix() { assertEquals("음성 수락", voiceLabel("수락")) }
}
