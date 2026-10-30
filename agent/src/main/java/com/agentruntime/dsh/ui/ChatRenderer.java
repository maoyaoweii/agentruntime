package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.agentruntime.dsh.runtime.DshController;
import com.agentruntime.dsh.store.SessionStore;
import org.json.JSONObject;
import java.util.*;
import java.util.function.Consumer;

final class ChatRenderer {
    private final Activity activity;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final Consumer<String> suggest;
    private String current="";
    private long sequence=-1;
    private TextView live;
    ChatRenderer(Activity activity,LinearLayout content,ScrollView scroll,Consumer<String> suggest) {
        this.activity=activity; this.content=content; this.scroll=scroll; this.suggest=suggest;
    }
    void reset() { sequence=-1; current=""; }
    void toBottom() { scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }
    void show(String session,List<SessionStore.Event> events,DshController.View active) throws Exception {
        boolean follow=scroll.getChildCount()==0||scroll.getChildAt(0).getHeight()-scroll.getHeight()-scroll.getScrollY()<UiKit.dp(activity,120);
        long last=events.isEmpty()?0:events.get(events.size()-1).sequence;
        if(!current.equals(session)||last!=sequence) {
            current=session; sequence=last; content.removeAllViews(); live=null;
            if(events.isEmpty()) welcome();
            Map<String,JSONObject> results=new HashMap<>();
            for(SessionStore.Event event:events) if("tool_result".equals(event.type)) results.put(event.turnId+":"+event.payload.optString("tool_call_id"),new JSONObject(event.payload.optString("content","{}")));
            for(SessionStore.Event event:events) {
                JSONObject data=event.payload;
                if("user_message".equals(event.type)) bubble(data.optString("content"),true);
                else if("assistant_message".equals(event.type)) {
                    String text=data.optString("content",""); if(!text.startsWith("[DSH Runtime]")&&!text.isEmpty()) bubble(text,false);
                } else if("assistant_attempt".equals(event.type)) { bubble(data.optString("content"),false); note("以上回复未完成",0xffa46516); }
                else if("tool_started".equals(event.type)) tool(data,results.get(event.turnId+":"+data.optString("id")));
                else if("turn_finished".equals(event.type)&&!"completed".equals(data.optString("state"))) note(explain(data.optString("code")),0xffa46516);
            }
            live=UiKit.text(activity,"",15,UiKit.INK); live.setTextIsSelectable(true); UiKit.pad(live,4,10,4,10); content.addView(live);
            if(follow) toBottom();
        }
        if(live!=null) { String text=active==null?"":active.live; live.setText(text); live.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE); if(!text.isEmpty()&&follow) toBottom(); }
    }
    private void welcome() {
        UiKit.gap(content,20);
        TextView eyebrow=UiKit.bold(activity,"一部手机，一个工作空间",12,UiKit.LINK); content.addView(eyebrow); UiKit.gap(content,9);
        TextView title=UiKit.title(activity,"你好，\n今天想做些什么？",30); title.setLineSpacing(UiKit.dp(activity,3),1); content.addView(title); UiKit.gap(content,11);
        content.addView(UiKit.text(activity,"聊想法、整理文字，或让助手调用手机上的工具。",14,UiKit.MUTED)); UiKit.gap(content,21);
        quick(DshIcons.Name.PHONE,"了解我的手机","查看设备状态与可用权限","请查看这部手机的环境状态，告诉我哪些手机工具已经就绪。");
        quick(DshIcons.Name.GRID,"发现手机技能","看看助手可以帮你完成什么","请列出可用的手机技能，按用途简单介绍，并标明需要确认的操作。");
        quick(DshIcons.Name.NOTE,"记录一个新想法","整理内容，保存到本机工作区","帮我整理一份简短的今日计划。先询问我的安排，确认内容后保存到工作区。");
    }
    private void quick(DshIcons.Name icon,String title,String description,String prompt) {
        LinearLayout card=UiKit.row(activity); UiKit.pad(card,15,12,14,12); card.setMinimumHeight(UiKit.dp(activity,82)); card.setBackground(UiKit.ripple(activity,UiKit.WHITE,UiKit.LINE,16));
        ImageView symbol=DshIcons.view(activity,icon,UiKit.INK,24); card.addView(symbol,new LinearLayout.LayoutParams(UiKit.dp(activity,36),UiKit.dp(activity,36)));
        LinearLayout labels=UiKit.column(activity); labels.addView(UiKit.bold(activity,title,15,UiKit.INK)); labels.addView(UiKit.text(activity,description,12,UiKit.MUTED)); card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=DshIcons.view(activity,DshIcons.Name.RIGHT,UiKit.MUTED,20); card.addView(arrow,new LinearLayout.LayoutParams(UiKit.dp(activity,24),UiKit.dp(activity,24)));
        card.setOnClickListener(v->suggest.accept(prompt)); card.setFocusable(true); card.setContentDescription(title+"，"+description);
        LinearLayout.LayoutParams p=UiKit.matchWrap(); p.bottomMargin=UiKit.dp(activity,10); content.addView(card,p);
    }
    private void bubble(String value,boolean user) {
        LinearLayout wrapper=UiKit.column(activity); UiKit.pad(wrapper,16,12,16,12);
        wrapper.setBackground(UiKit.shape(activity,user?0xffeaf0ff:UiKit.WHITE,user?0:UiKit.LINE,20));
        wrapper.addView(UiKit.bold(activity,user?"你":"DSH",11,user?UiKit.BLUE:UiKit.MUTED)); UiKit.gap(wrapper,5);
        TextView text=UiKit.text(activity,UiKit.visible(value),16,UiKit.INK); text.setTextIsSelectable(true); text.setLineSpacing(UiKit.dp(activity,5),1); wrapper.addView(text);
        LinearLayout.LayoutParams p=UiKit.matchWrap(); p.bottomMargin=UiKit.dp(activity,14); if(user) p.setMarginStart(UiKit.dp(activity,28)); else p.setMarginEnd(UiKit.dp(activity,12)); content.addView(wrapper,p);
    }
    private void tool(JSONObject started,JSONObject result) {
        String name=started.optString("name"); String label=result==null?"调用中":result.optBoolean("ok")?"已完成":"未完成";
        LinearLayout card=UiKit.row(activity); UiKit.pad(card,14,13,14,13); card.setBackground(UiKit.shape(activity,0xfff0f3fa,0,14));
        TextView text=UiKit.bold(activity,"◇  "+name,12,UiKit.INK); card.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(UiKit.text(activity,label+"  ›",11,result==null?UiKit.MUTED:result.optBoolean("ok")?0xff1a7858:0xffa46516));
        card.setFocusable(true); card.setOnClickListener(v->{
            try {
                String details="参数\n"+started.getJSONObject("arguments").toString(2)+"\n\n结果\n"+(result==null?"等待结果":result.toString(2));
                ScrollView scrollView=new ScrollView(activity); TextView body=UiKit.text(activity,UiKit.visible(details),13,UiKit.INK); UiKit.pad(body,22,12,22,12); body.setTextIsSelectable(true); scrollView.addView(body);
                new AlertDialog.Builder(activity).setTitle(name).setView(scrollView).setPositiveButton("关闭",null).show();
            } catch(Exception ignored) { UiKit.toast(activity,"暂时无法读取工具详情"); }
        });
        LinearLayout.LayoutParams p=UiKit.matchWrap(); p.bottomMargin=UiKit.dp(activity,10); content.addView(card,p);
    }
    private void note(String text,int color) { TextView view=UiKit.text(activity,text,12,color); UiKit.pad(view,4,4,4,16); content.addView(view); }
    static String explain(String code) {
        switch(code) {
            case "USER_CANCELLED": return "你已停止本次回复。已完成的操作保留，可查看工具详情。";
            case "PROCESS_RESTARTED": case "SERVICE_STOPPED": return "本次任务已中断，没有自动重试。结果未知的操作请先核实。";
            case "TURN_TIMEOUT": return "本次任务达到 3 分钟上限，已停止。";
            case "PLUGIN_DISABLED": return "插件已停用，本次任务已停止。";
            case "HTTP_401": case "HTTP_403": return "模型服务拒绝访问，请检查 API Key 和模型权限。";
            case "HTTP_402": return "模型服务余额不足，请检查账户。";
            case "HTTP_429": return "模型服务暂时限流，请稍后重新发送。";
            case "TOOL_LIMIT": case "MODEL_STEP_LIMIT": return "本次工具调用达到上限，已停止，请缩小任务范围。";
            default: return "本次回复未完成（"+code+"）。请检查网络和模型设置后重新发送。";
        }
    }
}
