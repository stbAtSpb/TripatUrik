package com.urik.keyboard.screenshot

import android.content.Intent

/**
 * Caches MediaProjection permission result so the user only sees the
 * system consent dialog once per keyboard service session.
 */
object ScreenshotPermissionCache {
    var resultCode: Int = 0
        private set
    var resultData: Intent? = null
        private set

    val hasPermission: Boolean
        get() = resultCode != 0 && resultData != null

    fun save(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.resultData = data.clone() as Intent
    }

    fun clear() {
        resultCode = 0
        resultData = null
    }
}
