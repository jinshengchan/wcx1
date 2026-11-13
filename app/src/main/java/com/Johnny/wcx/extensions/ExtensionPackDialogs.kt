package com.Johnny.wcx.extensions

import android.widget.Toast
import androidx.activity.ComponentActivity
import com.Johnny.wcx.activity.extensions.openExtensionsActivity

/** Dependency-prompt dialogs and extension screen entry shared by extension-pack consumers. */
object ExtensionPackDialogs {

    fun openExtensions(activity: ComponentActivity, pack: ExtensionPack?, autoDownload: Boolean) {
        runCatching { openExtensionsActivity(activity, pack, autoDownload) }
            .onFailure {
                Toast.makeText(activity, "无法打开扩展包页面", Toast.LENGTH_SHORT).show()
            }
    }
}
