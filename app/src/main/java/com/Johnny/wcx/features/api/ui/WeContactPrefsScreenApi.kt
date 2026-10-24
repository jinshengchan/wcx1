package com.Johnny.wcx.features.api.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.tencent.mm.ui.base.preference.MMPreference
import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "联系人页面扩展", categories = ["API"])
object WeContactPrefsScreenApi : ApiFeature() {

    interface IContactInfoProvider {
        fun getContactInfoItem(activity: Activity): List<PreferenceItem>
        fun onItemClick(activity: Activity, key: String): Boolean
    }

    data class PreferenceItem(
        val key: String,
        val title: String,
        val summary: String? = null,
        val position: Int = -1,
        /**
         * 锚点标题：非空时插到标题等于该值的那一项下一行，找不到则回落到 position。
         * 用于“地区下一行”这类相对位置，微信各页面的行顺序不固定时比写死 position 可靠。
         */
        val afterTitle: String? = null
    )

    private const val TAG = "WeContactPrefsScreenApi"

    private val providers = CopyOnWriteArrayList<IContactInfoProvider>()

    fun addProvider(provider: IContactInfoProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IContactInfoProvider) {
        providers.remove(provider)
    }

    private lateinit var prefConstructor: Constructor<*>
    private lateinit var prefKeyField: Field
    private lateinit var adapterField: Field
    private lateinit var addPreferenceMethod: Method
    private lateinit var setKeyMethod: Method
    private lateinit var setSummaryMethod: Method
    private lateinit var setTitleMethod: Method

    override fun onEnable() {
        initReflection()

        listOf(
            ContactInfoUI::class,
            ChatroomInfoUI::class
        ).forEach {
            it.reflekt().apply {
                firstMethod { name = "initView" }
                    .hookAfter {
                        val adapterInstance = adapterField.get(thisObject as Activity)
                        for (provider in providers) {
                            try {
                                val items = provider.getContactInfoItem(thisObject as Activity)
                                for (item in items) {
                                    val pref = prefConstructor.newInstance(thisObject as Context)
                                    setKeyMethod.invoke(pref, item.key)
                                    setTitleMethod.invoke(pref, item.title)
                                    item.summary?.let { summary -> setSummaryMethod.invoke(pref, summary) }
                                    val insertPos = resolveInsertPosition(adapterInstance, item)
                                    addPreferenceMethod.invoke(adapterInstance, pref, insertPos)
                                }
                            } catch (ex: Exception) {
                                WeLogger.e(
                                    TAG,
                                    "provider ${provider.javaClass.name} threw while providing contact info item",
                                    ex
                                )
                            }
                        }
                    }

                firstMethod {
                    name = "onPreferenceTreeClick"
                }.hookBefore {
                    val preference = args[1] ?: return@hookBefore
                    val key = prefKeyField.get(preference) as? String ?: return@hookBefore
                    for (provider in providers) {
                        try {
                            if (provider.onItemClick(thisObject as Activity, key)) {
                                // 仅当原方法返回 boolean 时才设置 result = true
                                if (method is java.lang.reflect.Method) {
                                    val returnType = (method as java.lang.reflect.Method).returnType
                                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                                        result = true
                                    }
                                }
                                return@hookBefore
                            }
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 带 afterTitle 的插入：先在 adapter 里找标题等于 afterTitle 的一项，插到它下一行；
     * 找不到则回落到 position（并记日志）。position 越界时钳到列表末尾。
     */
    private fun resolveInsertPosition(adapterInstance: Any, item: PreferenceItem): Int {
        val adapter = adapterInstance as? BaseAdapter ?: return item.position
        val count = runCatching { adapter.count }.getOrNull() ?: return item.position
        item.afterTitle?.let { anchor ->
            findPreferenceIndex(adapter, anchor)?.let { return (it + 1).coerceAtMost(count) }
            WeLogger.i(TAG, "anchor title '$anchor' not found, falling back to position ${item.position}")
        }
        return when {
            item.position < 0 -> count
            item.position > count -> count
            else -> item.position
        }
    }

    private fun findPreferenceIndex(adapter: BaseAdapter, title: String): Int? {
        val count = runCatching { adapter.count }.getOrNull() ?: return null
        for (i in 0 until count) {
            val pref = runCatching { adapter.getItem(i) }.getOrNull() ?: continue
            if (readPreferenceTitle(pref) == title) return i
        }
        return null
    }

    /** 读微信 Preference 的标题：优先 getTitle()，没有则读 title/mTitle 字段。 */
    private fun readPreferenceTitle(pref: Any): String? {
        return runCatching {
            val getter = pref.javaClass.methods.firstOrNull { it.name == "getTitle" && it.parameterCount == 0 }
            if (getter != null) return@runCatching (getter.invoke(pref) as? CharSequence)?.toString()
            var cls: Class<*>? = pref.javaClass
            while (cls != null) {
                val field = cls.declaredFields.firstOrNull {
                    (it.name == "title" || it.name == "mTitle") &&
                        (CharSequence::class.java.isAssignableFrom(it.type) || it.type == String::class.java)
                }
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching (field.get(pref) as? CharSequence)?.toString()
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()
    }

    private fun initReflection() {
        prefConstructor = Preference::class.reflekt()
            .firstConstructor {
                parameters(Context::class)
            }.self

        prefKeyField = Preference::class.reflekt()
            .firstField {
                type = String::class
                modifiers { !it.contains(Modifiers.FINAL) }
            }.self.makeAccessible()

        adapterField = MMPreference::class.reflekt()
            .firstField {
                modifiers { !it.contains(Modifiers.STATIC) }
                type { it isSubclassOf BaseAdapter::class }
            }.self.makeAccessible()

        addPreferenceMethod = adapterField.type.reflekt()
            .firstMethod {
                modifiers { !it.contains(Modifiers.FINAL) }
                parameters(Preference::class, Int::class)
            }.self

        setKeyMethod = Preference::class.reflekt()
            .firstMethod {
                parameters(String::class)
                returnType = Void.TYPE
            }.self

        val charSeqMethods = Preference::class.reflekt()
            .methods {
                parameters(CharSequence::class)
            }.map { it.self }

        setSummaryMethod = charSeqMethods.getOrElse(0) { error("setSummary method not found") }
        setTitleMethod = charSeqMethods.getOrElse(1) { error("setTitle method not found") }
    }
}
