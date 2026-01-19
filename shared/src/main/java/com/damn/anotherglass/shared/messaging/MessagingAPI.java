package com.damn.anotherglass.shared.messaging;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MessagingAPI {
    public static final String ID = "Messaging";

    // Messaging app package names
    public static final String GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging";
    public static final String DISCORD_PACKAGE = "com.discord";
    public static final String SLACK_PACKAGE = "com.Slack";

    public static final Set<String> MESSAGING_PACKAGES = new HashSet<>(Arrays.asList(
            GOOGLE_MESSAGES_PACKAGE,
            DISCORD_PACKAGE,
            SLACK_PACKAGE
    ));

    public static boolean isMessagingApp(String packageName) {
        return MESSAGING_PACKAGES.contains(packageName);
    }
}
