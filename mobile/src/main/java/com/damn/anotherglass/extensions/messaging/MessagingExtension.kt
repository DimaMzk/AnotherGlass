package com.damn.anotherglass.extensions.messaging

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.graphics.Canvas
import com.applicaster.xray.core.Logger
import com.damn.anotherglass.core.GlassService
import com.damn.anotherglass.extensions.notifications.NotificationEvent
import com.damn.anotherglass.shared.messaging.MessagingAPI
import com.damn.anotherglass.shared.messaging.MessagingData
import com.damn.anotherglass.shared.notifications.NotificationData
import com.damn.anotherglass.shared.rpc.RPCMessage
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class MessagingExtension(private val service: GlassService) {

    private val log = Logger.get(TAG)
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private var pendingImageUpdate: Future<*>? = null

    // Cache for app icons to avoid re-extracting
    private val appIconCache = mutableMapOf<String, ByteArray>()

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: NotificationEvent) {
        val sbn = event.notification
        val packageName = sbn.packageName

        if (!service.settings.messagingAppPackages.contains(packageName)) {
            return
        }

        val action = when (event.action) {
            NotificationData.Action.Posted -> MessagingData.Action.Posted
            NotificationData.Action.Removed -> MessagingData.Action.Removed
        }

        // Process notification in background to avoid blocking main thread
        imageExecutor.submit {
            try {
                val data = convertToMessagingData(action, sbn)
                
                // Send message data immediately (without large sender image)
                service.send(RPCMessage(MessagingAPI.ID, data))

                // Send progressive sender images asynchronously
                if (action == MessagingData.Action.Posted) {
                    sendProgressiveImages(sbn, data.id, data.packageName)
                }

                log.d(TAG).message("Messaging notification processed: ${data.appName} - ${data.senderName}")
            } catch (e: Exception) {
                log.e(TAG).exception(e).message("Failed to process messaging notification")
            }
        }
    }

    private fun convertToMessagingData(action: MessagingData.Action, sbn: StatusBarNotification): MessagingData {
        val notification = sbn.notification
        val extras = notification.extras

        val data = MessagingData()
        data.action = action
        data.id = sbn.id
        data.packageName = sbn.packageName
        data.postedTime = sbn.postTime

        // Get app name
        data.appName = getAppName(sbn.packageName)

        // Extract sender name (title) and message text
        data.senderName = extras.getString(Notification.EXTRA_TITLE) ?: "Unknown"
        data.messageText = extras.getString(Notification.EXTRA_TEXT) ?: ""

        // Get cached or extract app icon (small)
        data.appIcon = getAppIcon(sbn.packageName)

        // Get small sender image for immediate display
        data.senderImage = extractSenderImage(notification, SMALL_IMAGE_SIZE)

        return data
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = service.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            when (packageName) {
                MessagingAPI.GOOGLE_MESSAGES_PACKAGE -> "Messages"
                MessagingAPI.DISCORD_PACKAGE -> "Discord"
                MessagingAPI.SLACK_PACKAGE -> "Slack"
                else -> packageName
            }
        }
    }

    private fun getAppIcon(packageName: String): ByteArray? {
        appIconCache[packageName]?.let { return it }

        try {
            val pm = service.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)
            val scaled = bitmap.scale(16, 16, true)
            
            ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val bytes = stream.toByteArray()
                appIconCache[packageName] = bytes
                scaled.recycle()
                if (scaled !== bitmap) bitmap.recycle()
                return bytes
            }
        } catch (e: Exception) {
            log.e(TAG).exception(e).message("Failed to extract app icon for $packageName")
            return null
        }
    }

    private fun extractSenderImage(notification: Notification, size: Int): ByteArray? {
        try {
            // Try to get large icon (often the sender's profile image)
            var bitmap: Bitmap? = null
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val icon = notification.getLargeIcon()
                if (icon != null) {
                    val drawable = icon.loadDrawable(service)
                    if (drawable != null) {
                        bitmap = drawableToBitmap(drawable)
                    }
                }
            }
            
            if (bitmap == null) {
                bitmap = notification.largeIcon
            }

            if (bitmap != null) {
                val scaled = bitmap.scale(size, size, true)
                ByteArrayOutputStream().use { stream ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val bytes = stream.toByteArray()
                    scaled.recycle()
                    return bytes
                }
            }
        } catch (e: Exception) {
            log.e(TAG).exception(e).message("Failed to extract sender image")
        }
        return null
    }

    private fun sendProgressiveImages(sbn: StatusBarNotification, notificationId: Int, packageName: String) {
        pendingImageUpdate?.cancel(true)
        pendingImageUpdate = imageExecutor.submit {
            try {
                // Send larger sender image
                val largeImage = extractSenderImage(sbn.notification, LARGE_IMAGE_SIZE)
                if (largeImage != null) {
                    val imageUpdate = MessagingData()
                    imageUpdate.action = MessagingData.Action.Posted
                    imageUpdate.id = notificationId
                    imageUpdate.packageName = packageName
                    imageUpdate.senderImage = largeImage
                    service.send(RPCMessage(MessagingAPI.ID, imageUpdate))
                    log.d(TAG).message("Sent large sender image: ${largeImage.size} bytes")
                }
            } catch (e: Exception) {
                log.e(TAG).exception(e).message("Failed to send progressive image")
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            createBitmap(1, 1)
        } else {
            createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun start() {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        log.i(TAG).message("MessagingExtension started")
    }

    fun stop() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        pendingImageUpdate?.cancel(true)
        imageExecutor.shutdown()
        try {
            if (!imageExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                imageExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            imageExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        appIconCache.clear()
        log.i(TAG).message("MessagingExtension stopped")
    }

    companion object {
        private const val TAG = "MessagingExtension"
        private const val SMALL_IMAGE_SIZE = 16
        private const val LARGE_IMAGE_SIZE = 128
    }
}
