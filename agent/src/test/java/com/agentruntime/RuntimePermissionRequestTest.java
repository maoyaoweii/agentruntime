package com.agentruntime;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import com.agentruntime.ui.UiKit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowContextWrapper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 运行时权限申请逻辑。
 *
 * <p><b>为什么必须测：</b>这段逻辑的失败方式全都很安静——传一个清单里没声明的权限，
 * 系统直接判拒绝，界面上看不出任何异常，用户却永远点不出那个弹窗；
 * 而重复申请已授予的权限只会白弹一次窗。两种毛病都不会抛异常，
 * 只能靠断言清单内容来挡。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RuntimePermissionRequestTest {

    @Test
    @Config(sdk = 28)
    public void legacyDeviceAsksForReadAndWriteExternalStorage() {
        Set<String> perms = new HashSet<>(Arrays.asList(
                UiKit.runtimePermissions(RuntimeEnvironment.getApplication())));

        assertTrue("API 28 需要读外部存储", perms.contains("android.permission.READ_EXTERNAL_STORAGE"));
        assertTrue("API 28 需要写外部存储", perms.contains("android.permission.WRITE_EXTERNAL_STORAGE"));
        assertFalse("API 28 不该申请 media 分类型权限",
                perms.contains("android.permission.READ_MEDIA_IMAGES"));
    }

    @Test
    @Config(sdk = 30)
    public void android11AsksForReadButNotTheIgnoredWritePermission() {
        Set<String> perms = new HashSet<>(Arrays.asList(
                UiKit.runtimePermissions(RuntimeEnvironment.getApplication())));

        // 写外部存储在 API 29 之上已被系统忽略（maxSdkVersion=29），申请它只会
        // 弹一个永远不会出现的对话框，所以清单里声明了、代码里也必须不申请。
        assertFalse("API 30 不该申请被忽略的写外部存储",
                perms.contains("android.permission.WRITE_EXTERNAL_STORAGE"));
        assertTrue("API 30 仍需要读 app 专属外部目录",
                perms.contains("android.permission.READ_EXTERNAL_STORAGE"));
        assertFalse("API 30 还不该用 media 分类型权限",
                perms.contains("android.permission.READ_MEDIA_IMAGES"));
    }

    @Test
    @Config(sdk = 33)
    public void android13SwitchesToPerMediaTypePermissions() {
        Set<String> perms = new HashSet<>(Arrays.asList(
                UiKit.runtimePermissions(RuntimeEnvironment.getApplication())));

        assertTrue(perms.contains("android.permission.POST_NOTIFICATIONS"));
        assertTrue(perms.contains("android.permission.READ_MEDIA_IMAGES"));
        assertTrue(perms.contains("android.permission.READ_MEDIA_VIDEO"));
        assertTrue(perms.contains("android.permission.READ_MEDIA_AUDIO"));
        assertFalse("API 33 起 READ_EXTERNAL_STORAGE 已被 media 权限取代",
                perms.contains("android.permission.READ_EXTERNAL_STORAGE"));
    }

    /** 只申请清单里真的声明了的权限，是这份实现最容易被改坏的不变量。 */
    @Test
    @Config(sdk = 33)
    public void everyRequestedPermissionIsDeclaredInManifest() throws Exception {
        android.content.pm.PackageInfo info = RuntimeEnvironment.getApplication().getPackageManager()
                .getPackageInfo(RuntimeEnvironment.getApplication().getPackageName(),
                        PackageManager.GET_PERMISSIONS);
        Set<String> declared = new HashSet<>();
        if (info.requestedPermissions != null) {
            declared.addAll(Arrays.asList(info.requestedPermissions));
        }
        for (String p : UiKit.runtimePermissions(RuntimeEnvironment.getApplication())) {
            assertTrue("清单里没有声明却要申请（系统会直接判拒绝）：" + p, declared.contains(p));
        }
    }

    @Test
    @Config(sdk = 30)
    public void grantedListShrinksAsPermissionsAreDenied() {
        ShadowContextWrapper shadow = Shadow.extract(RuntimeEnvironment.getApplication());
        String[] all = UiKit.runtimePermissions(RuntimeEnvironment.getApplication());
        shadow.denyPermissions(all);
        assertEquals(all.length,
                UiKit.ungrantedRuntimePermissions(RuntimeEnvironment.getApplication()).length);

        shadow.grantPermissions(all);
        assertEquals(0, UiKit.ungrantedRuntimePermissions(RuntimeEnvironment.getApplication()).length);
        assertEquals(all.length,
                UiKit.grantedRuntimePermissions(RuntimeEnvironment.getApplication()).length);
    }

    /** 未授权时必须真的发起申请；已全部授权时不能空转弹窗。 */
    @Test
    @Config(sdk = 30)
    public void requestSendsExactlyTheMissingPermissions() {
        ShadowContextWrapper shadow = Shadow.extract(RuntimeEnvironment.getApplication());
        String[] all = UiKit.runtimePermissions(RuntimeEnvironment.getApplication());
        shadow.denyPermissions(all);

        // Activity.requestPermissions 是 final，改不动；Robolectric 的 ShadowActivity
        // 会把它记下来，这里读的就是系统真实会收到的那份请求。
        ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup();
        Activity a = controller.get();

        UiKit.requestRuntimePermissions(a, 1004);
        ShadowActivity shadowActivity = Shadow.extract(a);
        ShadowActivity.PermissionsRequest first = shadowActivity.getLastRequestedPermission();
        assertNotNull("未授权时应发起申请", first);
        assertEquals("应把全部未授予的一次性交给系统，由系统逐个弹窗",
                all.length, first.requestedPermissions.length);
        assertEquals("android.permission.POST_NOTIFICATIONS", first.requestedPermissions[0]);
        assertEquals(1004, first.requestCode);

        // 已授予之后不应再弹。注意 getLastRequestedPermission() 是粘的——
        // 它不会因为"这次没申请"而变 null，只能靠对象身份判断有没有新请求。
        shadow.grantPermissions(all);
        UiKit.requestRuntimePermissions(a, 1004);
        assertSame("已全部授予时不应再发起申请",
                first, Shadow.<ShadowActivity>extract(a).getLastRequestedPermission());

        controller.pause().stop().destroy();
    }

    /** 权限展示名必须覆盖清单里的每一项，否则申请前说明页会出现空行。 */
    @Test
    @Config(sdk = 33)
    public void everyPermissionHasAHumanLabel() {
        for (String p : UiKit.runtimePermissions(RuntimeEnvironment.getApplication())) {
            String label = UiKit.permissionLabel(p);
            assertNotNull(label);
            assertFalse("权限缺少展示名：" + p, label.isEmpty());
            assertFalse("展示名回退成了裸权限名：" + p, label.contains("permission."));
        }
    }

    @Test
    @Config(sdk = 33)
    public void storageAvailabilityFollowsApiLevel() {
        ShadowContextWrapper shadow = Shadow.extract(RuntimeEnvironment.getApplication());
        shadow.denyPermissions("android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO");
        assertFalse(UiKit.hasReadStoragePermission(RuntimeEnvironment.getApplication()));

        shadow.grantPermissions("android.permission.READ_MEDIA_IMAGES");
        assertTrue(UiKit.hasReadStoragePermission(RuntimeEnvironment.getApplication()));
    }

    public static class NoOpActivity extends Activity {
    }
}
