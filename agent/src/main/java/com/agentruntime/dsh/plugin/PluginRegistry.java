package com.agentruntime.dsh.plugin;

import android.content.SharedPreferences;
import com.agentruntime.dsh.model.Cancellation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class PluginRegistry {
    public interface Approval { boolean ask(String summary,Cancellation cancellation); }
    private final SharedPreferences preferences;
    private final Approval approval;
    private final LinkedHashMap<String,MobilePlugin> plugins=new LinkedHashMap<>();
    private final Set<Invocation> active=new HashSet<>();
    private java.util.function.Consumer<Cancellation> revoked=token->token.cancel("PLUGIN_DISABLED");
    public PluginRegistry(SharedPreferences preferences,Approval approval) { this.preferences=preferences; this.approval=approval; }

    public synchronized void register(MobilePlugin plugin) {
        if(!plugin.id().matches("[a-z][a-z0-9_-]{0,63}")||plugins.containsKey(plugin.id())) throw new IllegalArgumentException("Duplicate or invalid plugin");
        Set<String> names=new HashSet<>();
        for(MobilePlugin existing:plugins.values()) for(MobilePlugin.Tool tool:existing.tools()) names.add(tool.name);
        for(MobilePlugin.Tool tool:plugin.tools()) if(!tool.name.matches("[a-z][a-z0-9_]{0,63}")||!names.add(tool.name)) throw new IllegalArgumentException("Duplicate or invalid tool");
        plugins.put(plugin.id(),plugin);
    }
    public synchronized List<MobilePlugin> plugins() { return new ArrayList<>(plugins.values()); }
    public boolean enabled(String id) { return preferences.getBoolean(id,true); }
    public synchronized void onRevoked(java.util.function.Consumer<Cancellation> callback) { revoked=callback; }
    public void setEnabled(String id,boolean value) {
        List<Cancellation> stopped=new ArrayList<>(); java.util.function.Consumer<Cancellation> callback;
        synchronized(this) {
            if(!plugins.containsKey(id)) throw new IllegalArgumentException("Unknown plugin");
            if(!preferences.edit().putBoolean(id,value).commit()) throw new IllegalStateException("Plugin settings unavailable");
            if(!value) for(Invocation call:active) if(id.equals(call.plugin)) stopped.add(call.token);
            callback=revoked;
        }
        for(Cancellation token:stopped) callback.accept(token);
    }
    public synchronized Snapshot snapshot(boolean toolsMode) throws Exception {
        LinkedHashMap<String,Entry> admitted=new LinkedHashMap<>();
        if(toolsMode) for(MobilePlugin plugin:plugins.values()) if(enabled(plugin.id())) {
            for(MobilePlugin.Tool tool:plugin.tools()) admitted.put(tool.name,new Entry(plugin.id(),tool));
        }
        return new Snapshot(admitted);
    }
    private static final class Entry {
        final String plugin; final MobilePlugin.Tool tool;
        Entry(String plugin,MobilePlugin.Tool tool) throws Exception {
            this.plugin=plugin; this.tool=new MobilePlugin.Tool(tool.name,tool.description,new JSONObject(tool.parameters.toString()),tool.confirm,tool.sideEffect,tool.handler);
        }
    }
    private static final class Invocation {
        final String plugin; final Cancellation token;
        Invocation(String plugin,Cancellation token) { this.plugin=plugin; this.token=token; }
    }
    public final class Snapshot {
        private final Map<String,Entry> admitted;
        private Snapshot(Map<String,Entry> admitted) { this.admitted=Collections.unmodifiableMap(admitted); }
        public JSONArray schemas() throws Exception {
            JSONArray result=new JSONArray();
            for(Entry entry:admitted.values()) result.put(new JSONObject().put("type","function").put("function",
                    new JSONObject().put("name",entry.tool.name).put("description",entry.tool.description)
                            .put("parameters",new JSONObject(entry.tool.parameters.toString()))));
            return result;
        }
        public JSONObject invoke(String name,JSONObject arguments,Cancellation cancellation) throws Exception {
            cancellation.check(); Entry entry=admitted.get(name);
            if(entry==null) return failure("TOOL_NOT_AVAILABLE");
            Invocation invocation=new Invocation(entry.plugin,cancellation);
            synchronized(PluginRegistry.this) {
                if(!enabled(entry.plugin)) return failure("TOOL_NOT_AVAILABLE");
                active.add(invocation);
            }
            try {
            try { validate(entry.tool.parameters,arguments); }
            catch(IllegalArgumentException e) { return failure("INVALID_TOOL_ARGUMENTS"); }
            if(entry.tool.confirm) {
                if(!approval.ask(entry.tool.description+"\n\n"+display(arguments.toString(2)),cancellation)) return failure("USER_REJECTED");
                cancellation.check();
                if(!enabled(entry.plugin)) return failure("PLUGIN_DISABLED");
            }
            cancellation.check();
            if(!enabled(entry.plugin)) return failure("PLUGIN_DISABLED");
            JSONObject result;
            try { result=entry.tool.handler.execute(new JSONObject(arguments.toString()),cancellation); }
            catch(java.io.InterruptedIOException e) { throw e; }
            catch(Exception e) { return failure("TOOL_FAILED"); }
            // Discard observations obtained after revocation. Side effects keep their outcome so
            // interruption never turns a completed write/action into an implied safe-to-repeat failure.
            if(!entry.tool.sideEffect) { cancellation.check(); if(!enabled(entry.plugin)) return failure("PLUGIN_DISABLED"); }
            if(result==null) return failure("TOOL_FAILED");
            if(result.toString().length()>32768) result=clamp(result);
            return result;
            } finally { synchronized(PluginRegistry.this) { active.remove(invocation); } }
        }
    }
    public static JSONObject failure(String code) throws Exception { return new JSONObject().put("ok",false).put("code",code); }
    public static JSONObject success(String code,Object data) throws Exception { return new JSONObject().put("ok",true).put("code",code).put("data",data); }

    /**
     * 结果超限时保留内容而不是整段丢掉。
     *
     * <p>之前超 32 KB 直接返回 data_omitted，网页正文和读屏结果就变成了"工具成功了但没有内容"，
     * 模型只能空转。现在先按字段截断：output 留头留尾，data 里的长字符串截到 6000 字符，
     * 仍然超限才丢弃 data，但 output 始终保留。
     */
    private static JSONObject clamp(JSONObject result) throws Exception {
        JSONObject out=new JSONObject();
        out.put("ok",result.optBoolean("ok",false));
        out.put("code",result.optString("code","RESULT_CLAMPED"));
        out.put("truncated",true);
        String output=result.optString("output","");
        if(!output.isEmpty()) out.put("output",headTail(output,20000));
        JSONObject data=result.optJSONObject("data");
        if(data!=null) {
            JSONObject shrunk=shrink(data,6000);
            if(shrunk.toString().length()<=12000) out.put("data",shrunk);
        }
        if(!out.has("data")&&!out.has("output")) {
            out.put("output",headTail(result.toString(),12000));
        }
        return out;
    }

    private static JSONObject shrink(JSONObject source,int limit) {
        JSONObject copy=new JSONObject();
        Iterator<String> keys=source.keys();
        while(keys.hasNext()) {
            String key=keys.next();
            Object value=source.opt(key);
            try {
                if(value instanceof String) copy.put(key,headTail((String)value,limit));
                else if(value instanceof JSONObject) copy.put(key,shrink((JSONObject)value,limit));
                else if(value instanceof JSONArray) copy.put(key,shrink((JSONArray)value,limit));
                else copy.put(key,value);
            } catch(Exception ignored) { }
        }
        return copy;
    }

    private static JSONArray shrink(JSONArray source,int limit) {
        JSONArray copy=new JSONArray();
        for(int i=0;i<source.length();i++) {
            Object value=source.opt(i);
            try {
                if(value instanceof String) copy.put(headTail((String)value,limit));
                else if(value instanceof JSONObject) copy.put(shrink((JSONObject)value,limit));
                else if(value instanceof JSONArray) copy.put(shrink((JSONArray)value,limit));
                else copy.put(value);
            } catch(Exception ignored) { }
        }
        return copy;
    }

    private static String headTail(String value,int limit) {
        if(value==null||value.length()<=limit) return value==null?"":value;
        int head=limit*3/4, tail=limit-head;
        return value.substring(0,head)+"\n…（中间已省略 "+(value.length()-limit)+" 字）…\n"
                +value.substring(value.length()-tail);
    }
    public static JSONObject schema(JSONObject properties,String... required) throws Exception {
        return new JSONObject().put("type","object").put("properties",properties)
                .put("required",new JSONArray(Arrays.asList(required))).put("additionalProperties",false);
    }
    public static JSONObject field(String type,String description) throws Exception { return new JSONObject().put("type",type).put("description",description); }
    private static void validate(JSONObject schema,JSONObject args) throws Exception {
        JSONObject properties=schema.getJSONObject("properties"); JSONArray required=schema.getJSONArray("required");
        for(int i=0;i<required.length();i++) if(!args.has(required.getString(i))||args.isNull(required.getString(i))) throw new IllegalArgumentException();
        Iterator<String> keys=args.keys();
        while(keys.hasNext()) {
            String key=keys.next(); if(!properties.has(key)) throw new IllegalArgumentException();
            Object value=args.get(key); String type=properties.getJSONObject(key).getString("type");
            if(("string".equals(type)&&!(value instanceof String))||("object".equals(type)&&!(value instanceof JSONObject))) throw new IllegalArgumentException();
        }
        if(args.toString().length()>16000) throw new IllegalArgumentException();
    }
    private static String display(String input) {
        StringBuilder result=new StringBuilder();
        for(char c:input.toCharArray()) {
            if((c>='\u202a'&&c<='\u202e')||(c>='\u2066'&&c<='\u2069')) result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
            else result.append(c);
        }
        return result.toString();
    }
}
