package com.agentruntime.dsh;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import com.agentruntime.dsh.store.SessionStore;
import org.json.JSONObject;
import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;
import com.agentruntime.dsh.ui.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=AgentApp.class)
public class DshUiTest {
    @Test @Config(qualifiers="w320dp-h640dp-xhdpi") public void smallPhoneHasVisibleInputAndSend() { assertChat(320,640); }
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void phoneHasVisibleInputAndSend() { assertChat(411,891); }
    @Test @Config(qualifiers="w891dp-h411dp-land-xxhdpi") public void landscapeKeepsChatInputVisible() { assertChat(891,411); }
    @Test @Config(qualifiers="w800dp-h1280dp-xhdpi") public void tabletHasVisibleInputAndSend() { assertChat(800,1280); }
    private void assertChat(int width,int height) {
        try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class).setup()) {
            Activity activity=controller.get(); View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
            int w=UiKit.dp(activity,width),h=UiKit.dp(activity,height); root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); root.layout(0,0,w,h);
            for(int id:new int[]{R.id.dsh_composer,R.id.dsh_send}) {
                View control=activity.findViewById(id); assertNotNull(control); assertTrue(control.getHeight()>0); int[] location=new int[2]; control.getLocationInWindow(location);
                assertTrue(location[0]>=0&&location[0]+control.getWidth()<=w); assertTrue(location[1]>=0&&location[1]+control.getHeight()<=h);
            }
            assertTrue(activity.findViewById(R.id.dsh_send).getHeight()>=UiKit.dp(activity,48));
        }
    }
    @Test public void rotationRetainsTheDraftWithoutStartingAModelRequest() {
        try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class).setup()) {
            EditText input=controller.get().findViewById(R.id.dsh_composer); input.setText("旋转前的草稿"); controller.recreate();
            assertEquals("旋转前的草稿",((EditText)controller.get().findViewById(R.id.dsh_composer)).getText().toString());
            assertNull(AgentApp.get().dsh().controller.active());
        }
    }
    @Test public void allCompanionScreensCanOpen() {
        for(Class<? extends Activity> screen:java.util.Arrays.asList(SessionsActivity.class,PluginsActivity.class,DshSettingsActivity.class,WorkspaceActivity.class)) {
            try(ActivityController<? extends Activity> controller=Robolectric.buildActivity(screen).setup()) { assertNotNull(controller.get().findViewById(android.R.id.content)); }
        }
    }
    @Test public void reusedCallIdsInLaterTurnsCannotReplaceEarlierToolDetails() throws Exception {
        SessionStore store=AgentApp.get().dsh().sessions; String session=store.createSession().id;
        for(String value:new String[]{"first-result","second-result"}) {
            String turn=store.beginTurn(session,"fixture","tools","fixture");
            store.append(session,turn,"tool_started",new JSONObject().put("id","reused").put("name","fixture").put("arguments",new JSONObject()));
            store.append(session,turn,"tool_result",new JSONObject().put("role","tool").put("tool_call_id","reused").put("content",new JSONObject().put("ok",true).put("data",value).toString()));
            store.finish(session,turn,"completed","OK");
        }
        android.content.Intent intent=new android.content.Intent(RuntimeEnvironment.getApplication(),DshActivity.class).putExtra("session",session);
        try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class,intent).setup()) {
            TextView label=findText(controller.get().getWindow().getDecorView(),"◇  fixture"); assertNotNull(label); ((View)label.getParent()).performClick();
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); String details=allText(dialog.getWindow().getDecorView());
            assertTrue(details.contains("first-result")); assertFalse(details.contains("second-result"));
        }
    }
    @Test public void exportRejectsFileUrisInsteadOfOverwritingPrivateFiles() throws Exception {
        try(ActivityController<WorkspaceActivity> controller=Robolectric.buildActivity(WorkspaceActivity.class).setup()) {
            java.io.File target=new java.io.File(AgentApp.get().getFilesDir(),"private-fixture"); java.nio.file.Files.write(target.toPath(),"untouched".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            AgentApp.get().dsh().workspace.write("export.txt","replacement",new com.agentruntime.dsh.model.Cancellation(5000));
            java.lang.reflect.Field pending=WorkspaceActivity.class.getDeclaredField("exportPath"); pending.setAccessible(true); pending.set(controller.get(),"export.txt");
            java.lang.reflect.Method result=WorkspaceActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,android.content.Intent.class); result.setAccessible(true);
            result.invoke(controller.get(),41,Activity.RESULT_OK,new android.content.Intent().setData(android.net.Uri.fromFile(target)).addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            assertEquals("untouched",new String(java.nio.file.Files.readAllBytes(target.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private static TextView findText(View view,String text) {
        if(view instanceof TextView&&((TextView)view).getText().toString().equals(text)) return (TextView)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { TextView found=findText(((ViewGroup)view).getChildAt(i),text); if(found!=null) return found; }
        return null;
    }
    private static String allText(View view) {
        StringBuilder text=new StringBuilder(); if(view instanceof TextView) text.append(((TextView)view).getText());
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) text.append(allText(((ViewGroup)view).getChildAt(i))); return text.toString();
    }
}
