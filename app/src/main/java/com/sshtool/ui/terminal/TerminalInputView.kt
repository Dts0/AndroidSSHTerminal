package com.sshtool.ui.terminal

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.AppCompatEditText

/**
 * 更接近终端风格的输入桥接视图：
 * - 自身不保留用户输入内容
 * - 普通字符、回车、删除直接通过回调交给终端层
 */
class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    interface Callback {
        fun onTextInput(text: String)
        fun onEnter()
        fun onBackspace()
    }

    var callback: Callback? = null
    private var internalEdit = false

    init {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        setText("")
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        return super.onTextContextMenuItem(id)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                callback?.onEnter()
                clearInternalText()
                true
            }
            KeyEvent.KEYCODE_DEL -> {
                callback?.onBackspace()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DEL -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
        val base = super.onCreateInputConnection(outAttrs)
        return object : android.view.inputmethod.InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!internalEdit) {
                    val value = text?.toString().orEmpty()
                    if (value.isNotEmpty()) {
                        callback?.onTextInput(value)
                        clearInternalText()
                    }
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                return true
            }

            override fun finishComposingText(): Boolean {
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> {
                            callback?.onEnter()
                            clearInternalText()
                            return true
                        }
                        KeyEvent.KEYCODE_DEL -> {
                            callback?.onBackspace()
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    callback?.onBackspace()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    fun clearInternalText() {
        internalEdit = true
        text?.clear()
        internalEdit = false
    }
}
