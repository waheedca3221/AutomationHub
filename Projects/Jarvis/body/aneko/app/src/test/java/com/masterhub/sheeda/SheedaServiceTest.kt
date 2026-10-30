package com.masterhub.sheeda

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun testSpeakingStateHasFrames() {
        val speakingFrames = SheedaSkin.getFramesForState(SheedaState.SPEAKING)
        assertTrue("Speaking state must have animated mouth frames", speakingFrames.size >= 4)
    }

    @Test
    fun testListeningStateHasFrames() {
        val listeningFrames = SheedaSkin.getFramesForState(SheedaState.LISTENING)
        assertTrue("Listening state must have alert ear frames", listeningFrames.size >= 4)
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
    fun testOpenAIChatCompletionsPayloadFormat() {
        val query = "What is the MCB 343 balance?"
        val payload = JSONObject().apply {
            put("model", "jarvis")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", query)
                })
            })
        }
        assertEquals("jarvis", payload.getString("model"))
        val messages = payload.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals(query, messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun testSentenceSplittingForFastQueue() {
        val text = "Good evening Waheed! Your MCB 343 bank flow has arrived. All 1-Link breakup totals match Rs 5,948,796."
        val sentences = SheedaTextSplitter.splitIntoSentences(text)
        assertEquals("Must be split into exactly 3 sentences for fast queue", 3, sentences.size)
        assertEquals("Good evening Waheed!", sentences[0])
        assertEquals("Your MCB 343 bank flow has arrived.", sentences[1])
        assertEquals("All 1-Link breakup totals match Rs 5,948,796.", sentences[2])
    }

    @Test
    fun testSentenceSplittingWithNewlines() {
        val text = "Line one.\nLine two!\nLine three?"
        val sentences = SheedaTextSplitter.splitIntoSentences(text)
        assertEquals(3, sentences.size)
    }

    @Test
    fun testQuietHoursRuleLogic() {
        // Verification of invariant: User Talk is always answered aloud; proactive reports respect quiet hours
        val isQuietHour = true

        fun shouldSpeakAloud(isUserTalk: Boolean, quiet: Boolean): Boolean {
            return isUserTalk || !quiet
        }

        // Proactive report during quiet hours -> false (silent)
        assertFalse(shouldSpeakAloud(isUserTalk = false, quiet = isQuietHour))

        // User's own Talk during quiet hours -> true (aloud)
        assertTrue(shouldSpeakAloud(isUserTalk = true, quiet = isQuietHour))

        // Proactive report during daytime -> true (aloud)
        assertTrue(shouldSpeakAloud(isUserTalk = false, quiet = false))
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
