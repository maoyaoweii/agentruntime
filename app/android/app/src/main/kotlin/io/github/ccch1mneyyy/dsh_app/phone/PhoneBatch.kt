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

    /** 屏幕指纹：用来判断「这一步之后页面有没有变」。 */
    fun fingerprint(screen: ScreenSnapshot?): String? {
        screen ?: return null
        val app = screen.packageName ?: return null
        // 正文可能很长，取前 4000 字符够了：页面结构变化会体现在前面。
        return app + "\u0000" + screen.text.take(4000)
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
     */
    fun afterStep(
        before: ScreenSnapshot?,
        after: ScreenSnapshot?,
        allowSameScreen: Boolean,
        appBefore: String?,
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

        val same = fingerprint(before) != null && fingerprint(before) == fingerprint(after)
        if (same && !allowSameScreen) {
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
