package com.urik.keyboard.screenshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.urik.keyboard.R
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that performs screen capture via MediaProjection API.
 * Captures one frame after a brief delay (to allow keyboard to hide),
 * saves it as PNG, and opens the Android share sheet.
 */
class ScreenshotService : Service() {

    companion object {
        private const val TAG = "ScreenshotService"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "screenshot_channel"
        private const val NOTIFICATION_ID = 9001
        private const val CAPTURE_DELAY_MS = 500L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        createNotificationChannel()
        val notification = buildNotification()
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            Log.d(TAG, "startForeground succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val hasResultCode = intent?.hasExtra(EXTRA_RESULT_CODE) == true
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        Log.d(TAG, "hasResultCode=$hasResultCode resultCode=$resultCode resultData=${resultData != null}")

        if (!hasResultCode || resultData == null) {
            Log.e(TAG, "Invalid result, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            Log.d(TAG, "getMediaProjection result: ${mediaProjection != null}")
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection FAILED", e)
            ScreenshotPermissionCache.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "mediaProjection is null, stopping")
            ScreenshotPermissionCache.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Scheduling captureScreen in ${CAPTURE_DELAY_MS}ms")
        handler.postDelayed({ captureScreen() }, CAPTURE_DELAY_MS)

        return START_NOT_STICKY
    }

    private fun captureScreen() {
        Log.d(TAG, "captureScreen called")
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            Log.d(TAG, "Screen: ${width}x${height} density=$density")

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenshotCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                handler,
            )
            Log.d(TAG, "VirtualDisplay created: ${virtualDisplay != null}")

            imageReader?.setOnImageAvailableListener({ reader ->
                Log.d(TAG, "onImageAvailable called")
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val croppedBitmap = if (bitmap.width > width) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                            if (it !== bitmap) bitmap.recycle()
                        }
                    } else {
                        bitmap
                    }

                    val file = saveBitmap(croppedBitmap)
                    croppedBitmap.recycle()
                    Log.d(TAG, "Bitmap saved: ${file?.absolutePath}")

                    image.close()
                    releaseCapture()

                    if (file != null) {
                        Log.d(TAG, "Launching share intent")
                        shareScreenshot(file)
                    } else {
                        handler.post {
                            Toast.makeText(
                                this,
                                getString(R.string.screenshot_save_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }

                    handler.postDelayed({ stopSelf() }, 1000L)
                } catch (e: Exception) {
                    image.close()
                    cleanup()
                }
            }, handler)
        } catch (e: Exception) {
            ScreenshotPermissionCache.clear()
            handler.post {
                Toast.makeText(
                    this,
                    getString(R.string.screenshot_capture_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            cleanup()
        }
    }

    private fun saveBitmap(bitmap: Bitmap): File? {
        return try {
            val screenshotDir = File(cacheDir, "screenshots")
            screenshotDir.mkdirs()
            val file = File(screenshotDir, "screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun shareScreenshot(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, getString(R.string.screenshot_share_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(chooser)
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun cleanup() {
        releaseCapture()
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screenshot_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screenshot_notification_title))
            .setContentText(getString(R.string.screenshot_share_title))
            .setSmallIcon(R.drawable.ic_screenshot)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
