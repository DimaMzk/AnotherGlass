package com.damn.anotherglass.extensions.music

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.applicaster.xray.core.Logger
import com.damn.anotherglass.core.GlassService
import com.damn.anotherglass.extensions.notifications.NotificationService
import com.damn.anotherglass.shared.music.MusicAPI
import com.damn.anotherglass.shared.music.MusicControl
import com.damn.anotherglass.shared.music.MusicData
import com.damn.anotherglass.shared.rpc.RPCMessage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MusicExtension(private val service: GlassService) {

    private val log = Logger.get(TAG)
    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val albumArtExecutor = Executors.newSingleThreadExecutor()
    private var pendingArtUpdate: Future<*>? = null
    @Volatile private var isPlaying = false
    @Volatile private var lastSentTrack: String? = null
    @Volatile private var lastSentArtForTrack: String? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && currentController != null) {
                sendUpdate()
                handler.postDelayed(this, SYNC_INTERVAL)
            }
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateController(controllers)
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val wasPlaying = isPlaying
            isPlaying = state?.state == PlaybackState.STATE_PLAYING
            sendUpdate()
            
            if (isPlaying && !wasPlaying) {
                handler.removeCallbacks(syncRunnable)
                handler.postDelayed(syncRunnable, SYNC_INTERVAL)
            } else if (!isPlaying) {
                handler.removeCallbacks(syncRunnable)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            sendUpdate()
        }

        override fun onSessionDestroyed() {
            handler.removeCallbacks(syncRunnable)
            currentController = null
            isPlaying = false
        }
    }

    fun start() {
        try {
            mediaSessionManager = service.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(service, NotificationService::class.java)
            
            if (NotificationService.isEnabled(service)) {
                 val controllers = mediaSessionManager?.getActiveSessions(componentName)
                 updateController(controllers)
                 mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsListener, componentName)
                 log.i(TAG).message("MusicExtension started")
            } else {
                log.w(TAG).message("NotificationService not enabled, cannot get media sessions")
            }

        } catch (e: SecurityException) {
            log.e(TAG).exception(e).message("Failed to access media sessions")
        } catch (e: Exception) {
            log.e(TAG).exception(e).message("Error starting MusicExtension")
        }
    }

    fun stop() {
        try {
            handler.removeCallbacks(syncRunnable)
            pendingArtUpdate?.cancel(true)
            albumArtExecutor.shutdownNow()
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
            currentController?.unregisterCallback(callback)
            currentController = null
            isPlaying = false
            lastSentTrack = null
            lastSentArtForTrack = null
            log.i(TAG).message("MusicExtension stopped")
        } catch (e: Exception) {
            log.e(TAG).exception(e).message("Error stopping MusicExtension")
        }
    }

    fun onMessage(payload: Any?) {
        if (payload is MusicControl) {
            val controller = currentController ?: return
            val controls = controller.transportControls ?: return
            when (payload) {
                MusicControl.Play -> controls.play()
                MusicControl.Pause -> controls.pause()
                MusicControl.Next -> controls.skipToNext()
                MusicControl.Previous -> controls.skipToPrevious()
            }
        }
    }

    private fun updateController(controllers: List<MediaController>?) {
        // Find YouTube Music controller
        val ytMusicController = controllers?.find { it.packageName == MusicAPI.YOUTUBE_MUSIC_PACKAGE }

        if (ytMusicController != null) {
            if (currentController?.sessionToken != ytMusicController.sessionToken) {
                handler.removeCallbacks(syncRunnable)
                pendingArtUpdate?.cancel(true)
                currentController?.unregisterCallback(callback)
                currentController = ytMusicController
                currentController?.registerCallback(callback, handler)
                sendUpdate()
                log.d(TAG).message("Locked onto YouTube Music session")
            }
        } else {
             if (currentController != null && controllers?.contains(currentController) == false) {
                 currentController?.unregisterCallback(callback)
                 currentController = null
             }
        }
    }

    private fun sendUpdate() {
        val controller = currentController ?: return
        val metadata = controller.metadata ?: return
        val playbackState = controller.playbackState

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val playing = playbackState?.state == PlaybackState.STATE_PLAYING
        isPlaying = playing

        // Calculate current position accounting for time elapsed since last update
        // The position from playbackState is the position at lastPositionUpdateTime
        // We add elapsed time * playback speed to estimate current position
        var position = playbackState?.position ?: 0L
        if (playing && playbackState != null) {
            val timeDelta = SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
            val speed = playbackState.playbackSpeed
            position += (timeDelta * speed).toLong()
        }

        // Send track info immediately (no art)
        val data = MusicData(artist, track, null, playing, position, duration)
        service.send(RPCMessage(MusicAPI.ID, data))

        // Send album art async only when track changes or art wasn't sent yet
        val artistOrEmpty = artist ?: ""
        val trackOrEmpty = track ?: ""
        val trackKey = "$artistOrEmpty|$trackOrEmpty"
        
        synchronized(this) {
            if (trackKey != lastSentTrack) {
                lastSentTrack = trackKey
                lastSentArtForTrack = null
                pendingArtUpdate?.cancel(true)
            }
            
            if (lastSentArtForTrack != trackKey) {
                val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                
                if (bitmap != null) {
                    lastSentArtForTrack = trackKey
                    val currentPlaying = playing
                    
                    // Create defensive copy to avoid using framework-owned bitmap across threads
                    val safeConfig = bitmap.config ?: Bitmap.Config.ARGB_8888
                    val albumArtBitmap = try {
                        bitmap.copy(safeConfig, false)
                    } catch (e: Exception) {
                        log.e(TAG).exception(e).message("Failed to copy album art bitmap")
                        null
                    }
                    
                    if (albumArtBitmap != null) {
                        pendingArtUpdate = albumArtExecutor.submit {
                            var smallScaled: Bitmap? = null
                            var scaled: Bitmap? = null
                            try {
                                // Send small 32x32 thumbnail first for instant feedback
                                smallScaled = Bitmap.createScaledBitmap(albumArtBitmap, 32, 32, true)
                                ByteArrayOutputStream().use { smallStream ->
                                    smallScaled.compress(Bitmap.CompressFormat.JPEG, 80, smallStream)
                                    val smallArtBytes = smallStream.toByteArray()
                                    val artData = MusicData()
                                    artData.albumArt = smallArtBytes
                                    artData.isPlaying = currentPlaying
                                    artData.timestamp = System.currentTimeMillis()
                                    service.send(RPCMessage(MusicAPI.ID, artData))
                                }
                                
                                // Then send full 128x128 image
                                scaled = Bitmap.createScaledBitmap(albumArtBitmap, 128, 128, true)
                                ByteArrayOutputStream().use { stream ->
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                                    val artBytes = stream.toByteArray()
                                    val artData = MusicData()
                                    artData.albumArt = artBytes
                                    artData.isPlaying = currentPlaying
                                    artData.timestamp = System.currentTimeMillis()
                                    service.send(RPCMessage(MusicAPI.ID, artData))
                                    log.d(TAG).message("Sent album art: ${artBytes.size} bytes")
                                }
                            } catch (e: Exception) {
                                log.e(TAG).exception(e).message("Failed to send album art")
                            } finally {
                                smallScaled?.recycle()
                                scaled?.recycle()
                                albumArtBitmap.recycle()
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MusicExtension"
        private const val SYNC_INTERVAL = 5000L // 5 seconds
    }
}
