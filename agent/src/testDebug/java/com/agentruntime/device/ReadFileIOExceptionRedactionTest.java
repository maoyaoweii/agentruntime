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
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ReadFileIOExceptionRedactionTest {

    @Test
    public void unreadablePrivateFileUsesSanitizedIoFailureDetail() throws Exception {
        // 本用例验证的是「IO 失败时回执必须脱敏」这条契约，而不是「权限位能否挡住读取」。
        // 权限位在不同运行身份下行为不同：root（本仓库 CI/JVM 环境即 root）无视 mode 000，
        // 仍能读到内容；非 root 才会抛 AccessDeniedException。两者都不能让回执泄露路径。
        File file = new File(RuntimeEnvironment.getApplication().getFilesDir(),
                "synthetic-unreadable-read-" + UUID.randomUUID() + ".txt");
        Files.write(file.toPath(), "synthetic fixture".getBytes(StandardCharsets.UTF_8));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(file.toPath());
        ExecResult result;
        try {
            Files.setPosixFilePermissions(file.toPath(), Collections.emptySet());
            result = new SystemBridge(RuntimeEnvironment.getApplication())
                    .readFile(file.getAbsolutePath(), 1024);
        } finally {
            Files.setPosixFilePermissions(file.toPath(), originalPermissions);
            Files.deleteIfExists(file.toPath());
        }
        assertTrue("code must be one of the read outcomes",
                "FILE_READ_FAILED".equals(result.code) || "FILE_READ".equals(result.code));
        assertFalse("I/O error detail must not echo the absolute path",
                result.detail != null && result.detail.contains(file.getAbsolutePath()));
        assertFalse("I/O error detail must not echo the synthetic filename",
                result.detail != null && result.detail.contains(file.getName()));
    }
}
