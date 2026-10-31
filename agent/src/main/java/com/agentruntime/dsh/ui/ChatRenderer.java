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
    private final Set<String> expandedGroups=new HashSet<>();
    ChatRenderer(Activity activity,LinearLayout content,ScrollView scroll,Consumer<String> suggest) {
        this.activity=activity; this.content=content; this.scroll=scroll; this.suggest=suggest;
    }
    void reset() { sequence=-1; current=""; expandedGroups.clear(); }
    void toBottom() { scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }
    void show(String session,List<SessionStore.Event> events,DshController.View active) throws Exception {
        boolean follow=scroll.getChildCount()==0||scroll.getChildAt(0).getHeight()-scroll.getHeight()-scroll.getScrollY()<UiKit.dp(activity,120);
        long last=events.isEmpty()?0:events.get(events.size()-1).sequence;
        if(!current.equals(session)||last!=sequence) {
            boolean changedSession=!current.equals(session);
            if(changedSession) expandedGroups.clear();
            current=session; sequence=last; content.removeAllViews(); live=null;
            if(events.isEmpty()) welcome();
            Map<String,JSONObject> results=new HashMap<>();
            Map<String,JSONObject> requested=new HashMap<>();
            Set<String> started=new HashSet<>();
            Set<String> settledTurns=new HashSet<>();
            Set<String> continuedTurns=new HashSet<>();
            for(SessionStore.Event event:events) {
                if("tool_result".equals(event.type)) results.put(event.turnId+":"+event.payload.optString("tool_call_id"),new JSONObject(event.payload.optString("content","{}")));
                else if("tool_started".equals(event.type)) started.add(event.turnId+":"+event.payload.optString("id"));
                else if("turn_finished".equals(event.type)) settledTurns.add(event.turnId);
                else if("turn_continued".equals(event.type)) continuedTurns.add(event.turnId);
                else if("assistant_message".equals(event.type)) {
                    org.json.JSONArray calls=event.payload.optJSONArray("tool_calls");
                    if(calls!=null) for(int n=0;n<calls.length();n++) {
                        JSONObject call=calls.getJSONObject(n),function=call.getJSONObject("function"),arguments;
                        try { arguments=new JSONObject(function.optString("arguments","{}")); } catch(org.json.JSONException ignored) { arguments=new JSONObject(); }
                        String id=call.optString("id"); requested.put(event.turnId+":"+id,new JSONObject().put("id",id).put("name",function.optString("name","工具调用")).put("arguments",arguments));
                    }
                }
            }
            for(int i=0;i<events.size();i++) {
                SessionStore.Event event=events.get(i);
                JSONObject data=event.payload;
                if("user_message".equals(event.type)) bubble(data.optString("content"),true);
                else if("assistant_message".equals(event.type)) {
                    String text=data.optString("content",""); if(!text.startsWith("[DSH Runtime]")&&!text.isEmpty()) bubble(text,false);
                } else if("assistant_attempt".equals(event.type)) { bubble(data.optString("content"),false); note("以上回复未完成",0xffa46516); }
                else if("tool_started".equals(event.type)||orphanResult(event,started)) {
                    if(settledTurns.contains(event.turnId)) {
                        List<JSONObject> group=new ArrayList<>(); int next=i;
                        while(next<events.size()) {
                            SessionStore.Event part=events.get(next); if(!event.turnId.equals(part.turnId)) break;
                            if("tool_started".equals(part.type)) group.add(part.payload);
                            else if(orphanResult(part,started)) group.add(requestedCall(part,requested));
                            else if(!"tool_result".equals(part.type)&&!("assistant_message".equals(part.type)&&part.payload.optString("content","").isEmpty())) break;
                            next++;
                        }
                        toolGroup(event.turnId,event.sequence,group,results); i=next-1;
                    } else {
                        JSONObject call=orphanResult(event,started)?requestedCall(event,requested):data;
                        tool(content,call,results.get(event.turnId+":"+call.optString("id")),false);
                    }
                }
                else if("turn_finished".equals(event.type)&&!"completed".equals(data.optString("state"))) {
                    if(continuedTurns.contains(event.turnId)) note("本轮工具预算用完，已自动接着做。",UiKit.MUTED);
                    else note(explain(data.optString("code")),0xffa46516);
                }
            }
            live=UiKit.text(activity,"",15,UiKit.INK); live.setTextIsSelectable(true); UiKit.pad(live,4,10,4,10); content.addView(live);
            if(events.isEmpty()) { if(changedSession) scroll.post(()->scroll.scrollTo(0,0)); }
            else if(follow||changedSession) toBottom();
        }
        if(live!=null) { String text=active==null?"":active.live; live.setText(text); live.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE); if(!text.isEmpty()&&follow) toBottom(); }
    }
    private void welcome() {
        boolean wide=DshNavigation.isWide(activity); LinearLayout introduction=content,shortcuts=content;
        if(wide) {
            LinearLayout columns=UiKit.row(activity); columns.setGravity(Gravity.TOP); introduction=UiKit.column(activity); shortcuts=UiKit.column(activity);
            columns.addView(introduction,new LinearLayout.LayoutParams(0,-2,1)); LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1.15f); right.setMarginStart(UiKit.dp(activity,20)); columns.addView(shortcuts,right); content.addView(columns);
        }
        UiKit.gap(introduction,wide?6:20);
        TextView eyebrow=UiKit.bold(activity,"一部手机，一个工作空间",12,UiKit.LINK); introduction.addView(eyebrow); UiKit.gap(introduction,9);
        TextView title=UiKit.title(activity,"你好，\n今天想做些什么？",wide?26:30); title.setLineSpacing(UiKit.dp(activity,3),1); introduction.addView(title); UiKit.gap(introduction,11);
        introduction.addView(UiKit.text(activity,"聊想法、整理文字，或让助手调用手机上的工具。",14,UiKit.MUTED)); if(!wide) UiKit.gap(introduction,21);
        quick(shortcuts,DshIcons.Name.PHONE,"了解我的手机","查看设备状态与可用权限","请查看这部手机的环境状态，告诉我哪些手机工具已经就绪。",wide);
        quick(shortcuts,DshIcons.Name.GRID,"发现手机技能","看看助手可以帮你完成什么","请列出可用的手机技能，按用途简单介绍，并标明需要确认的操作。",wide);
        quick(shortcuts,DshIcons.Name.NOTE,"记录一个新想法","整理内容，保存到本机工作区","帮我整理一份简短的今日计划。先询问我的安排，确认内容后保存到工作区。",wide);
    }
    private void quick(LinearLayout target,DshIcons.Name icon,String title,String description,String prompt,boolean compact) {
        LinearLayout card=UiKit.row(activity); UiKit.pad(card,15,compact?8:12,14,compact?8:12); card.setMinimumHeight(UiKit.dp(activity,compact?68:82)); card.setBackground(UiKit.ripple(activity,UiKit.WHITE,UiKit.LINE,16));
        ImageView symbol=DshIcons.view(activity,icon,UiKit.INK,24); card.addView(symbol,new LinearLayout.LayoutParams(UiKit.dp(activity,36),UiKit.dp(activity,36)));
        LinearLayout labels=UiKit.column(activity); labels.addView(UiKit.bold(activity,title,15,UiKit.INK)); labels.addView(UiKit.text(activity,description,12,UiKit.MUTED)); card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=DshIcons.view(activity,DshIcons.Name.RIGHT,UiKit.MUTED,20); card.addView(arrow,new LinearLayout.LayoutParams(UiKit.dp(activity,24),UiKit.dp(activity,24)));
        card.setOnClickListener(v->suggest.accept(prompt)); card.setFocusable(true); card.setContentDescription(title+"，"+description);
        LinearLayout.LayoutParams p=UiKit.matchWrap(); p.bottomMargin=UiKit.dp(activity,10); target.addView(card,p);
    }
    private void bubble(String value,boolean user) {
        LinearLayout wrapper=UiKit.column(activity); UiKit.pad(wrapper,16,12,16,12);
        wrapper.setBackground(UiKit.shape(activity,user?0xffeaf0ff:UiKit.WHITE,user?0:UiKit.LINE,20));
        wrapper.addView(UiKit.bold(activity,user?"你":"DSH",11,user?UiKit.BLUE:UiKit.MUTED)); UiKit.gap(wrapper,5);
        String safe=UiKit.visible(value);
        // 助手的回复带 Markdown（**加粗**、`代码`、表格）；用户自己打的字原样显示。
        TextView text=user?UiKit.text(activity,safe,16,UiKit.INK):UiKit.rich(activity,Markdown.render(activity,safe),16,UiKit.INK);
        text.setTextIsSelectable(true); text.setLineSpacing(UiKit.dp(activity,5),1); wrapper.addView(text);
        LinearLayout.LayoutParams p=UiKit.matchWrap(); p.bottomMargin=UiKit.dp(activity,14); if(user) p.setMarginStart(UiKit.dp(activity,28)); else p.setMarginEnd(UiKit.dp(activity,12)); content.addView(wrapper,p);
    }
    private static boolean orphanResult(SessionStore.Event event,Set<String> started) {
        return "tool_result".equals(event.type)&&!started.contains(event.turnId+":"+event.payload.optString("tool_call_id"));
    }
    private static JSONObject requestedCall(SessionStore.Event event,Map<String,JSONObject> requested) throws org.json.JSONException {
        String id=event.payload.optString("tool_call_id"); JSONObject call=requested.get(event.turnId+":"+id);
        return call!=null?call:new JSONObject().put("id",id).put("name","工具调用").put("arguments",new JSONObject().put("提示","参数不在当前可见记录中"));
    }
    private void toolGroup(String turn,long sequence,List<JSONObject> calls,Map<String,JSONObject> results) {
        String key=turn+":"+sequence; int incomplete=0,unknown=0;
        for(JSONObject call:calls) {
            JSONObject result=results.get(turn+":"+call.optString("id"));
            if(result==null||"RESULT_UNAVAILABLE".equals(result.optString("code"))||result.optBoolean("outcome_uncertain",false)) unknown++;
            else if(!result.optBoolean("ok")) incomplete++;
        }
        String label=calls.size()+" 项工具记录"+(unknown>0?" · "+unknown+" 项结果未知":"")+(incomplete>0?" · "+incomplete+" 项未完成":"");
        LinearLayout group=UiKit.column(activity),details=UiKit.column(activity); details.setVisibility(expandedGroups.contains(key)?View.VISIBLE:View.GONE);
        Button toggle=UiKit.button(activity,label,false,()->{}); toggle.setTextSize(12); toggle.setTextColor(incomplete>0||unknown>0?0xff8e5a16:UiKit.MUTED); toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        toggle.setCompoundDrawablesRelative(new DshIcons(activity,DshIcons.Name.GRID,UiKit.MUTED,17,false),null,new DshIcons(activity,DshIcons.Name.DOWN,UiKit.MUTED,18,false),null);
        toggle.setCompoundDrawablePadding(UiKit.dp(activity,8));
        toggle.setContentDescription(label+(details.getVisibility()==View.VISIBLE?"，已展开，点击收起":"，点击展开"));
        toggle.setOnClickListener(view->{
            boolean expand=details.getVisibility()!=View.VISIBLE; details.setVisibility(expand?View.VISIBLE:View.GONE);
            if(expand) expandedGroups.add(key); else expandedGroups.remove(key);
            toggle.setContentDescription(label+(expand?"，已展开，点击收起":"，点击展开"));
        });
        group.addView(toggle,UiKit.matchWrap()); UiKit.gap(group,8);
        for(JSONObject call:calls) tool(details,call,results.get(turn+":"+call.optString("id")),true);
        group.addView(details); LinearLayout.LayoutParams p=UiKit.matchWrap(); p.bottomMargin=UiKit.dp(activity,8); content.addView(group,p);
    }
    private void tool(LinearLayout target,JSONObject started,JSONObject result,boolean settled) {
        String name=started.optString("name"); String label=result==null?(settled?"结果未知":"调用中"):
                "RESULT_UNAVAILABLE".equals(result.optString("code"))||result.optBoolean("outcome_uncertain",false)?"结果未知":result.optBoolean("ok")?"已完成":"未完成";
        LinearLayout card=UiKit.row(activity); UiKit.pad(card,14,13,14,13); card.setMinimumHeight(UiKit.dp(activity,48)); card.setBackground(UiKit.ripple(activity,0xfff0f3fa,0,14));
        TextView text=UiKit.bold(activity,"◇  "+name,12,UiKit.INK); card.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        int statusColor="结果未知".equals(label)||"未完成".equals(label)?0xffa46516:"已完成".equals(label)?0xff1a7858:UiKit.MUTED;
        card.addView(UiKit.text(activity,label+"  ›",11,statusColor));
        card.setFocusable(true); card.setContentDescription("工具 "+name+"，"+label+"，点击查看详情"); card.setOnClickListener(v->{
            try {
                String notice="结果未知".equals(label)?"结果未知：操作可能已生效，重试前请先核实当前状态。\n\n":"";
                String details=notice+"调用编号\n"+started.optString("id")+"\n\n参数\n"+started.getJSONObject("arguments").toString(2)+"\n\n结果\n"+(result==null?(settled?"结果未知":"等待结果"):result.toString(2));
                ScrollView scrollView=new ScrollView(activity); TextView body=UiKit.text(activity,UiKit.visible(details),13,UiKit.INK); UiKit.pad(body,22,12,22,12); body.setTextIsSelectable(true); scrollView.addView(body);
                new AlertDialog.Builder(activity).setTitle(name).setView(scrollView).setPositiveButton("关闭",null).show();
            } catch(Exception ignored) { UiKit.toast(activity,"暂时无法读取工具详情"); }
        });
        LinearLayout.LayoutParams p=UiKit.matchWrap(); p.bottomMargin=UiKit.dp(activity,10); target.addView(card,p);
    }
    private void note(String text,int color) { TextView view=UiKit.text(activity,text,12,color); UiKit.pad(view,4,4,4,16); content.addView(view); }
    static String explain(String code) {
        switch(code) {
            case "USER_CANCELLED": return "你已停止本次回复。已完成的操作保留，可查看工具详情。";
            case "PROCESS_RESTARTED": case "SERVICE_STOPPED": return "本次任务已中断，没有自动重试。结果未知的操作请先核实。";
            case "TURN_TIMEOUT": return "本轮达到运行时间上限。结果未知的操作请先核实，再决定是否继续。";
            case "PLUGIN_DISABLED": return "插件已停用，本次任务已停止。";
            case "CONFIRMATION_NOT_OBTAINED": return "操作未获得确认，已停止本次任务。";
            case "HTTP_401": case "HTTP_403": return "模型服务拒绝访问，请检查 API Key 和模型权限。";
            case "HTTP_402": return "模型服务余额不足，请检查账户。";
            case "HTTP_429": return "模型服务暂时限流，请稍后重新发送。";
            case "CONTEXT_LIMIT": return "会话上下文过长，已停止自动继续，避免遗漏已执行的步骤。请说明当前进度后继续。";
            case "TOOL_LIMIT": case "MODEL_STEP_LIMIT": return "本次工具调用达到上限，已停止，请缩小任务范围。";
            case "MODEL_REPEATED_CALL": case "DUPLICATE_TOOL_CALL":
                return "模型反复提交了同一次可能改变设备的操作，已停止重复执行。请先核实当前状态再继续。";
            case "OBSERVATION_STALLED": return "连续多次读到相同画面，已暂停自动尝试。请检查目标应用是否已加载，再告诉助手如何继续。";
            case "TOOL_CALL_INVALID": return "模型的工具调用格式不正确（编号或参数不合法），请重新发送一次。";
            default: return "本次回复未完成（"+code+"）。请检查网络和模型设置后重新发送。";
        }
    }
}
