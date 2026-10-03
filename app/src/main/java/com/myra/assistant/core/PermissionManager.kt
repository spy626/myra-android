package com.myra.assistant.core

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class PermissionManager(private val context: Context) {
    fun isGranted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
