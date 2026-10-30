package com.agentruntime.dsh;

import android.app.Application;
import com.agentruntime.dsh.model.*;
import com.agentruntime.dsh.plugin.PluginRegistry;
import com.agentruntime.dsh.plugin.Workspace;
import com.agentruntime.dsh.runtime.DshRuntime;
import com.agentruntime.dsh.store.SessionStore;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Exercises real HTTP framing + SSE + SQLite + the admitted workspace tool. No external service/cost. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=Application.class)
public class ChatHttpIntegrationTest {
    @Test(timeout=20000) public void realStreamRunsConfirmedToolAndCarriesItsResultIntoTheNextRequest() throws Exception {
        JSONObject call=new JSONObject().put("id","http_call_1").put("type","function").put("function",new JSONObject().put("name","workspace_write")
                .put("arguments",new JSONObject().put("path","notes/hello.txt").put("content","你好，手机 DSH 👋").toString()));
        String first=sse(new JSONObject().put("role","assistant").put("content","").put("reasoning_content","provider-fixture").put("tool_calls",new JSONArray().put(call)),"tool_calls");
        String second=sse(new JSONObject().put("content","已保存，你好 👋"),"stop");
        try(FixtureServer server=new FixtureServer(Arrays.asList(first,second,second)); SessionStore store=new SessionStore(RuntimeEnvironment.getApplication())) {
            PluginRegistry registry=new PluginRegistry(RuntimeEnvironment.getApplication().getSharedPreferences("http_plugins",0),(summary,token)->summary.contains("你好，手机 DSH 👋"));
            Workspace workspace=new Workspace(new File(RuntimeEnvironment.getApplication().getFilesDir(),"http-workspace")); registry.register(workspace);
            ProviderConfig.Snapshot provider=new ProviderConfig.Snapshot(server.url(),"fixture","synthetic-http-key",false);
            DshRuntime runtime=new DshRuntime(store,new ChatClient()); String session=store.createSession().id;
            DshRuntime.Listener quiet=new DshRuntime.Listener(){public void status(String s){} public void delta(String s){} public void settled(){}};
            String turn=runtime.run(session,"保存测试文字",true,provider,registry.snapshot(true),new Cancellation(10000),quiet);
            assertEquals("completed",store.turnState(turn)); assertEquals("你好，手机 DSH 👋",workspace.read("notes/hello.txt").getString("data"));
            runtime.run(session,"接着聊",true,provider,registry.snapshot(true),new Cancellation(10000),quiet);
            server.done.get(5,TimeUnit.SECONDS);
            assertEquals(3,server.requests.size());
            for(int i=1;i<=2;i++) {
                JSONArray messages=server.requests.get(i).getJSONArray("messages");
                assertEquals("provider-fixture",messages.getJSONObject(2).getString("reasoning_content"));
                assertEquals("http_call_1",messages.getJSONObject(3).getString("tool_call_id"));
                assertTrue(new JSONObject(messages.getJSONObject(3).getString("content")).getBoolean("ok"));
            }
            assertFalse(store.history(session,100000).toString().contains("synthetic-http-key"));
        }
    }
    @Test(timeout=10000) public void redirectsDoNotForwardCredentials() throws Exception {
        try(FixtureServer server=new FixtureServer(Collections.singletonList("redirect"))) {
            ProviderConfig.Snapshot provider=new ProviderConfig.Snapshot(server.url(),"fixture","synthetic-http-key",false);
            IOException error=assertThrows(IOException.class,()->new ChatClient().complete(provider,new JSONArray().put(new JSONObject().put("role","user").put("content","fixture")),new JSONArray(),new Cancellation(5000),s->{}));
            assertEquals("HTTP_302",error.getMessage()); server.done.get(3,TimeUnit.SECONDS); assertEquals(1,server.requests.size());
        }
    }
    private static String sse(JSONObject delta,String reason) throws Exception {
        JSONArray calls=delta.optJSONArray("tool_calls"); if(calls!=null) for(int i=0;i<calls.length();i++) calls.getJSONObject(i).put("index",i);
        return "data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("delta",delta).put("finish_reason",JSONObject.NULL)))+"\n\n"
                +"data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("delta",new JSONObject()).put("finish_reason",reason)))+"\n\n"
                +"data: [DONE]\n\n";
    }
    private static final class FixtureServer implements AutoCloseable {
        final ServerSocket socket;
        final List<JSONObject> requests=Collections.synchronizedList(new ArrayList<>());
        final FutureTask<Void> done;
        FixtureServer(List<String> replies) throws Exception {
            socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1")); socket.setSoTimeout(5000);
            done=new FutureTask<>(()->{
                for(String reply:replies) try(Socket client=socket.accept()) {
                    client.setSoTimeout(5000); InputStream input=client.getInputStream(); StringBuilder header=new StringBuilder(); int c;
                    while((c=input.read())!=-1) { header.append((char)c); if(header.toString().endsWith("\r\n\r\n")) break; if(header.length()>16000) throw new IOException("header too large"); }
                    assertTrue(header.toString().contains("Authorization: Bearer synthetic-http-key"));
                    int length=0; for(String line:header.toString().split("\r\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                    assertTrue(length>0&&length<400000); byte[] body=input.readNBytes(length); assertEquals(length,body.length); requests.add(new JSONObject(new String(body,StandardCharsets.UTF_8)));
                    String headers; byte[] response;
                    if("redirect".equals(reply)) { response=new byte[0]; headers="HTTP/1.1 302 Found\r\nLocation: "+url()+"/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"; }
                    else { response=reply.getBytes(StandardCharsets.UTF_8); headers="HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n"; }
                    client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); client.getOutputStream().write(response); client.getOutputStream().flush();
                }
                return null;
            }); Thread worker=new Thread(done,"dsh-http-fixture"); worker.setDaemon(true); worker.start();
        }
        String url() { return "http://127.0.0.1:"+socket.getLocalPort(); }
        @Override public void close() throws Exception { socket.close(); if(!done.isDone()) done.cancel(true); }
    }
}
