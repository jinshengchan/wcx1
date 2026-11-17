package com.Johnny.wcx.features.items.beautify

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.WeakHashMap

/** Tab 项以原生标签定位；不依赖子节点序号，不替换容器或触摸监听。 */
internal class MainTabAssets {
    private class Label(val index: Int, var original: CharSequence, var installed: String? = null)
    private class Icon(var original: Drawable?, var installed: Drawable? = null, var path: String? = null)
    private val labels = WeakHashMap<TextView, Label>()
    private val icons = WeakHashMap<ImageView, Icon>()
    private val decoded = object : LinkedHashMap<String, Drawable?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable?>?) = size > 8
    }
    var resolve: ((String) -> File?)? = null
    private var profile: ThemeProfile? = null

    fun configure(value: ThemeProfile?) {
        if (profile === value) return
        clear()
        profile = value
    }

    fun apply(label: TextView, selected: Boolean) {
        val config = profile ?: return
        val current = label.text.toString()
        val old = labels[label]
        val index = old?.index ?: nativeLabels.indexOfFirst { current in it }.takeIf { it >= 0 } ?: return
        val patch = old ?: Label(index, label.text).also { labels[label] = it }
        if (patch.installed != null && current != patch.installed) patch.original = label.text
        val desired = config.tabLabel(index)
        if (desired != null) {
            if (current != desired) label.text = desired
            patch.installed = desired
        } else {
            if (current == patch.installed) label.text = patch.original
            patch.installed = null
        }
        val image = itemIcon(label) ?: return
        val path = config.tabIcon(index, selected)
        val drawable = path?.let { resolve?.invoke(it) }?.let { file ->
            val key = file.absolutePath
            if (!decoded.containsKey(key)) decoded[key] = Drawable.createFromPath(key)
            decoded[key]
        }
        val icon = icons[image]
        if (drawable == null) {
            if (icon != null && image.drawable === icon.installed) image.setImageDrawable(icon.original)
            icons.remove(image)
            return
        }
        val value = icon ?: Icon(image.drawable).also { icons[image] = it }
        if (value.installed != null && image.drawable !== value.installed) value.original = image.drawable
        if (image.drawable === value.installed && value.path == path) return
        val installed = drawable.constantState?.newDrawable()?.mutate() ?: return
        image.setImageDrawable(installed)
        value.installed = installed
        value.path = path
    }

    fun ownsIcon(view: ImageView): Boolean = icons.containsKey(view)

    private fun itemIcon(label: TextView): ImageView? {
        var item = label.parent as? ViewGroup
        repeat(8) {
            val group = item ?: return null
            if (isTab(group)) return null
            val images = ArrayList<ImageView>(2)
            var visited = 0
            fun collect(view: View, depth: Int) {
                if (++visited > 32 || depth > 5 || images.size > 1) return
                if (view is ImageView) images.add(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i) ?: continue, depth + 1)
            }
            collect(group, 0)
            if (images.size == 1) return images.single()
            if (images.size > 1) return null
            item = group.parent as? ViewGroup
        }
        return null
    }

    fun clear() {
        labels.forEach { (view, patch) -> if (view.text.toString() == patch.installed) view.text = patch.original }
        icons.forEach { (view, patch) -> if (view.drawable === patch.installed) view.setImageDrawable(patch.original) }
        labels.clear()
        icons.clear()
        decoded.clear()
    }

    companion object {
        private val nativeLabels = listOf(setOf("微信", "WeChat"), setOf("通讯录", "Contacts"), setOf("发现", "Discover"), setOf("我", "Me"))
        fun configured(profile: ThemeProfile?): Boolean = profile != null && (0..3).any {
            profile.tabLabel(it) != null || profile.tabIcon(it, false) != null || profile.tabIcon(it, true) != null
        }
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
