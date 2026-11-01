package com.agentruntime.dsh.runtime;

import com.agentruntime.AgentApp;
import com.agentruntime.dsh.model.Cancellation;
import com.agentruntime.dsh.model.ModelTransport;
import com.agentruntime.dsh.model.ProviderConfig;
import com.agentruntime.dsh.store.SessionStore;
import com.agentruntime.state.UiNode;
import com.agentruntime.service.AgentAccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Robolectric;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import static org.junit.Assert.*;

/** Exercises the registered read-screen skill and observation channel with changing Android UI trees. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=AgentApp.class)
public class ScreenObservationIntegrationTest {
    private static UiNode screen(String text) {
        UiNode visible=new UiNode("n1","android.widget.TextView",text,null,null,
                false,true,true,false,false,false,false,10,20,400,80,Collections.emptyList());
        return new UiNode("n0","android.widget.FrameLayout",null,null,null,
                false,true,true,false,false,false,false,0,0,1080,2000,Collections.singletonList(visible));
    }
    private static JSONObject read() throws Exception {
        JSONObject args=new JSONObject().put("skill_id","sys.read_screen").put("args",new JSONObject());
        return new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(
                new JSONObject().put("id","call_0").put("type","function")
                        .put("function",new JSONObject().put("name","run_skill").put("arguments",args.toString()))));
    }
    @Test public void reusedProviderIdDeliversUpdatedScreenThroughTheRealPhoneSkill() throws Exception {
        AgentApp app=AgentApp.get(); AtomicInteger requests=new AtomicInteger();
        boolean[] observed=new boolean[2];
        ModelTransport model=(p,m,t,c,d)->{
            int n=requests.getAndIncrement();
            if(n==0) { app.stateEngine().rebuildSnapshot("tv.danmaku.bili","B站",screen("搜索框"),null,"fixture"); return read(); }
            if(n==1) { observed[0]=m.toString().contains("搜索框"); app.stateEngine().rebuildSnapshot("tv.danmaku.bili","B站",screen("搜索结果：测试关键词"),null,"fixture"); return read(); }
            observed[1]=m.toString().contains("搜索结果：测试关键词");
            return new JSONObject().put("role","assistant").put("content","已读取搜索结果");
        };
        ProviderConfig.Snapshot provider=new ProviderConfig.Snapshot("https://api.deepseek.com","fixture","synthetic-key",true);
        DshRuntime.Listener listener=new DshRuntime.Listener(){public void status(String s){} public void delta(String s){} public void settled(){}};
        try(SessionStore store=new SessionStore(app)) {
            String session=store.createSession().id;
            String turn=new DshRuntime(store,model).run(session,"读取结果",true,provider,app.dsh().plugins.snapshot(true),new Cancellation(10000),listener);
            assertEquals("completed",store.turnState(turn)); assertTrue(observed[0]); assertTrue(observed[1]); assertEquals(3,requests.get());
        }
    }

    private static JSONObject skill(String id,String text) throws Exception {
        JSONObject parameters=new JSONObject(); if(text!=null) parameters.put("text",text);
        JSONObject args=new JSONObject().put("skill_id",id).put("args",parameters);
        return new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(
                new JSONObject().put("id","call_0").put("type","function")
                        .put("function",new JSONObject().put("name","run_skill").put("arguments",args.toString()))));
    }

    private static void mountSearchScreen(ShadowAccessibilityService service,String typed,boolean results,AtomicInteger actions) {
        AccessibilityNodeInfo root=AccessibilityNodeInfo.obtain(); root.setPackageName("tv.danmaku.bili");
        root.setClassName("android.widget.FrameLayout"); root.setVisibleToUser(true); root.setEnabled(true);
        if(results) {
            AccessibilityNodeInfo result=AccessibilityNodeInfo.obtain(); result.setClassName("android.widget.TextView");
            result.setText("搜索结果：测试关键词"); result.setVisibleToUser(true); result.setEnabled(true); result.setBoundsInScreen(new Rect(10,200,800,280));
            ((ShadowAccessibilityNodeInfo)Shadow.extract(root)).addChild(result);
        } else {
            AccessibilityNodeInfo field=AccessibilityNodeInfo.obtain(); field.setClassName("android.widget.EditText");
            field.setText(typed.isEmpty()?"搜索框":typed); field.setViewIdResourceName("tv.danmaku.bili:id/search_src_text");
            field.setEditable(true); field.setClickable(true); field.setEnabled(true); field.setVisibleToUser(true); field.setBoundsInScreen(new Rect(10,20,700,100));
            ((ShadowAccessibilityNodeInfo)Shadow.extract(field)).setOnPerformActionListener((action,args)->{
                if(action==AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    actions.incrementAndGet(); CharSequence value=args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
                    mountSearchScreen(service,value==null?"":value.toString(),false,actions); return true;
                }
                if(action==AccessibilityNodeInfo.ACTION_CLICK) { actions.incrementAndGet(); return true; }
                return action==AccessibilityNodeInfo.ACTION_FOCUS;
            });
            AccessibilityNodeInfo search=AccessibilityNodeInfo.obtain(); search.setClassName("android.widget.Button"); search.setText("搜索");
            search.setClickable(true); search.setEnabled(true); search.setVisibleToUser(true); search.setBoundsInScreen(new Rect(720,20,900,100));
            ((ShadowAccessibilityNodeInfo)Shadow.extract(search)).setOnPerformActionListener((action,args)->{
                if(action!=AccessibilityNodeInfo.ACTION_CLICK) return false;
                actions.incrementAndGet(); mountSearchScreen(service,typed,true,actions); return true;
            });
            ((ShadowAccessibilityNodeInfo)Shadow.extract(root)).addChild(field);
            ((ShadowAccessibilityNodeInfo)Shadow.extract(root)).addChild(search);
        }
        AccessibilityWindowInfo window=AccessibilityWindowInfo.obtain(); ShadowAccessibilityWindowInfo shadow=Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION); shadow.setActive(true); shadow.setRoot(root);
        service.setWindows(Collections.singletonList(window));
    }

    @Test public void realSkillChainSearchesThenReadsTheUpdatedResultInASimulatedAndroidWindow() throws Exception {
        AgentApp app=AgentApp.get(); AtomicInteger requests=new AtomicInteger(),actions=new AtomicInteger(); boolean[] seen=new boolean[3];
        org.robolectric.android.controller.ServiceController<AgentAccessibilityService> serviceController=Robolectric.buildService(AgentAccessibilityService.class).create();
        try(SessionStore store=new SessionStore(app)) {
            AgentAccessibilityService service=serviceController.get(); ShadowAccessibilityService shadow=Shadow.extract(service);
            mountSearchScreen(shadow,"",false,actions);
            java.lang.reflect.Field instance=AgentAccessibilityService.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null,service);
            ModelTransport model=(p,m,t,c,d)->{
                int n=requests.getAndIncrement(); String history=m.toString();
                switch(n) {
                    case 0: return read();
                    case 1: seen[0]=history.contains("搜索框"); return skill("sys.tap_text","搜索框");
                    case 2: return skill("sys.type_text","测试关键词");
                    case 3: return read();
                    case 4: seen[1]=history.contains("测试关键词"); return skill("sys.tap_text","搜索");
                    case 5: return read();
                    default: seen[2]=history.contains("搜索结果：测试关键词"); return new JSONObject().put("role","assistant").put("content","已读取结果");
                }
            };
            ProviderConfig.Snapshot provider=new ProviderConfig.Snapshot("https://api.deepseek.com","fixture","synthetic-key",true);
            DshRuntime.Listener listener=new DshRuntime.Listener(){public void status(String s){} public void delta(String s){} public void settled(){}};
            String session=store.createSession().id;
            String turn=new DshRuntime(store,model).run(session,"搜索并读取结果",true,provider,app.dsh().plugins.snapshot(true),new Cancellation(15000),listener);
            assertEquals("completed",store.turnState(turn)); assertEquals(3,actions.get());
            assertTrue(seen[0]); assertTrue(seen[1]); assertTrue(seen[2]);
        } finally { serviceController.destroy(); }
    }
}
