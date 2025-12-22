package moe.fuqiuluo.mamu.floating.dialog

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.WindowManager
import moe.fuqiuluo.mamu.R

abstract class BaseDialog(
    protected val context: Context,
) {
    var onCancel: (() -> Unit)? = null

    protected val dialog = Dialog(context, R.style.CustomDarkDialogTheme)
    private var isDialogSetup = false

    init {
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
        )
    }

    abstract fun setupDialog()

    fun show() {
        // 确保 setupDialog() 只在第一次 show() 时调用，此时子类属性已完全初始化
        if (!isDialogSetup) {
            setupDialog()
            isDialogSetup = true
        }

        dialog.show()
    }

    open fun dismiss() {
        dialog.dismiss()
    }
}