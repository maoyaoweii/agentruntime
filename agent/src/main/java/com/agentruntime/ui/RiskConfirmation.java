package com.agentruntime.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;

import com.agentruntime.agent.R;

import com.agentruntime.executor.RiskGate;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A confirmation belongs to the visible Activity and expires when that Activity leaves. */
public final class RiskConfirmation implements Application.ActivityLifecycleCallbacks,
        RiskGate.ConfirmationChannel {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context application;
    private volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private Request pending;

    private static final class Request {
        final CompletableFuture<Boolean> answer = new CompletableFuture<>();
        AlertDialog dialog;
        Activity owner;
    }

    public RiskConfirmation(Context context) {
        application = context.getApplicationContext();
    }

    @Override
    public boolean requestConfirmation(String actionId, String summary) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        Activity activity = foreground.get();
        if (activity == null && !Settings.canDrawOverlays(application)) return false;
        Request request = new Request();
        main.post(() -> {
            if (request.answer.isDone() || (activity != null && (foreground.get() != activity
                    || activity.isFinishing() || activity.isDestroyed()))
                    || (activity == null && !Settings.canDrawOverlays(application))) {
                request.answer.complete(false);
                return;
            }
            dismissPending();
            pending = request;
            request.owner = activity;
            try {
                Context dialogContext = activity != null ? activity : new ContextThemeWrapper(application, R.style.AppTheme);
                request.dialog = new AlertDialog.Builder(dialogContext)
                        .setTitle("确认本次操作")
                        .setMessage("即将执行需要你确认的操作：\n\n" + summary
                                + "\n\n仅在你了解操作内容时允许。")
                        .setNegativeButton("取消操作", (dialog, which) -> request.answer.complete(false))
                        .setPositiveButton("允许本次", (dialog, which) -> request.answer.complete(true))
                        .setOnCancelListener(dialog -> request.answer.complete(false))
                        .create();
                request.dialog.setCanceledOnTouchOutside(false);
                if (activity == null) request.dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                request.dialog.show();
                request.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
            } catch (RuntimeException e) {
                dismissPending();
            }
        });
        try {
            return request.answer.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            request.answer.complete(false);
            main.post(() -> {
                if (pending == request) dismissPending();
            });
        }
    }

    public void cancelPending() {
        main.post(this::dismissPending);
    }

    private void dismissPending() {
        if (pending == null) return;
        pending.answer.complete(false);
        if (pending.dialog != null) pending.dialog.dismiss();
        pending = null;
    }

    @Override public void onActivityResumed(Activity activity) {
        foreground = new WeakReference<>(activity);
    }

    @Override public void onActivityPaused(Activity activity) {
        if (foreground.get() == activity) {
            foreground.clear();
            if (pending != null && pending.owner == activity) dismissPending();
        }
    }

    @Override public void onActivityDestroyed(Activity activity) {
        onActivityPaused(activity);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
