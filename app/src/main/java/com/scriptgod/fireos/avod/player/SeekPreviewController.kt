package com.scriptgod.fireos.avod.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.scriptgod.fireos.avod.model.PlaybackInfo

/**
 * Manages the seek-preview thumbnail card and D-pad seek accumulation.
 */
@UnstableApi
class SeekPreviewController(
    private val cardSeekThumbnail: CardView,
    private val ivSeekThumbnail: ImageView,
    private val tvSeekTime: TextView,
    private val bifThumbnailProvider: BifThumbnailProvider,
    private val playbackInfoProvider: () -> PlaybackInfo?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideThumbnail() }
    private var previewPos: Long = -1L
    private var requestSequence = 0L

    fun showThumbnailAt(posMs: Long) {
        previewPos = posMs.coerceAtLeast(0L)
        val requestedPosition = previewPos
        val requestId = ++requestSequence
        val totalSec = requestedPosition / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        tvSeekTime.text = if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
        cardSeekThumbnail.visibility = View.VISIBLE

        val info = playbackInfoProvider() ?: return
        if (!info.hasThumbnails || !bifThumbnailProvider.hasBifEntries()) return

        bifThumbnailProvider.getThumbnailAt(requestedPosition, info.bifUrl) { bitmap ->
            if (requestId == requestSequence &&
                previewPos == requestedPosition &&
                cardSeekThumbnail.visibility == View.VISIBLE
            ) {
                ivSeekThumbnail.setImageBitmap(bitmap)
            }
        }
    }

    /** Called when the asynchronous BIF index finishes loading during an active scrub. */
    fun retryCurrentPreview() {
        if (previewPos >= 0L) showThumbnailAt(previewPos)
    }

    fun hideThumbnail() {
        requestSequence++
        previewPos = -1L
        cardSeekThumbnail.visibility = View.GONE
        ivSeekThumbnail.setImageDrawable(null)
    }

    fun handleDpadSeek(keyCode: Int, player: ExoPlayer?) {
        val duration = player?.duration?.takeIf { it > 0L } ?: Long.MAX_VALUE
        if (previewPos < 0L) previewPos = player?.currentPosition ?: 0L
        previewPos = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            minOf(previewPos + 10_000L, duration)
        } else {
            maxOf(previewPos - 10_000L, 0L)
        }
        val position = previewPos
        handler.removeCallbacks(hideRunnable)
        handler.post { showThumbnailAt(position) }
        handler.postDelayed(hideRunnable, 1_500L)
    }

    fun cancelPendingHide() {
        handler.removeCallbacksAndMessages(null)
        requestSequence++
    }
}
