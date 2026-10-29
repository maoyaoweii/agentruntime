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
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SystemBridgePathConfinementTest {

    @Test public void nonexistentOutputUnderLinkedParentCannotEscapePrivateRoots() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Path outside = Files.createTempDirectory(context.getCacheDir().toPath(), "outside-parent-");
        Path link = context.getFilesDir().toPath().resolve("parent-link-" + UUID.randomUUID());
        try {
            Files.createSymbolicLink(link, outside);
            String target = link.resolve("new-file.txt").toString();
            ExecResult result = new SystemBridge(context).writeFile(target, "synthetic fixture");
            assertEquals("PATH_NOT_ALLOWED", result.code);
            assertFalse(Files.exists(outside.resolve("new-file.txt")));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    private static final String OUTSIDE_MARKER = "synthetic-outside-cache-secret-marker";
    private static final String SAFE_MARKER = "synthetic-app-private-safe-marker";

    @Test
    public void canonicalTraversalAndSymlinkEscapesAreRejectedForReadAndWrite() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SystemBridge bridge = new SystemBridge(context);
        File filesDir = context.getFilesDir();
        File cacheDir = context.getCacheDir();
        String nonce = UUID.randomUUID().toString();
        File safeFile = new File(filesDir, "safe-path-" + nonce + ".txt");
        File outsideFile = new File(cacheDir, "outside-path-" + nonce + ".txt");
        File linkFile = new File(filesDir, "link-path-" + nonce + ".txt");
        try {
            Files.write(outsideFile.toPath(), OUTSIDE_MARKER.getBytes(StandardCharsets.UTF_8));
            assertTrue("outside fixture must not be inside filesDir", !outsideFile.getCanonicalPath()
                    .startsWith(filesDir.getCanonicalPath() + File.separator));

            ExecResult safeWrite = bridge.writeFile(safeFile.getAbsolutePath(), SAFE_MARKER);
            assertEquals("FILE_WRITTEN", safeWrite.code);
            ExecResult safeRead = bridge.readFile(safeFile.getAbsolutePath(), 256);
            assertEquals("FILE_READ", safeRead.code);
            assertEquals(SAFE_MARKER, safeRead.detail);

            String traversal = filesDir.toPath().relativize(outsideFile.toPath()).toString();
            ExecResult traversalRead = bridge.readFile(traversal, 256);
            assertEquals("PATH_NOT_ALLOWED", traversalRead.code);
            ExecResult traversalWrite = bridge.writeFile(traversal, "synthetic-overwrite-attempt");
            assertEquals("PATH_NOT_ALLOWED", traversalWrite.code);
            assertEquals(OUTSIDE_MARKER, new String(Files.readAllBytes(outsideFile.toPath()), StandardCharsets.UTF_8));

            Files.createSymbolicLink(linkFile.toPath(), outsideFile.toPath());
            assertTrue(Files.isSymbolicLink(linkFile.toPath()));
            ExecResult symlinkRead = bridge.readFile(linkFile.getAbsolutePath(), 256);
            assertEquals("PATH_NOT_ALLOWED", symlinkRead.code);
            ExecResult symlinkWrite = bridge.writeFile(linkFile.getAbsolutePath(), "synthetic-symlink-overwrite");
            assertEquals("PATH_NOT_ALLOWED", symlinkWrite.code);
            assertEquals(OUTSIDE_MARKER, new String(Files.readAllBytes(outsideFile.toPath()), StandardCharsets.UTF_8));
            assertFalse("symlink target remains unchanged", SAFE_MARKER.equals(
                    new String(Files.readAllBytes(outsideFile.toPath()), StandardCharsets.UTF_8)));
        } finally {
            Files.deleteIfExists(linkFile.toPath());
            Files.deleteIfExists(safeFile.toPath());
            Files.deleteIfExists(outsideFile.toPath());
        }
    }
}
