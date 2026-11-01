package com.agentruntime.executor;

import android.app.Application;
import com.agentruntime.audit.AuditLogger;
import com.agentruntime.device.AccessibilityBridge;
import com.agentruntime.device.IntentBridge;
import com.agentruntime.dsl.ActionSpec;
import com.agentruntime.state.StateEngine;
import com.agentruntime.state.UiNode;
import com.agentruntime.system1.RuleBasedSystem1;
import com.agentruntime.vision.ScreenOcrSettings;
import com.agentruntime.vision.ScreenTextOcr;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30, application=Application.class)
public class ScreenOcrFallbackTest {
    private Application context;
    private StateEngine state;

    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("dsh_screen_ocr", 0).edit().clear().commit();
        state=new StateEngine();
        state.rebuildSnapshot("example.chat", "Chat", node(null), null, "test");
    }
    @After public void cleanup() { context.getSharedPreferences("dsh_screen_ocr", 0).edit().clear().commit(); }

    /** 默认开启：空树就该自己看图，不能要求用户先去设置里打开。 */
    @Test public void defaultOnCapturesWithoutAskingForASetting() throws Exception {
        context.getSharedPreferences("dsh_screen_ocr",android.content.Context.MODE_PRIVATE).edit().clear().commit();
        AtomicInteger calls=new AtomicInteger();
        ExecResult result=run((s,c,d)->{ calls.incrementAndGet(); return new ScreenTextOcr.Result("SCREEN_OCR","可见内容"); },null);
        assertEquals("SCREEN_OCR",result.code);
        assertEquals(1,calls.get());
    }

    /** 用户显式关掉后仍然不截图，并说明是关掉的。 */
    @Test public void explicitlyDisabledNeverCapturesAndSaysSo() throws Exception {
        ScreenOcrSettings.setEnabled(context,false);
        AtomicInteger calls=new AtomicInteger();
        ExecResult result=run((s,c,d)->{ calls.incrementAndGet(); return new ScreenTextOcr.Result("SCREEN_OCR","private"); },null);
        assertEquals("SCREEN_EMPTY",result.code);
        assertEquals(0,calls.get());
        assertTrue(result.detail.contains("画面文字识别"));
        assertFalse(result.detail.contains("微信不支持"));
    }

    @Test public void enabledEmptyTreeReturnsOcrTextWithLimitsAndNoClickClaim() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        AtomicInteger calls=new AtomicInteger();
        ExecResult result=run((s,c,d)->{ calls.incrementAndGet(); return new ScreenTextOcr.Result("SCREEN_OCR","可见内容"); },null);
        assertEquals("SCREEN_OCR",result.code); assertEquals(1,calls.get());
        assertTrue(result.detail.contains("可见内容")); assertTrue(result.detail.contains("可能误识别"));
        assertTrue(result.detail.contains("不能证明文字可点击")); assertFalse(result.toLogJson().has("detail"));
    }

    @Test public void accessibleTextDoesNotTakeAScreenshot() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        state.rebuildSnapshot("example.chat", "Chat", node("已暴露的文字"), null, "test");
        AtomicInteger calls=new AtomicInteger();
        ExecResult result=run((s,c,d)->{ calls.incrementAndGet(); return null; },null);
        assertEquals("SCREEN_TEXT",result.code); assertEquals(0,calls.get());
        assertTrue(result.detail.contains("已暴露的文字"));
    }

    @Test public void unlabelledEditableFieldDoesNotTriggerScreenshotFallback() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        UiNode edit = new UiNode("n1","EditText",null,null,null,true,true,true,true,true,false,true,
                0,0,1080,200,Collections.emptyList());
        state.rebuildSnapshot("example.chat","Chat",edit,null,"test");
        AtomicInteger calls=new AtomicInteger();
        ExecResult result=run((s,c,d)->{ calls.incrementAndGet(); return null; },null);
        assertEquals("SCREEN_TEXT",result.code); assertEquals(0,calls.get());
    }

    @Test public void unlabelledScrollTreeStillUsesOcr() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        UiNode scroll = new UiNode("n2","ScrollView",null,null,null,false,true,true,false,false,true,false,
                0,0,1080,2200,Collections.emptyList());
        UiNode root = new UiNode("n1","View",null,null,null,false,true,true,false,false,false,false,
                0,0,1080,2200,Collections.singletonList(scroll));
        state.rebuildSnapshot("example.chat", "Chat", root, null, "test");
        ExecResult result=run((s,c,d)->new ScreenTextOcr.Result("SCREEN_OCR","synthetic page"),null);
        assertEquals("SCREEN_OCR",result.code);
    }

    @Test public void oneUnlabelledScrollableRootStillUsesOcr() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        UiNode scrollable=new UiNode("root","View",null,null,null,false,true,true,false,false,true,false,
                0,0,1080,2200,Collections.emptyList());
        state.rebuildSnapshot("example.chat", "Chat", scrollable, null, "test");
        ExecResult result=run((s,c,d)->new ScreenTextOcr.Result("SCREEN_OCR","synthetic page"),null);
        assertEquals("SCREEN_OCR",result.code); assertTrue(result.detail.contains("synthetic page"));
    }

    @Test public void protectedAndEmptyImagesReportTheActualFailure() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        ExecResult secure=run((s,c,d)->new ScreenTextOcr.Result("SCREENSHOT_SECURE",null),null);
        assertFalse(secure.ok()); assertEquals("SCREENSHOT_SECURE",secure.code); assertTrue(secure.detail.contains("保护"));
        ExecResult empty=run((s,c,d)->new ScreenTextOcr.Result("OCR_EMPTY",null),null);
        assertFalse(empty.ok()); assertEquals("OCR_EMPTY",empty.code);
    }

    @Test public void cancellationDiscardsRecognizedText() throws Exception {
        ScreenOcrSettings.setEnabled(context,true); AtomicBoolean cancelled=new AtomicBoolean();
        ExecResult result=run((s,c,d)->{ c.set(true); return new ScreenTextOcr.Result("SCREEN_OCR","secret"); },cancelled);
        assertEquals("USER_CANCEL",result.code); assertFalse(result.detail.contains("secret"));
    }

    @Test public void changingTheSettingDiscardsInFlightTextEvenWhenReenabled() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        ExecResult result=run((s,c,d)->{
            ScreenOcrSettings.setEnabled(context,false); ScreenOcrSettings.setEnabled(context,true);
            return new ScreenTextOcr.Result("SCREEN_OCR","secret");
        },null);
        assertEquals("OCR_DISABLED",result.code); assertFalse(result.detail.contains("secret"));
    }

    @Test public void changingTheForegroundDiscardsInFlightText() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        ExecResult result=run((s,c,d)->{
            state.rebuildSnapshot("example.other", "Other", node(null), null, "test");
            return new ScreenTextOcr.Result("SCREEN_OCR","secret");
        },null);
        assertEquals("SCREEN_CHANGED",result.code); assertFalse(result.detail.contains("secret"));
    }

    @Test public void separateReadsCanCollectNewVisiblePages() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        AtomicInteger page=new AtomicInteger();
        ScreenTextOcr ocr=(s,c,d)->new ScreenTextOcr.Result("SCREEN_OCR","合成页面 "+page.incrementAndGet());
        ExecResult first=run(ocr,null), second=run(ocr,null);
        assertTrue(first.detail.contains("合成页面 1")); assertTrue(second.detail.contains("合成页面 2"));
    }

    @Test public void missingRootOrOwnWindowNeverTriggersOcr() throws Exception {
        ScreenOcrSettings.setEnabled(context,true);
        AtomicInteger calls=new AtomicInteger();
        ScreenTextOcr ocr=(s,c,d)->{ calls.incrementAndGet(); return null; };
        state.rebuildSnapshot(null,null,null,null,"test");
        assertEquals("A11Y_ROOT_UNAVAILABLE",run(ocr,null).code);
        state.rebuildSnapshot(context.getPackageName(),"DSH",node(null),null,"test");
        assertEquals("SELF_WINDOW_FOCUSED",run(ocr,null).code);
        assertEquals(0,calls.get());
    }

    @Test @Config(sdk=28) public void olderAndroidReportsThatScreenCaptureIsUnsupported() {
        ScreenOcrSettings.setEnabled(context,true);
        com.agentruntime.vision.OnDeviceScreenOcr real = new com.agentruntime.vision.OnDeviceScreenOcr(
                context,new com.agentruntime.vision.ScreenCapturer(null));
        assertEquals("SCREENSHOT_UNSUPPORTED",real.read(null,null,System.currentTimeMillis()+1000).code);
    }

    private ExecResult run(ScreenTextOcr ocr, AtomicBoolean cancellation) throws Exception {
        ActionExecutor executor=new ActionExecutor(context,state,new AccessibilityBridge(state),
                new IntentBridge(context),new RuleBasedSystem1(),new RiskGate(),new AuditLogger(context));
        executor.attach(new ExtendedCapabilities(null,null,null,null,null,null,ocr));
        ActionSpec spec=new ActionSpec(ActionSpec.ActionType.READ_SCREEN,new JSONObject().put("max_chars",4000),
                22000,0,ActionSpec.RiskLevel.LOW,false,null);
        return executor.execute("fixture","read","read",spec,Collections.emptyList(),cancellation,System.currentTimeMillis()+30000);
    }
    private static UiNode node(String text) {
        return new UiNode("n1","android.view.View",text,null,null,false,true,true,false,false,false,false,
                0,0,1080,2200,Collections.emptyList());
    }
}
