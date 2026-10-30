package com.agentruntime.ui;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.BuildConfig;
import com.agentruntime.dsl.DslParser;
import com.agentruntime.runtime.AgentRuntime;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AgentApp.class)
public class TaskAdmissionTest {
    @Test(timeout = 10000) public void rejectsConcurrentTaskWithoutChangingApprovalAndRetainsCancellation() throws Exception {
        AgentApp app = (AgentApp) RuntimeEnvironment.getApplication();
        File file = new File(app.getFilesDir(), "admission-fixture.txt");
        Files.write(file.toPath(), new byte[]{1});
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        app.riskGate().setConfirmationChannel((id, summary) -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return false;
        });
        String json = task(file.getAbsolutePath());
        FutureTask<AgentRuntime.TaskResult> first = new FutureTask<>(() -> TaskRunner.runTaskJson(json, false, false));
        Thread worker = new Thread(first, "admission-fixture");
        worker.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> TaskRunner.runTaskJson(json, true, false));
            assertEquals("BUSY", app.agentRuntime().runTask(DslParser.parseTask(json), false, false).status);
            TaskRunner.cancel();
            release.countDown();
            assertEquals("CANCELLED", first.get(3, TimeUnit.SECONDS).status);
            assertTrue("rejected/cancelled task must not delete a file", file.exists());
            assertFalse(TaskRunner.isBusy());
        } finally {
            release.countDown();
            worker.join(3000);
            app.riskGate().setConfirmationChannel(app.confirmations());
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test public void parseFailureReleasesAdmissionAndResetsTestApproval() throws Exception {
        assertThrows(Exception.class, () -> TaskRunner.runTaskJson("invalid fixture", true, false));
        assertFalse(TaskRunner.isBusy());
        AgentApp app = (AgentApp) RuntimeEnvironment.getApplication();
        app.riskGate().setConfirmationChannel((id, summary) -> false);
        assertEquals("FAILED", TaskRunner.runTaskJson(task("missing-fixture.txt"), false, false).status);
    }

    @Test(timeout = 8000) public void approvingAfterStopCannotDeleteTheTarget() throws Exception {
        AgentApp app = (AgentApp) RuntimeEnvironment.getApplication();
        File file = new File(app.getFilesDir(), "cancel-during-approval.txt");
        Files.write(file.toPath(), new byte[]{1});
        app.riskGate().setConfirmationChannel((id, summary) -> {
            TaskRunner.cancel();
            return true;
        });
        FutureTask<AgentRuntime.TaskResult> task = new FutureTask<>(
                () -> TaskRunner.runTaskJson(task(file.getAbsolutePath()), false, false));
        new Thread(task, "cancel-approval-fixture").start();
        try {
            assertEquals("CANCELLED", task.get(3, TimeUnit.SECONDS).status);
            assertTrue(file.exists());
        } finally {
            app.riskGate().setConfirmationChannel(app.confirmations());
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test public void automaticApprovalIsConfinedToDebugBuilds() throws Exception {
        AgentApp app = (AgentApp) RuntimeEnvironment.getApplication();
        File file = new File(app.getFilesDir(), "debug-approval-fixture.txt");
        Files.write(file.toPath(), new byte[]{1});
        app.riskGate().setConfirmationChannel((id, summary) -> false);
        try {
            AgentRuntime.TaskResult result = TaskRunner.runTaskJson(task(file.getAbsolutePath()), true, false);
            assertEquals(BuildConfig.DEBUG ? "SUCCESS" : "FAILED", result.status);
            assertEquals(!BuildConfig.DEBUG, file.exists());
        } finally {
            app.riskGate().setConfirmationChannel(app.confirmations());
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test(timeout=8000) public void aDifferentOwnersStopCannotCancelTheActiveTask() throws Exception {
        AgentApp app=(AgentApp)RuntimeEnvironment.getApplication(); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        app.riskGate().setConfirmationChannel((id,summary)->{
            entered.countDown(); try { release.await(4,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } return false;
        });
        FutureTask<AgentRuntime.TaskResult> task=new FutureTask<>(()->TaskRunner.runTaskJson(task("scoped-cancel-fixture.txt"),false,false));
        Thread worker=new Thread(task,"other-owner-fixture"); worker.start();
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            TaskRunner.cancel(new java.util.concurrent.atomic.AtomicBoolean());
            assertFalse(worker.isInterrupted()); assertFalse(task.isDone());
            release.countDown(); assertEquals("FAILED",task.get(3,TimeUnit.SECONDS).status);
        } finally { release.countDown(); worker.join(1000); app.riskGate().setConfirmationChannel(app.confirmations()); }
    }

    private String task(String path) throws Exception {
        return new JSONObject().put("task_version", "1.0").put("task_id", "admission")
                .put("goal", "synthetic fixture").put("allowed_apps", new JSONArray())
                .put("preconditions", new JSONArray()).put("postconditions", new JSONArray())
                .put("steps", new JSONArray().put(new JSONObject().put("skill", "sys.file_delete")
                        .put("args", new JSONObject().put("path", path))))
                .put("limits", new JSONObject().put("max_steps", 5).put("max_replans", 0)
                        .put("timeout_seconds", 10)).toString();
    }
}
