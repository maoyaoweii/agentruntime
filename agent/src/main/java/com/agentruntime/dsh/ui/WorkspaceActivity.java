package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.plugin.Workspace;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public final class WorkspaceActivity extends Activity {
    private Workspace workspace;
    private String exportPath;
    @Override public void onCreate(Bundle state) { super.onCreate(state); workspace=AgentApp.get().dsh().workspace; if(state!=null) exportPath=state.getString("export"); render(); }
    private void render() {
        LinearLayout page=UiKit.page(this,"本机工作区","让助手将整理好的内容保存为文本。文件只属于这个应用，可通过系统文件选择器导出。");
        try {
            JSONArray files=workspace.list().getJSONArray("data");
            if(files.length()==0) {
                LinearLayout empty=UiKit.card(this); empty.addView(UiKit.bold(this,"留一个位置给你的想法",22,UiKit.INK)); UiKit.gap(empty,10); empty.addView(UiKit.text(this,"还没有文件。回到对话，让助手帮你写一份计划、笔记或清单。",15,UiKit.MUTED)); page.addView(empty); return;
            }
            for(int i=0;i<files.length();i++) {
                JSONObject file=files.getJSONObject(i); String path=file.getString("path");
                Button button=UiKit.button(this,"▤  "+UiKit.visible(path)+"\n"+file.getLong("bytes")+" bytes  ·  点击查看",false,()->read(path));
                button.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL); button.setMinHeight(UiKit.dp(this,76)); page.addView(button,UiKit.matchWrap()); UiKit.gap(page,10);
            }
        } catch(Exception e) { page.addView(UiKit.text(this,"工作区暂时不可用，请重新打开。",14,UiKit.MUTED)); }
    }
    private void read(String path) {
        try {
            JSONObject file=workspace.read(path); if(!file.optBoolean("ok")) { UiKit.toast(this,"文件过大或已不可用（最大预览 16 KiB）"); return; }
            TextView text=UiKit.text(this,UiKit.visible(file.getString("data")),15,UiKit.INK); text.setTextIsSelectable(true); UiKit.pad(text,22,16,22,16); ScrollView scroll=new ScrollView(this); scroll.addView(text);
            new AlertDialog.Builder(this).setTitle(UiKit.visible(path)).setView(scroll).setNegativeButton("关闭",null).setPositiveButton("导出文件",(d,w)->{
                exportPath=path; Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,new java.io.File(path).getName());
                try { startActivityForResult(create,41); } catch(RuntimeException e) { exportPath=null; UiKit.toast(this,"没有可用的系统文件选择器"); }
            }).show();
        } catch(Exception e) { UiKit.toast(this,"文件暂时无法读取"); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(request!=41||result!=RESULT_OK||data==null||exportPath==null) return;
        String path=exportPath; exportPath=null; Uri target=data.getData();
        if(target==null||!"content".equalsIgnoreCase(target.getScheme())
                ||(data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0
                ||checkUriPermission(target,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            UiKit.toast(this,"文件选择器未提供可写的文档位置，请重新选择"); return;
        }
        new Thread(()->{
            String message;
            try {
                JSONObject file=workspace.read(path); if(!file.optBoolean("ok")) throw new java.io.IOException();
                try(java.io.OutputStream out=getContentResolver().openOutputStream(target,"wt")) { if(out==null) throw new java.io.IOException(); out.write(file.getString("data").getBytes(StandardCharsets.UTF_8)); }
                message="文件已导出";
            } catch(Exception e) { message="导出未完成，请重试"; }
            final String value=message; runOnUiThread(()->{ if(!isDestroyed()) UiKit.toast(this,value); });
        },"dsh-export").start();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("export",exportPath); super.onSaveInstanceState(state); }
}
