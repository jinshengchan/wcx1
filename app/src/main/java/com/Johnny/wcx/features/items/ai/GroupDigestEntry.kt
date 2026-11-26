// Adapted from AARCNoa/zhiyou, GroupDigestEntry.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.activity.settings.ai.showGroupDigestPanel
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.features.items.beautify.HomeSidePanelFeature
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Article
import com.tencent.mm.ui.LauncherUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Launcher decor overlay, limited to the space immediately beside the search action. */
internal object GroupDigestEntry : Application.ActivityLifecycleCallbacks {
    private const val ENTRY_KEY = "ai_workbench_home_group_entry"
    var entryEnabled: Boolean
        get() = WePrefs.getBoolOrDef(ENTRY_KEY, true)
        set(value) { WePrefs.putBool(ENTRY_KEY, value) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var host: Activity? = null
    private var entry: ComposeView? = null

    fun register(app: Application) {
        mainHandler.post {
            if (application == null) {
                app.registerActivityLifecycleCallbacks(this)
                application = app
                LauncherUI.getInstance()?.takeIf { it.hasWindowFocus() }?.let(::onActivityResumed)
            }
        }
    }

    fun unregister() {
        mainHandler.post {
            application?.unregisterActivityLifecycleCallbacks(this)
            application = null
            detach()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name != "com.tencent.mm.ui.LauncherUI") return
        if (host === activity && entry?.parent != null) return
        detach()
        val decor = activity.window.decorView as? FrameLayout ?: return
        val view = ComposeView(activity)
        view.setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity))
        view.isSaveEnabled = false
        view.visibility = View.GONE
        view.setContent {
            var unread by remember { mutableIntStateOf(0) }
            LaunchedEffect(activity) {
                while (true) {
                    val visible = isVisible(activity, decor)
                    view.visibility = if (visible) View.VISIBLE else View.GONE
                    unread = if (visible) withContext(Dispatchers.IO) {
                        GroupDigestStore.accountId()?.let(GroupDigestStore::unreadTotal) ?: 0
                    } else 0
                    delay(500)
                }
            }
            InjectedUiTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = {
                            if (isVisible(activity, decor)) showGroupDigestPanel(activity)
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            MaterialSymbols.Outlined.Article,
                            contentDescription = if (unread == 0) "群聊摘要" else "群聊摘要，$unread 条未读",
                            tint = WxPalette.textPrimary(),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    if (unread > 0) {
                        Badge(
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp),
                            containerColor = WxPalette.Danger,
                            contentColor = Color.White,
                        ) {
                            Text(if (unread > 9) "9+" else unread.toString(), fontSize = 9.sp)
                        }
                    }
                }
            }
        }
        val density = activity.resources.displayMetrics.density
        val size = (48 * density).roundToInt()
        val statusId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusHeight = if (statusId == 0) 0 else activity.resources.getDimensionPixelSize(statusId)
        val toolbarAttrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        val toolbarHeight = try {
            toolbarAttrs.getDimensionPixelSize(0, (56 * density).roundToInt())
        } finally {
            toolbarAttrs.recycle()
        }
        val params = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
            topMargin = statusHeight + (toolbarHeight - size) / 2
            marginEnd = (96 * density).roundToInt()
        }
        decor.addView(view, params)
        host = activity
        entry = view
    }

    private fun isVisible(activity: Activity, decor: FrameLayout): Boolean =
        AiConfig.isMasterEnabled && entryEnabled && activity.hasWindowFocus() &&
            decor.findViewWithTag<View>("home_side_panel_overlay") == null &&
            HomeSidePanelFeature.isHomePageActive(activity)

    private fun detach() {
        entry?.let { (it.parent as? ViewGroup)?.removeView(it) }
        entry = null
        host = null
    }

    override fun onActivityPaused(activity: Activity) {
        if (host === activity) detach()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) detach()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
