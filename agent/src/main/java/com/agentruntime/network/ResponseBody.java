package com.agentruntime.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounded UTF-8 decoding shared by local and cloud model transports. */
public final class ResponseBody {
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private ResponseBody() { }

    public static String read(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > MAX_BYTES - bytes.size()) {
                    throw new IOException("model response exceeds size limit");
                }
                bytes.write(buffer, 0, count);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
}
