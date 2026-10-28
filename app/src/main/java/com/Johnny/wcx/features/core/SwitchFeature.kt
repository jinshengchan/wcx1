package com.Johnny.wcx.features.core

import android.content.Context
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger

abstract class SwitchFeature : BaseFeature() {

    companion object {
        /** 带分类总开关的分类名（见 [Preferences.secretFriendMasterEnabled]）。 */
        const val SECRET_FRIEND_CATEGORY = "密友功能"
    }

    /** Whether this feature is governed by the 「文章去广告」 master switch. */
    open val underArticleAdMaster: Boolean = false

    /** Default state when the user has never toggled this feature. */
    protected open val defaultEnabled: Boolean = false

    /** Keep a preference-gated controller installed even while its effect is disabled. */
    protected open val keepControllerEnabled: Boolean = false

    /** Whether this feature should load in the current process. Defaults to the main process only. */
    protected open val shouldLoadInCurrentProcess: Boolean
        get() = TargetProcesses.isInMain

    /** Saved user choice, including the feature-specific default (also used by settings UI). */
    val configuredEnabled: Boolean
        get() = WePrefs.getBoolOrDef(name, defaultEnabled)

    /**
     * Effective on/off for features governed by the 「文章去广告」 master switch:
     * the feature's own saved choice AND the master switch. Per-event reads
     * use this so a master toggle from another process takes effect without reload.
     * Features outside the group just read their own saved choice.
     */
    protected val isMasterGroupEnabled: Boolean
        get() = configuredEnabled && (!underArticleAdMaster || Preferences.articleAdMasterEnabled)

    /** Whether the feature should be active at startup, given the cached preference. */
    protected open val shouldEnableOnStartup: Boolean
        get() = _isEnabled

    final override fun startup() {
        if (!shouldLoadInCurrentProcess) return
        // 「密友功能」分类有总开关：关掉总开关时整个分类都不加载（各功能自身开关状态保留）。
        if (SECRET_FRIEND_CATEGORY in categories && !Preferences.secretFriendMasterEnabled) return
        // Controllers must also start with the master off: existing host processes need
        // to observe later preference changes and acknowledge page cleanup.
        if (underArticleAdMaster && !Preferences.articleAdMasterEnabled && !keepControllerEnabled) return
        _isEnabled = configuredEnabled
        if (keepControllerEnabled || shouldEnableOnStartup) enable()
    }

    /** Cached user preference (desired state). Distinct from [isActive], the runtime truth. */
    @Suppress("PropertyName")
    protected var _isEnabled = false

    var isEnabled
        get() = _isEnabled
        set(value) {
            if (keepControllerEnabled) {
                _isEnabled = value
                // Idempotent, and also recovers an inactive controller without relying
                // on a cached preference transition. Off never discards pending cleanup.
                enable()
                return
            }
            if (_isEnabled == value) return
            _isEnabled = value
            if (value) {
                WeLogger.i("SwitchFeature", "enabling $displayName...")
                enable()
            } else {
                WeLogger.i("SwitchFeature", "disabling $displayName...")
                disable()
            }
        }

    private var toggleCompletionCallback: Runnable? = null

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    fun setToggleCompletionCallback(callback: Runnable) {
        toggleCompletionCallback = callback
    }

    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(name, newState)
        isEnabled = newState
        toggleCompletionCallback?.run()
    }
}

