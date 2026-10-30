package com.agentruntime.dsh.model;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.Arrays;

public final class ProviderConfig {
    public static final String DEFAULT_URL="https://api.deepseek.com";
    public static final String DEFAULT_MODEL="deepseek-flash";
    private final SharedPreferences preferences;
    private final CredentialVault vault;
    private long editRevision;

    public static final class Snapshot {
        public final String endpoint,model,apiKey;
        public final boolean thinking;
        public Snapshot(String url,String model,String apiKey,boolean thinking) {
            endpoint=endpoint(url);
            if(model==null||model.trim().isEmpty()||model.length()>128||model.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException("请输入有效的模型名称");
            if(apiKey==null||apiKey.trim().isEmpty()||apiKey.length()>4096||apiKey.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException("请输入有效的 API Key");
            this.model=model.trim(); this.apiKey=apiKey.trim(); this.thinking=thinking;
        }
        public boolean deepSeek() { return "api.deepseek.com".equalsIgnoreCase(URI.create(endpoint).getHost()); }
    }

    public ProviderConfig(Context context,CredentialVault vault) {
        preferences=vault.preferences(); this.vault=vault;
    }
    public String url() { return preferences.getString("url",DEFAULT_URL); }
    public String model() { return preferences.getString("model",DEFAULT_MODEL); }
    public boolean thinking() { return preferences.getBoolean("thinking",false); }
    public boolean configured() { return vault.hasKey(); }
    public synchronized Snapshot snapshot() throws Exception { return new Snapshot(url(),model(),vault.load(),thinking()); }
    public synchronized long reserveEdit() { return ++editRevision; }
    public synchronized void clearKey() { ++editRevision; vault.clear(); }

    public synchronized void save(String url,String model,String enteredKey,boolean thinking) throws Exception {
        save(url,model,enteredKey,thinking,reserveEdit());
    }
    public synchronized void save(String url,String model,String enteredKey,boolean thinking,long revision) throws Exception {
        if(revision!=editRevision) throw new IllegalStateException("设置已被更新，请重新保存");
        String key=enteredKey==null||enteredKey.trim().isEmpty()?vault.load():enteredKey;
        new Snapshot(url,model,key,thinking);
        if(configured()&&(enteredKey==null||enteredKey.trim().isEmpty())
                &&!URI.create(endpoint(url())).getAuthority().equalsIgnoreCase(URI.create(endpoint(url)).getAuthority())) {
            throw new IllegalArgumentException("更换模型服务时，请重新输入对应的 API Key");
        }
        SharedPreferences.Editor editor=preferences.edit().putString("url",url.trim()).putString("model",model.trim()).putBoolean("thinking",thinking);
        if(enteredKey!=null&&!enteredKey.trim().isEmpty()) vault.put(editor,enteredKey.trim());
        if(!editor.commit()) {
            throw new java.io.IOException("Model settings unavailable");
        }
    }

    public static String endpoint(String input) {
        try {
            if(input==null||input.length()>2048) throw new IllegalArgumentException();
            URI uri=new URI(input.trim()); String host=uri.getHost(); String scheme=uri.getScheme();
            boolean local=host!=null&&Arrays.asList("localhost","127.0.0.1").contains(host.toLowerCase(java.util.Locale.ROOT));
            if(host==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||!("https".equalsIgnoreCase(scheme)||("http".equalsIgnoreCase(scheme)&&local))
                    ||uri.getPort()==0||uri.getPort()>65535) throw new IllegalArgumentException();
            String path=uri.getPath()==null?"":uri.getPath().replaceAll("/+$","");
            if(!path.endsWith("/chat/completions")) path+="/chat/completions";
            return new URI(scheme.toLowerCase(java.util.Locale.ROOT),null,host,uri.getPort(),path,null,null).toASCIIString();
        } catch(Exception e) { throw new IllegalArgumentException("模型地址需要 HTTPS，或本机回环 HTTP；请勿在地址中填写密钥或查询参数"); }
    }
}
