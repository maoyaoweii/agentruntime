package com.agentruntime.dsh;

import android.app.Application;
import com.agentruntime.dsh.model.Cancellation;
import com.agentruntime.dsh.plugin.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=Application.class)
public class PluginRegistryTest {
    private PluginRegistry registry(PluginRegistry.Approval approval) {
        return new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),approval);
    }
    private MobilePlugin plugin(String id,String name,boolean confirm,MobilePlugin.Handler handler) throws Exception {
        MobilePlugin.Tool tool=new MobilePlugin.Tool(name,"fixture",PluginRegistry.schema(new JSONObject()),confirm,handler);
        return new MobilePlugin() {
            public String id() { return id; } public String title() { return id; } public String description() { return id; }
            public List<Tool> tools() { return Collections.singletonList(tool); }
        };
    }
    @Test public void admissionIsFrozenAndRevocationAppliesToExistingSnapshots() throws Exception {
        PluginRegistry registry=registry((s,t)->true); registry.register(plugin("fixture","observe",false,(a,t)->PluginRegistry.success("OK",true)));
        registry.setEnabled("fixture",false); PluginRegistry.Snapshot before=registry.snapshot(true); registry.setEnabled("fixture",true);
        assertFalse(before.invoke("observe",new JSONObject(),new Cancellation(5000)).getBoolean("ok"));
        PluginRegistry.Snapshot enabled=registry.snapshot(true); assertEquals(1,enabled.schemas().length());
        registry.setEnabled("fixture",false); assertFalse(enabled.invoke("observe",new JSONObject(),new Cancellation(5000)).getBoolean("ok"));
        assertEquals(0,registry.snapshot(false).schemas().length());
    }
    @Test public void rejectingOrCancellingApprovalCannotWriteAFile() throws Exception {
        Application app=RuntimeEnvironment.getApplication(); File root=new File(app.getFilesDir(),"isolated"); Workspace workspace=new Workspace(root);
        PluginRegistry denied=registry((s,t)->false); denied.register(workspace);
        JSONObject args=new JSONObject().put("path","note.txt").put("content","fixture");
        assertEquals("USER_REJECTED",denied.snapshot(true).invoke("workspace_write",args,new Cancellation(5000)).getString("code"));
        assertFalse(new File(root,"note.txt").exists());
        PluginRegistry cancelled=registry((s,t)->{ t.cancel("USER_CANCELLED"); return true; }); cancelled.register(workspace);
        try { assertThrows(java.io.InterruptedIOException.class,()->cancelled.snapshot(true).invoke("workspace_write",args,new Cancellation(5000))); }
        finally { Thread.interrupted(); }
        assertFalse(new File(root,"note.txt").exists());
    }
    @Test public void validatesArgumentsAndRegistrationBeforeAnySideEffect() throws Exception {
        AtomicInteger calls=new AtomicInteger(); PluginRegistry registry=registry((s,t)->true);
        registry.register(plugin("one","fixture",false,(a,t)->{ calls.incrementAndGet(); return PluginRegistry.success("OK",true); }));
        assertThrows(IllegalArgumentException.class,()->registry.register(plugin("two","fixture",false,(a,t)->null)));
        assertEquals(1,registry.plugins().size());
        assertEquals("INVALID_TOOL_ARGUMENTS",registry.snapshot(true).invoke("fixture",new JSONObject().put("unknown",1),new Cancellation(5000)).getString("code"));
        assertEquals(0,calls.get());
    }
    @Test(timeout=8000) public void disablingAPluginCancelsItsRunningHandler() throws Exception {
        CountDownLatch entered=new CountDownLatch(1); Cancellation token=new Cancellation(5000); PluginRegistry registry=registry((s,t)->true);
        registry.register(plugin("fixture","long_tool",false,(a,t)->{ entered.countDown(); while(true) { t.check(); Thread.sleep(10); } }));
        PluginRegistry.Snapshot snapshot=registry.snapshot(true);
        FutureTask<JSONObject> job=new FutureTask<>(()->{ token.bind(); return snapshot.invoke("long_tool",new JSONObject(),token); });
        Thread worker=new Thread(job); worker.start(); assertTrue(entered.await(3,TimeUnit.SECONDS)); registry.setEnabled("fixture",false);
        try { job.get(3,TimeUnit.SECONDS); } catch(ExecutionException expected) { assertTrue(token.flag.get()); }
        assertEquals("PLUGIN_DISABLED",token.reason()); worker.join(1000); assertFalse(worker.isAlive());
    }
    @Test public void workspaceCannotEscapeItsRootOrReadOversizedFiles() throws Exception {
        File root=new File(RuntimeEnvironment.getApplication().getFilesDir(),"workspace"); Workspace workspace=new Workspace(root);
        for(String value:Arrays.asList("../outside.txt","/absolute.txt","C:/outside","a\\outside",".")) assertThrows(Exception.class,()->workspace.resolve(value));
        assertTrue(workspace.write("notes/a.txt","你好",new Cancellation(5000)).getBoolean("ok"));
        assertEquals("你好",workspace.read("notes/a.txt").getString("data"));
        assertFalse(workspace.write("large.txt","x".repeat(16385),new Cancellation(5000)).getBoolean("ok"));
        assertEquals(1,workspace.list().getJSONArray("data").length());
    }
    @Test public void revokedObservationIsDiscardedBeforeItCanEnterHistory() throws Exception {
        PluginRegistry registry=registry((s,t)->true); Cancellation token=new Cancellation(5000);
        registry.register(plugin("fixture","read_private",false,(a,t)->{ registry.setEnabled("fixture",false); return PluginRegistry.success("READ","late-private-data"); }));
        try { assertThrows(java.io.InterruptedIOException.class,()->registry.snapshot(true).invoke("read_private",new JSONObject(),token)); }
        finally { Thread.interrupted(); }
        assertEquals("PLUGIN_DISABLED",token.reason());
    }
}
