package io.heckel.ntfy.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Records a short AAC/M4A voice message and publishes it as an ntfy attachment.
 *
 * The view deliberately owns the complete voice-message flow so the existing
 * DetailActivity publishing code stays untouched. The current server and topic
 * are read from the activity intent, exactly like the rest of the detail view.
 */
class VoiceMessageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.imageButtonStyle
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAtMs: Long = 0L
    private var uploading = false

    init {
        setOnClickListener { onVoiceButtonClick() }
    }

    private fun onVoiceButtonClick() {
        if (uploading) return

        if (recorder != null) {
            stopAndSendRecording()
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission()
            return
        }

        startRecording()
    }

    private fun requestMicrophonePermission() {
        val activity = findActivity() ?: run {
            showToast(R.string.message_bar_voice_no_activity)
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE_RECORD_AUDIO
        )
        showToast(R.string.message_bar_voice_permission_requested)
    }

    private fun startRecording() {
        val file = File(context.cacheDir, "ntfy-voice-${System.currentTimeMillis()}.m4a")
        val newRecorder = MediaRecorder()

        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            newRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()

            recorder = newRecorder
            recordingFile = file
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            setRecordingUi(true)
            showToast(R.string.message_bar_voice_recording_started)
        } catch (e: Exception) {
            runCatching { newRecorder.release() }
            file.delete()
            showError(e)
        }
    }

    private fun stopAndSendRecording() {
        val elapsed = SystemClock.elapsedRealtime() - recordingStartedAtMs
        if (elapsed < MIN_RECORDING_MS) {
            cancelRecording()
            showToast(R.string.message_bar_voice_too_short)
            return
        }

        val currentRecorder = recorder ?: return
        val file = recordingFile
        recorder = null
        recordingFile = null
        setRecordingUi(false)

        val stoppedCleanly = try {
            currentRecorder.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            runCatching { currentRecorder.release() }
        }

        if (!stoppedCleanly || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            showToast(R.string.message_bar_voice_too_short)
            return
        }

        publishRecording(file)
    }

    private fun publishRecording(file: File) {
        val activity = findActivity() ?: run {
            file.delete()
            showToast(R.string.message_bar_voice_no_activity)
            return
        }
        val baseUrl = activity.intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL)
        val topic = activity.intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC)
        if (baseUrl.isNullOrBlank() || topic.isNullOrBlank()) {
            file.delete()
            showToast(R.string.message_bar_voice_missing_topic)
            return
        }

        uploading = true
        isEnabled = false

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repository = Repository.getInstance(context.applicationContext)
                val api = ApiService(context.applicationContext)
                val user = repository.getUser(baseUrl)
                val body = file.asRequestBody(VOICE_MEDIA_TYPE.toMediaType())

                api.publish(
                    baseUrl = baseUrl,
                    topic = topic,
                    user = user,
                    message = context.getString(R.string.message_bar_voice_default_message),
                    priority = 3,
                    tags = VOICE_TAGS,
                    body = body,
                    filename = file.name
                )

                withContext(Dispatchers.Main) {
                    showToast(R.string.message_bar_voice_sent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(e)
                }
            } finally {
                file.delete()
                withContext(Dispatchers.Main) {
                    uploading = false
                    isEnabled = true
                }
            }
        }
    }

    private fun cancelRecording() {
        val currentRecorder = recorder
        recorder = null
        val file = recordingFile
        recordingFile = null
        recordingStartedAtMs = 0L
        setRecordingUi(false)

        if (currentRecorder != null) {
            runCatching { currentRecorder.reset() }
            runCatching { currentRecorder.release() }
        }
        file?.delete()
    }

    private fun setRecordingUi(recording: Boolean) {
        setImageResource(if (recording) R.drawable.ic_stop_gray_24dp else R.drawable.ic_mic_gray_24dp)
        contentDescription = context.getString(
            if (recording) R.string.message_bar_voice_stop_button_description
            else R.string.message_bar_voice_button_description
        )
    }

    private fun findActivity(): AppCompatActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun showError(error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        Toast.makeText(
            context,
            context.getString(R.string.message_bar_voice_failed, detail),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDetachedFromWindow() {
        if (recorder != null) cancelRecording()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REQUEST_CODE_RECORD_AUDIO = 8401
        private const val AUDIO_BIT_RATE = 64_000
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val MIN_RECORDING_MS = 650L
        private const val VOICE_MEDIA_TYPE = "audio/mp4"
        private val VOICE_TAGS = listOf("microphone", "voice_message")
    }
}
