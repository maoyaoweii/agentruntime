package com.agentruntime.ui;

import android.app.*;
import android.os.Looper;
import android.view.WindowManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSettings;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class RiskConfirmationTest {
    @Test(timeout = 8000) public void requiresExplicitApprovalFromTheVisibleActivity() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RiskConfirmation confirmation = new RiskConfirmation(activity);
        confirmation.onActivityResumed(activity);
        FutureTask<Boolean> answer = request(confirmation);
        AlertDialog dialog = awaitDialog();
        assertFalse(answer.isDone());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(answer.get(2, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test(timeout = 8000) public void leavingTheActivityRejectsThePendingOperation() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RiskConfirmation confirmation = new RiskConfirmation(activity);
        confirmation.onActivityResumed(activity);
        FutureTask<Boolean> answer = request(confirmation);
        AlertDialog dialog = awaitDialog();
        confirmation.onActivityPaused(activity);
        assertFalse(answer.get(2, TimeUnit.SECONDS));
        assertFalse(dialog.isShowing());
    }

    @Test(timeout = 8000) public void externalApplicationUsesAnAuthorizedOverlayAndAllowsRejection() throws Exception {
        ShadowSettings.setCanDrawOverlays(true);
        try {
            RiskConfirmation confirmation = new RiskConfirmation(RuntimeEnvironment.getApplication());
            FutureTask<Boolean> answer = request(confirmation);
            AlertDialog dialog = awaitDialog();
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, dialog.getWindow().getAttributes().type);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(answer.get(2, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        } finally {
            ShadowSettings.setCanDrawOverlays(false);
        }
    }

    @Test(timeout = 8000) public void withoutAVisibleActivityOrOverlayPermissionFailsClosed() throws Exception {
        ShadowSettings.setCanDrawOverlays(false);
        RiskConfirmation confirmation = new RiskConfirmation(RuntimeEnvironment.getApplication());
        assertFalse(request(confirmation).get(2, TimeUnit.SECONDS));
    }

    private FutureTask<Boolean> request(RiskConfirmation confirmation) {
        FutureTask<Boolean> task = new FutureTask<>(() -> confirmation.requestConfirmation("fixture", "synthetic operation"));
        new Thread(task, "confirmation-fixture").start();
        return task;
    }

    private AlertDialog awaitDialog() throws Exception {
        for (int i = 0; i < 100; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            if (dialog != null && dialog.isShowing()) return dialog;
            Thread.sleep(10);
        }
        throw new AssertionError("confirmation dialog did not appear");
    }
}
