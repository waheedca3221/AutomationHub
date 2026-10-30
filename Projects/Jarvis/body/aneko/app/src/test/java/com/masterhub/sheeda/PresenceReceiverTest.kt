package com.masterhub.sheeda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceReceiverTest {

    @Test
    fun testReminderQueryDetection() {
        assertTrue(SheedaOverlayService.isReminderQuery("remind me at 5 to call the bank"))
        assertTrue(SheedaOverlayService.isReminderQuery("in 20 minutes check the upload"))
        assertTrue(SheedaOverlayService.isReminderQuery("every weekday at 9:30 bank flow"))
        assertTrue(SheedaOverlayService.isReminderQuery("tomorrow morning check fees"))
        assertTrue(SheedaOverlayService.isReminderQuery("snooze 10 minutes"))
        assertTrue(SheedaOverlayService.isReminderQuery("cancel the bank reminder"))
        assertTrue(SheedaOverlayService.isReminderQuery("what are my reminders"))
        assertTrue(SheedaOverlayService.isReminderQuery("list reminders"))

        assertFalse(SheedaOverlayService.isReminderQuery("how is the weather in Lahore?"))
        assertFalse(SheedaOverlayService.isReminderQuery("who are you?"))
        assertFalse(SheedaOverlayService.isReminderQuery("explain ACCA AFM chapter 12"))
    }

    @Test
    fun testPresenceEventDataClass() {
        val evt = PresenceEvent(
            id = "rem_45",
            type = "speak",
            text = "Gentle reminder: Call bank manager",
            audio = true,
            volume = 0.8f,
            category = "reminder",
            timestamp = "2026-10-30T18:00:00Z"
        )
        assertEquals("rem_45", evt.id)
        assertEquals("speak", evt.type)
        assertEquals("Gentle reminder: Call bank manager", evt.text)
        assertTrue(evt.audio)
        assertEquals(0.8f, evt.volume, 0.001f)
        assertEquals("reminder", evt.category)
    }

    @Test
    fun testTextSplitterSentences() {
        val text = "Good morning Waheed. Your bank statement is ready! Please review."
        val sentences = SheedaTextSplitter.splitIntoSentences(text)
        assertEquals(3, sentences.size)
        assertEquals("Good morning Waheed.", sentences[0])
        assertEquals("Your bank statement is ready!", sentences[1])
        assertEquals("Please review.", sentences[2])
    }
}
