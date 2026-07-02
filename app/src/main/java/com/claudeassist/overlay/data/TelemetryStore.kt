package com.claudeassist.overlay.data

import com.claudeassist.overlay.core.driver.DriverStep
import com.claudeassist.overlay.core.driver.RunLog
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists one row per automation run — the seed corpus for the learning loop. */
class TelemetryStore(private val dao: TelemetryDao) {

    private val json = Json { ignoreUnknownKeys = true }
    private val stepsSerializer = ListSerializer(DriverStep.serializer())

    suspend fun record(run: RunLog) {
        dao.insert(
            TelemetryRunEntity(
                brainId = run.brainId,
                startedAtMs = run.startedAtMs,
                totalMs = run.totalMs,
                success = run.success,
                failureReason = run.failureReason,
                stepsJson = json.encodeToString(stepsSerializer, run.steps),
            )
        )
    }

    fun observeRecent(): Flow<List<TelemetryRunEntity>> = dao.observeRecent()

    fun decodeSteps(entity: TelemetryRunEntity): List<DriverStep> = try {
        json.decodeFromString(stepsSerializer, entity.stepsJson)
    } catch (t: Throwable) {
        emptyList()
    }
}
