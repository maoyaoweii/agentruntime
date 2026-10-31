package com.agentruntime.executor;

import android.app.Application;
import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.system1.RuleBasedSystem1;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=Application.class)
public class SensitiveTapConfirmationTest {
    private static StateEngine state(String label) {
        UiNode button=new UiNode("button","android.widget.Button",label,null,null,
                true,true,true,false,false,false,false,10,20,210,80,Collections.emptyList());
        UiNode root=new UiNode("root","android.widget.FrameLayout",null,null,null,
                false,true,true,false,false,false,false,0,0,1080,2000,Collections.singletonList(button));
        StateEngine state=new StateEngine(); state.rebuildSnapshot("third.party.app","目标应用",root,null,"fixture"); return state;
    }
    private static ActionExecutor executor(StateEngine state,RiskGate gate) {
        Application app=RuntimeEnvironment.getApplication();
        return new ActionExecutor(app,state,new AccessibilityBridge(state),new IntentBridge(app),new RuleBasedSystem1(),gate,new AuditLogger(app));
    }
    private static ActionSpec tap(String query) throws Exception {
        return new ActionSpec(ActionSpec.ActionType.TAP_TEXT,new JSONObject().put("text",query).put("scroll",false),
                3000,0,ActionSpec.RiskLevel.LOW,false,null);
    }
    @Test public void sensitiveMatchedTargetNeedsConfirmationEvenForPartialQuery() throws Exception {
        StateEngine state=state("发送"); RiskGate gate=new RiskGate(); AtomicInteger prompts=new AtomicInteger();
        gate.setConfirmationChannel((id,summary)->{prompts.incrementAndGet(); return false;});
        ExecResult result=executor(state,gate).execute("task","step","tap",tap("发"),Collections.emptyList());
        assertEquals("RISK_REJECTED",result.code); assertEquals(1,prompts.get());
    }
    @Test public void ordinarySearchDoesNotNeedAConfirmation() throws Exception {
        StateEngine state=state("搜索"); RiskGate gate=new RiskGate(); AtomicInteger prompts=new AtomicInteger();
        gate.setConfirmationChannel((id,summary)->{prompts.incrementAndGet(); return false;});
        ExecResult result=executor(state,gate).execute("task","step","tap",tap("搜索"),Collections.emptyList());
        assertEquals("CLICK_FAILED",result.code); assertEquals(0,prompts.get());
    }
    @Test public void cancellationDuringConfirmationPreventsTheClick() throws Exception {
        StateEngine state=state("确认支付"); RiskGate gate=new RiskGate(); AtomicBoolean cancelled=new AtomicBoolean();
        gate.setConfirmationChannel((id,summary)->{cancelled.set(true); return true;});
        ExecResult result=executor(state,gate).execute("task","step","tap",tap("确认支付"),Collections.emptyList(),cancelled,Long.MAX_VALUE);
        assertEquals("USER_CANCEL",result.code);
    }
    @Test public void explicitAmbiguousOkButtonAlsoNeedsConfirmation() throws Exception {
        StateEngine state=state("OK"); RiskGate gate=new RiskGate(); AtomicInteger prompts=new AtomicInteger();
        gate.setConfirmationChannel((id,summary)->{prompts.incrementAndGet(); return false;});
        ExecResult result=executor(state,gate).execute("task","step","tap",tap("OK"),Collections.emptyList());
        assertEquals("RISK_REJECTED",result.code); assertEquals(1,prompts.get());
    }
    @Test public void changingForegroundWhileConfirmingPreventsTheClick() throws Exception {
        StateEngine state=state("发送"); RiskGate gate=new RiskGate();
        gate.setConfirmationChannel((id,summary)->{
            state.rebuildSnapshot("different.app","different",state.current().root,null,"fixture"); return true;
        });
        ExecResult result=executor(state,gate).execute("task","step","tap",tap("发送"),Collections.emptyList());
        assertEquals("TARGET_CHANGED",result.code);
    }
    @Test public void unknownForegroundPackagePreventsTextTap() throws Exception {
        StateEngine state=state("搜索"); state.rebuildSnapshot(null,"unknown",state.current().root,null,"fixture");
        ExecResult result=executor(state,new RiskGate()).execute("task","step","tap",tap("搜索"),Collections.emptyList());
        assertEquals("A11Y_ROOT_UNAVAILABLE",result.code);
    }
}
