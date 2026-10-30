package com.masterhub.sheeda

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheedaServiceTest {

    @Test
    fun testAllStatesMapToValidFrames() {
        for (state in SheedaState.values()) {
            val frames = SheedaSkin.getFramesForState(state)
            assertNotNull("Frames array for $state should not be null", frames)
            assertTrue("Frames array for $state must contain at least 1 frame", frames.isNotEmpty())
        }
    }

    @Test
    fun testIdleStateHasSixFrames() {
        val idleFrames = SheedaSkin.getFramesForState(SheedaState.IDLE)
        assertEquals("Idle state must have 6 frames for breathing/blink cycle", 6, idleFrames.size)
    }

    @Test
    fun testSleepStateHasFourFrames() {
        val sleepFrames = SheedaSkin.getFramesForState(SheedaState.SLEEP)
        assertEquals("Sleep state must have 4 frames for slumber breath cycle", 4, sleepFrames.size)
    }

    @Test
    fun testVoiceTurnPayloadStructure() {
        val bodyJson = JSONObject().apply {
            put("text", "Check daily bank flow status")
            put("source", "phone_aneko_overlay")
            put("persona", "Sheeda")
        }
        assertEquals("Check daily bank flow status", bodyJson.getString("text"))
        assertEquals("phone_aneko_overlay", bodyJson.getString("source"))
        assertEquals("Sheeda", bodyJson.getString("persona"))
    }

    @Test
    fun testCircadianResponseParsing() {
        val mockCircadianJson = JSONObject("""
            {
                "phase": "Office Focus & Bank Statement Flow",
                "time_slot": "10:00-13:00",
                "predicted_action": "Monitor MCB 343 & 8640 statement downloads, verify 1-Link breakup",
                "goal": "Wealth"
            }
        """.trimIndent())

        val phase = mockCircadianJson.optString("phase")
        val action = mockCircadianJson.optString("predicted_action")
        val formatted = "[$phase]\n$action"

        assertTrue(formatted.contains("Office Focus"))
        assertTrue(formatted.contains("MCB 343"))
    }
}
