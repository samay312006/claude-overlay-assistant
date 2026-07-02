package com.claudeassist.overlay.core.driver

/** Result of one readiness probe (app installed, service on, chat reachable…). */
data class CheckResult(
    val name: String,
    val ok: Boolean,
    val detail: String = "",
)

data class DriverCheckReport(val results: List<CheckResult>) {
    val ok: Boolean get() = results.all { it.ok }
    val firstFailure: CheckResult? get() = results.firstOrNull { !it.ok }
}
