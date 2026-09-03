package com.myra.assistant.screen

enum class PendingVisualPermissionState { CREATED, REQUESTING, APPROVED }

data class PendingVisualAction(
    val command: YouTubeSemanticCommand,
    val expectedPackage: String,
    val turnId: Long,
    val windowId: Int?,
    val contextGeneration: Long,
    val createdAt: Long,
    val permissionState: PendingVisualPermissionState = PendingVisualPermissionState.CREATED
)

/** Process-local task state. It is never written to Room or preferences. */
object PendingVisualActionStore {
    const val TIMEOUT_MS = 20_000L
    private var pending: PendingVisualAction? = null

    @Synchronized fun snapshot(now: Long = android.os.SystemClock.elapsedRealtime()): PendingVisualAction? {
        val value = pending ?: return null
        if (now - value.createdAt > TIMEOUT_MS) {
            pending = null
            return null
        }
        return value
    }

    @Synchronized fun replace(action: PendingVisualAction): PendingVisualAction? {
        val old = pending
        pending = action
        return old
    }

    @Synchronized fun markPermissionRequesting(): PendingVisualAction? {
        val value = pending ?: return null
        return value.copy(permissionState = PendingVisualPermissionState.REQUESTING).also { pending = it }
    }

    @Synchronized fun markPermissionApproved(): PendingVisualAction? {
        val value = pending ?: return null
        return value.copy(permissionState = PendingVisualPermissionState.APPROVED).also { pending = it }
    }

    @Synchronized fun takeForResume(packageName: String, now: Long = android.os.SystemClock.elapsedRealtime()): PendingVisualAction? {
        val value = snapshot(now) ?: return null
        if (value.permissionState != PendingVisualPermissionState.APPROVED ||
            !value.expectedPackage.equals(packageName, true)
        ) return null
        pending = null
        return value
    }

    /** The LYRA Activity is an expected temporary owner while Android consent is visible. */
    @Synchronized fun cancelForIncompatiblePackage(packageName: String): PendingVisualAction? {
        val value = pending ?: return null
        if (packageName.equals(value.expectedPackage, true) ||
            packageName.equals("com.myra.assistant", true)
        ) return null
        pending = null
        return value
    }

    @Synchronized fun clear(): PendingVisualAction? = pending.also { pending = null }
}

enum class VisualFallbackDecision { COMPLETE, USE_ACTIVE_VISION, REQUEST_PERMISSION }

object AccessibilityFirstVisualPolicy {
    fun decide(accessibilityAccepted: Boolean, projectionActive: Boolean): VisualFallbackDecision = when {
        accessibilityAccepted -> VisualFallbackDecision.COMPLETE
        projectionActive -> VisualFallbackDecision.USE_ACTIVE_VISION
        else -> VisualFallbackDecision.REQUEST_PERMISSION
    }
}
