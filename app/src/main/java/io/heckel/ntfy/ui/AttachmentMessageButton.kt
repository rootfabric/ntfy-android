package io.heckel.ntfy.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException

/**
 * Sends arbitrary files or a newly captured full-resolution photo to the
 * currently opened ntfy topic.
 *
 * The view owns its activity-result contracts so DetailActivity does not need
 * special picker/camera plumbing. A pending camera path is stored on the
 * activity intent so a camera round-trip also survives activity recreation.
 */
class AttachmentMessageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.imageButtonStyle
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    private val activity = findActivity()
    private var uploading = false

    private val filePickerLauncher: ActivityResultLauncher<Array<String>>? = activity?.activityResultRegistry?.register(
        FILE_PICKER_KEY,
        activity,
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val metadata = readUriMetadata(uri)
            publishUri(
                uri = uri,
                fileName = metadata.fileName,
                fileSize = metadata.fileSize,
                mimeType = metadata.mimeType,
                message = context.getString(R.string.message_bar_attachment_file_message, metadata.fileName),
                tags = FILE_TAGS
            )
        }
    }

    private val cameraLauncher: ActivityResultLauncher<Uri>? = activity?.activityResultRegistry?.register(
        CAMERA_KEY,
        activity,
        ActivityResultContracts.TakePicture()
    ) { success ->
        handleCameraResult(success)
    }

    init {
        setOnClickListener { showAttachmentMenu() }
    }

    private fun showAttachmentMenu() {
        if (uploading) return
        if (activity == null || filePickerLauncher == null || cameraLauncher == null) {
            showToast(R.string.message_bar_attachment_unavailable)
            return
        }

        PopupMenu(context, this).apply {
            menu.add(0, MENU_CHOOSE_FILE, 0, R.string.message_bar_attachment_choose_file)
            menu.add(0, MENU_TAKE_PHOTO, 1, R.string.message_bar_attachment_take_photo)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CHOOSE_FILE -> {
                        filePickerLauncher.launch(arrayOf("*/*"))
                        true
                    }
                    MENU_TAKE_PHOTO -> {
                        launchCamera()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun launchCamera() {
        val currentActivity = activity ?: return
        val launcher = cameraLauncher ?: return
        val file = File(currentActivity.cacheDir, "ntfy-photo-${System.currentTimeMillis()}.jpg")
        currentActivity.intent.putExtra(PENDING_CAMERA_FILE_EXTRA, file.absolutePath)

        try {
            val uri = FileProvider.getUriForFile(
                currentActivity,
                "${BuildConfig.APPLICATION_ID}.provider",
                file
            )
            launcher.launch(uri)
        } catch (e: Exception) {
            currentActivity.intent.removeExtra(PENDING_CAMERA_FILE_EXTRA)
            file.delete()
            showError(e)
        }
    }

    private fun handleCameraResult(success: Boolean) {
        val currentActivity = activity ?: return
        val path = currentActivity.intent.getStringExtra(PENDING_CAMERA_FILE_EXTRA)
        currentActivity.intent.removeExtra(PENDING_CAMERA_FILE_EXTRA)
        if (path.isNullOrBlank()) {
            if (success) showToast(R.string.message_bar_attachment_camera_failed)
            return
        }

        val file = File(path)
        if (!success) {
            file.delete()
            return
        }
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            showToast(R.string.message_bar_attachment_camera_failed)
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                currentActivity,
                "${BuildConfig.APPLICATION_ID}.provider",
                file
            )
            publishUri(
                uri = uri,
                fileName = file.name,
                fileSize = file.length(),
                mimeType = PHOTO_MEDIA_TYPE,
                message = context.getString(R.string.message_bar_attachment_photo_message),
                tags = PHOTO_TAGS,
                deleteAfterUpload = file
            )
        } catch (e: Exception) {
            file.delete()
            showError(e)
        }
    }

    private fun publishUri(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        message: String,
        tags: List<String>,
        deleteAfterUpload: File? = null
    ) {
        val currentActivity = activity ?: run {
            deleteAfterUpload?.delete()
            showToast(R.string.message_bar_attachment_unavailable)
            return
        }
        val baseUrl = currentActivity.intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL)
        val topic = currentActivity.intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC)
        if (baseUrl.isNullOrBlank() || topic.isNullOrBlank()) {
            deleteAfterUpload?.delete()
            showToast(R.string.message_bar_attachment_missing_topic)
            return
        }

        uploading = true
        isEnabled = false
        showToast(R.string.message_bar_attachment_sending)

        currentActivity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repository = Repository.getInstance(context.applicationContext)
                val api = ApiService(context.applicationContext)
                val user = repository.getUser(baseUrl)
                val body = createRequestBody(uri, fileSize, mimeType)

                api.publish(
                    baseUrl = baseUrl,
                    topic = topic,
                    user = user,
                    message = message,
                    priority = 3,
                    tags = tags,
                    body = body,
                    filename = fileName
                )

                withContext(Dispatchers.Main) {
                    showToast(R.string.message_bar_attachment_sent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(e)
                }
            } finally {
                deleteAfterUpload?.delete()
                withContext(Dispatchers.Main) {
                    uploading = false
                    isEnabled = true
                }
            }
        }
    }

    private fun createRequestBody(uri: Uri, fileSize: Long, mimeType: String): RequestBody {
        val resolver = context.contentResolver
        val mediaType = mimeType.toMediaTypeOrNull() ?: DEFAULT_MEDIA_TYPE.toMediaType()

        return object : RequestBody() {
            override fun contentType(): MediaType = mediaType
            override fun contentLength(): Long = if (fileSize >= 0L) fileSize else -1L

            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri)
                    ?: throw IOException("Unable to open attachment URI")
                input.use { stream -> sink.writeAll(stream.source()) }
            }
        }
    }

    private fun readUriMetadata(uri: Uri): AttachmentMetadata {
        val resolver = context.contentResolver
        var fileName = "file-${System.currentTimeMillis()}"
        var fileSize = -1L

        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { fileName = it }
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        }

        return AttachmentMetadata(
            fileName = fileName,
            fileSize = fileSize,
            mimeType = resolver.getType(uri) ?: DEFAULT_MEDIA_TYPE
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
        val message = when (error) {
            is ApiService.EntityTooLargeException -> context.getString(R.string.detail_test_message_error_too_large)
            is ApiService.UnauthorizedException -> context.getString(R.string.message_bar_attachment_unauthorized)
            else -> {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                context.getString(R.string.message_bar_attachment_failed, detail)
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private data class AttachmentMetadata(
        val fileName: String,
        val fileSize: Long,
        val mimeType: String
    )

    companion object {
        private const val FILE_PICKER_KEY = "ntfy_message_bar_file_picker"
        private const val CAMERA_KEY = "ntfy_message_bar_camera"
        private const val PENDING_CAMERA_FILE_EXTRA = "ntfy.pendingCameraFile"
        private const val MENU_CHOOSE_FILE = 1
        private const val MENU_TAKE_PHOTO = 2
        private const val DEFAULT_MEDIA_TYPE = "application/octet-stream"
        private const val PHOTO_MEDIA_TYPE = "image/jpeg"
        private val FILE_TAGS = listOf("paperclip", "attachment")
        private val PHOTO_TAGS = listOf("camera", "photo", "attachment")
    }
}
