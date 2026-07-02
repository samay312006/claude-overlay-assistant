package com.claudeassist.overlay.core

import com.claudeassist.overlay.core.driver.DriverStep
import com.claudeassist.overlay.core.driver.RunLogBuilder
import com.claudeassist.overlay.core.driver.StepOutcome
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunLogTest {

    @Test
    fun buildsOrderedStepLogWithTiming() {
        var clock = 0L
        val builder = RunLogBuilder(brainId = "claude-app", startedAtMs = 0L)
        builder.timed("launch", { clock }) { clock += 100 }
        builder.timed("type", { clock }) { clock += 50 }
        val log = builder.build(endedAtMs = clock, success = true)

        assertEquals(2, log.steps.size)
        assertEquals("launch", log.steps[0].name)
        assertEquals(100L, log.steps[0].durationMs)
        assertEquals(StepOutcome.OK, log.steps[1].outcome)
        assertEquals(150L, log.totalMs)
        assertTrue(log.success)
    }

    @Test
    fun recordsFailedStepAndRethrows() {
        var clock = 0L
        val builder = RunLogBuilder(brainId = "claude-app", startedAtMs = 0L)
        assertFailsWith<IllegalStateException> {
            builder.timed("send", { clock }) {
                clock += 10
                error("boom")
            }
        }
        val log = builder.build(endedAtMs = clock, success = false, failureReason = "boom")
        assertEquals(StepOutcome.FAILED, log.steps.single().outcome)
        assertEquals("boom", log.failureReason)
    }

    @Test
    fun stepsRoundTripThroughJson() {
        val builder = RunLogBuilder("claude-app", 0L)
        builder.step("launch", StepOutcome.OK, 100)
        val log = builder.build(100L, success = true)
        val json = Json.encodeToString(ListSerializer(DriverStep.serializer()), log.steps)
        val decoded = Json.decodeFromString(ListSerializer(DriverStep.serializer()), json)
        assertEquals(log.steps, decoded)
    }
}
