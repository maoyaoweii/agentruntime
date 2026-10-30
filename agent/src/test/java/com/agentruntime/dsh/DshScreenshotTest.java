package com.agentruntime.dsh;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.ui.*;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;

/** Optional Linux-native view capture for visual review. These are simulated, not device screenshots. */
@RunWith(DshScreenshotTest.OptionalRunner.class)
@Config(sdk=30,application=AgentApp.class,qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class DshScreenshotTest {
    public static final class OptionalRunner extends Runner {
        private final Runner delegate;
        public OptionalRunner(Class<?> type) throws InitializationError {
            delegate=Boolean.getBoolean("dsh.capture.ui")?new RobolectricTestRunner(type):new BlockJUnit4ClassRunner(type);
        }
        @Override public Description getDescription() { return delegate.getDescription(); }
        @Override public void run(RunNotifier notifier) { delegate.run(notifier); }
    }
    @Test public void captureFourScreensFromNativeAndroidViews() throws Exception {
        Assume.assumeTrue("Opt-in Linux native graphics capture",Boolean.getBoolean("dsh.capture.ui"));
        com.agentruntime.dsh.store.SessionStore store=AgentApp.get().dsh().sessions;
        String chat=store.createSession().id;
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),DshActivity.class).putExtra("session",chat);
        try(ActivityController<DshActivity> screen=Robolectric.buildActivity(DshActivity.class,intent).setup()) { capture(screen.get(),"01-chat.png"); }
        store.delete(chat);
        for(String name:new String[]{"手机任务","旅行灵感","今日计划"}) store.rename(store.createSession().id,name);
        try(ActivityController<SessionsActivity> screen=Robolectric.buildActivity(SessionsActivity.class).setup()) { capture(screen.get(),"02-sessions.png"); }
        try(ActivityController<PluginsActivity> screen=Robolectric.buildActivity(PluginsActivity.class).setup()) { capture(screen.get(),"03-plugins.png"); }
        try(ActivityController<DshSettingsActivity> screen=Robolectric.buildActivity(DshSettingsActivity.class).setup()) { capture(screen.get(),"04-settings.png"); }
    }
    private void capture(Activity activity,String name) throws Exception {
        View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
        int width=UiKit.dp(activity,411),height=UiKit.dp(activity,891);
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); root.draw(new Canvas(image));
        File directory=new File(System.getProperty("dsh.capture.dir")); if(!directory.exists()&&!directory.mkdirs()) throw new java.io.IOException("Cannot create capture directory");
        try(FileOutputStream out=new FileOutputStream(new File(directory,name))) { if(!image.compress(Bitmap.CompressFormat.PNG,100,out)) throw new java.io.IOException("Cannot encode screenshot"); }
        finally { image.recycle(); }
    }
}
