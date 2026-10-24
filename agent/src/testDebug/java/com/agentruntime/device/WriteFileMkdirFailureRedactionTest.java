package com.agentruntime.device;

import android.app.Application;

import com.agentruntime.executor.ExecResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class WriteFileMkdirFailureRedactionTest {

    @Test
    public void mkdirFailureDoesNotEchoBlockedParentPath() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        String marker = "synthetic-mkdir-failure-" + UUID.randomUUID();
        File blocker = new File(context.getFilesDir(), marker + "-blocker");
        File target = new File(new File(blocker, "child"), "result.txt");
        String content = "synthetic-write-content-" + marker;
        Files.write(blocker.toPath(), "blocker fixture".getBytes(StandardCharsets.UTF_8));
        try {
            assertTrue(blocker.isFile());
            ExecResult result = new SystemBridge(context).writeFile(target.getAbsolutePath(), content);
            assertEquals("MKDIR_FAILED", result.code);
            assertFalse("mkdir failure detail must not echo the blocked parent path",
                    result.detail.contains(blocker.getAbsolutePath()));
            assertFalse("mkdir failure detail must not echo the path marker", result.detail.contains(marker));
            assertFalse("mkdir failure detail must not echo the requested contents", result.detail.contains(content));
            assertTrue("blocker fixture must remain unchanged", blocker.isFile());
            assertFalse("failed write must not create the target", target.exists());
        } finally {
            Files.deleteIfExists(blocker.toPath());
        }
    }
}
