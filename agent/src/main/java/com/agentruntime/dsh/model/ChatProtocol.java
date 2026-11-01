package com.agentruntime.dsh.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Provider messages retain reasoning_content for DeepSeek multi-turn tool compatibility. */
public final class ChatProtocol {
    private ChatProtocol() { }
    public static JSONObject request(ProviderConfig.Snapshot config,JSONArray messages,JSONArray tools) throws Exception {
        JSONArray outgoing=new JSONArray();
        for(int i=0;i<messages.length();i++) {
            JSONObject message=new JSONObject(messages.getJSONObject(i).toString());
            if(config.deepSeek()&&"assistant".equals(message.optString("role"))) {
                if(!message.has("reasoning_content")) message.put("reasoning_content","");
            } else if(!config.deepSeek()) message.remove("reasoning_content");
            outgoing.put(message);
        }
        JSONObject body=new JSONObject().put("model",config.model).put("messages",outgoing)
                .put("stream",true).put("max_tokens",8192);
        if(tools.length()>0) body.put("tools",tools).put("tool_choice","auto");
        if(config.deepSeek()) body.put("thinking",new JSONObject().put("type",config.thinking?"enabled":"disabled"));
        return body;
    }

    public static JSONObject validate(JSONObject message,String finishReason) throws Exception {
        // 供应商可能返回 length / content_filter / 其它自造值。把原文状态带出去，
        // 一律报 MODEL_OUTPUT_INCOMPLETE 会让"被截断"和"被过滤"看起来一样。
        if(!"stop".equals(finishReason)&&!"tool_calls".equals(finishReason)) {
            String raw=finishReason==null?"null":finishReason.trim();
            if(!raw.matches("[A-Za-z0-9_.-]{1,40}")) raw="other";
            throw new IOException("MODEL_OUTPUT_INCOMPLETE:"+raw);
        }
        JSONObject clean=new JSONObject().put("role","assistant");
        Object content=message.opt("content");
        if(content!=null&&content!=JSONObject.NULL&&!(content instanceof String)) throw new IOException("MODEL_RESPONSE_INVALID");
        clean.put("content",content instanceof String?content:"");
        Object reasoning=message.opt("reasoning_content");
        if(reasoning!=null&&reasoning!=JSONObject.NULL) {
            if(!(reasoning instanceof String)) throw new IOException("MODEL_RESPONSE_INVALID");
            clean.put("reasoning_content",reasoning);
        }
        JSONArray calls=message.optJSONArray("tool_calls");
        if("tool_calls".equals(finishReason)!=(calls!=null&&calls.length()>0)) throw new IOException("MODEL_RESPONSE_INVALID");
        if(calls!=null&&calls.length()>0) {
            if(calls.length()>8) throw new IOException("TOO_MANY_TOOL_CALLS");
            Set<String> ids=new HashSet<>(); JSONArray normalized=new JSONArray();
            for(int i=0;i<calls.length();i++) {
                JSONObject call=calls.getJSONObject(i); String id=call.getString("id");
                JSONObject function=call.getJSONObject("function"); String name=function.getString("name"),args=function.getString("arguments");
                if(!id.matches("[A-Za-z0-9_.:-]{1,160}")||!ids.add(id)||!name.matches("[a-z][a-z0-9_]{0,63}")||args.length()>16000) throw new IOException("TOOL_CALL_INVALID");
                new JSONObject(args);
                normalized.put(new JSONObject().put("id",id).put("type","function")
                        .put("function",new JSONObject().put("name",name).put("arguments",args)));
            }
            clean.put("tool_calls",normalized);
        } else if(clean.optString("content").trim().isEmpty()) throw new IOException("MODEL_RESPONSE_EMPTY");
        if(clean.toString().length()>512*1024) throw new IOException("MODEL_RESPONSE_TOO_LARGE");
        return clean;
    }
}
