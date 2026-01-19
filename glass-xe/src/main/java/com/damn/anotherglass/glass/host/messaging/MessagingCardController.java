package com.damn.anotherglass.glass.host.messaging;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.format.DateUtils;
import android.widget.RemoteViews;

import com.damn.anotherglass.glass.host.HostService;
import com.damn.anotherglass.glass.host.R;
import com.damn.anotherglass.shared.messaging.MessagingData;
import com.google.android.glass.timeline.LiveCard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for messaging notification cards.
 * Each messaging app with active notifications gets its own card showing the latest message.
 * Tapping a card opens a list of all notifications for that app.
 */
public class MessagingCardController {

    private static final String CARD_TAG_PREFIX = "MessagingCard_";

    private final HostService service;

    // Per-app live cards
    private final Map<String, LiveCard> appCards = new HashMap<>();

    // Per-app notifications (ordered by time, newest first)
    private final Map<String, List<MessagingData>> appNotifications = new HashMap<>();

    // Cached sender images per notification ID within each app
    private final Map<String, Map<Integer, Bitmap>> appSenderImages = new HashMap<>();

    // Cached app icons per package
    private final Map<String, Bitmap> appIcons = new HashMap<>();

    public MessagingCardController(HostService service) {
        this.service = service;
    }

    public void update(MessagingData data) {
        if (data == null || data.packageName == null) return;

        String packageName = data.packageName;

        // Handle image-only update (progressive loading)
        if (data.senderName == null && data.senderImage != null) {
            updateSenderImage(packageName, data.id, data.senderImage);
            return;
        }

        if (data.action == MessagingData.Action.Posted) {
            handlePosted(data);
        } else if (data.action == MessagingData.Action.Removed) {
            handleRemoved(data);
        }
    }

    private void handlePosted(MessagingData data) {
        String packageName = data.packageName;

        // Ensure we have storage for this app
        if (!appNotifications.containsKey(packageName)) {
            appNotifications.put(packageName, new ArrayList<MessagingData>());
            appSenderImages.put(packageName, new HashMap<Integer, Bitmap>());
        }

        List<MessagingData> notifications = appNotifications.get(packageName);

        // Remove existing notification with same ID (update)
        for (int i = 0; i < notifications.size(); i++) {
            if (notifications.get(i).id == data.id) {
                notifications.remove(i);
                break;
            }
        }

        // Add new notification at the beginning (newest first)
        notifications.add(0, data);

        // Cache app icon
        if (data.appIcon != null && !appIcons.containsKey(packageName)) {
            Bitmap icon = BitmapFactory.decodeByteArray(data.appIcon, 0, data.appIcon.length);
            appIcons.put(packageName, icon);
        }

        // Cache sender image
        if (data.senderImage != null) {
            Map<Integer, Bitmap> senderImages = appSenderImages.get(packageName);
            if (senderImages != null) {
                Bitmap oldImage = senderImages.get(data.id);
                if (oldImage != null && !oldImage.isRecycled()) {
                    oldImage.recycle();
                }
                senderImages.put(data.id, BitmapFactory.decodeByteArray(
                        data.senderImage, 0, data.senderImage.length));
            }
        }

        // Update or create card for this app
        refreshCard(packageName);
    }

    private void handleRemoved(MessagingData data) {
        String packageName = data.packageName;
        List<MessagingData> notifications = appNotifications.get(packageName);
        if (notifications == null) return;

        // Remove the notification
        for (int i = 0; i < notifications.size(); i++) {
            if (notifications.get(i).id == data.id) {
                notifications.remove(i);
                break;
            }
        }

        // Remove cached sender image
        Map<Integer, Bitmap> senderImages = appSenderImages.get(packageName);
        if (senderImages != null) {
            Bitmap image = senderImages.remove(data.id);
            if (image != null && !image.isRecycled()) {
                image.recycle();
            }
        }

        if (notifications.isEmpty()) {
            // Remove card and clean up
            removeAppCard(packageName);
        } else {
            // Refresh card with latest notification
            refreshCard(packageName);
        }
    }

    private void updateSenderImage(String packageName, int notificationId, byte[] imageData) {
        Map<Integer, Bitmap> senderImages = appSenderImages.get(packageName);
        if (senderImages == null) return;

        Bitmap oldImage = senderImages.get(notificationId);
        if (oldImage != null && !oldImage.isRecycled()) {
            oldImage.recycle();
        }

        Bitmap newImage = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
        senderImages.put(notificationId, newImage);

        // Refresh card if this is the latest notification
        List<MessagingData> notifications = appNotifications.get(packageName);
        if (notifications != null && !notifications.isEmpty() 
                && notifications.get(0).id == notificationId) {
            refreshCard(packageName);
        }
    }

    private void refreshCard(String packageName) {
        List<MessagingData> notifications = appNotifications.get(packageName);
        if (notifications == null || notifications.isEmpty()) return;

        MessagingData latestData = notifications.get(0);
        LiveCard card = appCards.get(packageName);

        if (card == null) {
            card = new LiveCard(service, CARD_TAG_PREFIX + packageName);
            appCards.put(packageName, card);
        }

        RemoteViews views = new RemoteViews(service.getPackageName(), R.layout.messaging_card);

        // Top row: app icon and app name
        Bitmap appIcon = appIcons.get(packageName);
        if (appIcon != null) {
            views.setImageViewBitmap(R.id.app_icon, appIcon);
        }
        views.setTextViewText(R.id.app_name, latestData.appName != null ? latestData.appName : packageName);

        // Bottom row: sender image, sender name, message text
        Map<Integer, Bitmap> senderImages = appSenderImages.get(packageName);
        Bitmap senderImage = senderImages != null ? senderImages.get(latestData.id) : null;
        if (senderImage != null) {
            views.setImageViewBitmap(R.id.sender_image, senderImage);
        }

        views.setTextViewText(R.id.sender_name, latestData.senderName != null ? latestData.senderName : "Unknown");
        views.setTextViewText(R.id.message_text, latestData.messageText != null ? latestData.messageText : "");

        // Timestamp at bottom right
        CharSequence timestamp = DateUtils.getRelativeTimeSpanString(
                latestData.postedTime,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
        views.setTextViewText(R.id.timestamp, timestamp);

        card.setViews(views);

        // Set action to open list of all notifications for this app
        Intent listIntent = new Intent(service, MessagingListActivity.class);
        listIntent.putExtra(MessagingListActivity.EXTRA_PACKAGE_NAME, packageName);
        listIntent.putExtra(MessagingListActivity.EXTRA_APP_NAME, latestData.appName);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                service, packageName.hashCode(), listIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        card.setAction(pendingIntent);

        if (!card.isPublished()) {
            card.publish(LiveCard.PublishMode.REVEAL);
        } else {
            card.navigate();
        }
    }

    private void removeAppCard(String packageName) {
        LiveCard card = appCards.remove(packageName);
        if (card != null && card.isPublished()) {
            card.unpublish();
        }
        appNotifications.remove(packageName);

        // Clean up cached images
        Map<Integer, Bitmap> senderImages = appSenderImages.remove(packageName);
        if (senderImages != null) {
            for (Bitmap bitmap : senderImages.values()) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }
    }

    /**
     * Get all notifications for a specific app.
     * Used by MessagingListActivity.
     */
    public List<MessagingData> getNotificationsForApp(String packageName) {
        List<MessagingData> notifications = appNotifications.get(packageName);
        return notifications != null ? new ArrayList<>(notifications) : new ArrayList<MessagingData>();
    }

    /**
     * Get cached sender image for a notification.
     * Used by MessagingListActivity.
     */
    public Bitmap getSenderImage(String packageName, int notificationId) {
        Map<Integer, Bitmap> senderImages = appSenderImages.get(packageName);
        return senderImages != null ? senderImages.get(notificationId) : null;
    }

    /**
     * Get cached app icon.
     * Used by MessagingListActivity.
     */
    public Bitmap getAppIcon(String packageName) {
        return appIcons.get(packageName);
    }

    public void remove() {
        // Clean up all cards
        for (LiveCard card : appCards.values()) {
            if (card != null && card.isPublished()) {
                card.unpublish();
            }
        }
        appCards.clear();
        appNotifications.clear();

        // Clean up all cached images
        for (Map<Integer, Bitmap> senderImages : appSenderImages.values()) {
            for (Bitmap bitmap : senderImages.values()) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }
        appSenderImages.clear();

        for (Bitmap icon : appIcons.values()) {
            if (icon != null && !icon.isRecycled()) {
                icon.recycle();
            }
        }
        appIcons.clear();
    }

    // Singleton for access from MessagingListActivity
    private static MessagingCardController instance;

    public static void setInstance(MessagingCardController controller) {
        instance = controller;
    }

    public static MessagingCardController getInstance() {
        return instance;
    }
}
