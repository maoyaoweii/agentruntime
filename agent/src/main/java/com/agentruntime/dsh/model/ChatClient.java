package com.agentruntime.dsh.model;

import com.agentruntime.network.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class ChatClient implements ModelTransport {
    @Override public JSONObject complete(ProviderConfig.Snapshot provider,JSONArray messages,JSONArray tools,
                                         Cancellation cancellation,Consumer<String> delta) throws Exception {
        cancellation.check(); HttpURLConnection connection=(HttpURLConnection)new URL(provider.endpoint).openConnection();
        try {
            cancellation.attach(connection);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000); connection.setReadTimeout(90000); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            connection.setRequestProperty("Accept","text/event-stream, application/json");
            connection.setRequestProperty("Authorization","Bearer "+provider.apiKey);
            byte[] bytes=ChatProtocol.request(provider,messages,tools).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=connection.getOutputStream()) { cancellation.check(); out.write(bytes); }
            int status=connection.getResponseCode();
            if(status<200||status>=300) throw new IOException("HTTP_"+status);
            String type=connection.getContentType();
            try(InputStream input=connection.getInputStream()) {
                if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/event-stream")) return readStream(input,cancellation,delta);
                JSONObject response=new JSONObject(ResponseBody.read(input));
                if(response.has("error")) throw new IOException("PROVIDER_ERROR");
                JSONObject choice=response.getJSONArray("choices").getJSONObject(0);
                JSONObject message=ChatProtocol.validate(choice.getJSONObject("message"),choice.optString("finish_reason",""));
                cancellation.check(); delta.accept(message.optString("content","")); return message;
            }
        } finally { cancellation.detach(connection); connection.disconnect(); }
    }

    public static JSONObject readStream(InputStream input,Cancellation cancellation,Consumer<String> output) throws Exception {
        Accumulator result=new Accumulator(); StringBuilder line=new StringBuilder(),event=new StringBuilder();
        int total=0;
        try(Reader reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {
            int c;
            while((c=reader.read())!=-1) {
                if(++total>2*1024*1024) throw new IOException("MODEL_RESPONSE_TOO_LARGE");
                if((total&255)==0) cancellation.check();
                if(c=='\r') continue;
                if(c!='\n') { line.append((char)c); if(line.length()>65536) throw new IOException("STREAM_FRAME_TOO_LARGE"); continue; }
                String value=line.toString(); line.setLength(0);
                if(value.isEmpty()) {
                    if(event.length()>0) {
                        if(process(event.toString(),result,output)) break;
                        event.setLength(0);
                    }
                } else if(value.startsWith("data:")) {
                    if(event.length()>0) event.append('\n'); event.append(value.substring(5).trim());
                    if(event.length()>65536) throw new IOException("STREAM_FRAME_TOO_LARGE");
                }
            }
            if(line.length()>0&&line.toString().startsWith("data:")) event.append(line.substring(5).trim());
            if(event.length()>0&&!event.toString().equals("[DONE]")) process(event.toString(),result,output);
        }
        cancellation.check(); return result.finish();
    }

    private static boolean process(String data,Accumulator result,Consumer<String> output) throws Exception {
        if(data.equals("[DONE]")) return true;
        JSONObject frame=new JSONObject(data);
        if(frame.has("error")) throw new IOException("PROVIDER_ERROR");
        JSONArray choices=frame.optJSONArray("choices"); if(choices==null||choices.length()==0) return false;
        JSONObject choice=choices.getJSONObject(0),delta=choice.optJSONObject("delta");
        if(delta!=null) result.add(delta,output);
        if(!choice.isNull("finish_reason")&&choice.has("finish_reason")) result.reason=choice.getString("finish_reason");
        return false;
    }

    private static final class Accumulator {
        final StringBuilder text=new StringBuilder(),reasoning=new StringBuilder();
        final TreeMap<Integer,JSONObject> calls=new TreeMap<>();
        String reason="";
        void add(JSONObject delta,Consumer<String> output) throws Exception {
            if(!delta.isNull("content")&&delta.has("content")) {
                String part=delta.getString("content"); text.append(part); output.accept(part);
            }
            if(!delta.isNull("reasoning_content")&&delta.has("reasoning_content")) reasoning.append(delta.getString("reasoning_content"));
            JSONArray tools=delta.optJSONArray("tool_calls");
            if(tools!=null) for(int i=0;i<tools.length();i++) {
                JSONObject change=tools.getJSONObject(i); int index=change.getInt("index");
                if(index<0||index>7) throw new IOException("TOO_MANY_TOOL_CALLS");
                JSONObject call=calls.get(index);
                if(call==null) { call=new JSONObject().put("id","").put("function",new JSONObject().put("name","").put("arguments","")); calls.put(index,call); }
                if(change.has("id")) call.put("id",call.getString("id")+change.getString("id"));
                JSONObject function=change.optJSONObject("function");
                if(function!=null) for(String key:new String[]{"name","arguments"}) {
                    if(function.has(key)) call.getJSONObject("function").put(key,call.getJSONObject("function").getString(key)+function.getString(key));
                }
            }
            int size=text.length()+reasoning.length();
            for(JSONObject call:calls.values()) size+=call.toString().length();
            if(size>512*1024) throw new IOException("MODEL_RESPONSE_TOO_LARGE");
        }
        JSONObject finish() throws Exception {
            JSONObject message=new JSONObject().put("role","assistant").put("content",text.toString());
            if(reasoning.length()>0) message.put("reasoning_content",reasoning.toString());
            if(!calls.isEmpty()) { JSONArray all=new JSONArray(); for(JSONObject call:calls.values()) all.put(call); message.put("tool_calls",all); }
            return ChatProtocol.validate(message,reason);
        }
    }
}
