// Adapted from AARCNoa/zhiyou, AiTools.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Schemas and decoding for model output. Changes are staged in memory before a slice is saved. */
object AiTools {

    const val UPDATE_PROFILE = "update_profile"
    const val PROPOSE_AUDIT = "propose_audit"
    const val UPDATE_STYLE = "update_style"

    /**
     * 审查提案的**单片上限**：审查按每片 150 条切，这几个数字都按片算。
     *
     * 另有 `MAX_TOTAL_*` 按整次审查算（14 片累计），防止一次审查把档案删空。
     */
    private const val MAX_DELETE_TAGS = 100
    private const val MAX_DELETE_MEMORIES = 150
    private const val MAX_MERGE_GROUPS = 12
    private const val MAX_MERGE_ITEMS = 20
    private const val MAX_MARK_IMPORTANT = 40
    private const val MAX_MERGE_TAGS = 40

    /** 合并标签的值上限。现有 value 均值 36.8 字，截到 20 会切掉一半内容。 */
    const val VALUE_MAX = 40

    /** 审查切片：每片多少条记忆。一片约 36k 输入，14 片约 7 分钟。 */
    const val AUDIT_SLICE_MAX = 150

    /** 一次审查累计的提案上限，按「2000 条压到 500 要删 1500」估算。 */
    private const val MAX_TOTAL_DELETE_MEMORIES = 1_500
    private const val MAX_TOTAL_MERGE_GROUPS = 200

    /** 建议理由的字数：它要显示在提案页的每条下面，太长读不完。 */
    private const val REASON_MAX = 30

    /**
     * 字段字数上限。**只在 schema 的字段说明里写一次**，代码按同一组常量截断，
     * 提示词里不再重复 —— 两边各写一遍一定会走偏（evidence 曾经是 40 / 60 两个值）。
     */
    const val TITLE_MAX = 20
    const val DETAIL_MAX = 200
    const val EVIDENCE_MAX = 40

    /**
     * 片内观察的字数上限。**这是护栏不是目标**：提示词里让模型按片长写
     * （500 条以下只写 3~4 条也行），这里只挡住明显跑飞的一条。
     */
    const val STYLE_SUMMARY_MAX = 240
    const val STYLE_ITEM_MAX = 40
    const val BEHAVIOR_ITEM_MAX = 50

    /** 单片最多收多少条现象 / 场景。提示词要 4~7 条，给到 8 是余量。 */
    private const val STYLE_ITEMS_MAX = 8

    /**
     * 「关键事件」的判定标准。
     *
     * 也只写在工具层：`memories[].important`（整理时标）与 `propose_audit.mark_important`
     * （审查补标）共用这一份，提示词里不再重复。schema 是字段语义的唯一来源。
     */
    const val IMPORTANT_CRITERIA =
        "第一次（见面/约会/通话）、争吵与和解、明显情绪起伏、承诺、关系转折、重大决定、" +
            "家庭或健康变故、金钱往来"

    const val MAX_MEMORIES = 2_000
    const val MAX_TAGS = 200

    private val json = Json

    fun forInit(withStyle: Boolean): JsonArray = JsonArray(listOf(updateProfileTool(withStyle)))
    fun forAuditTags(): JsonArray = JsonArray(listOf(proposeAuditTool(withTags = true, withMemories = false)))
    fun forAuditMemories(): JsonArray = JsonArray(listOf(proposeAuditTool(withTags = false, withMemories = true)))
    fun forStyle(): JsonArray = JsonArray(listOf(updateStyleTool()))

    private fun updateProfileTool(withStyle: Boolean = false): JsonObject = tool(
        name = UPDATE_PROFILE,
        description = buildString {
            append("一次性写入标签与记忆")
            if (withStyle) append("，以及用户本人的说话方式")
            append("。\n")
            // 这里**不写条数**：初始化不列现有事件（[AiPrompts.initYearUser] 只给标签），
            // 模型看不到档案里已有多少条记忆，写「2000 条封顶」它既没法据此调整，还会读成
            // 「名额还很宽」。数量信息只在它真能看到的地方给（标签的 `tagRule`）。
            append("- 标签是对方的画像（不是聊天摘要），同 category+key 覆盖旧值；")
            append("同一维度只用一个键，值变了用原键覆盖。\n")
            append("- 记忆是双方之间的一件事（不是一句话），同 occurred_at+title 覆盖旧条目；")
            append("琐碎闲聊合并成一条，不要把每句话单独记。\n")
            append("- 全部内容用一次调用写完；没有新信息时传入空的 tags 和 memories 数组。")
            if (withStyle) {
                append("\n- style 是**用户本人**的说话方式，**必须写**，和标签在同一层一起交上来。")
                append("只记看得见的现象，不写人格结论（「嘴硬心软」「很讲义气」一律不收）。")
            }
        },
        properties = buildJsonObject {
            put("tags", tagsArrayProp())
            put("memories", memoriesArrayProp())
            if (withStyle) put("style", styleObjectProp())
        },
        required = arrayOf("tags", "memories"),
    )

    /**
     * `update_profile.style` 的结构。
     *
     * 与图片段同名工具（原来的 `note_style`）合并成字段：两个工具在同一响应里时，
     * 模型经常只调第一个。并成一个调用后，「写三段内容」是它熟悉的形态。
     */
    private fun styleObjectProp(): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", "用户本人（「我」这一侧）的说话方式；只记现象，不下人格结论。")
        put("properties", buildJsonObject {
            put(
                "summary",
                strProp(
                    "整体怎么说话、有哪几种状态，$STYLE_SUMMARY_MAX 字以内。" +
                        "写现象（「话少，回得快，很少主动问」），不写评价。"
                ),
            )
            put(
                "style",
                buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "4~7 条语言现象，每条 $STYLE_ITEM_MAX 字以内：句长、拆条、标点、表情、" +
                            "口头禅、情绪怎么表现、被追问时怎么变。每条必须可数可验证" +
                            "（「单条基本不超过 12 字」可以，「文笔好」不行）。",
                    )
                    put("items", buildJsonObject { put("type", "string") })
                },
            )
            put(
                "behavior",
                buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "4~7 条「什么场景 → 他做了什么」，每条 $BEHAVIOR_ITEM_MAX 字以内：" +
                            "对方求助 / 抱怨 / 分享 / 冷淡 / 邀约、被追问、要拒绝时分别怎么做。" +
                            "必须是行为（「对方抱怨工作 → 先跟着骂两句，再给一个具体办法」可以）。",
                    )
                    put("items", buildJsonObject { put("type", "string") })
                },
            )
        })
    }

    /** 汇总的唯一出口。只写 summary：`basedUntil` 由代码从观察窗推出来。 */
    private fun updateStyleTool(): JsonObject = tool(
        name = UPDATE_STYLE,
        description = "把给出的观察合并成一份「用户本人的说话方式」侧写。只调用一次。",
        properties = buildJsonObject {
            put(
                "summary",
                strProp(
                    "侧写正文，分节文本：【整体】【消息形态】【口头禅】【情绪】【压力下】" +
                        "【场景反应】【边界】【梗与称呼】。每一条都要能改变一句话的写法。"
                ),
            )
        },
    )


    /**
     * 审查提案工具：**只提交建议，不修改档案**。
     *
     * 两个片共用一个工具名，schema 按片裁剪：
     * - 标签片：`merge_tags` + `delete_tags`；
     * - 记忆片：`delete_memories` + `merge_memories` + `mark_important`。
     *
     * 审查场景刻意不下发 `update_profile` —— 「清理前必须让用户确认」不能靠提示词约束，
     * 必须让模型在能力上就删不掉东西。
     */
    private fun proposeAuditTool(withTags: Boolean, withMemories: Boolean): JsonObject = tool(
        name = PROPOSE_AUDIT,
        description = "提交清理建议。只写建议、不修改档案，用户确认后才执行。\n" +
            "没有需要处理的就**不要调用**。条目用档案里的序号 `#N` 指定，" +
            "序号不存在会返回 missing。",
        properties = buildJsonObject {
            if (withTags) {
                put(
                    "merge_tags",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "建议合并的标签组（最多 $MAX_MERGE_TAGS 组）。同一维度被拆成多个键时用，"
                                + "合并后只留一条。",
                        )
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("category", strProp("合并后的分类"))
                                put("key", strProp("合并后的键，用稳定维度名，不带作品或日期"))
                                put(
                                    "value",
                                    strProp(
                                        "合并后的值，$VALUE_MAX 字以内。冲突的（换工作、搬家）只写最新结论；" +
                                            "不冲突的（喜欢的游戏）全部并列写",
                                    ),
                                )
                                put(
                                    "keys",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("description", "要合并掉的原键，格式「分类/键」，至少 2 个")
                                        put("items", buildJsonObject { put("type", "string") })
                                    },
                                )
                                put("reason", strProp("为什么合并，写具体依据，最多 $REASON_MAX 字"))
                            })
                            put("required", strArray("category", "key", "value", "keys", "reason"))
                        })
                    },
                )
                put(
                    "delete_tags",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "建议删除的标签（最多 $MAX_DELETE_TAGS 条）")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("category", strProp("分类，与档案一致"))
                                put("key", strProp("键，与档案一致"))
                                put("reason", strProp("具体依据，最多 $REASON_MAX 字；不要只写「重复」「不重要」"))
                            })
                            put("required", strArray("category", "key", "reason"))
                        })
                    },
                )
            }
            if (withMemories) {
                put(
                    "delete_memories",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "建议删除的事件（最多 $MAX_DELETE_MEMORIES 条）")
                        put("items", numberRefSchema())
                    },
                )
                put(
                    "merge_memories",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "建议合并的事件组（最多 $MAX_MERGE_GROUPS 组，每组 2~$MAX_MERGE_ITEMS 条）。",
                        )
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("title", strProp("合并后的标题，例如「日常闲聊」，$TITLE_MAX 字以内"))
                                put(
                                    "detail",
                                    strProp(
                                        "合并后的正文，$DETAIL_MAX 字以内。写清时间、地点、做了什么、结果、承诺",
                                    ),
                                )
                                put("numbers", intArrayProp("被合并的条目序号（#N），每组 2~$MAX_MERGE_ITEMS 条"))
                                put("reason", strProp("为什么合并，写具体依据，最多 $REASON_MAX 字"))
                            })
                            put("required", strArray("title", "numbers", "reason"))
                        })
                    },
                )
                put(
                    "mark_important",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "建议标记为关键事件的条目（最多 $MAX_MARK_IMPORTANT 条）。" +
                                "够重要但还没标的，在这里补上。判定标准：$IMPORTANT_CRITERIA。",
                        )
                        put("items", numberRefSchema())
                    },
                )
            }
            put("summary", strProp("一句话总结发现了什么，60 字以内"))
        },
    )

    /** 提案里引用一条记忆的最小结构：序号 + 理由。 */
    private fun numberRefSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("number", intProp("记忆序号（档案里的 #N）"))
            put("reason", strProp("具体依据，最多 $REASON_MAX 字；不要只写「不重要」"))
        })
        put("required", strArray("number", "reason"))
    }

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        vararg required: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    // 不写 required 时模型会漏字段（曾把 occurred_at 写成 occurredAt），
                    // 加在 schema 里比在 system 里重复一遍格式更有效。
                    if (required.isNotEmpty()) put("required", strArray(*required))
                })
            })
        }

    private fun tagsArrayProp(): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "标签数组")
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "属性分类")
                    put(
                        "enum",
                        strArray(
                            "基础", "关系", "兴趣", "家庭", "性格", "作息", "健康", "经济", "自定义",
                        ),
                    )
                })
                put("key", strProp("属性名，同一属性始终用同一个键"))
                put(
                    "value",
                    strProp(
                        "具体值（「骑行」而不是「运动」），不写某一次发生的事" +
                            "（日期 + 金额 + 经过，那是记忆）；生日这类固定日期照常写"
                    ),
                )
                put("confidence", numberProp("0 到 1：明确说过 0.9、多次提到 0.8、间接推断 0.6；低于 0.5 丢弃"))
                put("evidence", strProp("原文片段，最多 $EVIDENCE_MAX 字"))
            })
            put("required", strArray("category", "key", "value"))
        })
    }

    private fun memoriesArrayProp(): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "记忆数组")
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                // 字段名必须写进 schema：只给 `type: object` 时模型会自己起名（实测填 occurredAt），
                // 代码按 occurred_at 读就读不到，最后全变成「未知日期」。
                put(
                    "occurred_at",
                    strProp("记录行首的时间，精确到分钟：yyyy-MM-dd HH:mm；只有日期就写 yyyy-MM-dd"),
                )
                put("title", strProp("一件事的标题，$TITLE_MAX 字以内"))
                put("detail", strProp("写清起因、经过、结果、还没做完的部分，$DETAIL_MAX 字以内"))
                put(
                    "important",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "是否关键事件，逐条判断：$IMPORTANT_CRITERIA；日常闲聊一律 false。",
                        )
                    },
                )
            })
            put("required", strArray("occurred_at", "title", "detail"))
        })
    }

    private fun strArray(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun intProp(desc: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", desc)
    }

    /** 整数数组（记忆序号列表）。 */
    private fun intArrayProp(desc: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", desc)
        put("items", buildJsonObject { put("type", "integer") })
    }

    private fun strProp(desc: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", desc)
    }

    private fun numberProp(desc: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", desc)
    }

    internal fun applyProfileCall(
        profile: AiProfile,
        call: AiConfig.ToolCall,
        yearHint: Int,
        endSec: Long,
        styleWindow: StyleWindow?,
    ): AiProfile? {
        if (call.name != UPDATE_PROFILE) return null
        val args = parseObject(call.arguments) ?: return null
        if (args["tags"] !is JsonArray || args["memories"] !is JsonArray) return null
        return updateProfile(profile, args, yearHint, endSec, styleWindow)
    }

    internal fun applyStyleCall(profile: AiProfile, call: AiConfig.ToolCall): AiProfile? {
        if (call.name != UPDATE_STYLE) return null
        val summary = parseObject(call.arguments)?.str("summary").orEmpty().trim().take(5_000)
        if (summary.isEmpty() || profile.style.userEdited) return null
        val latest = profile.styleNotes.maxOfOrNull { it.toSec } ?: 0L
        return profile.copy(
            style = profile.style.copy(
                summary = summary,
                basedUntil = AiPrompts.monthOf(latest - 1).ifBlank { profile.style.basedUntil },
                insufficient = false,
            ),
        )
    }

    /** 提案里的一条建议：解析后的真实条目 + 模型给的理由。 */
    private class ResolvedMemory(val entry: MemoryEntry, val reason: String)

    internal fun proposeAudit(
        profile: AiProfile,
        call: AiConfig.ToolCall,
        memoryNumbers: Set<Int>?,
    ): AiProfile? {
        if (call.name != PROPOSE_AUDIT) return null
        val args = parseObject(call.arguments) ?: return null
        val fields = if (memoryNumbers == null) listOf("merge_tags", "delete_tags")
        else listOf("merge_memories", "delete_memories", "mark_important")
        if (fields.any { key -> args[key]?.let { it !is JsonArray } == true }) return null
        val missing = mutableListOf<String>()
        var droppedGroups = 0
        var droppedTagGroups = 0
        // 序号 → 条目：整批建议共用同一份映射，保证「模型看到的 #N」与这里解析的完全一致。
        val byNumber = if (memoryNumbers == null) emptyMap()
        else AiMemoryIndex.byNumber(profile.memories).filterKeys { it in memoryNumbers }
        // 标签合并组按档案预校验：模型抄回来的键可能拼错或带空白，而 `apply` 里 targets < 2
        // 会整组跳过 —— 这里不挡掉，提案页就会显示「合并 3 组」，执行时却一组都落不下去。
        val existingTagIds = profile.tags.mapTo(HashSet()) { TagRef.id(it.category, it.key) }

        // ── 标签：合并组先解析，delete 里命中合并目标的不单独处理 ──
        val mergeTags = mutableListOf<AuditTagGroup>()
        val tagGroups = if (memoryNumbers == null) args.array("merge_tags") else emptyList()
        tagGroups.forEach { element ->
            val category = element.str("category").orEmpty().trim()
            val key = element.str("key").orEmpty().trim()
            if (category.isEmpty() || key.isEmpty()) {
                droppedTagGroups++
                return@forEach
            }
            // 只留档案里真实存在的键。`apply` 会把「目标键已在档案里」也算进被合并的一方，
            // 所以校验口径必须一致，否则「目标键 + 1 个旧键」这种能落地的 2 条会被误丢。
            val refs = element.stringArray("keys").filter { ref ->
                TagRef.parse(ref)?.let { (refCategory, refKey) ->
                    TagRef.id(refCategory, refKey) in existingTagIds
                } == true
            }
            val targets = refs.mapNotNull { TagRef.parse(it) }
                .mapTo(HashSet()) { TagRef.id(it.first, it.second) } + TagRef.id(category, key)
            if (targets.count { it in existingTagIds } < 2) {
                droppedTagGroups++
                return@forEach
            }
            mergeTags += AuditTagGroup(
                category = category,
                key = key,
                value = element.str("value").orEmpty().trim().take(VALUE_MAX),
                keys = refs.take(MAX_MERGE_ITEMS),
                reason = element.str("reason").orEmpty().trim().take(REASON_MAX),
            )
        }
        val tagDeletions = if (memoryNumbers == null) args.array("delete_tags") else emptyList()
        val (deleteTags, tagMissing) = resolveTags(profile, tagDeletions)
        missing += tagMissing

        val (markImportant, markMissing) = resolveByNumber(byNumber, args.array("mark_important"))
        missing += markMissing

        // 合并组必须先解析：同一批建议里「删 A」和「合并 A、B」并存时，A 只能二选一。
        // 合并保留内容、删除丢弃内容，所以合并优先，删除列表里把命中合并的条目剔掉 ——
        // 否则执行时先删后合并，组内不足 2 条会被静默跳过，用户勾了却没生效。
        val groups = mutableListOf<AuditMergeGroup>()
        val mergedIds = HashSet<String>()
        val memoryGroups = if (memoryNumbers != null) args.array("merge_memories") else emptyList()
        memoryGroups.forEach { element ->
            // 合并组只给序号，日期取组内最早一条：让模型再写一遍日期只会多一个出错点。
            val (items, itemMissing) = resolveNumbers(byNumber, element.intArray("numbers"))
            missing += itemMissing
            if (items.size < 2) {
                droppedGroups++
                return@forEach
            }
            val kept = items.take(MAX_MERGE_ITEMS)
            val date = kept.minByOrNull { AiDates.sortKey(it.occurredAt) }?.occurredAt
                ?: AiDates.UNKNOWN
            kept.forEach { mergedIds += it.id }
            groups += AuditMergeGroup(
                occurredAt = date,
                title = element.str("title").orEmpty().trim().ifBlank { "日常闲聊" }.take(TITLE_MAX),
                detail = element.str("detail").orEmpty().trim().take(DETAIL_MAX),
                items = kept.map { AuditMemoryRef(it.occurredAt, it.title) },
                reason = element.str("reason").orEmpty().trim().take(REASON_MAX),
            )
        }

        val (deleteMemories, memoryMissing) = resolveByNumber(byNumber, args.array("delete_memories"))
        missing += memoryMissing
        val effectiveDelete = deleteMemories.filterNot { it.entry.id in mergedIds }

        val plan = AiAuditPlan(
            createdAt = System.currentTimeMillis(),
            mergeTags = mergeTags.take(MAX_MERGE_TAGS),
            deleteTags = deleteTags.take(MAX_DELETE_TAGS),
            deleteMemories = effectiveDelete.take(MAX_DELETE_MEMORIES)
                .map { AuditMemoryRef(it.entry.occurredAt, it.entry.title, it.reason) },
            mergeGroups = groups.take(MAX_MERGE_GROUPS),
            markImportant = markImportant.take(MAX_MARK_IMPORTANT)
                .map { AuditMemoryRef(it.entry.occurredAt, it.entry.title, it.reason) },
            note = args.str("summary").orEmpty().trim().take(60),
        )

        if (plan.isEmpty) {
            return profile.takeIf { missing.isEmpty() && droppedGroups + droppedTagGroups == 0 }
        }

        // ── 累加：14 片各提交一次，后一片不能覆盖前一片 ──
        val existing = profile.pendingAudit
        val merged = if (existing == null) plan else existing.copy(
            mergeTags = (existing.mergeTags + plan.mergeTags)
                .distinctBy { TagRef.id(it.category, it.key) },
            deleteTags = (existing.deleteTags + plan.deleteTags)
                .distinctBy { TagRef.id(it.category, it.key) },
            deleteMemories = (existing.deleteMemories + plan.deleteMemories)
                .distinctBy { it.occurredAt + "\u0000" + it.title },
            mergeGroups = (existing.mergeGroups + plan.mergeGroups)
                .distinctBy { g -> g.items.map { it.occurredAt + "\u0000" + it.title }.sorted() },
            markImportant = (existing.markImportant + plan.markImportant)
                .distinctBy { it.occurredAt + "\u0000" + it.title },
            // 14 片的 summary 拼起来没有意义，保留第一次非空的。
            note = existing.note.ifBlank { plan.note },
        )

        // 超限只丢超出的那一类，其余照收：整片拒绝会让后面几片的合并与标记一起白跑。
        val overDelete = merged.deleteMemories.size > MAX_TOTAL_DELETE_MEMORIES
        val overGroups = merged.mergeGroups.size > MAX_TOTAL_MERGE_GROUPS
        val next = merged.copy(
            deleteMemories = if (overDelete) existing?.deleteMemories.orEmpty() else merged.deleteMemories,
            mergeGroups = if (overGroups) existing?.mergeGroups.orEmpty() else merged.mergeGroups,
        )
        return profile.copy(pendingAudit = next)
    }

    /** `[{number, reason}]` → 条目 + 理由；序号不存在的进 missing。 */
    private fun resolveByNumber(
        byNumber: Map<Int, MemoryEntry>,
        elements: List<JsonObject>,
    ): Pair<List<ResolvedMemory>, List<String>> {
        val hits = mutableListOf<ResolvedMemory>()
        val missing = mutableListOf<String>()
        val seen = HashSet<String>()
        elements.forEach { element ->
            val number = element.int("number") ?: return@forEach
            val entry = byNumber[number]
            if (entry == null) {
                missing += "#$number"
            } else if (seen.add(entry.id)) {
                hits += ResolvedMemory(entry, element.str("reason").orEmpty().trim().take(REASON_MAX))
            }
        }
        return hits to missing
    }

    /** `[3, 4, 5]` → 条目（合并组用，没有逐条理由）。越界序号进 missing。 */
    private fun resolveNumbers(
        byNumber: Map<Int, MemoryEntry>,
        numbers: List<Int>,
    ): Pair<List<MemoryEntry>, List<String>> {
        val missing = numbers.filterNot { it in byNumber }.map { "#$it" }
        val hits = numbers.mapNotNull { byNumber[it] }.distinctBy { it.id }
        return hits to missing
    }

    private fun resolveTags(
        profile: AiProfile,
        elements: List<JsonObject>,
    ): Pair<List<AuditTagRef>, List<String>> {
        val hits = mutableListOf<AuditTagRef>()
        val missing = mutableListOf<String>()
        val seen = HashSet<String>()
        elements.forEach { element ->
            val category = element.str("category").orEmpty().trim()
            val key = element.str("key").orEmpty().trim()
            if (category.isEmpty() || key.isEmpty()) return@forEach
            val matched = profile.tags.firstOrNull { it.category == category && it.key == key }
            if (matched == null) {
                missing += "$category/$key"
            } else if (seen.add(matched.id)) {
                hits += AuditTagRef(
                    category = matched.category,
                    key = matched.key,
                    reason = element.str("reason").orEmpty().trim().take(REASON_MAX),
                )
            }
        }
        return hits to missing
    }

    private fun updateProfile(
        profile: AiProfile,
        args: JsonObject,
        yearHint: Int,
        endSec: Long,
        window: StyleWindow?,
    ): AiProfile {
        val tagItems = args.array("tags")
        val memoryItems = args.array("memories")

        // ── 标签：同 category+key 覆盖 ──
        val tags = LinkedHashMap<String, ProfileTag>()
        profile.tags.forEach { tags[it.id] = it }

        val fallbackStamp = endSec - 1
        tagItems.forEach { element ->
            val category = element.str("category").orEmpty().trim()
            val key = element.str("key").orEmpty().trim()
            val value = element.str("value").orEmpty().trim()
            if (category.isEmpty() || key.isEmpty() || value.isEmpty()) return@forEach
            val confidence = element.float("confidence") ?: 0.6f
            if (!confidence.isFinite() || confidence < 0.5f) return@forEach
            val evidence = element.str("evidence").orEmpty().trim()
            // 值没变说明没有新证据：沿用旧时间，否则每次增量都会把所有标签刷成今天，
            // 审查的「这条超过一年没更新」就永远不成立。
            val id = category + "\u0000" + key
            val old = tags[id]
            if (old != null && old.updatedAt > fallbackStamp) return@forEach
            val stamp = if (old != null && old.value == value) {
                old.updatedAt.takeIf { it > 0 } ?: fallbackStamp
            } else {
                fallbackStamp
            }
            val tag = ProfileTag(
                category = category,
                key = key,
                value = value.take(VALUE_MAX),
                confidence = confidence.coerceIn(0f, 1f),
                evidence = evidence.take(EVIDENCE_MAX),
                updatedAt = stamp,
            )
            tags[tag.id] = tag
        }

        // ── 记忆：同 occurred_at+title 覆盖 ──
        val memories = LinkedHashMap<String, MemoryEntry>()
        profile.memories.forEach { memories[it.id] = it }

        val now = System.currentTimeMillis()
        memoryItems.forEach { element ->
            val rawDate = element.str("occurred_at").orEmpty()
            val title = element.str("title").orEmpty().trim().take(TITLE_MAX)
            if (title.isEmpty()) return@forEach
            val detail = element.str("detail").orEmpty().trim().take(DETAIL_MAX)
            val occurredAt = AiDates.normalize(rawDate) ?: yearHint.toString()
            val id = memoryId(occurredAt, title)
            // 模型这次没写 important 时沿用旧值：初始化会给同一件事重写一次，
            // 不能因为漏字段就把上次标好的关键事件冲掉。
            val important = element.bool("important") ?: memories[id]?.important ?: false
            val entry = MemoryEntry(
                id = id,
                occurredAt = occurredAt,
                title = title,
                detail = detail,
                createdAt = now,
                important = important,
            )
            memories[entry.id] = entry
        }

        // 总量上限：关键事件优先保留，其余按日期留最新的。
        // 只按日期留的话，「第一次见面」「吵过一次」这些最旧的事件会最先被新事件挤掉。
        val memoryList = AiProfileLimits.memories(memories.values)
        val tagList = AiProfileLimits.tags(tags.values)

        // ── style：只在自己的片里记，增量片不记 ──
        // 时间窗由代码补（[StyleWindow]），同片重跑按 `period` 覆盖而不是追加 ——
        // 否则「重新整理」跑两遍会让同样的现象出现两次，汇总时被当成更高频。
        var styleNote: StyleNote? = null
        if (window != null) {
            val styleArgs = args.obj("style")
            val summary = styleArgs?.str("summary").orEmpty().trim().take(STYLE_SUMMARY_MAX)
            val styleItems = styleArgs?.stringArray("style").orEmpty()
                .map { it.take(STYLE_ITEM_MAX) }
                .filter { it.isNotEmpty() }
                .take(STYLE_ITEMS_MAX)
            val behavior = styleArgs?.stringArray("behavior").orEmpty()
                .map { it.take(BEHAVIOR_ITEM_MAX) }
                .filter { it.isNotEmpty() }
                .take(STYLE_ITEMS_MAX)
            if (summary.isNotEmpty() || styleItems.isNotEmpty() || behavior.isNotEmpty()) {
                styleNote = StyleNote(
                    period = window.period,
                    fromSec = window.fromSec,
                    toSec = window.toSec,
                    summary = summary,
                    style = styleItems,
                    behavior = behavior,
                )
            }
        }

        return profile.copy(
            tags = tagList,
            memories = memoryList,
            styleNotes = if (styleNote == null) profile.styleNotes
            else profile.styleNotes.filterNot { it.fromSec == window?.fromSec } + styleNote,
        )
    }

    /** `sha1(occurredAt + title)` 前 16 位：同一件事重复写不会产生第二条。 */
    fun memoryId(occurredAt: String, title: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((occurredAt + "\u0000" + title).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun parseObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.intArray(key: String): List<Int> = runCatching {
        this[key]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
    }.getOrElse { emptyList() }

    /** 字符串数组（观察里的现象 / 场景）：空白项直接丢掉，不让它们占条数。 */
    private fun JsonObject.stringArray(key: String): List<String> = runCatching {
        this[key]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }.getOrElse { emptyList() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

    private fun JsonObject.array(key: String): List<JsonObject> = runCatching {
        this[key]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
    }.getOrElse { emptyList() }

    /** 单个嵌套对象（`update_profile.style`）。缺失或类型不对都返回 null，不抛。 */
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
