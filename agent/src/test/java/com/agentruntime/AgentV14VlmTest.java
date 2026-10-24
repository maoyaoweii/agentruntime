package com.agentruntime;

import com.agentruntime.vision.vlm.VlmPreprocessor;
import com.agentruntime.vision.vlm.VlmTokenizer;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * V1.4 端侧视觉模型链路单测。
 *
 * <p>这里测的不是"代码能编译"，而是两件在真机上错了很难查的事：
 * <b>分词是否可逆</b>、<b>图像预处理尺寸与归一化是否正确</b>。
 * 用的都是真实数据——词表来自 SmolVLM-256M 官方 tokenizer。
 */
public class AgentV14VlmTest {

    private static VlmTokenizer tokenizer;

    @BeforeClass
    public static void loadTokenizer() throws Exception {
        File vocab = new File("src/main/assets/models/smolvlm/vocab.txt");
        File merges = new File("src/main/assets/models/smolvlm/merges.txt");
        assertTrue("missing vocab.txt", vocab.isFile());
        assertTrue("missing merges.txt", merges.isFile());
        tokenizer = VlmTokenizer.fromFiles(vocab, merges);
    }

    // ------------------------------------------------------------ 分词

    @Test
    public void tokenizerLoadsRealVocabulary() {
        assertEquals(49152, tokenizer.vocabSize());
        assertEquals(48900, tokenizer.mergeCount());
    }

    @Test
    public void encodesAsciiToNonEmptyIds() {
        int[] ids = tokenizer.encode("hello");
        assertTrue("should produce tokens", ids.length > 0);
        for (int id : ids) {
            assertTrue("id in range: " + id, id >= 0 && id < tokenizer.vocabSize());
        }
    }

    @Test
    public void roundTripsAscii() {
        assertRoundTrip("hello world");
        assertRoundTrip("def express 2 + 2");
    }

    @Test
    public void roundTripsChinese() {
        assertRoundTrip("帮我点击发送按钮");
        assertRoundTrip("截图中包含 3 个按钮");
    }

    @Test
    public void roundTripsMixedWithSymbols() {
        assertRoundTrip("打开 https://example.com 并读取内容");
        assertRoundTrip("x=1, y=2; return x");
    }

    @Test
    public void emptyInputProducesNoTokens() {
        assertEquals(0, tokenizer.encode("").length);
        assertEquals(0, tokenizer.encode(null).length);
    }

    @Test
    public void specialTokensResolveToExpectedIds() {
        // 来自 tokenizer_meta.json：<|im_start|>=1, <|im_end|>=2
        assertEquals(Integer.valueOf(1), tokenizer.idOf("<|im_start|>"));
        assertEquals(Integer.valueOf(2), tokenizer.idOf("<|im_end|>"));
    }

    @Test
    public void imageTokenIdDefaultsToSmolVlmValue() {
        assertEquals(49190, tokenizer.imageTokenId());
    }

    @Test
    public void digitsAreSplitIndividually() {
        // individual_digits=true：数字应逐位编码，所以 "123" 至少 3 个 token
        int[] three = tokenizer.encode("123");
        int[] one = tokenizer.encode("1");
        assertTrue("digits should split, got " + three.length,
                three.length >= one.length * 3);
    }

    @Test
    public void decodeIsInverseOfEncode() {
        String s = "hello";
        assertEquals(s, tokenizer.decode(tokenizer.encode(s)));
    }

    // --------------------------------------------------------- 图像预处理

    @Test
    public void gridKeepsPortraitScreenshotTall() {
        // 1080x2340 竖屏截图，最多 4 个 patch -> 应该变成 1x3 而不是 2x2
        VlmPreprocessor.Grid g = VlmPreprocessor.planGrid(1080, 2340, 512, 4);
        assertEquals(1, g.cols);
        assertEquals(3, g.rows);
        assertEquals(3, g.patchCount());
    }

    @Test
    public void gridKeepsLandscapeWide() {
        VlmPreprocessor.Grid g = VlmPreprocessor.planGrid(1920, 1080, 512, 4);
        assertEquals(2, g.cols);
        assertEquals(2, g.rows);
        assertEquals(4, g.patchCount());
    }

    @Test
    public void gridNeverExceedsPatchBudget() {
        int[][] sizes = {{1080, 2340}, {2340, 1080}, {720, 1280}, {100, 100}, {4000, 3000}};
        for (int[] s : sizes) {
            for (int max : new int[]{1, 2, 4}) {
                VlmPreprocessor.Grid g = VlmPreprocessor.planGrid(s[0], s[1], 512, max);
                assertTrue("grid " + s[0] + "x" + s[1] + " max=" + max + " -> " + g.patchCount(),
                        g.patchCount() <= max);
                assertTrue(g.cols >= 1 && g.rows >= 1);
            }
        }
    }

    @Test
    public void normalizeMapsBlackAndWhiteToMinusOneAndOne() {
        assertEquals(-1.0f, VlmPreprocessor.normalize(0), 1e-6);
        assertEquals(0.0f, VlmPreprocessor.normalize(127.5), 1e-3);
        assertEquals(1.0f, VlmPreprocessor.normalize(255), 1e-6);
    }

    @Test
    public void preprocessProducesExpectedShape() {
        int w = 1080;
        int h = 2340;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, 0xFF808080);
        float[][] patches = VlmPreprocessor.preprocess(pixels, w, h, 4);
        assertEquals(3, patches.length);
        assertEquals(3 * 512 * 512, patches[0].length);
    }

    @Test
    public void whiteImageNormalizesToOne() {
        int w = 64;
        int h = 64;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        float[][] patches = VlmPreprocessor.preprocess(pixels, w, h, 1);
        assertEquals(1, patches.length);
        for (float v : patches[0]) {
            assertEquals(1.0f, v, 1e-5);
        }
    }

    @Test
    public void blackImageNormalizesToMinusOne() {
        int w = 64;
        int h = 64;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, 0xFF000000);
        float[][] patches = VlmPreprocessor.preprocess(pixels, w, h, 1);
        for (float v : patches[0]) {
            assertEquals(-1.0f, v, 1e-5);
        }
    }

    @Test
    public void allValuesStayInNormalizedRange() {
        int w = 200;
        int h = 400;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int gray = (i * 37) % 256;
            pixels[i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
        float[][] patches = VlmPreprocessor.preprocess(pixels, w, h, 2);
        for (float[] patch : patches) {
            for (float v : patch) {
                assertTrue("out of range: " + v, v >= -1.0001f && v <= 1.0001f);
            }
        }
    }

    @Test
    public void channelPlanesAreSeparated() {
        // 纯红图：R 通道应为 1.0，G/B 应为 -1.0（验证 NCHW 平面不串位）
        int w = 32;
        int h = 32;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, 0xFFFF0000);
        float[][] patches = VlmPreprocessor.preprocess(pixels, w, h, 1);
        int plane = 512 * 512;
        assertEquals(1.0f, patches[0][0], 1e-5);
        assertEquals(-1.0f, patches[0][plane], 1e-5);
        assertEquals(-1.0f, patches[0][2 * plane], 1e-5);
    }

    // ------------------------------------------------------------ 工具

    private static void assertRoundTrip(String s) {
        int[] ids = tokenizer.encode(s);
        assertNotNull(ids);
        assertTrue("no tokens for: " + s, ids.length > 0);
        String back = tokenizer.decode(ids);
        assertEquals("round trip failed for: " + s, s, back);
    }
}
