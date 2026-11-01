package com.agentruntime.dsh;

import com.agentruntime.AgentApp;
import com.agentruntime.dsh.model.*;
import com.agentruntime.dsh.plugin.PluginRegistry;
import com.agentruntime.dsh.runtime.*;
import com.agentruntime.dsh.store.SessionStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=AgentApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DshControllerTest {
    private DshController controller(SessionStore store) throws Exception {
        AgentApp app=AgentApp.get(); CredentialVault vault=new CredentialVault(app.getSharedPreferences("controller_vault",0),()->new SecretKeySpec(new byte[32],"AES"));
        ProviderConfig provider=new ProviderConfig(app,vault); provider.save("https://api.deepseek.com","fixture","synthetic-key",false);
        PluginRegistry plugins=new PluginRegistry(app.getSharedPreferences("controller_plugins",0),(s,t)->false);
        DshRuntime runtime=new DshRuntime(store,(p,m,t,c,d)->new JSONObject().put("role","assistant").put("content","fixture finished"));
        return new DshController(app,store,provider,plugins,runtime);
    }
    private static String latestTurn(SessionStore store,String session) throws Exception {
        // 取最后一个 turn_started：turn_continued 会插在更后面，按"最后一条事件"取会取错回合。
        String turn=null;
        for(SessionStore.Event event:store.events(session)) if("turn_started".equals(event.type)) turn=event.turnId;
        return turn;
    }

    /**
     * 长任务：单轮工具预算用完不该判死刑，要自动开下一轮接着做。
     */
    @Test public void budgetExhaustionContinuesInANewTurn() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String id=controller.reserve(session,"把 B 站的三个视频都收藏",true);
            store.finish(session,latestTurn(store,session),"failed","MODEL_STEP_LIMIT");

            String next=controller.continuationFor(id);

            assertNotNull(next);
            assertNotNull(controller.active());
            assertEquals(next,controller.active().id);
            String text=store.events(session).stream()
                    .filter(e->e.type.equals("user_message")).reduce((a,b)->b).get().payload.optString("content");
            assertTrue(text.startsWith("[DSH Runtime]"));
            assertTrue(text.contains("把 B 站的三个视频都收藏"));
            assertTrue(store.events(session).stream().anyMatch(e->e.type.equals("turn_continued")));
        }
    }

    @Test public void maximumLengthGoalCanStillBeAutomaticallyContinued() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String goal="a".repeat(SessionStore.MAX_INPUT); String id=controller.reserve(session,goal,true);
            store.finish(session,latestTurn(store,session),"failed","MODEL_STEP_LIMIT");
            assertNotNull(controller.continuationFor(id));
            String next=store.events(session).stream().filter(e->e.type.equals("user_message"))
                    .reduce((a,b)->b).get().payload.getString("content");
            assertTrue(next.length()<=SessionStore.MAX_INPUT); assertTrue(next.contains("完整目标"));
            assertEquals(goal,store.history(session,96000).getJSONObject(0).getString("content"));
        }
    }

    @Test public void completedTurnsAreNeverContinued() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String done=controller.reserve(session,"已经做完",true);
            store.finish(session,latestTurn(store,session),"completed","OK");
            assertNull(controller.continuationFor(done));
        }
    }

    @Test public void cancelledTurnsAreNeverContinued() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String stopped=controller.reserve(session,"我要停",true);
            controller.cancel(stopped,"USER_CANCELLED");
            store.finish(session,latestTurn(store,session),"cancelled","USER_CANCELLED");
            assertNull(controller.continuationFor(stopped));
        }
    }

    /** 超时是预算问题，不是用户喊停；长任务应当接着做。 */
    @Test public void timeoutStillContinues() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String id=controller.reserve(session,"很长的任务",true);
            controller.cancel(id,"TURN_TIMEOUT");
            store.finish(session,latestTurn(store,session),"cancelled","TURN_TIMEOUT");
            assertNotNull(controller.continuationFor(id));
        }
    }

    @Test public void originalStopButtonStillStopsAnAutomaticallyContinuedTurn() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String original=controller.reserve(session,"长任务",true);
            store.finish(session,latestTurn(store,session),"failed","MODEL_STEP_LIMIT");
            String next=controller.continuationFor(original); assertNotNull(next); assertNotEquals(original,next);
            controller.cancel(original,"USER_CANCELLED");
            assertEquals("正在停止…",controller.active().status);
            store.finish(session,latestTurn(store,session),"failed","MODEL_STEP_LIMIT");
            assertNull(controller.continuationFor(next));
        }
    }

    @Test public void userStopAfterTimeoutAlwaysOverridesAutomaticContinuation() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String id=controller.reserve(session,"长任务",true);
            controller.cancel(id,"TURN_TIMEOUT"); controller.cancel(id,"USER_CANCELLED");
            store.finish(session,latestTurn(store,session),"cancelled","TURN_TIMEOUT");
            assertNull(controller.continuationFor(id));
        }
    }

    @Test public void previousRoundTimeoutCannotCancelTheNewRound() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String original=controller.reserve(session,"长任务",true);
            store.finish(session,latestTurn(store,session),"failed","MODEL_STEP_LIMIT");
            String next=controller.continuationFor(original); assertNotNull(next);
            controller.cancel(original,"TURN_TIMEOUT");
            assertNotEquals("正在停止…",controller.active().status);
            store.finish(session,latestTurn(store,session),"failed","MODEL_STEP_LIMIT");
            assertNotNull(controller.continuationFor(next));
        }
    }

    @Test public void unknownInterruptedToolOutcomeDoesNotAutomaticallyContinue() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String id=controller.reserve(session,"执行手机任务",true),turn=latestTurn(store,session);
            org.json.JSONArray calls=new org.json.JSONArray().put(new JSONObject().put("id","call_0").put("type","function")
                    .put("function",new JSONObject().put("name","run_skill").put("arguments","{}")));
            store.append(session,turn,"assistant_message",new JSONObject().put("role","assistant").put("content","").put("tool_calls",calls));
            store.append(session,turn,"tool_started",new JSONObject().put("id","call_0").put("name","run_skill").put("arguments",new JSONObject()));
            controller.cancel(id,"TURN_TIMEOUT"); store.finish(session,turn,"cancelled","TURN_TIMEOUT");
            assertNull(controller.continuationFor(id));
        }
    }

    @Test public void continuationStopsAfterTheRoundBudget() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String id=controller.reserve(session,"超长任务",true);
            int rounds=0;
            while(true) {
                store.finish(session,latestTurn(store,session),"failed","TOOL_LIMIT");
                String next=controller.continuationFor(controller.active()==null?id:controller.active().id);
                if(next==null) break;
                rounds++;
                if(rounds>10) fail("续跑没有上限");
            }
            assertEquals(DshController.MAX_ROUNDS,rounds);
        }
    }

    @Test public void serviceStartFailureKeepsTheUserMessageAndReleasesAdmission() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id;
            String id=controller.reserve(session,"不能丢失的消息",false);
            assertTrue(store.events(session).stream().anyMatch(e->e.type.equals("user_message")&&e.payload.optString("content").equals("不能丢失的消息")));
            controller.abandon(id); assertNull(controller.active());
            assertEquals("SERVICE_START_FAILED",store.events(session).get(store.events(session).size()-1).payload.getString("code"));
            assertNotNull(controller.reserve(session,"可以再次发送",false)); controller.abandon(controller.active().id);
        }
    }
    @Test(timeout=10000) public void aNewTurnCannotStartBeforeThePreviousServiceCleanupCallback() throws Exception {
        try(SessionStore store=new SessionStore(AgentApp.get())) {
            DshController controller=controller(store); String session=store.createSession().id; AtomicInteger finished=new AtomicInteger();
            String id=controller.reserve(session,"first",false); assertTrue(controller.launch(id,finished::incrementAndGet));
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while(store.events(session).stream().noneMatch(e->e.type.equals("turn_finished"))&&System.nanoTime()<until) Thread.sleep(10);
            assertTrue(store.events(session).stream().anyMatch(e->e.type.equals("turn_finished")));
            assertNotNull(controller.active()); assertThrows(IllegalStateException.class,()->controller.reserve(session,"too soon",false));
            for(int i=0;i<50&&controller.active()!=null;i++) { org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle(); Thread.sleep(10); }
            assertNull(controller.active()); assertEquals(1,finished.get());
            String next=controller.reserve(session,"next",false); assertNotEquals(id,next);
            controller.cancel(id,"USER_CANCELLED"); assertEquals(next,controller.active().id); controller.abandon(next);
        }
    }
}
