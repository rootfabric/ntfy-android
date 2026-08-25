package io.heckel.ntfy.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.msg.DownloadManager
import io.heckel.ntfy.msg.DownloadType
import java.util.Collections
import java.util.WeakHashMap

/**
 * Turns the normal attachment icon into an inline play/stop control for audio.
 *
 * The existing DetailAdapter deliberately remains unchanged. The view resolves
 * the currently bound Notification through its RecyclerView ViewHolder, so it
 * also continues to work when rows are recycled.
 */
class VoicePlaybackImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var applyingPlaybackIcon = false
    private var lastAttachmentIconRes: Int = 0

    init {
        setOnClickListener {
            val notification = currentNotification() ?: return@setOnClickListener
            val attachment = notification.attachment ?: return@setOnClickListener
            if (!isVoice(notification)) return@setOnClickListener

            val contentUri = attachment.contentUri
            if (contentUri.isNullOrBlank()) {
                val expired = attachment.expires != null && attachment.expires < System.currentTimeMillis() / 1000
                if (expired) {
                    Toast.makeText(context, R.string.voice_playback_expired, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                VoicePlaybackController.requestAutoPlay(notification.id)
                DownloadManager.enqueue(context, notification.id, userAction = true, DownloadType.ATTACHMENT)
                Toast.makeText(context, R.string.voice_playback_downloading, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            VoicePlaybackController.toggle(context, notification)
        }
    }

    override fun setImageResource(resId: Int) {
        if (!applyingPlaybackIcon) {
            lastAttachmentIconRes = resId
        }
        super.setImageResource(resId)
        if (!applyingPlaybackIcon) {
            post { refreshVoiceState() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        VoicePlaybackController.register(this)
        post { refreshVoiceState() }
    }

    override fun onDetachedFromWindow() {
        VoicePlaybackController.unregister(this)
        super.onDetachedFromWindow()
    }

    internal fun refreshVoiceState() {
        val notification = currentNotification()
        if (notification == null || !isVoice(notification)) {
            isClickable = false
            isFocusable = false
            contentDescription = null
            if (lastAttachmentIconRes != 0) {
                setPlaybackIcon(lastAttachmentIconRes)
            }
            return
        }

        isClickable = true
        isFocusable = true

        val attachment = notification.attachment ?: return
        if (!attachment.contentUri.isNullOrBlank()) {
            VoicePlaybackController.maybeAutoPlay(context, notification)
        }

        val playing = VoicePlaybackController.isPlaying(notification.id)
        setPlaybackIcon(if (playing) R.drawable.ic_stop_gray_24dp else R.drawable.ic_play_arrow_gray_24dp)
        contentDescription = context.getString(
            if (playing) R.string.voice_playback_stop_description
            else R.string.voice_playback_play_description
        )
    }

    private fun setPlaybackIcon(resId: Int) {
        applyingPlaybackIcon = true
        try {
            super.setImageResource(resId)
        } finally {
            applyingPlaybackIcon = false
        }
    }

    private fun isVoice(notification: Notification): Boolean {
        val attachment = notification.attachment ?: return false
        if (attachment.type?.lowercase()?.startsWith("audio/") == true) return true

        val tags = notification.tags
            .split(',')
            .map { it.trim().lowercase() }
            .toSet()
        return "voice_message" in tags || "microphone" in tags
    }

    private fun currentNotification(): Notification? {
        var child: View = this
        while (true) {
            val parent = child.parent
            if (parent is RecyclerView) {
                val holder = runCatching { parent.getChildViewHolder(child) }.getOrNull() ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                val adapter = (holder.bindingAdapter as? DetailAdapter) ?: (parent.adapter as? DetailAdapter)
                return adapter?.get(position)
            }
            child = parent as? View ?: return null
        }
    }
}

/** One audio stream per chat. Starting another voice message stops the previous one. */
private object VoicePlaybackController {
    private val listeners = Collections.newSetFromMap(WeakHashMap<VoicePlaybackImageView, Boolean>())
    private var mediaPlayer: MediaPlayer? = null
    private var playingNotificationId: String? = null
    private var pendingAutoPlayId: String? = null

    fun register(view: VoicePlaybackImageView) {
        listeners.add(view)
    }

    fun unregister(view: VoicePlaybackImageView) {
        listeners.remove(view)
    }

    fun isPlaying(notificationId: String): Boolean = playingNotificationId == notificationId

    fun requestAutoPlay(notificationId: String) {
        pendingAutoPlayId = notificationId
        notifyViews()
    }

    fun maybeAutoPlay(context: Context, notification: Notification) {
        if (pendingAutoPlayId != notification.id) return
        if (notification.attachment?.contentUri.isNullOrBlank()) return
        pendingAutoPlayId = null
        play(context, notification)
    }

    fun toggle(context: Context, notification: Notification) {
        if (playingNotificationId == notification.id) {
            stop()
        } else {
            pendingAutoPlayId = null
            play(context, notification)
        }
    }

    private fun play(context: Context, notification: Notification) {
        val uriString = notification.attachment?.contentUri ?: return
        stop()

        val player = MediaPlayer()
        mediaPlayer = player
        playingNotificationId = notification.id
        notifyViews()

        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(context.applicationContext, Uri.parse(uriString))
            player.setOnPreparedListener { prepared ->
                if (mediaPlayer === prepared) {
                    prepared.start()
                    notifyViews()
                }
            }
            player.setOnCompletionListener { completed ->
                if (mediaPlayer === completed) stop()
            }
            player.setOnErrorListener { failed, _, _ ->
                if (mediaPlayer === failed) {
                    stop()
                    Toast.makeText(context.applicationContext, R.string.voice_playback_failed, Toast.LENGTH_LONG).show()
                }
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            if (mediaPlayer === player) stop()
            Toast.makeText(context.applicationContext, R.string.voice_playback_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun stop() {
        val player = mediaPlayer
        mediaPlayer = null
        playingNotificationId = null
        if (player != null) {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        notifyViews()
    }

    private fun notifyViews() {
        listeners.toList().forEach { view ->
            view.post { view.refreshVoiceState() }
        }
    }
}
