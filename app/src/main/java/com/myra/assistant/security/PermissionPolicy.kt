package com.myra.assistant.security

object PermissionPolicy {
    fun requestOnlyForFeature(permission: String, featurePermissions: Set<String>): Boolean = permission in featurePermissions
}
