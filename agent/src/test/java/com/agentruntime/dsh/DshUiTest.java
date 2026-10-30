package com.agentruntime.dsh;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Spinner;
import android.content.Intent;
import android.text.method.PasswordTransformationMethod;
import android.view.WindowManager;
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
    @Test public void rootTabsOpenTheirDestinationsAndHighlightTheCurrentPage() {
        try(ActivityController<DshActivity> chat=Robolectric.buildActivity(DshActivity.class).setup()) {
            assertTrue(chat.get().findViewById(R.id.dsh_tab_chat).isSelected());
            chat.get().findViewById(R.id.dsh_tab_plugins).performClick();
            Intent next=Shadows.shadowOf(chat.get()).getNextStartedActivity();
            assertEquals(PluginsActivity.class.getName(),next.getComponent().getClassName());
            assertFalse(chat.get().isFinishing());
        }
        try(ActivityController<PluginsActivity> plugins=Robolectric.buildActivity(PluginsActivity.class).setup()) {
            assertTrue(plugins.get().findViewById(R.id.dsh_tab_plugins).isSelected());
            plugins.get().findViewById(R.id.dsh_tab_settings).performClick();
            Intent next=Shadows.shadowOf(plugins.get()).getNextStartedActivity();
            assertEquals(DshSettingsActivity.class.getName(),next.getComponent().getClassName());
            assertTrue(plugins.get().isFinishing());
        }
        try(ActivityController<SessionsActivity> sessions=Robolectric.buildActivity(SessionsActivity.class).setup();
            ActivityController<DshSettingsActivity> settings=Robolectric.buildActivity(DshSettingsActivity.class).setup()) {
            assertTrue(sessions.get().findViewById(R.id.dsh_tab_sessions).isSelected());
            assertTrue(settings.get().findViewById(R.id.dsh_tab_settings).isSelected());
        }
    }
    @Test public void pluginSwitchUpdatesTheActualRegistry() {
        try(ActivityController<PluginsActivity> controller=Robolectric.buildActivity(PluginsActivity.class).setup()) {
            Switch toggle=(Switch)findDescription(controller.get().getWindow().getDecorView(),"启用本机工作区");
            assertNotNull(toggle); boolean original=AgentApp.get().dsh().plugins.enabled("workspace");
            try { toggle.setChecked(!original); assertEquals(!original,AgentApp.get().dsh().plugins.enabled("workspace")); }
            finally { toggle.setChecked(original); }
        }
    }
    @Test public void revealingTheKeyIsExplicitAndItIsHiddenAgainOnPause() {
        try(ActivityController<DshSettingsActivity> controller=Robolectric.buildActivity(DshSettingsActivity.class).setup()) {
            DshSettingsActivity activity=controller.get(); EditText key=activity.findViewById(R.id.dsh_api_key); key.setText("synthetic-test-key");
            assertFalse(key.isSaveEnabled()); assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
            activity.findViewById(R.id.dsh_reveal_key).performClick();
            assertFalse(key.getTransformationMethod() instanceof PasswordTransformationMethod);
            assertTrue((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            controller.pause(); assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        }
    }
    @Test public void rotationDoesNotRestoreAnEnteredKey() {
        try(ActivityController<DshSettingsActivity> controller=Robolectric.buildActivity(DshSettingsActivity.class).setup()) {
            DshSettingsActivity activity=controller.get();
            ((EditText)activity.findViewById(R.id.dsh_api_key)).setText("synthetic-test-key");
            ((EditText)activity.findViewById(R.id.dsh_model_url)).setText("https://example.org/v1");
            ((Spinner)activity.findViewById(R.id.dsh_protocol)).setSelection(2);
            boolean expected=!((Switch)activity.findViewById(R.id.dsh_thinking)).isChecked();
            ((Switch)activity.findViewById(R.id.dsh_thinking)).setChecked(expected);
            controller.recreate(); activity=controller.get();
            assertEquals("",((EditText)activity.findViewById(R.id.dsh_api_key)).getText().toString());
            assertEquals("https://example.org/v1",((EditText)activity.findViewById(R.id.dsh_model_url)).getText().toString());
            assertEquals(2,((Spinner)activity.findViewById(R.id.dsh_protocol)).getSelectedItemPosition());
            assertEquals(expected,((Switch)activity.findViewById(R.id.dsh_thinking)).isChecked());
        }
    }
    @Test public void aTranscriptReadFailureIsVisibleToTheUser() throws Exception {
        SessionStore store=AgentApp.get().dsh().sessions; String session=store.createSession().id;
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase(); db.execSQL("ALTER TABLE events RENAME TO events_temporarily_unavailable");
        try {
            Intent intent=new Intent(RuntimeEnvironment.getApplication(),DshActivity.class).putExtra("session",session);
            try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class,intent).setup()) {
                TextView status=controller.get().findViewById(R.id.dsh_status);
                assertEquals(View.VISIBLE,status.getVisibility()); assertTrue(status.getText().toString().contains("读取会话失败"));
            }
        } finally { db.execSQL("ALTER TABLE events_temporarily_unavailable RENAME TO events"); }
    }
    @Test @Config(qualifiers="w320dp-h640dp-xhdpi") public void fourPrimaryScreensKeepNavigationUsableOnASmallPhone() {
        for(Class<? extends Activity> screen:java.util.Arrays.asList(DshActivity.class,SessionsActivity.class,PluginsActivity.class,DshSettingsActivity.class)) {
            try(ActivityController<? extends Activity> controller=Robolectric.buildActivity(screen).setup()) {
                Activity activity=controller.get(); View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
                int width=UiKit.dp(activity,320),height=UiKit.dp(activity,640);
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); root.layout(0,0,width,height);
                for(int id:new int[]{R.id.dsh_tab_chat,R.id.dsh_tab_sessions,R.id.dsh_tab_plugins,R.id.dsh_tab_settings}) {
                    View tab=activity.findViewById(id); assertNotNull(tab); assertTrue(tab.getWidth()>=UiKit.dp(activity,48)); assertTrue(tab.getHeight()>=UiKit.dp(activity,48));
                    int[] position=new int[2]; tab.getLocationInWindow(position); assertTrue(position[0]>=0&&position[0]+tab.getWidth()<=width); assertTrue(position[1]+tab.getHeight()<=height);
                }
            }
        }
    }
    @Test @Config(qualifiers="w891dp-h411dp-land-xxhdpi") public void landscapeKeyboardKeepsTheComposerUsableAndRestoresNavigation() {
        try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class).setup()) {
            DshActivity activity=controller.get(); View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
            EditText composer=activity.findViewById(R.id.dsh_composer); composer.setText("保持草稿"); composer.requestFocus();
            layout(root,UiKit.dp(activity,891),UiKit.dp(activity,240)); layout(root,UiKit.dp(activity,891),UiKit.dp(activity,240));
            assertEquals(View.VISIBLE,activity.findViewById(R.id.dsh_bottom_navigation).getVisibility());
            View send=activity.findViewById(R.id.dsh_send); int[] location=new int[2]; send.getLocationInWindow(location);
            assertTrue(location[1]>=0&&location[1]+send.getHeight()<=UiKit.dp(activity,240));
            root.requestFocus(); layout(root,UiKit.dp(activity,891),UiKit.dp(activity,411));
            assertEquals(View.VISIBLE,activity.findViewById(R.id.dsh_bottom_navigation).getVisibility());
            assertEquals("保持草稿",composer.getText().toString());
        }
    }
    @Test @Config(qualifiers="w891dp-h411dp-land-xxhdpi") public void landscapeUsesAScrollableRailAndKeepsTheChatCentered() {
        try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class).setup()) {
            DshActivity activity=controller.get(); View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
            int width=UiKit.dp(activity,891),height=UiKit.dp(activity,411); layout(root,width,height);
            View rail=activity.findViewById(R.id.dsh_bottom_navigation); assertTrue(rail instanceof android.widget.ScrollView);
            assertEquals(UiKit.dp(activity,80),rail.getWidth()); assertEquals(height,rail.getHeight());
            View composer=activity.findViewById(R.id.dsh_composer); int[] pos=new int[2]; composer.getLocationInWindow(pos);
            assertTrue(pos[0]>=rail.getWidth()); assertTrue(pos[0]+composer.getWidth()<width);
        }
    }
    private static void layout(View root,int width,int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); root.layout(0,0,width,height);
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
            TextView summary=findText(controller.get().getWindow().getDecorView(),"1 项工具记录"); assertNotNull(summary); summary.performClick();
            TextView label=findText(controller.get().getWindow().getDecorView(),"◇  fixture"); assertNotNull(label); ((View)label.getParent()).performClick();
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); String details=allText(dialog.getWindow().getDecorView());
            assertTrue(details.contains("first-result")); assertFalse(details.contains("second-result"));
        }
    }
    @Test public void completedToolsCollapseButFailuresAndDetailsRemainAccessible() throws Exception {
        SessionStore store=AgentApp.get().dsh().sessions; String session=store.createSession().id,turn=store.beginTurn(session,"fixture","tools","fixture");
        for(String id:new String[]{"ok","failed"}) {
            store.append(session,turn,"tool_started",new JSONObject().put("id",id).put("name",id).put("arguments",new JSONObject()));
            store.append(session,turn,"tool_result",new JSONObject().put("role","tool").put("tool_call_id",id).put("content",new JSONObject().put("ok",id.equals("ok")).put("code",id).toString()));
        }
        store.finish(session,turn,"completed","OK");
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),DshActivity.class).putExtra("session",session);
        try(ActivityController<DshActivity> controller=Robolectric.buildActivity(DshActivity.class,intent).setup()) {
            View root=controller.get().getWindow().getDecorView(); TextView summary=findText(root,"2 项工具记录 · 1 项未完成"); assertNotNull(summary);
            TextView failed=findText(root,"◇  failed"); assertNotNull(failed); assertFalse(failed.isShown());
            summary.performClick(); assertTrue(failed.isShown());
            ((View)failed.getParent()).performClick();
            assertTrue(allText(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView()).contains("failed"));
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
    private static View findDescription(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { View found=findDescription(((ViewGroup)view).getChildAt(i),description); if(found!=null) return found; }
        return null;
    }
    private static String allText(View view) {
        StringBuilder text=new StringBuilder(); if(view instanceof TextView) text.append(((TextView)view).getText());
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) text.append(allText(((ViewGroup)view).getChildAt(i))); return text.toString();
    }
}
