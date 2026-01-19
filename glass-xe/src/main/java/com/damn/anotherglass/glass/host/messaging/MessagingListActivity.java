package com.damn.anotherglass.glass.host.messaging;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;

import com.damn.anotherglass.glass.host.R;
import com.damn.anotherglass.shared.messaging.MessagingData;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Activity that shows all notifications for a specific messaging app.
 * Displayed when user taps on a messaging card.
 */
public class MessagingListActivity extends Activity {

    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_APP_NAME = "app_name";

    private CardScrollView mCardScrollView;
    private MessagingListAdapter mAdapter;
    private String packageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);

        if (packageName == null) {
            finish();
            return;
        }

        MessagingCardController controller = MessagingCardController.getInstance();
        if (controller == null) {
            finish();
            return;
        }

        List<MessagingData> notifications = controller.getNotificationsForApp(packageName);
        if (notifications.isEmpty()) {
            finish();
            return;
        }

        mCardScrollView = new CardScrollView(this);
        mAdapter = new MessagingListAdapter(this, notifications, controller, packageName);
        mCardScrollView.setAdapter(mAdapter);
        mCardScrollView.activate();

        mCardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // Could add actions here in the future (reply, dismiss, etc.)
                finish();
            }
        });

        setContentView(mCardScrollView);
    }

    @Override
    protected void onDestroy() {
        if (mCardScrollView != null) {
            mCardScrollView.deactivate();
        }
        super.onDestroy();
    }

    private static class MessagingListAdapter extends CardScrollAdapter {

        private final Context context;
        private final List<MessagingData> notifications;
        private final MessagingCardController controller;
        private final String packageName;
        private final DateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());

        MessagingListAdapter(Context context, List<MessagingData> notifications,
                           MessagingCardController controller, String packageName) {
            this.context = context;
            this.notifications = notifications;
            this.controller = controller;
            this.packageName = packageName;
        }

        @Override
        public int getCount() {
            return notifications.size();
        }

        @Override
        public Object getItem(int position) {
            return notifications.get(position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            MessagingData data = notifications.get(position);

            // Use CardBuilder with AUTHOR layout for consistency with notification cards
            CardBuilder card = new CardBuilder(context, CardBuilder.Layout.AUTHOR)
                    .setHeading(data.senderName != null ? data.senderName : "Unknown")
                    .setText(data.messageText != null ? data.messageText : "")
                    .setTimestamp(timeFormat.format(new Date(data.postedTime)))
                    .setFootnote(data.appName != null ? data.appName : packageName);

            // Set sender image as icon
            Bitmap senderImage = controller.getSenderImage(packageName, data.id);
            if (senderImage != null) {
                card.setIcon(senderImage);
            }

            return card.getView(convertView, parent);
        }

        @Override
        public int getPosition(Object item) {
            return notifications.indexOf(item);
        }
    }
}
