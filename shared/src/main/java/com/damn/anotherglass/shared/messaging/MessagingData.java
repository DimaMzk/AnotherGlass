package com.damn.anotherglass.shared.messaging;

import java.io.Serializable;

public class MessagingData implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Action implements Serializable {
        Posted, Removed
    }

    public Action action;
    public int id;
    public String packageName;
    public String appName;
    public long postedTime;
    public String senderName;
    public String messageText;
    public byte[] appIcon;      // Small app icon (16x16)
    public byte[] senderImage;  // Sender profile image (progressive: 16x16 then 128x128)

    public MessagingData() {
    }

    public MessagingData(Action action, int id, String packageName, String appName, 
                         long postedTime, String senderName, String messageText) {
        this.action = action;
        this.id = id;
        this.packageName = packageName;
        this.appName = appName;
        this.postedTime = postedTime;
        this.senderName = senderName;
        this.messageText = messageText;
    }
}
