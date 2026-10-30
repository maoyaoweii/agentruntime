package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
    private ImageButton reveal;
    private boolean keyVisible,secureInitially;
    private Cancellation check;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); graph=AgentApp.get().dsh();
        secureInitially=(getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0;
        LinearLayout page=UiKit.page(this,"连接与设置",null);
        LinearLayout card=UiKit.card(this); card.addView(UiKit.bold(this,"模型服务",18,UiKit.INK)); UiKit.gap(card,14);
        url=UiKit.input(this,"https://api.deepseek.com",false); url.setId(com.agentruntime.agent.R.id.dsh_model_url); url.setContentDescription("服务地址");
        url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); url.setText(graph.provider.url()); UiKit.formRow(card,"服务地址",url);
        model=UiKit.input(this,"deepseek-flash",false); model.setId(com.agentruntime.agent.R.id.dsh_model_name); model.setContentDescription("模型名称"); model.setText(graph.provider.model());
        ImageButton chooseModel=UiKit.iconButton(this,DshIcons.Name.DOWN,"选择常用模型",()->{
            String[] names={"deepseek-flash","deepseek-v4-pro"};
            new AlertDialog.Builder(this).setTitle("选择模型").setItems(names,(dialog,which)->model.setText(names[which])).setNegativeButton("自定义",(dialog,which)->model.requestFocus()).show();
        }); UiKit.formRow(card,"模型名称",withAction(model,chooseModel));
        protocol=new Spinner(this); protocol.setId(com.agentruntime.agent.R.id.dsh_protocol); ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"自动识别（推荐）","DeepSeek（含代理服务）","OpenAI 兼容"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); protocol.setAdapter(adapter); protocol.setContentDescription("接口协议");
        protocol.setSelection(graph.provider.protocol()); protocol.setMinimumHeight(UiKit.dp(this,50)); protocol.setBackground(UiKit.shape(this,UiKit.WHITE,UiKit.LINE,14)); UiKit.pad(protocol,12,0,32,0);
        FrameLayout protocolBox=new FrameLayout(this); protocolBox.addView(protocol,new FrameLayout.LayoutParams(-1,UiKit.dp(this,50)));
        ImageView down=DshIcons.view(this,DshIcons.Name.DOWN,UiKit.MUTED,18); FrameLayout.LayoutParams downParams=new FrameLayout.LayoutParams(UiKit.dp(this,18),UiKit.dp(this,18),Gravity.END|Gravity.CENTER_VERTICAL); downParams.setMarginEnd(UiKit.dp(this,14)); protocolBox.addView(down,downParams);
        UiKit.formRow(card,"接口协议",protocolBox);
        key=UiKit.input(this,graph.provider.configured()?"已保存 · 留空保留":"输入 API Key",false); key.setId(com.agentruntime.agent.R.id.dsh_api_key); key.setContentDescription("API Key");
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); key.setSaveEnabled(false); key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        reveal=UiKit.iconButton(this,DshIcons.Name.EYE,"显示 API Key",()->setKeyVisible(!keyVisible)); reveal.setId(com.agentruntime.agent.R.id.dsh_reveal_key); UiKit.formRow(card,"API Key",withAction(key,reveal));
        LinearLayout thinkingRow=UiKit.row(this); TextView thinkingLabel=UiKit.bold(this,"DeepSeek 深度思考",14,UiKit.INK);
        thinking=UiKit.toggle(this,"DeepSeek 深度思考",graph.provider.thinking()); thinking.setId(com.agentruntime.agent.R.id.dsh_thinking); thinkingLabel.setLabelFor(thinking.getId());
        thinkingRow.addView(thinkingLabel,new LinearLayout.LayoutParams(0,-2,1)); thinkingRow.addView(thinking); card.addView(thinkingRow); UiKit.gap(card,8);
        save=UiKit.button(this,"保存连接",true,this::save); card.addView(save,UiKit.matchWrap()); UiKit.gap(card,8);
        test=UiKit.button(this,"测试连接",false,this::test); test.setTextColor(UiKit.LINK); test.setBackground(UiKit.ripple(this,UiKit.WHITE,0xffb7c5ff,14)); card.addView(test,UiKit.matchWrap()); UiKit.gap(card,7);
        card.addView(UiKit.text(this,"测试会请求一次模型，费用按你的服务账户计费。",11,UiKit.MUTED)); UiKit.gap(card,7);
        LinearLayout safety=UiKit.row(this); ImageView shield=DshIcons.view(this,DshIcons.Name.SHIELD,UiKit.LINK,18); safety.addView(shield,new LinearLayout.LayoutParams(UiKit.dp(this,20),UiKit.dp(this,20)));
        saved=UiKit.text(this,graph.provider.configured()?"密钥已加密保存在本机":"API Key 将加密保存在本机",12,UiKit.MUTED); LinearLayout.LayoutParams safeText=new LinearLayout.LayoutParams(0,-2,1); safeText.setMarginStart(UiKit.dp(this,8)); safety.addView(saved,safeText); card.addView(safety); page.addView(card); UiKit.gap(page,14);
        Button advanced=UiKit.button(this,"手机权限与高级设置",false,()->UiKit.open(this,com.agentruntime.ui.SettingsActivity.class)); advanced.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        advanced.setCompoundDrawablesRelative(null,null,new DshIcons(this,DshIcons.Name.RIGHT,UiKit.MUTED,18,false),null); page.addView(advanced,UiKit.matchWrap()); UiKit.gap(page,18);
        LinearLayout privacy=UiKit.card(this); privacy.addView(UiKit.bold(this,"由你掌控",18,UiKit.INK)); UiKit.gap(privacy,8);
        privacy.addView(UiKit.text(this,"会话和工作区文件保存在应用内。调用模型时，当前上下文和工具结果会发送到你配置的服务。工作区写入和高风险手机操作需要单独确认。",14,UiKit.MUTED));
        UiKit.gap(privacy,14); clear=UiKit.button(this,"清除已保存的 API Key",false,()->new AlertDialog.Builder(this).setTitle("清除模型密钥？").setMessage("正在运行的回复仍使用开始时的配置；新回复需要重新设置密钥。")
                .setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{
                    try { graph.provider.clearKey(); setKeyVisible(false); key.setText(""); key.setHint("输入 API Key"); saved.setText("密钥已清除"); }
                    catch(IllegalStateException e) { saved.setText("清除失败，请重试"); }
                }).show());
        privacy.addView(clear,UiKit.matchWrap()); page.addView(privacy);
        UiKit.gap(page,12); page.addView(UiKit.text(this,"DSH Mobile 2.0  ·  Android 原生实现\n参考 DeepSeek Harness 的插件、会话事件与工具循环设计。",12,UiKit.MUTED));
    }
    private FrameLayout withAction(EditText input,ImageButton action) {
        FrameLayout frame=new FrameLayout(this); input.setPadding(UiKit.dp(this,14),UiKit.dp(this,12),UiKit.dp(this,52),UiKit.dp(this,12));
        frame.addView(input,new FrameLayout.LayoutParams(-1,-2)); frame.addView(action,new FrameLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48),Gravity.END|Gravity.CENTER_VERTICAL)); return frame;
    }
    private void setKeyVisible(boolean visible) {
        if(visible) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        int selection=key.getSelectionStart(); keyVisible=visible;
        key.setTransformationMethod(visible?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance());
        if(selection>=0) key.setSelection(Math.min(selection,key.length()));
        reveal.setContentDescription(visible?"隐藏 API Key":"显示 API Key");
        if(!visible&&!secureInitially) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
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
            runOnUiThread(()->{ if(isDestroyed()) return; save.setEnabled(true); clear.setEnabled(true); saved.setText(message); setKeyVisible(false); key.setText(""); if(graph.provider.configured()) key.setHint("已保存 · 留空保留"); });
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
    @Override protected void onPause() { if(keyVisible) setKeyVisible(false); super.onPause(); }
    @Override protected void onDestroy() { if(check!=null) check.cancel("USER_CANCELLED"); key.setText(""); super.onDestroy(); }
}
