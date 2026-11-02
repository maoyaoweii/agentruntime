package io.github.ccch1mneyyy.dsh_app.phone

/**
 * 多步批量执行：把「点搜索框 → 输入关键词 → 点搜索」这类确定性动作合成一轮下发。
 *
 * 移植自 Mika 的 `phone_run` / `do_steps`，但按 dsh-go 的执行模型重写：
 *
 *  - dsh-go 的单步动作都要过 [SafetyGate]（发送、支付、密码框会弹确认），批量不能
 *    绕开它 —— 每一步仍然走原来的 `act()`，所以安全策略照旧逐条生效。
 *  - 每一步之后拿新屏幕的指纹做**步间校验**：如果某步预期会跳转，但屏幕没变，
 *    或者屏幕变得完全认不出来（切到别的 App），立刻停下并把现场交回给模型。
 *    这样批量不会在错误页面上继续盲点。
 *  - 任一步出错就停，不做「忽略错误继续下一步」——那正是批量最容易造成误操作的地方。
 *
 * 这里只做**判定**，不碰 Android API；实际执行由 [PhoneCore] 调用 `act()` 完成，
 * 所以单元测试可以脱离设备直接验证判定逻辑。
 */
object PhoneBatch {

    /** 一次批量最多几步。再多应该分两轮，否则出问题时现场太难看懂。 */
    const val MAX_STEPS = 6

    /** 允许出现在批里的动作。读操作和导航放行；handoff 必须单独调用。 */
    val ALLOWED = setOf(
        PhoneToolCatalog.TAP,
        PhoneToolCatalog.TYPE,
        PhoneToolCatalog.SCROLL,
        PhoneToolCatalog.TAP_XY,
        PhoneToolCatalog.KEY,
        PhoneToolCatalog.OPEN,
    )

    /** 一步的计划。 */
    data class Step(
        /** 工具名，取值见 [ALLOWED]。 */
        val tool: String,
        /** 该步原始参数。 */
        val args: Map<String, Any?>,
    )

    /** 解析结果：要么是可以执行的步骤，要么是一句能直接回给模型的错误。 */
    sealed interface Parsed {
        data class Go(val steps: List<Step>) : Parsed
        data class Bad(val message: String) : Parsed
    }

    /**
     * 解析 `steps` 参数。
     *
     * 接受两种写法：`[{tool, args}, …]` 或 `[{tool, …参数平铺}]`。后者对模型更省事，
     * 所以两种都认。
     */
    fun parse(raw: List<Map<String, Any?>>?): Parsed {
        if (raw == null || raw.isEmpty()) {
            // l10n:keep agent tool result
            return Parsed.Bad("batch 需要 steps：一个动作数组，每项形如 {tool, ...参数}。")
        }
        if (raw.size > MAX_STEPS) {
            // l10n:keep agent tool result
            return Parsed.Bad("batch 一次最多 $MAX_STEPS 步，这次给了 ${raw.size} 步。拆成两轮。")
        }

        val steps = ArrayList<Step>(raw.size)
        raw.forEachIndexed { index, item ->
            val n = index + 1
            val tool = (item["tool"] as? String)?.trim()?.lowercase()
                ?: (item["name"] as? String)?.trim()?.lowercase()
            if (tool.isNullOrEmpty()) {
                // l10n:keep agent tool result
                return Parsed.Bad("第 $n 步缺少 tool。")
            }
            if (tool !in ALLOWED) {
                // l10n:keep agent tool result
                return Parsed.Bad(
                    "第 $n 步的 $tool 不能放进 batch。可用的：" +
                        ALLOWED.joinToString(" / ") +
                        "；handoff 请单独调用。",
                )
            }
            // 参数既可以在 args 子对象里，也可以与 tool 平铺在同一层。
            val nested = item["args"] as? Map<*, *>
            val args = if (nested != null) {
                nested.entries.associate { it.key.toString() to it.value }
            } else {
                item.filterKeys { it != "tool" && it != "name" }
            }
            steps.add(Step(tool, args))
        }
        return Parsed.Go(steps)
    }

    /**
     * 把后续步骤里过期的屏幕编号换成最新的。
     *
     * 模型下发 batch 时只见过批量开始那一屏（比如 `s7`）。每步执行后快照号会推进
     * 到 `s8`、`s9`，后续步骤若还带着 `s7`，driver 的 `snap.id != screenId` 会
     * 直接判过期。这里把 [stale] 里的旧编号替换成 [fresh]。
     *
     * 只在编号确实过期时改；模型自己写对了就不动。
     */
    fun advanceScreen(
        args: Map<String, Any?>,
        stale: Set<String>,
        fresh: String?,
    ): Map<String, Any?> {
        if (fresh == null) return args
        val key = when {
            args.containsKey("screen") -> "screen"
            args.containsKey("observation") -> "observation"
            else -> return args
        }
        val given = args[key] as? String ?: return args
        if (given == fresh || given !in stale) return args
        return args + (key to fresh)
    }

    /**
     * 屏幕指纹：用来判断「这一步之后页面有没有变」。
     *
     * 只取可比较的部分：[ScreenSnapshot.text] 的首行是「屏幕 s7 · App · 标题」这种
     * 带快照号的抬头，每步都会变；也有可能是 ScreenDigest 的增量缩写
     * （「（与 s7 相同，编号不变）」「（只列变化：…）」）。这些都不该算进指纹，
     * 否则「页面没变」永远判不出来。
     */
    fun fingerprint(screen: ScreenSnapshot?): String? {
        screen ?: return null
        val app = screen.packageName ?: return null
        val lines = screen.text.lines()
        val body = lines
            // 首行是「屏幕 s7 · …」时才跳过；万一上游只给了正文，别把正文丢了。
            .drop(if (isHeadline(lines.firstOrNull())) 1 else 0)
            // 整屏没变时 ScreenDigest 只回一行「（与 s7 相同，编号不变）」。
            // 这样的内容要当作「和上一屏一样」，而不是「空屏」——否则拿空字符串
            // 去比，反而把它判成变化了。
            .filterNot { isDigestMarker(it) }
            .joinToString("\n")
            .trim()
        return app + "\u0000" + body.take(4000)
    }

    /**
     * 整屏没变（只有增量说明行）时，指纹就是「空正文」。
     *
     * `afterStep` 用它区分两种空：真·空内容 vs 明确表示「与上一屏相同」。
     * 后者配上 `before` 的空正文就应当判成「没变」。
     */
    private fun isDigestOnly(screen: ScreenSnapshot?): Boolean {
        screen ?: return false
        val lines = screen.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (isHeadline(lines.firstOrNull())) lines.drop(1) else lines
        return body.isNotEmpty() && body.all { isDigestMarker(it) }
    }

    /** 首行是不是「屏幕 sN · …」那种带快照号的抬头。 */
    private fun isHeadline(line: String?): Boolean {
        line ?: return false
        // l10n:keep agent screen format
        return line.trimStart().startsWith("屏幕 ")
    }

    /** ScreenDigest 的增量说明行：它们描述的是「和上一屏的关系」，不是内容本身。 */
    private fun isDigestMarker(line: String): Boolean {
        val t = line.trim()
        // l10n:keep agent screen format
        return t.startsWith("（与 ") || t.startsWith("（只列变化") || t.startsWith("（其余 ")
    }

    /** 步间校验的结论。 */
    sealed interface Verdict {
        /** 继续下一步。 */
        data object Continue : Verdict

        /** 停下，把现场交给模型。 */
        data class Stop(val message: String) : Verdict
    }

    /**
     * 判断这一步之后能不能继续。
     *
     * @param before 执行前的屏幕
     * @param after 执行后的屏幕（null 表示没拿到）
     * @param allowSameScreen 这一步是否允许屏幕完全不变（输入文字时常常不变）
     * @param appBefore 执行前的 App 标签，用来发现「跳去别的 App 了」
     * @param nextTool 下一步要做什么；下一步是 type 时，本步 tap 只是聚焦输入框，
     *   屏幕元素不变属于正常
     */
    fun afterStep(
        before: ScreenSnapshot?,
        after: ScreenSnapshot?,
        allowSameScreen: Boolean,
        appBefore: String?,
        nextTool: String? = null,
        ranTool: String? = null,
    ): Verdict {
        if (after == null) {
            // l10n:keep agent tool result
            return Verdict.Stop("这一步之后没拿到新屏幕，批量已停。先用 screen 看一眼再继续。")
        }
        if (after.secure) {
            // l10n:keep agent tool result
            return Verdict.Stop("这一步之后页面禁止截屏（可能是密码或支付页），批量已停。")
        }

        val app = after.appLabel ?: after.packageName
        if (appBefore != null && app != null && app != appBefore) {
            // l10n:keep agent tool result
            return Verdict.Stop("App 从「$appBefore」跳到了「$app」，批量已停。确认这是不是你要去的地方。")
        }

        val beforePrint = fingerprint(before)
        val same = beforePrint != null && beforePrint == fingerprint(after)
        // after 只回了「（与 s7 相同，编号不变）」时，指纹正文是空的；这种要按
        // 「明确没变」处理，而不是「空内容所以算变了」。
        val digestSaysSame = isDigestOnly(after)
        // 「点输入框聚焦 → 输入」是 batch 最核心的用法：点完只是聚焦、弹键盘，
        // 元素列表常常不变。下一步是 type 时这一屏不变属于正常，不能停。
        val focusesForTyping = nextTool == PhoneToolCatalog.TYPE &&
            (ranTool == PhoneToolCatalog.TAP || ranTool == PhoneToolCatalog.TAP_XY)
        if ((same || digestSaysSame) && !allowSameScreen && !focusesForTyping) {
            // l10n:keep agent tool result
            return Verdict.Stop(
                "这一步点了之后页面完全没变，后面几步很可能点空。批量已停：" +
                    "换成 tap_xy 点它的位置，或者用 open / intent 直接跳转。",
            )
        }
        return Verdict.Continue
    }

    /** 输入类动作不会立刻改变屏幕，所以单独放行。 */
    fun allowsSameScreen(tool: String): Boolean =
        tool == PhoneToolCatalog.TYPE || tool == PhoneToolCatalog.SCROLL
}
