package com.agentruntime.device;

import android.app.Application;
import android.content.Intent;
import android.provider.Settings;

import com.agentruntime.executor.ExecResult;
import com.agentruntime.skill.SkillEngine;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 常规动作必须是一条技能就能做完的事。
 *
 * <p>真机上模型因为"没有强行停止的能力"整轮拒绝执行——它要的是应用详情页入口，
 * 而 openSettingsPage 只认固定的几页。这里锁住 app_details 与三个常规技能。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class RoutineSkillTest {

    @Test
    public void appDetailsPageOpensTheRequestedPackage() {
        Application context = RuntimeEnvironment.getApplication();
        ExecResult result = new SystemBridge(context).openSettingsPage("app_details", "com.tencent.mm");

        assertEquals("SETTINGS_OPENED", result.code);
        Intent started = Shadows.shadowOf(context).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.getAction());
        assertEquals("package:com.tencent.mm", started.getDataString());
    }

    @Test
    public void appDetailsRejectsMalformedPackageNames() {
        Application context = RuntimeEnvironment.getApplication();
        SystemBridge bridge = new SystemBridge(context);

        assertEquals("BAD_PACKAGE", bridge.openSettingsPage("app_details", "").code);
        assertEquals("BAD_PACKAGE", bridge.openSettingsPage("app_details", "com.tencent.mm; reboot").code);
        assertEquals("BAD_PACKAGE", bridge.openSettingsPage("app_details", "../../etc/passwd").code);
    }

    @Test
    public void ordinaryPagesStillWork() {
        Application context = RuntimeEnvironment.getApplication();
        ExecResult result = new SystemBridge(context).openSettingsPage("accessibility", null);

        assertEquals("SETTINGS_OPENED", result.code);
        Intent started = Shadows.shadowOf(context).getNextStartedActivity();
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, started.getAction());
        assertTrue(SystemBridge.allowedPages().contains("app_details"));
    }

    @Test
    public void routineSkillsAreRegisteredWithTheirArguments() throws Exception {
        SkillEngine skills = new SkillEngine(RuntimeEnvironment.getApplication());

        assertTrue(skills.hasSkill("sys.open_app"));
        assertTrue(skills.hasSkill("sys.scroll_read"));
        assertTrue(skills.hasSkill("sys.press_key"));
        assertTrue(skills.hasSkill("sys.open_app_info"));
        assertTrue(skills.hasSkill("sys.force_stop"));

        assertEquals(java.util.Collections.singletonList("package"), skills.manifest("sys.open_app").requiredArgs);
        assertEquals(java.util.Collections.singletonList("direction"), skills.manifest("sys.scroll_read").requiredArgs);
        assertEquals(java.util.Collections.singletonList("key"), skills.manifest("sys.press_key").requiredArgs);
        assertEquals(java.util.Collections.singletonList("package"), skills.manifest("sys.open_app_info").requiredArgs);
        assertEquals(java.util.Collections.singletonList("package"), skills.manifest("sys.force_stop").requiredArgs);
        assertFalse(skills.manifest("sys.press_key").recoveryHint.isEmpty());
    }
}
