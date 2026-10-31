package com.agentruntime.dsh;

import android.app.Application;
import com.agentruntime.dsh.store.SessionStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=Application.class)
public class SessionStoreTest {
    @Test public void persistsMessagesAcrossReopenAndKeepsSessionsIsolated() throws Exception {
        Application context=RuntimeEnvironment.getApplication();
        String session;
        try(SessionStore store=new SessionStore(context)) {
            session=store.createSession().id;
            String turn=store.beginTurn(session,"你好","chat","fixture");
            store.append(session,turn,"assistant_message",new JSONObject().put("role","assistant").put("content","你好，手机助手已就绪"));
            store.finish(session,turn,"completed","OK");
            assertEquals(0,store.history(store.createSession().id,10000).length());
        }
        try(SessionStore store=new SessionStore(context)) {
            assertEquals("你好",store.session(session).title);
            assertEquals(2,store.history(session,10000).length());
            assertEquals("你好，手机助手已就绪",store.history(session,10000).getJSONObject(1).getString("content"));
        }
    }

    @Test public void restartMarksActiveTurnInterruptedWithoutReplayingIt() throws Exception {
        Application context=RuntimeEnvironment.getApplication(); String session,turn;
        try(SessionStore store=new SessionStore(context)) {
            session=store.createSession().id; turn=store.beginTurn(session,"stop fixture","tools","fixture");
            assertThrows(IllegalStateException.class,()->store.delete(session));
        }
        try(SessionStore store=new SessionStore(context)) {
            store.recoverInterrupted();
            assertEquals("interrupted",store.turnState(turn));
            assertEquals(2,store.history(session,10000).length());
            assertTrue(store.history(session,10000).getJSONObject(1).getString("content").contains("Do not resume"));
            int count=store.events(session).size(); store.recoverInterrupted();
            assertEquals(count,store.events(session).size());
            store.delete(session); assertNull(store.session(session));
        }
    }

    @Test public void interruptedToolCallsAreClosedWithoutClaimingTheyDidNotRun() throws Exception {
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id,turn=store.beginTurn(session,"write fixture","tools","fixture");
            store.append(session,turn,"assistant_message",new JSONObject("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"pending\",\"type\":\"function\",\"function\":{\"name\":\"workspace_write\",\"arguments\":\"{}\"}}]}"));
            store.recoverInterrupted();
            org.json.JSONArray history=store.history(session,10000);
            assertEquals(4,history.length());
            assertEquals("pending",history.getJSONObject(2).getString("tool_call_id"));
            assertTrue(history.getJSONObject(2).getString("content").contains("may have taken effect"));
            int count=store.events(session).size(); store.recoverInterrupted(); assertEquals(count,store.events(session).size());
        }
    }

    @Test public void longLatestTurnKeepsToolProtocolAndRecentObservationInHistory() throws Exception {
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id,turn=store.beginTurn(session,"浏览搜索结果","tools","fixture");
            for(int i=0;i<4;i++) {
                String id="call_"+i;
                org.json.JSONArray calls=new org.json.JSONArray().put(new JSONObject().put("id",id).put("type","function")
                        .put("function",new JSONObject().put("name","run_skill").put("arguments","{}")));
                store.append(session,turn,"assistant_message",new JSONObject().put("role","assistant").put("content","").put("tool_calls",calls));
                JSONObject result=new JSONObject().put("ok",true).put("code","SCREEN_TEXT")
                        .put("output",(i==3?"LATEST_SCREEN ":"OLDER_SCREEN ")+"x".repeat(6000));
                store.append(session,turn,"tool_result",new JSONObject().put("role","tool").put("tool_call_id",id).put("content",result.toString()));
            }
            store.finish(session,turn,"failed","MODEL_STEP_LIMIT");
            org.json.JSONArray history=store.history(session,12000);
            assertTrue(history.length()>=9); assertTrue(history.toString().contains("LATEST_SCREEN"));
            assertTrue(history.toString().contains("history_compacted"));
            for(int i=0;i<4;i++) assertTrue(history.toString().contains("call_"+i));
            assertTrue(store.events(session).stream().filter(e->e.type.equals("tool_result"))
                    .anyMatch(e->e.payload.optString("content").length()>6000));
        }
    }

    @Test public void oversizedEssentialHistoryFailsInsteadOfSilentlyDisappearing() throws Exception {
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String session=store.createSession().id,turn=store.beginTurn(session,"a".repeat(5000),"chat","fixture");
            store.finish(session,turn,"completed","OK");
            org.json.JSONException error=assertThrows(org.json.JSONException.class,()->store.history(session,1000));
            assertEquals("CONTEXT_LIMIT",error.getMessage());
        }
    }

    @Test public void rejectsUnknownSessionsAndBoundsUserInput() throws Exception {
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            assertThrows(IllegalArgumentException.class,()->store.beginTurn("missing","hello","chat","fixture"));
            String session=store.createSession().id;
            assertThrows(IllegalArgumentException.class,()->store.beginTurn(session,"x".repeat(16001),"chat","fixture"));
        }
    }

    @Test public void cannotAppendOrFinishAnotherSessionsTurn() throws Exception {
        try(SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            String a=store.createSession().id,b=store.createSession().id;
            String turn=store.beginTurn(a,"fixture","chat","fixture");
            assertThrows(IllegalArgumentException.class,()->store.append(b,turn,"assistant_message",new JSONObject()));
            assertThrows(IllegalArgumentException.class,()->store.finish(b,turn,"completed","OK"));
            assertTrue(store.events(b).isEmpty());
            assertEquals("running",store.turnState(turn));
        }
    }
}
