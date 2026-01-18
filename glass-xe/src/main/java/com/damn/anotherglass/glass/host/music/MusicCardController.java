package com.damn.anotherglass.glass.host.music;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import com.damn.anotherglass.glass.host.HostService;
import com.damn.anotherglass.glass.host.R;
import com.damn.anotherglass.shared.music.MusicAPI;
import com.damn.anotherglass.shared.music.MusicControl;
import com.damn.anotherglass.shared.music.MusicData;
import com.damn.anotherglass.shared.rpc.IRPCClient;
import com.damn.anotherglass.shared.rpc.RPCMessage;
import com.google.android.glass.timeline.LiveCard;

public class MusicCardController extends BroadcastReceiver {

    private static final String CARD_TAG = "MusicCard";
    private static final long UI_UPDATE_INTERVAL = 1000L;
    
    private final HostService service;
    private final IRPCClient rpcClient;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LiveCard liveCard;
    private MusicData lastData;
    private Bitmap cachedArt;
    private long syncedPosition;
    private long syncedTimestamp;
    private String lastTrackKey;
    
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (lastData != null && lastData.isPlaying) {
                refreshCard();
                handler.postDelayed(this, UI_UPDATE_INTERVAL);
            }
        }
    };

    public MusicCardController(HostService service, IRPCClient rpcClient) {
        this.service = service;
        this.rpcClient = rpcClient;
        IntentFilter filter = new IntentFilter(MusicMenuActivity.ACTION_BROADCAST);
        service.registerReceiver(this, filter);
    }

    public void update(MusicData data) {
        synchronized (this) {
            // If this is an art-only update, just cache the art and refresh
            if (data.track == null && data.albumArt != null) {
                if (cachedArt != null) {
                    cachedArt.recycle();
                }
                cachedArt = BitmapFactory.decodeByteArray(data.albumArt, 0, data.albumArt.length);
                if (lastData != null) {
                    refreshCard();
                }
                return;
            }

            boolean wasPlaying = lastData != null && lastData.isPlaying;
            
            // Check if track changed
            String trackKey = (data.artist != null ? data.artist : "") + "|" + (data.track != null ? data.track : "");
            boolean trackChanged = !trackKey.equals(lastTrackKey);
            lastTrackKey = trackKey;
            
            this.lastData = data;
            
            // Sync position from server
            syncedPosition = data.position;
            syncedTimestamp = data.timestamp;
            
            // Update cached art if included
            if (data.albumArt != null && data.albumArt.length > 0) {
                if (cachedArt != null) {
                    cachedArt.recycle();
                }
                cachedArt = BitmapFactory.decodeByteArray(data.albumArt, 0, data.albumArt.length);
            }
            
            refreshCard();
            
            // Focus the card when track changes
            if (trackChanged && liveCard != null && liveCard.isPublished()) {
                liveCard.navigate();
            }
            
            // Start/stop local progress timer based on playback state
            if (data.isPlaying && !wasPlaying) {
                handler.removeCallbacks(progressRunnable);
                handler.postDelayed(progressRunnable, UI_UPDATE_INTERVAL);
            } else if (!data.isPlaying) {
                handler.removeCallbacks(progressRunnable);
            }
        }
    }

    private void refreshCard() {
        synchronized (this) {
            if (lastData == null) return;
            
            if (liveCard == null) {
                liveCard = new LiveCard(service, CARD_TAG);
            }

            RemoteViews views = new RemoteViews(service.getPackageName(), R.layout.music_card);
            
            // Set album art (fixed 124dp x 124dp in layout)
            if (cachedArt != null) {
                views.setImageViewBitmap(R.id.album_art, cachedArt);
            }
            
            // Set track title
            String trackText = lastData.track != null ? lastData.track : "Unknown Track";
            views.setTextViewText(R.id.track_title, trackText);
            
            // Set artist
            String artistText = lastData.artist != null ? lastData.artist : "Unknown Artist";
            views.setTextViewText(R.id.artist, artistText);
            
            // Set progress (calculate current position locally)
            if (lastData.duration > 0) {
                long currentPosition = syncedPosition;
                if (lastData.isPlaying && syncedTimestamp > 0) {
                    currentPosition += System.currentTimeMillis() - syncedTimestamp;
                }
                currentPosition = Math.min(currentPosition, lastData.duration);
                String progress = formatTime(currentPosition) + " / " + formatTime(lastData.duration);
                views.setTextViewText(R.id.progress, progress);
            } else {
                views.setTextViewText(R.id.progress, "");
            }

            liveCard.setViews(views);

            Intent menuIntent = new Intent(service, MusicMenuActivity.class);
            menuIntent.putExtra(MusicMenuActivity.EXTRA_IS_PLAYING, lastData.isPlaying);
            
            liveCard.setAction(PendingIntent.getActivity(service, 0, menuIntent, PendingIntent.FLAG_UPDATE_CURRENT));

            if (!liveCard.isPublished()) {
                liveCard.publish(LiveCard.PublishMode.REVEAL);
            }
        }
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    public void remove() {
        handler.removeCallbacks(progressRunnable);
        try {
            service.unregisterReceiver(this);
        } catch (IllegalArgumentException e) {
            // Ignore if not registered
        }
        if (liveCard != null && liveCard.isPublished()) {
            liveCard.unpublish();
        }
        liveCard = null;
        if (cachedArt != null && !cachedArt.isRecycled()) {
            cachedArt.recycle();
        }
        cachedArt = null;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (MusicMenuActivity.ACTION_BROADCAST.equals(intent.getAction())) {
            String action = intent.getStringExtra(MusicMenuActivity.EXTRA_ACTION);
            MusicControl control = null;
            if ("Play".equals(action)) control = MusicControl.Play;
            else if ("Pause".equals(action)) control = MusicControl.Pause;
            else if ("Next".equals(action)) control = MusicControl.Next;
            else if ("Previous".equals(action)) control = MusicControl.Previous;

            if (control != null) {
                rpcClient.send(new RPCMessage(MusicAPI.ID, control));
                // Optimistically update UI for responsiveness without mutating shared state
                if (lastData != null && (control == MusicControl.Play || control == MusicControl.Pause)) {
                    boolean newPlayingState = (control == MusicControl.Play);
                    // Create a copy to avoid mutating the shared lastData object
                    MusicData optimisticData = new MusicData(
                        lastData.artist,
                        lastData.track,
                        null, // Don't include art in optimistic update
                        newPlayingState,
                        lastData.position,
                        lastData.duration
                    );
                    optimisticData.timestamp = lastData.timestamp;
                    update(optimisticData);
                }
            }
        }
    }
}
