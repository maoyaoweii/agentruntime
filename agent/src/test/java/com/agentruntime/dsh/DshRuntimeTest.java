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
    private PluginRegistry registry(AtomicInteger calls) throws Exception { return registry(calls,false); }
    private PluginRegistry registry(AtomicInteger calls,boolean sideEffect) throws Exception {
        PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),(s,t)->true);
        registry.register(new MobilePlugin() {
            public String id(){return "fixture";} public String title(){return "fixture";} public String description(){return "fixture";}
            public List<Tool> tools(){ try { return Collections.singletonList(new Tool("fixture","fixture",
                    PluginRegistry.schema(new JSONObject().put("n",PluginRegistry.field("integer","可选参数，用于区分同名调用"))),
                    false,sideEffect,(a,t)->{ calls.incrementAndGet(); return PluginRegistry.success("DONE","fixture data"); })); } catch(Exception e){throw new AssertionError(e);} }
        }); return registry;
    }
    private static JSONObject tool(String id) throws Exception { return tool(id,"{}"); }
    private static JSONObject tool(String id,String arguments) throws Exception {
        return new JSONObject().put("role","assistant").put("content","").put("reasoning_content","opaque provider field")
                .put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name","fixture").put("arguments",arguments))));
    }
    private static JSONObject reply() throws Exception { return new JSONObject().put("role","assistant").put("content","已完成"); }
    private static JSONObject namedTool(String id,String name,JSONObject args) throws Exception {
        return new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(
                new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
    }
    private static JSONObject skillArgs(String skill) throws Exception {
        return new JSONObject().put("skill_id",skill).put("args",new JSONObject());
    }
    private PluginRegistry phoneRegistry(AtomicInteger reads,AtomicInteger actions) throws Exception {
        return phoneRegistry(reads,actions,true);
    }
    private PluginRegistry phoneRegistry(AtomicInteger reads,AtomicInteger actions,boolean changing) throws Exception {
        PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),(s,t)->true);
        registry.register(new MobilePlugin() {
            public String id(){return "phone-skills";} public String title(){return "phone";} public String description(){return "phone";}
            public List<Tool> tools(){ try { return Collections.singletonList(new Tool("run_skill","phone",
                    PluginRegistry.schema(new JSONObject().put("skill_id",PluginRegistry.field("string","skill"))
                            .put("args",PluginRegistry.field("object","args")),"skill_id","args"),false,true,(a,t)-> {
                        if("sys.read_screen".equals(a.getString("skill_id"))) { int n=reads.incrementAndGet(); return new JSONObject().put("ok",true).put("output",changing?"screen-"+n:"unchanged screen"); }
                        actions.incrementAndGet(); return PluginRegistry.success("DONE","acted");
                    })); } catch(Exception e){throw new AssertionError(e);} }
        }); return registry;
    }
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

    @Test public void reusedIdReadsFreshScreensAcrossActionsAndContinuesTheTask() throws Exception {
        AtomicInteger reads=new AtomicInteger(),actions=new AtomicInteger(),requests=new AtomicInteger();
        PluginRegistry registry=phoneRegistry(reads,actions); List<JSONArray> seen=new ArrayList<>();
        String[] steps={"sys.read_screen","sys.tap_text","sys.type_text","sys.read_screen","sys.read_screen","sys.tap_text","sys.read_screen"};
        ModelTransport transport=(p,m,t,c,d)->{
            seen.add(new JSONArray(m.toString())); int n=requests.getAndIncrement();
            if(n>=steps.length) return reply();
            JSONObject args=skillArgs(steps[n]);
            if(n==1) args.getJSONObject("args").put("text","搜索框");
            if(n==2) args.getJSONObject("args").put("text","测试关键词");
            if(n==5) args.getJSONObject("args").put("text","搜索");
            return namedTool("call_0","run_skill",args);
        };
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"搜索并读取结果",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn));
            assertEquals(4,reads.get()); assertEquals(3,actions.get());
            assertTrue(seen.get(seen.size()-1).toString().contains("screen-4"));
            Set<String> used=new HashSet<>();
            for(SessionStore.Event event:store.events(session)) if("tool_started".equals(event.type)) assertTrue(used.add(event.payload.getString("id")));
        }
    }

    @Test public void reusedIdWithDifferentFunctionDoesNotReplayTheWrongTool() throws Exception {
        AtomicInteger first=new AtomicInteger(),second=new AtomicInteger(),requests=new AtomicInteger();
        PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),(s,t)->true);
        registry.register(new MobilePlugin() {
            public String id(){return "fixture";} public String title(){return "fixture";} public String description(){return "fixture";}
            public List<Tool> tools(){ try { return Arrays.asList(
                    new Tool("one","one",PluginRegistry.schema(new JSONObject()),false,true,(a,t)->{first.incrementAndGet(); return PluginRegistry.success("ONE","one");}),
                    new Tool("two","two",PluginRegistry.schema(new JSONObject()),false,true,(a,t)->{second.incrementAndGet(); return PluginRegistry.success("TWO","two");}));
                } catch(Exception e){throw new AssertionError(e);} }
        });
        ModelTransport transport=(p,m,t,c,d)->{int n=requests.getAndIncrement(); return n==0?namedTool("call_0","one",new JSONObject()):n==1?namedTool("call_0","two",new JSONObject()):reply();};
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn)); assertEquals(1,first.get()); assertEquals(1,second.get());
        }
    }

    @Test public void repeatedUnchangedScreensStopOnlyAfterSeveralFreshObservations() throws Exception {
        AtomicInteger reads=new AtomicInteger(),actions=new AtomicInteger(); PluginRegistry registry=phoneRegistry(reads,actions,false);
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,(p,m,t,c,d)->namedTool("call_0","run_skill",skillArgs("sys.read_screen")))
                    .run(session,"读取屏幕",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("failed",store.turnState(turn)); assertEquals("OBSERVATION_STALLED",store.turnCode(turn));
            assertEquals(5,reads.get()); assertEquals(0,actions.get());
            assertEquals(5,store.events(session).stream().filter(e->e.type.equals("tool_result")).count());
        }
    }

    @Test public void identicalSwipesCanContinueAfterFreshScreenShowsTheNextPage() throws Exception {
        AtomicInteger reads=new AtomicInteger(),actions=new AtomicInteger(),requests=new AtomicInteger();
        PluginRegistry registry=phoneRegistry(reads,actions); String[] steps={"sys.read_screen","sys.swipe","sys.read_screen","sys.swipe","sys.read_screen"};
        ModelTransport transport=(p,m,t,c,d)->{ int n=requests.getAndIncrement(); if(n>=steps.length) return reply();
            JSONObject args=skillArgs(steps[n]); if("sys.swipe".equals(steps[n])) args.getJSONObject("args").put("direction","up");
            return namedTool("call_0","run_skill",args);
        };
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"向下浏览并读取内容",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn)); assertEquals(3,reads.get()); assertEquals(2,actions.get());
        }
    }

    @Test public void identicalSwipesAreNotRepeatedWithoutANewScreenObservation() throws Exception {
        AtomicInteger reads=new AtomicInteger(),actions=new AtomicInteger(),requests=new AtomicInteger();
        PluginRegistry registry=phoneRegistry(reads,actions); String[] steps={"sys.read_screen","sys.swipe","sys.swipe"};
        ModelTransport transport=(p,m,t,c,d)->{ int n=requests.getAndIncrement(); if(n>=steps.length) return reply();
            JSONObject args=skillArgs(steps[n]); if("sys.swipe".equals(steps[n])) args.getJSONObject("args").put("direction","up");
            return namedTool("call_"+n,"run_skill",args);
        };
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"向下浏览",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn)); assertEquals(1,reads.get()); assertEquals(1,actions.get());
        }
    }

    @Test public void aFailedSwipeIsNotRetriedAutomaticallyEvenIfTheScreenChanges() throws Exception {
        AtomicInteger reads=new AtomicInteger(),actions=new AtomicInteger(),requests=new AtomicInteger();
        PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),(s,t)->true);
        registry.register(new MobilePlugin() {
            public String id(){return "phone-skills";} public String title(){return "phone";} public String description(){return "phone";}
            public List<Tool> tools(){ try { return Collections.singletonList(new Tool("run_skill","phone",
                    PluginRegistry.schema(new JSONObject().put("skill_id",PluginRegistry.field("string","skill"))
                            .put("args",PluginRegistry.field("object","args")),"skill_id","args"),false,true,(a,t)-> {
                        if("sys.read_screen".equals(a.getString("skill_id"))) return new JSONObject().put("ok",true).put("output","screen-"+reads.incrementAndGet());
                        actions.incrementAndGet(); return PluginRegistry.failure("TOOL_FAILED");
                    })); } catch(Exception e){throw new AssertionError(e);} }
        });
        String[] steps={"sys.read_screen","sys.swipe","sys.read_screen","sys.swipe"};
        ModelTransport transport=(p,m,t,c,d)->{int n=requests.getAndIncrement(); if(n>=steps.length) return reply();
            JSONObject args=skillArgs(steps[n]); if("sys.swipe".equals(steps[n])) args.getJSONObject("args").put("direction","up");
            return namedTool("call_"+n,"run_skill",args);};
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"浏览",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn)); assertEquals(1,actions.get());
            assertTrue(store.hasUnsafeToolOutcome(session,turn));
        }
    }

    @Test public void generatedCallIdsDoNotCollideWithOtherIdsInTheSameResponse() throws Exception {
        AtomicInteger calls=new AtomicInteger(),requests=new AtomicInteger(); PluginRegistry registry=registry(calls);
        ModelTransport transport=(p,m,t,c,d)->{
            int n=requests.getAndIncrement(); if(n==0) return tool("call_0");
            if(n==1) {
                JSONObject answer=tool("call_0"); answer.getJSONArray("tool_calls").put(tool("call_0_1").getJSONArray("tool_calls").getJSONObject(0)); return answer;
            }
            return reply();
        };
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn)); assertEquals(3,calls.get());
            Set<String> used=new HashSet<>();
            for(SessionStore.Event event:store.events(session)) if("tool_started".equals(event.type)) assertTrue(used.add(event.payload.getString("id")));
        }
    }

    @Test public void reorderedArgumentsDoNotRepeatTheSameSideEffect() throws Exception {
        AtomicInteger calls=new AtomicInteger(),requests=new AtomicInteger();
        PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),(s,t)->true);
        registry.register(new MobilePlugin() {
            public String id(){return "fixture";} public String title(){return "fixture";} public String description(){return "fixture";}
            public List<Tool> tools(){ try { return Collections.singletonList(new Tool("fixture","fixture",
                    PluginRegistry.schema(new JSONObject().put("a",PluginRegistry.field("string","a")).put("b",PluginRegistry.field("string","b"))),
                    false,true,(a,t)->{calls.incrementAndGet(); return PluginRegistry.success("DONE","done");})); } catch(Exception e){throw new AssertionError(e);} }
        });
        ModelTransport transport=(p,m,t,c,d)->{int n=requests.getAndIncrement();
            return n==0?tool("call_0","{\"a\":\"1\",\"b\":\"2\"}"):n==1?tool("call_0","{\"b\":\"2\",\"a\":\"1\"}"):reply();};
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn)); assertEquals(1,calls.get());
        }
    }

    @Test public void rejectedActionIsNotRepromptedWhenTheModelChangesItsCallId() throws Exception {
        AtomicInteger approvals=new AtomicInteger(),actions=new AtomicInteger(),requests=new AtomicInteger();
        PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0),
                (summary,token)->{approvals.incrementAndGet(); return false;});
        registry.register(new MobilePlugin() {
            public String id(){return "fixture";} public String title(){return "fixture";} public String description(){return "fixture";}
            public List<Tool> tools(){ try { return Collections.singletonList(new Tool("fixture","fixture",PluginRegistry.schema(new JSONObject()),
                    true,true,(a,t)->{actions.incrementAndGet(); return PluginRegistry.success("DONE","done");}));
                } catch(Exception e){throw new AssertionError(e);} }
        });
        ModelTransport transport=(p,m,t,c,d)->{int n=requests.getAndIncrement();return n<2?tool("call_"+n):reply();};
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("failed",store.turnState(turn)); assertEquals("CONFIRMATION_NOT_OBTAINED",store.turnCode(turn));
            assertEquals(1,requests.get()); assertEquals(1,approvals.get()); assertEquals(0,actions.get());
            assertTrue(store.hasUnsafeToolOutcome(session,turn));
        }
    }
    /**
     * 真机上报的 DUPLICATE_TOOL_CALL：有些代理每轮都从 call_0 重新编号。
     * 这不该让整轮作废——同 id 同参数只当作"没拿到结果的重试"，复用上次结果且不重复执行；
     * 连续两次还这样才判定模型卡死，用一个能解释的码停下。
     */
    @Test public void repeatedIdenticalCallIsReplayedOnceThenStops() throws Exception {
        AtomicInteger calls=new AtomicInteger(); PluginRegistry registry=registry(calls,true);
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,(p,m,t,c,d)->tool("same_id")).run(session,"fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("failed",store.turnState(turn));
            assertEquals(1,calls.get());
            // 第一次执行 + 一次重放，之后停下，不会把同一调用重复执行
            assertEquals(2,store.events(session).stream().filter(e->e.type.equals("tool_result")).count());
            assertEquals(2,store.events(session).stream().filter(e->e.type.equals("assistant_message")&&e.payload.has("tool_calls")).count());
        }
    }

    @Test public void reusedIdWithDifferentArgumentsIsRenamedAndBothRun() throws Exception {
        AtomicInteger calls=new AtomicInteger(),requests=new AtomicInteger(); PluginRegistry registry=registry(calls);
        List<JSONArray> observed=new ArrayList<>();
        ModelTransport transport=(p,m,t,c,d)->{
            observed.add(new JSONArray(m.toString()));
            int n=requests.getAndIncrement();
            if(n==0) return tool("call_0","{\"n\":1}");
            if(n==1) return tool("call_0","{\"n\":2}");
            return reply();
        };
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,transport).run(session,"fixture",true,provider,registry.snapshot(true),new Cancellation(5000),QUIET);
            assertEquals("completed",store.turnState(turn));
            assertEquals(2,calls.get());
            JSONArray last=observed.get(2);
            String second=last.getJSONObject(last.length()-1).getString("tool_call_id");
            assertFalse("call_0",second.equals("call_0"));
            assertTrue(second.startsWith("call_0_"));
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
