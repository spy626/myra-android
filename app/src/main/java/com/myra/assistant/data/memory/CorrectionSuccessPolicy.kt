package com.myra.assistant.data.memory

/** Success text/audio is legal only after a successful write and read-after-write check. */
object CorrectionSuccessPolicy {
    const val UNRESOLVED_CLARIFICATION_REPLY =
        "Naam clear nahi hua. Ek baar sirf naam bolo ya spelling karo."

    fun acknowledgementAllowed(writeSuccess: Boolean, verified: Boolean): Boolean =
        writeSuccess && verified
}
