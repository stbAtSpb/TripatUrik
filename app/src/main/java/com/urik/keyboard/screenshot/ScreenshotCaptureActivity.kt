package com.urik.keyboard.screenshot

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.urik.keyboard.R

/**
 * Transparent Activity that requests screen capture permission via MediaProjection API.
 * Caches the permission in [ScreenshotPermissionCache] so subsequent captures
 * skip this dialog. Launches [ScreenshotService] on permission grant, then finishes
 * after a short delay to allow the service to call startForeground().
 */
class ScreenshotCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val FINISH_DELAY_MS = 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureNotificationChannel()

        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenshotPermissionCache.save(resultCode, data)

                val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                    putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenshotService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(serviceIntent)

                Handler(Looper.getMainLooper()).postDelayed({ finish() }, FINISH_DELAY_MS)
            } else {
                Toast.makeText(this, getString(R.string.screenshot_permission_denied), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            ScreenshotService.CHANNEL_ID,
            getString(R.string.screenshot_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
