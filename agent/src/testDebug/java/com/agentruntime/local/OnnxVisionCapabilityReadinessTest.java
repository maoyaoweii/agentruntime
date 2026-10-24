package com.agentruntime.local;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class OnnxVisionCapabilityReadinessTest {

    @Test
    public void runtimePresenceDoesNotAdvertiseUnimplementedVisionInference() {
        LocalModelConfig config = new LocalModelConfig(LocalModelConfig.Backend.ONNX,
                "127.0.0.1", 8080, "smolvlm", "decider", 5000, 128);
        OnnxLocalModelProvider provider = new OnnxLocalModelProvider(config);

        assertTrue("ONNX Runtime classpath probe should remain independent", provider.available());
        assertTrue(provider.runtimeDetail().contains("found"));
        assertFalse("weights/inference are not wired, so VLM must not be advertised",
                provider.supportsVision());
        assertEquals("INFERENCE_NOT_IMPLEMENTED",
                provider.complete(LocalModelRequest.builder("smolvlm").user("synthetic query").build()).code);
    }
}
