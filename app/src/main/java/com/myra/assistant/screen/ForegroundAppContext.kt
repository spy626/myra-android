package com.myra.assistant.screen

/**
 * Ephemeral action-time identity sourced from AccessibilityService.rootInActiveWindow.
 * It is never persisted as long-term memory.
 */
data class ForegroundAppContext(
    val packageName: String,
    val appName: String? = null,
    val windowId: Int,
    val generation: Long,
    val observedAt: Long,
    val rootAvailable: Boolean = true
)

data class ForegroundActionScope(
    val expectedPackage: String,
    val expectedWindowId: Int,
    val expectedGeneration: Long
)

object ForegroundActionPolicy {
    fun scope(context: ForegroundAppContext?): ForegroundActionScope? =
        context?.takeIf { it.rootAvailable && it.packageName.isNotBlank() }?.let {
            ForegroundActionScope(it.packageName, it.windowId, it.generation)
        }

    fun canExecute(scope: ForegroundActionScope, current: ForegroundAppContext?): Boolean =
        current != null &&
            current.rootAvailable &&
            current.packageName == scope.expectedPackage &&
            current.windowId == scope.expectedWindowId &&
            current.generation == scope.expectedGeneration

    fun destinationPackage(
        currentPackage: String?,
        explicitlyRequestedPackage: String?
    ): String? = explicitlyRequestedPackage?.takeIf(String::isNotBlank) ?: currentPackage

    fun mayLaunchDifferentApp(explicitlyRequestedPackage: String?): Boolean =
        !explicitlyRequestedPackage.isNullOrBlank()
}
