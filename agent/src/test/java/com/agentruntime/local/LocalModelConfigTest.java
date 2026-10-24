package com.agentruntime.local;

import org.junit.Test;

import java.net.URL;

import static org.junit.Assert.assertEquals;

public class LocalModelConfigTest {

    @Test
    public void rawIpv6HostIsBracketedInChatEndpoint() throws Exception {
        LocalModelConfig config = new LocalModelConfig(LocalModelConfig.Backend.HTTP,
                "::1", 8080, null, null, 15000, 512);

        String endpoint = config.chatEndpoint();

        assertEquals("http://[::1]:8080/v1/chat/completions", endpoint);
        new URL(endpoint);
    }

    @Test
    public void bracketedIpv6HostIsNotBracketedTwice() {
        LocalModelConfig config = new LocalModelConfig(LocalModelConfig.Backend.HTTP,
                "[2001:db8::1]", 8081, null, null, 15000, 512);

        assertEquals("http://[2001:db8::1]:8081/v1/chat/completions", config.chatEndpoint());
    }

    @Test
    public void ipv4EndpointRemainsUnchanged() {
        LocalModelConfig config = new LocalModelConfig(LocalModelConfig.Backend.HTTP,
                "192.168.1.20", 8080, null, null, 15000, 512);

        assertEquals("http://192.168.1.20:8080/v1/chat/completions", config.chatEndpoint());
    }

    @Test
    public void portRangeAcceptsTcpBoundsAndDefaultsInvalidValues() {
        LocalModelConfig min = new LocalModelConfig(LocalModelConfig.Backend.HTTP,
                "127.0.0.1", 1, null, null, 15000, 512);
        LocalModelConfig max = new LocalModelConfig(LocalModelConfig.Backend.HTTP,
                "127.0.0.1", 65535, null, null, 15000, 512);
        assertEquals(1, min.port);
        assertEquals(65535, max.port);

        for (int invalidPort : new int[] {-1, 0, 65536}) {
            LocalModelConfig invalid = new LocalModelConfig(LocalModelConfig.Backend.HTTP,
                    "127.0.0.1", invalidPort, null, null, 15000, 512);
            assertEquals("invalid port " + invalidPort,
                    LocalModelConfig.DEFAULT_HTTP_PORT, invalid.port);
        }
    }
}
