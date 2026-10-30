package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.agentruntime.agent.R;

/** Root destinations keep a single chat beneath them, so switching tabs preserves its draft/turn. */
public final class DshNavigation {
    public enum Section {
        CHAT("对话",DshActivity.class,DshIcons.Name.CHAT,R.id.dsh_tab_chat),
        SESSIONS("会话",SessionsActivity.class,DshIcons.Name.SESSIONS,R.id.dsh_tab_sessions),
        PLUGINS("插件",PluginsActivity.class,DshIcons.Name.GRID,R.id.dsh_tab_plugins),
        SETTINGS("设置",DshSettingsActivity.class,DshIcons.Name.SETTINGS,R.id.dsh_tab_settings);
        final String label; final Class<? extends Activity> activity; final DshIcons.Name icon; final int id;
        Section(String label,Class<? extends Activity> activity,DshIcons.Name icon,int id) { this.label=label; this.activity=activity; this.icon=icon; this.id=id; }
    }
    private DshNavigation() { }
    public static Section section(Activity activity) {
        for(Section item:Section.values()) if(item.activity.isInstance(activity)) return item; return null;
    }
    public static void go(Activity activity,Section destination) {
        if(destination.activity.isInstance(activity)) return;
        Intent intent=new Intent(activity,destination.activity).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        if(!(activity instanceof DshActivity)) activity.finish();
    }
    public static boolean isWide(Activity activity) { return activity.getResources().getConfiguration().screenWidthDp>=720; }

    /** Phones use a bottom bar; wider windows keep a scrollable rail and a readable content width. */
    public static View mount(Activity activity,LinearLayout content,Section selected) {
        if(!isWide(activity)) {
            LinearLayout nav=bar(activity,selected); content.addView(nav); activity.setContentView(content); return nav;
        }
        ScrollView rail=new ScrollView(activity); rail.setId(R.id.dsh_bottom_navigation); rail.setFillViewport(true); rail.setVerticalScrollBarEnabled(false); rail.setBackgroundColor(UiKit.WHITE);
        LinearLayout items=UiKit.column(activity); UiKit.pad(items,6,12,6,12);
        TextView mark=UiKit.title(activity,"ds",18); mark.setTextColor(UiKit.WHITE); mark.setGravity(Gravity.CENTER); mark.setBackground(UiKit.shape(activity,UiKit.BLUE,0,11));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markParams=new LinearLayout.LayoutParams(UiKit.dp(activity,38),UiKit.dp(activity,38)); markParams.gravity=Gravity.CENTER_HORIZONTAL; items.addView(mark,markParams); UiKit.gap(items,16);
        for(Section section:Section.values()) {
            Button item=tab(activity,section,selected==section); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,UiKit.dp(activity,62)); p.bottomMargin=UiKit.dp(activity,5); items.addView(item,p);
        }
        rail.addView(items,new ScrollView.LayoutParams(-1,-2));
        LinearLayout shell=UiKit.row(activity); shell.setGravity(Gravity.TOP); shell.setBackgroundColor(UiKit.BG);
        shell.addView(rail,new LinearLayout.LayoutParams(UiKit.dp(activity,80),-1));
        FrameLayout frame=new FrameLayout(activity); int width=Math.min(640,activity.getResources().getConfiguration().screenWidthDp-80);
        frame.addView(content,new FrameLayout.LayoutParams(UiKit.dp(activity,width),-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        shell.addView(frame,new LinearLayout.LayoutParams(0,-1,1)); activity.setContentView(shell); return rail;
    }
    public static LinearLayout bar(Activity activity,Section selected) {
        LinearLayout nav=UiKit.row(activity); nav.setId(R.id.dsh_bottom_navigation); UiKit.pad(nav,12,6,12,8); nav.setBackgroundColor(UiKit.WHITE);
        nav.setElevation(UiKit.dp(activity,1));
        for(Section section:Section.values()) {
            Button tab=tab(activity,section,selected==section);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,UiKit.dp(activity,62),1); p.setMarginStart(UiKit.dp(activity,2)); p.setMarginEnd(UiKit.dp(activity,2)); nav.addView(tab,p);
        }
        return nav;
    }
    private static Button tab(Activity activity,Section section,boolean active) {
        Button tab=UiKit.button(activity,section.label,false,()->go(activity,section));
        tab.setId(section.id); tab.setSelected(active); tab.setTextSize(11); tab.setTextColor(active?UiKit.LINK:UiKit.MUTED);
        tab.setContentDescription(section.label+(active?"，当前页面":"")); tab.setBackground(UiKit.ripple(activity,active?UiKit.TINT:UiKit.WHITE,0,15));
        tab.setCompoundDrawables(null,new DshIcons(activity,section.icon,active?UiKit.BLUE:UiKit.MUTED,23,active),null,null);
        tab.setCompoundDrawablePadding(UiKit.dp(activity,4)); UiKit.pad(tab,3,7,3,5); return tab;
    }
}
