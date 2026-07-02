package com.claudeassist.overlay.core

import com.claudeassist.overlay.core.driver.AnswerTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerTrackerTest {

    @Test
    fun finishesAfterStableSamples() {
        val tracker = AnswerTracker(stableSamplesToFinish = 3, maxDurationMs = 60_000)
        assertTrue(tracker.onSample("Hel", 0) is AnswerTracker.State.Streaming)
        assertTrue(tracker.onSample("Hello", 100) is AnswerTracker.State.Streaming)
        // Now stable for 3 identical samples.
        assertTrue(tracker.onSample("Hello", 200) is AnswerTracker.State.Streaming)
        assertTrue(tracker.onSample("Hello", 300) is AnswerTracker.State.Streaming)
        val done = tracker.onSample("Hello", 400)
        assertTrue(done is AnswerTracker.State.Finished)
        assertEquals("Hello", (done as AnswerTracker.State.Finished).fullText)
        assertEquals(false, done.timedOut)
    }

    @Test
    fun reportsChangedOnlyWhenTextGrows() {
        val tracker = AnswerTracker(stableSamplesToFinish = 5, maxDurationMs = 60_000)
        val first = tracker.onSample("a", 0) as AnswerTracker.State.Streaming
        assertTrue(first.changed)
        val same = tracker.onSample("a", 50) as AnswerTracker.State.Streaming
        assertTrue(!same.changed)
    }

    @Test
    fun timesOutWithPartialAnswer() {
        val tracker = AnswerTracker(stableSamplesToFinish = 100, maxDurationMs = 1_000)
        tracker.onSample("partial", 0)
        val done = tracker.onSample("partial answer", 1_500)
        assertTrue(done is AnswerTracker.State.Finished)
        done as AnswerTracker.State.Finished
        assertTrue(done.timedOut)
        assertEquals("partial answer", done.fullText)
    }

    @Test
    fun ignoresTransientPrefixShrink() {
        val tracker = AnswerTracker(stableSamplesToFinish = 2, maxDurationMs = 60_000)
        tracker.onSample("Hello world", 0)
        // A re-render momentarily returns a prefix; must not lose the longer text.
        tracker.onSample("Hello", 100)
        val done = tracker.onSample("Hello world", 200)
        // "Hello world" seen again == stable count 1, need one more.
        val done2 = tracker.onSample("Hello world", 300)
        assertTrue(done2 is AnswerTracker.State.Finished)
        assertEquals("Hello world", (done2 as AnswerTracker.State.Finished).fullText)
    }
}
