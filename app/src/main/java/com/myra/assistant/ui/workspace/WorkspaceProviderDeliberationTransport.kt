package com.myra.assistant.ui.workspace

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Read-only network preparation for D4. It never selects providers, writes files, retries across
 * providers or treats model output as verification.
 */
internal object WorkspaceProviderDeliberationTransport {
    data class Dispatch(
        val provider: WorkspaceProviderRegistry.Id,
        val request: Request,
        val client: OkHttpClient,
    )

    fun prepare(
        envelope: WorkspaceProviderDeliberation.Envelope,
        key: String,
        providerEnabled: Boolean,
        sourceApproved: Boolean,
        zaiModel: String = WorkspaceZaiFree.DEFAULT_TEXT_MODEL,
    ): Dispatch {
        require(providerEnabled) {
            "${WorkspaceProviderRegistry.capability(envelope.provider).displayName} deliberation permission is OFF"
        }
        val sourceIncluded =
            envelope.sharingLevel == WorkspaceProviderDeliberation.SharingLevel.BOUNDED_SOURCE
        if (sourceIncluded) {
            require(sourceApproved) {
                "${WorkspaceProviderRegistry.capability(envelope.provider).displayName} source sharing is not approved"
            }
        }

        return when (envelope.provider) {
            WorkspaceProviderRegistry.Id.XKIRO_FREE -> Dispatch(
                provider = envelope.provider,
                request = WorkspaceXKiroFree.deliberationRequest(key, envelope.body),
                client = WorkspaceXKiroFree.deliberationClient,
            )
            WorkspaceProviderRegistry.Id.ZAI_FREE -> Dispatch(
                provider = envelope.provider,
                request = WorkspaceZaiFree.deliberationRequest(
                    key = key,
                    prompt = envelope.body,
                    textModel = zaiModel,
                    sourceIncluded = sourceIncluded,
                    sourceApproved = sourceApproved,
                ),
                client = WorkspaceZaiFree.client,
            )
            else -> error(
                "This D4 transport currently supports xKiro Free and Z.ai only; no alternate provider was selected")
        }
    }

    fun read(dispatch: Dispatch, response: Response): String = when (dispatch.provider) {
        WorkspaceProviderRegistry.Id.XKIRO_FREE -> WorkspaceXKiroFree.readDeliberation(response)
        WorkspaceProviderRegistry.Id.ZAI_FREE -> WorkspaceZaiFree.read(response)
        else -> error("Unsupported deliberation response provider")
    }
}
