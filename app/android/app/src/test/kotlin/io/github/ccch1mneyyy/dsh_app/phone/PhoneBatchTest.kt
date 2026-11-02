package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PhoneBatch 的判定逻辑测试。
 *
 * 这些测试不碰 Android：批量执行时真正危险的判断是「这一步之后该不该继续」，
 * 把它抽成纯函数之后就能在这里把各种坏情形跑一遍。
 */
class PhoneBatchTest {

    // ── parse ──────────────────────────────────────────────────────────────────

    @Test
    fun `parses steps with nested args`() {
        val parsed = PhoneBatch.parse(
            listOf(
                mapOf("tool" to "tap", "args" to mapOf("screen" to "s1", "id" to 3)),
                mapOf("tool" to "type", "args" to mapOf("screen" to "s1", "id" to 5, "text" to "hi")),
            ),
        )
        assertTrue(parsed is PhoneBatch.Parsed.Go)
        val steps = (parsed as PhoneBatch.Parsed.Go).steps
        assertEquals(2, steps.size)
        assertEquals("tap", steps[0].tool)
        assertEquals(3, steps[0].args["id"])
        assertEquals("hi", steps[1].args["text"])
    }

    @Test
    fun `parses steps with flattened args`() {
        val parsed = PhoneBatch.parse(
            listOf(
                mapOf("tool" to "tap", "screen" to "s2", "id" to 7),
                mapOf("tool" to "key", "key" to "back"),
            ),
        )
        assertTrue(parsed is PhoneBatch.Parsed.Go)
        val steps = (parsed as PhoneBatch.Parsed.Go).steps
        assertEquals("s2", steps[0].args["screen"])
        assertEquals(7, steps[0].args["id"])
        assertEquals("back", steps[1].args["key"])
        // tool 本身不该混进参数里
        assertNull(steps[0].args["tool"])
    }

    @Test
    fun `rejects empty steps`() {
        assertTrue(PhoneBatch.parse(emptyList()) is PhoneBatch.Parsed.Bad)
        assertTrue(PhoneBatch.parse(null) is PhoneBatch.Parsed.Bad)
    }

    @Test
    fun `rejects too many steps`() {
        val many = (1..PhoneBatch.MAX_STEPS + 1).map { mapOf("tool" to "tap", "id" to it) }
        val parsed = PhoneBatch.parse(many)
        assertTrue(parsed is PhoneBatch.Parsed.Bad)
        assertTrue((parsed as PhoneBatch.Parsed.Bad).message.contains("最多"))
    }

    @Test
    fun `rejects a step without tool`() {
        val parsed = PhoneBatch.parse(listOf(mapOf("id" to 1)))
        assertTrue(parsed is PhoneBatch.Parsed.Bad)
        assertTrue((parsed as PhoneBatch.Parsed.Bad).message.contains("没有 tool").not())
    }

    @Test
    fun `rejects handoff inside a batch`() {
        val parsed = PhoneBatch.parse(
            listOf(
                mapOf("tool" to "tap", "id" to 1),
                mapOf("tool" to "handoff", "reason" to "login"),
            ),
        )
        assertTrue(parsed is PhoneBatch.Parsed.Bad)
        val message = (parsed as PhoneBatch.Parsed.Bad).message
        assertTrue(message.contains("handoff"))
        assertTrue(message.contains("单独"))
    }

    @Test
    fun `rejects a read-only tool inside a batch`() {
        val parsed = PhoneBatch.parse(listOf(mapOf("tool" to "screen")))
        assertTrue(parsed is PhoneBatch.Parsed.Bad)
    }

    // ── afterStep ──────────────────────────────────────────────────────────────

    private fun snap(
        id: String = "s1",
        text: String = "body",
        pkg: String = "com.example",
        app: String? = "示例",
        secure: Boolean = false,
    ) = ScreenSnapshot(
        id = id,
        text = text,
        appLabel = app,
        packageName = pkg,
        secure = secure,
    )

    @Test
    fun `continues when the screen changed`() {
        val verdict = PhoneBatch.afterStep(
            before = snap(text = "list"),
            after = snap(text = "detail"),
            allowSameScreen = false,
            appBefore = "示例",
        )
        assertTrue(verdict is PhoneBatch.Verdict.Continue)
    }

    @Test
    fun `stops when the tap changed nothing`() {
        val verdict = PhoneBatch.afterStep(
            before = snap(text = "same"),
            after = snap(text = "same"),
            allowSameScreen = false,
            appBefore = "示例",
        )
        assertTrue(verdict is PhoneBatch.Verdict.Stop)
        val message = (verdict as PhoneBatch.Verdict.Stop).message
        assertTrue(message.contains("没变"))
        assertTrue(message.contains("tap_xy"))
    }

    @Test
    fun `allows an unchanged screen for typing`() {
        val verdict = PhoneBatch.afterStep(
            before = snap(text = "same"),
            after = snap(text = "same"),
            allowSameScreen = true,
            appBefore = "示例",
        )
        assertTrue(verdict is PhoneBatch.Verdict.Continue)
    }

    @Test
    fun `stops when the screen could not be read`() {
        val verdict = PhoneBatch.afterStep(
            before = snap(),
            after = null,
            allowSameScreen = false,
            appBefore = "示例",
        )
        assertTrue(verdict is PhoneBatch.Verdict.Stop)
        assertTrue((verdict as PhoneBatch.Verdict.Stop).message.contains("没拿到新屏幕"))
    }

    @Test
    fun `stops on a secure screen`() {
        val verdict = PhoneBatch.afterStep(
            before = snap(),
            after = snap(secure = true),
            allowSameScreen = false,
            appBefore = "示例",
        )
        assertTrue(verdict is PhoneBatch.Verdict.Stop)
        assertTrue((verdict as PhoneBatch.Verdict.Stop).message.contains("禁止截屏"))
    }

    @Test
    fun `stops when the app switched`() {
        val verdict = PhoneBatch.afterStep(
            before = snap(app = "微信", pkg = "com.tencent.mm", text = "chat"),
            after = snap(app = "设置", pkg = "com.android.settings", text = "settings"),
            allowSameScreen = false,
            appBefore = "微信",
        )
        assertTrue(verdict is PhoneBatch.Verdict.Stop)
        val message = (verdict as PhoneBatch.Verdict.Stop).message
        assertTrue(message.contains("微信"))
        assertTrue(message.contains("设置"))
    }

    @Test
    fun `does not stop on app switch when the previous app is unknown`() {
        val verdict = PhoneBatch.afterStep(
            before = null,
            after = snap(app = "设置"),
            allowSameScreen = false,
            appBefore = null,
        )
        assertTrue(verdict is PhoneBatch.Verdict.Continue)
    }

    // ── fingerprint ────────────────────────────────────────────────────────────

    @Test
    fun `fingerprint is null without a screen or a package`() {
        assertNull(PhoneBatch.fingerprint(null))
        assertNull(PhoneBatch.fingerprint(snap(pkg = "").copy(packageName = null)))
    }

    @Test
    fun `fingerprint differs when the body changes`() {
        val a = PhoneBatch.fingerprint(snap(text = "one"))
        val b = PhoneBatch.fingerprint(snap(text = "two"))
        assertNotNull(a)
        assertTrue(a != b)
    }

    @Test
    fun `fingerprint is stable for the same screen`() {
        assertEquals(PhoneBatch.fingerprint(snap(text = "x")), PhoneBatch.fingerprint(snap(text = "x")))
    }

    // ── allowsSameScreen ───────────────────────────────────────────────────────

    @Test
    fun `typing and scrolling may leave the screen unchanged`() {
        assertTrue(PhoneBatch.allowsSameScreen(PhoneToolCatalog.TYPE))
        assertTrue(PhoneBatch.allowsSameScreen(PhoneToolCatalog.SCROLL))
        assertTrue(!PhoneBatch.allowsSameScreen(PhoneToolCatalog.TAP))
        assertTrue(!PhoneBatch.allowsSameScreen(PhoneToolCatalog.TAP_XY))
    }
}
