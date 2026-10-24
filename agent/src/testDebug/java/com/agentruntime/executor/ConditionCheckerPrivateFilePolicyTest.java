package com.agentruntime.executor;

import android.app.Application;

import com.agentruntime.state.WorldState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ConditionCheckerPrivateFilePolicyTest {

    @Test
    public void fileExistsConditionIsLimitedToCanonicalAppPrivateRoots() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File filesDir = context.getFilesDir();
        File cacheDir = context.getCacheDir();
        String nonce = UUID.randomUUID().toString();
        File privateFile = new File(filesDir, "condition-private-" + nonce + ".txt");
        File outsideFile = new File(cacheDir, "condition-outside-" + nonce + ".txt");
        File privateSymlink = new File(filesDir, "condition-link-" + nonce + ".txt");
        try {
            Files.write(privateFile.toPath(), "private fixture".getBytes(StandardCharsets.UTF_8));
            Files.write(outsideFile.toPath(), "outside fixture".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(privateSymlink.toPath(), outsideFile.toPath());
            ConditionChecker checker = new ConditionChecker(context);
            WorldState unusedState = null;

            assertTrue(checker.evaluate("file_exists:" + privateFile.getAbsolutePath(), unusedState));
            assertFalse("absolute cacheDir paths are outside the file tool's private roots",
                    checker.evaluate("file_exists:" + outsideFile.getAbsolutePath(), unusedState));
            String traversal = filesDir.toPath().relativize(outsideFile.toPath()).toString();
            assertFalse("relative traversal must not expose cache file existence",
                    checker.evaluate("file_exists:" + traversal, unusedState));
            assertFalse("symlinks must not extend app-private roots",
                    checker.evaluate("file_exists:" + privateSymlink.getAbsolutePath(), unusedState));
        } finally {
            Files.deleteIfExists(privateSymlink.toPath());
            Files.deleteIfExists(privateFile.toPath());
            Files.deleteIfExists(outsideFile.toPath());
        }
    }
}
