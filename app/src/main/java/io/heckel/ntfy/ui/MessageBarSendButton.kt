package io.heckel.ntfy.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R

/**
 * Send button for the lightweight message bar.
 *
 * ntfy treats an empty publish as a valid trigger event and renders it as
 * "triggered". That behavior is useful for generic ntfy publishing, but it is
 * surprising in this chat-like UI. This button therefore refuses to become
 * enabled while the message field is blank and also guards performClick().
 *
 * We keep track of the enabled state requested by DetailActivity so a temporary
 * disable during an in-flight publish is preserved even if the user edits the
 * text before the request completes.
 */
class MessageBarSendButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.floatingActionButtonStyle
) : FloatingActionButton(context, attrs, defStyleAttr) {

    private var messageText: TextInputEditText? = null
    private var externallyEnabled: Boolean = true

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            syncEnabledState()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        messageText = rootView.findViewById(R.id.message_bar_text)
        messageText?.addTextChangedListener(textWatcher)
        syncEnabledState()
    }

    override fun onDetachedFromWindow() {
        messageText?.removeTextChangedListener(textWatcher)
        messageText = null
        super.onDetachedFromWindow()
    }

    override fun setEnabled(enabled: Boolean) {
        externallyEnabled = enabled
        syncEnabledState()
    }

    override fun performClick(): Boolean {
        if (!hasMessageContent()) return false
        return super.performClick()
    }

    private fun syncEnabledState() {
        super.setEnabled(externallyEnabled && hasMessageContent())
    }

    private fun hasMessageContent(): Boolean {
        return !messageText?.text.isNullOrBlank()
    }
}
