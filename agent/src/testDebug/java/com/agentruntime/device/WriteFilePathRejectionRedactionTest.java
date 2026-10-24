package com.agentruntime.device;

import android.app.Application;

import com.agentruntime.executor.ExecResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class WriteFilePathRejectionRedactionTest {

    @Test
    public void rejectedWriteDoesNotEchoOutsidePathOrContent() {
        Application context = RuntimeEnvironment.getApplication();
        String marker = "synthetic-write-outside-" + UUID.randomUUID();
        File target = new File(context.getCacheDir(), marker + ".txt");
        String content = "synthetic-write-content-" + marker;

        ExecResult result = new SystemBridge(context).writeFile(target.getAbsolutePath(), content);

        assertEquals("PATH_NOT_ALLOWED", result.code);
        assertFalse("rejection detail must not echo the absolute path", result.detail.contains(target.getAbsolutePath()));
        assertFalse("rejection detail must not echo the filename", result.detail.contains(marker));
        assertFalse("rejection detail must not echo the content", result.detail.contains(content));
        assertFalse("rejected writes must not create a file outside the allowed roots", target.exists());
    }
}
