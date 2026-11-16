package com.Johnny.wcx.features.items.beautify

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ListView
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 明确的顶栏、Tab 和列表底板；未知 Drawable 保留原生反馈，不作全局替换。 */
internal class MainSurfaceBackgrounds {
    private class Patch(var original: Drawable?, var installed: Drawable?, var rule: BackgroundRule)
    private val patches = WeakHashMap<View, Patch>()
    private val images = HashMap<String, Drawable?>()
    private var profile: ThemeProfile? = null
    var resolve: ((String) -> File?)? = null

    fun configure(value: ThemeProfile?) {
        if (profile === value) return
        clear()
        profile = value
    }

    fun apply(view: View) {
        val config = profile ?: return
        val slot = when {
            isTab(view) -> "chrome" to "tabBackground"
            view is ListView -> "lists" to "background"
            resourceName(view) in setOf("action_bar_container", "action_bar", "top_actionbar") -> "chrome" to "toolbarBackground"
            else -> return
        }
        val rule = config.background(slot.first, slot.second)
        val existing = patches[view]
        if (rule === BackgroundRule.Inherit) {
            restore(view)
            return
        }
        if (existing != null && view.background === existing.installed && existing.rule == rule) return
        val original = if (existing != null && view.background === existing.installed) existing.original
            else PageWallpaperUnderlay.originalBackground(view)
        if (original != null && original !is ColorDrawable && existing == null) return
        val drawable = when (rule) {
            BackgroundRule.Native -> original
            BackgroundRule.Transparent -> ColorDrawable(Color.TRANSPARENT)
            is BackgroundRule.Solid -> ColorDrawable(rule.argb)
            is BackgroundRule.Image -> {
                val file = resolve?.invoke(rule.asset) ?: return
                val key = file.absolutePath
                if (!images.containsKey(key)) images[key] = Drawable.createFromPath(key)
                images[key]?.constantState?.newDrawable()?.mutate()?.apply { alpha = (rule.opacity * 255).toInt() } ?: return
            }
            BackgroundRule.Inherit -> return
        }
        PageWallpaperUnderlay.setBackgroundPreservingPadding(view, drawable)
        patches[view] = Patch(original, drawable, rule)
        owners[view] = WeakReference(drawable ?: nativeNull)
    }

    /** Release only the native tab fill so the wallpaper underlay can own that surface. */
    fun releaseForWallpaper(view: View): Boolean {
        val patch = patches[view] ?: return false
        if (patch.rule !== BackgroundRule.Native) return false
        restore(view)
        return true
    }

    private fun restore(view: View) {
        val patch = patches.remove(view) ?: return
        if (view.background === patch.installed) PageWallpaperUnderlay.setBackgroundPreservingPadding(view, patch.original)
        owners.remove(view)
    }

    fun clear() {
        patches.keys.toList().forEach(::restore)
        images.clear()
    }

    companion object {
        private val nativeNull = ColorDrawable(Color.TRANSPARENT)
        private val owners = WeakHashMap<View, WeakReference<Drawable>>()
        fun owns(view: View): Boolean = owners[view]?.get()?.let { it === view.background || (it === nativeNull && view.background == null) } == true
        fun configured(profile: ThemeProfile?): Boolean = profile != null && slots.any { (section, key) ->
            profile.background(section, key) !== BackgroundRule.Inherit
        }
        private val slots = listOf("chrome" to "toolbarBackground", "chrome" to "tabBackground", "lists" to "background")
        private fun resourceName(view: View): String? = if (view.id > 0) runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() else null
        private fun isTab(view: View): Boolean {
            var type: Class<*>? = view.javaClass
            while (type != null) {
                if (type.name == "com.tencent.mm.ui.LauncherUIBottomTabView") return true
                type = type.superclass
            }
            return false
        }
    }
}
