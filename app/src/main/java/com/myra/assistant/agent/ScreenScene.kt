package com.myra.assistant.agent

enum class ModalKind { NONE, APP_DIALOG, SYSTEM_DIALOG, PERMISSION, KEYBOARD, MENU, UNKNOWN }

data class ScreenScene(
    val packageName: String,
    val externalForegroundPackage: String,
    val assistantUiPackage: String? = null,
    val windowId: Int,
    val generation: Long,
    val screenType: String,
    val semanticElements: List<SemanticElement>,
    val screenshotReference: ScreenshotReference?,
    val modal: ModalKind = ModalKind.NONE,
    val keyboardVisible: Boolean = false,
    val observedAt: Long,
    val confidence: Double
)

object ScreenSceneFactory {
    private const val ASSISTANT_PACKAGE = "com.myra.assistant"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    fun from(context: CurrentActivityContext, previousExternalPackage: String? = null): ScreenScene {
        val overlay = context.packageName in setOf(ASSISTANT_PACKAGE, SYSTEM_UI_PACKAGE)
        val external = if (overlay) previousExternalPackage ?: context.packageName else context.packageName
        val permissionSignal = context.visibleElements.any {
            Regex("\\b(?:allow|permission|while using|only this time|deny)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.label)
        }
        val modal = when {
            context.packageName == SYSTEM_UI_PACKAGE && permissionSignal -> ModalKind.PERMISSION
            context.packageName == SYSTEM_UI_PACKAGE -> ModalKind.SYSTEM_DIALOG
            context.screenType.contains("KEYBOARD", true) -> ModalKind.KEYBOARD
            context.screenType.contains("DIALOG", true) -> ModalKind.APP_DIALOG
            context.screenType.contains("MENU", true) -> ModalKind.MENU
            else -> ModalKind.NONE
        }
        return ScreenScene(
            packageName = context.packageName, externalForegroundPackage = external,
            assistantUiPackage = context.packageName.takeIf { it == ASSISTANT_PACKAGE },
            windowId = context.windowId, generation = context.generation, screenType = context.screenType,
            semanticElements = context.visibleElements, screenshotReference = context.screenshotReference,
            modal = modal, observedAt = context.timestamp, confidence = context.confidence
        )
    }
}
