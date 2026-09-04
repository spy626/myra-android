package com.myra.assistant.agent

/** Suppresses publication churn while retaining immediate structural/UI changes. */
class ActivityObservationCoalescer {
    @Volatile private var signature: Int? = null

    @Synchronized fun shouldPublish(context: CurrentActivityContext, force: Boolean = false): Boolean {
        val next = signatureOf(context)
        if (force || signature != next) {
            signature = next
            return true
        }
        return false
    }

    @Synchronized fun reset() { signature = null }

    private fun signatureOf(context: CurrentActivityContext): Int {
        var result = 17
        result = 31 * result + context.packageName.hashCode()
        result = 31 * result + context.windowId
        result = 31 * result + context.screenType.hashCode()
        context.visibleElements.forEach {
            result = 31 * result + it.role.hashCode()
            result = 31 * result + it.label.lowercase().trim().hashCode()
            result = 31 * result + listOf(it.left, it.top, it.right, it.bottom).hashCode()
            result = 31 * result + it.actionable.hashCode()
            result = 31 * result + it.selected.hashCode()
        }
        return result
    }
}
