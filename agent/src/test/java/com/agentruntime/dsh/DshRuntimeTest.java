package com.agentruntime.dsh;

import android.app.Application;
import com.agentruntime.dsh.model.*;
import com.agentruntime.dsh.plugin.*;
import com.agentruntime.dsh.runtime.DshRuntime;
import com.agentruntime.dsh.store.SessionStore;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=Application.class)
public class DshRuntimeTest {
    private static final DshRuntime.Listener QUIET=new DshRuntime.Listener() {
        public void status(String s) {} public void delta(String s) {} public void settled() {}
    };
    private final ProviderConfig.Snapshot provider=new ProviderConfig.Snapshot("https://api.deepseek.com","fixture","synthetic-key",true);
    private PluginRegistry registry(AtomicInteger calls) throws Exception {
        PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),(s,t)->true);
        registry.register(new MobilePlugin() {
            public String id(){return "fixture";} public String title(){return "fixture";} public String description(){return "fixture";}
            public List<Tool> tools(){ try { return Collections.singletonList(new Tool("fixture","fixture",PluginRegistry.schema(new JSONObject()),false,(a,t)->{ calls.incrementAndGet(); return PluginRegistry.success("DONE","fixture data"); })); } catch(Exception e){throw new AssertionError(e);} }
        }); return registry;
    }
    private static JSONObject tool(String id) throws Exception {
        return new JSONObject().put("role","assistant").put("content","").put("reasoning_content","opaque provider field")
                .put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name","fixture").put("arguments","{}"))));
    }
    private static JSONObject reply() throws Exception { return new JSONObject().put("role","assistant").put("content","已完成"); }
    @Test public void toolsLoopPreservesProviderProtocolAndNextTurnContext() throws Exception {
        AtomicInteger calls=new AtomicInteger(),requests=new AtomicInteger(); PluginRegistry registry=registry(calls); List<JSONArray> observed=new ArrayList<>();
        ModelTransport transport=(p,m,t,c,d)->{ observed.add(new JSONArray(m.toString())); return requests.getAndIncrement()==0?tool("call_1"):reply(); };
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id; DshRuntime runtime=new DshRuntime(store,transport);
            String first=runtime.run(session,"执行 fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(first)); assertEquals(1,calls.get());
            assertEquals("opaque provider field",observed.get(1).getJSONObject(2).getString("reasoning_content"));
            assertEquals("call_1",observed.get(1).getJSONObject(3).getString("tool_call_id"));
            runtime.run(session,"继续聊",false,provider,registry.snapshot(false),new Cancellation(5000),QUIET);
            assertEquals("call_1",observed.get(2).getJSONObject(3).getString("tool_call_id"));
            assertFalse(store.history(session,100000).toString().contains("synthetic-key"));
        }
    }
    @Test public void cancelledStreamIsAnAttemptAndNeverACompletedReply() throws Exception {
        AtomicInteger calls=new AtomicInteger(); PluginRegistry registry=registry(calls);
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id; Cancellation token=new Cancellation(5000);
            ModelTransport transport=(p,m,t,c,d)->{ d.accept("尚未完成的片段"); c.cancel("USER_CANCELLED"); c.check(); return reply(); };
            try {
                String turn=new DshRuntime(store,transport).run(session,"fixture",true,provider,registry.snapshot(true),token,QUIET);
                assertEquals("cancelled",store.turnState(turn));
                assertTrue(store.events(session).stream().anyMatch(e->e.type.equals("assistant_attempt")));
                assertFalse(store.history(session,10000).toString().contains("尚未完成的片段"));
                assertEquals(0,calls.get());
            } finally { Thread.interrupted(); }
        }
    }
    @Test public void rejectsDuplicateIdsBeforePersistingASecondRequestAndBoundsTheLoop() throws Exception {
        AtomicInteger calls=new AtomicInteger(); PluginRegistry registry=registry(calls);
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,(p,m,t,c,d)->tool("same_id")).run(session,"fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("failed",store.turnState(turn)); assertEquals(1,calls.get());
            assertEquals(1,store.events(session).stream().filter(e->e.type.equals("tool_result")).count());
            assertEquals(1,store.events(session).stream().filter(e->e.type.equals("assistant_message")&&e.payload.has("tool_calls")).count());
        }
    }
    @Test public void noToolsModeCannotInvokeARegisteredTool() throws Exception {
        AtomicInteger calls=new AtomicInteger(),requests=new AtomicInteger(); PluginRegistry registry=registry(calls);
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            new DshRuntime(store,(p,m,t,c,d)->requests.getAndIncrement()==0?tool("denied"):reply()).run(session,"fixture",false,provider,registry.snapshot(false),new Cancellation(5000),QUIET);
            assertEquals(0,calls.get()); assertTrue(store.history(session,10000).toString().contains("TOOL_NOT_AVAILABLE"));
        }
    }
}
