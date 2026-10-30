package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.DshGraph;
import com.agentruntime.dsh.model.*;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DshSettingsActivity extends Activity {
    private DshGraph graph;
    private EditText url,model,key;
    private Switch thinking;
    private Spinner protocol;
    private TextView saved;
    private Button save,test,clear;
    private Cancellation check;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); graph=AgentApp.get().dsh();
        LinearLayout page=UiKit.page(this,"连接与设置","连接你自己的模型服务，开始在手机上使用 DSH。");
        LinearLayout card=UiKit.card(this); card.addView(UiKit.bold(this,"模型服务",19,UiKit.INK)); UiKit.gap(card,6);
        card.addView(UiKit.text(this,"默认连接 DeepSeek，也支持 Chat Completions 兼容接口。",13,UiKit.MUTED)); UiKit.gap(card,22);
        url=UiKit.input(this,"https://api.deepseek.com",false); url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); url.setText(graph.provider.url()); UiKit.field(card,"服务地址",url);
        model=UiKit.input(this,"deepseek-flash",false); model.setText(graph.provider.model()); UiKit.field(card,"模型名称",model);
        protocol=new Spinner(this); protocol.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"自动识别（推荐）","DeepSeek（含代理服务）","OpenAI 兼容"}));
        protocol.setSelection(graph.provider.protocol()); protocol.setMinimumHeight(UiKit.dp(this,48)); UiKit.field(card,"接口协议",protocol);
        key=UiKit.input(this,graph.provider.configured()?"已安全保存；留空继续使用":"输入 API Key",false); key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false); key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); UiKit.field(card,"API Key",key);
        thinking=new Switch(this); thinking.setText("DeepSeek 深度思考"); thinking.setTextColor(UiKit.INK); thinking.setTextSize(14); thinking.setChecked(graph.provider.thinking()); thinking.setMinHeight(UiKit.dp(this,48)); card.addView(thinking);
        card.addView(UiKit.text(this,"适用于 DeepSeek 协议。思考内容用于工具协议，不在聊天中展示。",12,UiKit.MUTED)); UiKit.gap(card,20);
        save=UiKit.button(this,"保存连接",true,this::save); card.addView(save,UiKit.matchWrap()); UiKit.gap(card,10);
        test=UiKit.button(this,"测试已保存的连接",false,this::test); card.addView(test,UiKit.matchWrap()); UiKit.gap(card,10);
        card.addView(UiKit.text(this,"连接测试会请求一次模型，费用按你的服务账户计费。",11,UiKit.MUTED)); UiKit.gap(card,8);
        saved=UiKit.text(this,graph.provider.configured()?"密钥已加密保存在本机":"尚未连接模型",12,UiKit.MUTED); card.addView(saved); page.addView(card); UiKit.gap(page,22);
        LinearLayout privacy=UiKit.card(this); privacy.addView(UiKit.bold(this,"由你掌控",18,UiKit.INK)); UiKit.gap(privacy,8);
        privacy.addView(UiKit.text(this,"会话和工作区文件保存在应用内。调用模型时，当前上下文和工具结果会发送到你配置的服务。工作区写入和高风险手机操作需要单独确认。",14,UiKit.MUTED));
        UiKit.gap(privacy,14); clear=UiKit.button(this,"清除已保存的 API Key",false,()->new AlertDialog.Builder(this).setTitle("清除模型密钥？").setMessage("正在运行的回复仍使用开始时的配置；新回复需要重新设置密钥。")
                .setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{
                    try { graph.provider.clearKey(); key.setText(""); key.setHint("输入 API Key"); saved.setText("密钥已清除"); }
                    catch(IllegalStateException e) { saved.setText("清除失败，请重试"); }
                }).show());
        privacy.addView(clear,UiKit.matchWrap()); page.addView(privacy);
        UiKit.gap(page,20); page.addView(UiKit.button(this,"手机权限与高级设置  ↗",false,()->UiKit.open(this,com.agentruntime.ui.SettingsActivity.class)),UiKit.matchWrap());
        UiKit.gap(page,12); page.addView(UiKit.text(this,"DSH Mobile 2.0  ·  Android 原生实现\n参考 DeepSeek Harness 的插件、会话事件与工具循环设计。",12,UiKit.MUTED));
    }
    private void save() {
        save.setEnabled(false); clear.setEnabled(false); String endpoint=url.getText().toString(),name=model.getText().toString(),secret=key.getText().toString(); boolean enabled=thinking.isChecked();
        long revision=graph.provider.reserveEdit();
        int protocolChoice=protocol.getSelectedItemPosition();
        new Thread(()->{
            String result;
            try { graph.provider.save(endpoint,name,secret,enabled,protocolChoice,revision); result="连接已保存。现在可以返回对话。"; }
            catch(IllegalArgumentException|IllegalStateException e) { result=e.getMessage(); }
            catch(Exception e) { result="无法保存加密密钥，请重试"; }
            final String message=result;
            runOnUiThread(()->{ if(isDestroyed()) return; save.setEnabled(true); clear.setEnabled(true); saved.setText(message); key.setText(""); if(graph.provider.configured()) key.setHint("已安全保存；留空继续使用"); });
        },"dsh-save-config").start();
    }
    private void test() {
        if(!graph.provider.configured()) { saved.setText("请先保存连接和 API Key"); return; }
        if(check!=null) return;
        check=new Cancellation(30000); Cancellation token=check; test.setEnabled(false); saved.setText("正在测试，模型服务可能收取少量调用费用…");
        new android.os.Handler(getMainLooper()).postDelayed(()->{ if(check==token) token.cancel("TURN_TIMEOUT"); },30000);
        new Thread(()->{
            String result;
            try {
                token.bind(); new ChatClient().complete(graph.provider.snapshot(),new JSONArray().put(new JSONObject().put("role","user").put("content","Reply only OK.")),new JSONArray(),token,s->{});
                result="连接成功，模型可以正常回复。";
            } catch(Exception e) { result=ChatRenderer.explain(com.agentruntime.dsh.runtime.DshRuntime.errorCode(e)); }
            final String message=result;
            runOnUiThread(()->{ if(isDestroyed()) return; check=null; test.setEnabled(true); saved.setText(message); });
        },"dsh-check-model").start();
    }
    @Override protected void onDestroy() { if(check!=null) check.cancel("USER_CANCELLED"); key.setText(""); super.onDestroy(); }
}
