package com.agentruntime.network;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ResponseBodyTest {
    @Test public void decodesChineseAndEmojiAcrossArbitraryReadBoundaries() throws Exception {
        String expected = "x".repeat(8191) + "新建笔记，保存截图。😀";
        ByteArrayInputStream chunks = new ByteArrayInputStream(expected.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 7));
            }
        };
        assertEquals(expected, ResponseBody.read(chunks));
    }

    @Test public void rejectsOversizedReplyAndClosesStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[ResponseBody.MAX_BYTES + 1]) {
            @Override public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> ResponseBody.read(input));
        assertTrue(closed.get());
    }

    @Test public void acceptsExactLimitAndEmptyBody() throws Exception {
        assertEquals(ResponseBody.MAX_BYTES,
                ResponseBody.read(new ByteArrayInputStream(new byte[ResponseBody.MAX_BYTES])).length());
        assertEquals("", ResponseBody.read(null));
    }
}
