package com.agentruntime.service;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Rect;
import com.agentruntime.AgentApp;
import com.agentruntime.state.WorldState;
import com.agentruntime.state.UiNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=AgentApp.class)
public class AccessibilityWindowFallbackTest {
    private static AccessibilityWindowInfo window(String pkg,int type,boolean active) {
        AccessibilityWindowInfo window=AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow=Shadow.extract(window); shadow.setType(type); shadow.setActive(active);
        if(pkg!=null) { AccessibilityNodeInfo root=AccessibilityNodeInfo.obtain(); root.setPackageName(pkg); shadow.setRoot(root); }
        return window;
    }

    @Test public void fallbackUsesTopmostApplicationInsteadOfAnOlderWindow() {
        AgentAccessibilityService service=Robolectric.buildService(AgentAccessibilityService.class).create().get();
        ShadowAccessibilityService shadow=Shadow.extract(service);
        shadow.setWindows(Arrays.asList(window("front.app",AccessibilityWindowInfo.TYPE_APPLICATION,false),
                window("behind.app",AccessibilityWindowInfo.TYPE_APPLICATION,false)));
        WorldState state=service.snapshot("test"); assertNotNull(state); assertEquals("front.app",state.foregroundPackage);
    }

    @Test public void fallbackDoesNotReadBehindAnActiveSystemWindow() {
        AgentAccessibilityService service=Robolectric.buildService(AgentAccessibilityService.class).create().get();
        ShadowAccessibilityService shadow=Shadow.extract(service);
        shadow.setWindows(Arrays.asList(window("system.dialog",AccessibilityWindowInfo.TYPE_SYSTEM,true),
                window("behind.app",AccessibilityWindowInfo.TYPE_APPLICATION,false)));
        WorldState state=service.snapshot("test"); assertNotNull(state); assertNull(state.root);
    }

    @Test public void fallbackNeverSubstitutesAnOlderApplicationForAnUnreadableActiveOne() {
        AgentAccessibilityService service=Robolectric.buildService(AgentAccessibilityService.class).create().get();
        ShadowAccessibilityService shadow=Shadow.extract(service);
        shadow.setWindows(Arrays.asList(window(null,AccessibilityWindowInfo.TYPE_APPLICATION,true),
                window("behind.app",AccessibilityWindowInfo.TYPE_APPLICATION,false)));
        WorldState state=service.snapshot("test"); assertNotNull(state); assertNull(state.root);
    }

    @Test public void snapshotDoesNotExposePasswordNodeTextOrDescription() {
        AgentAccessibilityService service=Robolectric.buildService(AgentAccessibilityService.class).create().get();
        AccessibilityNodeInfo root=AccessibilityNodeInfo.obtain(); root.setPackageName("front.app");
        AccessibilityNodeInfo password=AccessibilityNodeInfo.obtain(); password.setClassName("android.widget.EditText");
        password.setPassword(true); password.setText("secret-password"); password.setContentDescription("secret-password");
        ((ShadowAccessibilityNodeInfo)Shadow.extract(root)).addChild(password);
        AccessibilityWindowInfo win=AccessibilityWindowInfo.obtain(); ShadowAccessibilityWindowInfo window=Shadow.extract(win);
        window.setType(AccessibilityWindowInfo.TYPE_APPLICATION); window.setActive(true); window.setRoot(root);
        ((ShadowAccessibilityService)Shadow.extract(service)).setWindows(Collections.singletonList(win));
        WorldState state=service.snapshot("test"); assertNotNull(state); assertNotNull(state.root);
        assertFalse(state.root.toJson().toString().contains("secret-password"));
    }

    @Test public void clickRefusesMatchingNodesInADifferentForegroundApp() {
        AgentAccessibilityService service=Robolectric.buildService(AgentAccessibilityService.class).create().get();
        AccessibilityNodeInfo root=AccessibilityNodeInfo.obtain(); root.setPackageName("other.app");
        AccessibilityNodeInfo button=AccessibilityNodeInfo.obtain(); button.setClassName("android.widget.Button");
        button.setText("搜索"); button.setClickable(true); button.setEnabled(true); button.setVisibleToUser(true);
        java.util.concurrent.atomic.AtomicInteger clicks=new java.util.concurrent.atomic.AtomicInteger();
        ((ShadowAccessibilityNodeInfo)Shadow.extract(button)).setOnPerformActionListener((action,args)->{clicks.incrementAndGet(); return true;});
        ((ShadowAccessibilityNodeInfo)Shadow.extract(root)).addChild(button);
        AccessibilityWindowInfo win=AccessibilityWindowInfo.obtain(); ShadowAccessibilityWindowInfo window=Shadow.extract(win);
        window.setType(AccessibilityWindowInfo.TYPE_APPLICATION); window.setActive(true); window.setRoot(root);
        ((ShadowAccessibilityService)Shadow.extract(service)).setWindows(Collections.singletonList(win));
        UiNode target=new UiNode("n","android.widget.Button","搜索",null,null,true,true,true,false,false,false,false,
                0,0,100,100,Collections.emptyList());
        assertFalse(service.click(target,"expected.app")); assertEquals(0,clicks.get());
    }

    @Test public void textTapDoesNotFallBackToAChangedButtonWithTheSameResourceId() {
        AgentAccessibilityService service=Robolectric.buildService(AgentAccessibilityService.class).create().get();
        AccessibilityNodeInfo root=AccessibilityNodeInfo.obtain(); root.setPackageName("expected.app");
        AccessibilityNodeInfo button=AccessibilityNodeInfo.obtain(); button.setClassName("android.widget.Button");
        button.setText("删除"); button.setViewIdResourceName("expected.app:id/action"); button.setClickable(true);
        button.setEnabled(true); button.setVisibleToUser(true); button.setBoundsInScreen(new Rect(0,0,100,100));
        java.util.concurrent.atomic.AtomicInteger clicks=new java.util.concurrent.atomic.AtomicInteger();
        ((ShadowAccessibilityNodeInfo)Shadow.extract(button)).setOnPerformActionListener((action,args)->{clicks.incrementAndGet(); return true;});
        ((ShadowAccessibilityNodeInfo)Shadow.extract(root)).addChild(button);
        AccessibilityWindowInfo win=AccessibilityWindowInfo.obtain(); ShadowAccessibilityWindowInfo window=Shadow.extract(win);
        window.setType(AccessibilityWindowInfo.TYPE_APPLICATION); window.setActive(true); window.setRoot(root);
        ((ShadowAccessibilityService)Shadow.extract(service)).setWindows(Collections.singletonList(win));
        UiNode target=new UiNode("n","android.widget.Button","发送",null,"expected.app:id/action",true,true,true,false,false,false,false,
                0,0,100,100,Collections.emptyList());
        assertFalse(service.click(target,"expected.app")); assertEquals(0,clicks.get());
    }

    @Test public void textTapCanClickTheSameObservedButtonInTheSameApp() {
        AgentAccessibilityService service=Robolectric.buildService(AgentAccessibilityService.class).create().get();
        AccessibilityNodeInfo root=AccessibilityNodeInfo.obtain(); root.setPackageName("expected.app");
        AccessibilityNodeInfo button=AccessibilityNodeInfo.obtain(); button.setClassName("android.widget.Button");
        button.setText("搜索"); button.setClickable(true); button.setEnabled(true); button.setVisibleToUser(true);
        button.setBoundsInScreen(new Rect(0,0,100,100));
        java.util.concurrent.atomic.AtomicInteger clicks=new java.util.concurrent.atomic.AtomicInteger();
        ((ShadowAccessibilityNodeInfo)Shadow.extract(button)).setOnPerformActionListener((action,args)->{clicks.incrementAndGet(); return true;});
        ((ShadowAccessibilityNodeInfo)Shadow.extract(root)).addChild(button);
        AccessibilityWindowInfo win=AccessibilityWindowInfo.obtain(); ShadowAccessibilityWindowInfo window=Shadow.extract(win);
        window.setType(AccessibilityWindowInfo.TYPE_APPLICATION); window.setActive(true); window.setRoot(root);
        ((ShadowAccessibilityService)Shadow.extract(service)).setWindows(Collections.singletonList(win));
        UiNode target=new UiNode("n","android.widget.Button","搜索",null,null,true,true,true,false,false,false,false,
                0,0,100,100,Collections.emptyList());
        assertTrue(service.click(target,"expected.app")); assertEquals(1,clicks.get());
    }
}
