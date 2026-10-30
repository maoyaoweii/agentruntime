package com.agentruntime.dsh;

import com.agentruntime.dsh.model.*;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ChatStreamTest {
    @Test public void decodesUtf8AndJoinsToolArgumentDeltasWhileRetainingReasoning() throws Exception {
        JSONArray first=new JSONArray().put(new JSONObject().put("index",0).put("id","call_1")
                .put("function",new JSONObject().put("name","workspace_read").put("arguments","{\"path\":")));
        JSONArray second=new JSONArray().put(new JSONObject().put("index",0)
                .put("function",new JSONObject().put("arguments","\"笔记.txt\"}")));
        String stream=frame(new JSONObject().put("reasoning_content","reason-fixture"),null)
                +frame(new JSONObject().put("content","查看笔记😀").put("tool_calls",first),null)
                +frame(new JSONObject().put("tool_calls",second),"tool_calls")+"data: [DONE]\n\n";
        StringBuilder text=new StringBuilder();
        JSONObject result=ChatClient.readStream(bytes(stream),new Cancellation(5000),text::append);
        assertEquals("查看笔记😀",text.toString());
        assertEquals("reason-fixture",result.getString("reasoning_content"));
        String args=result.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments");
        assertEquals("笔记.txt",new JSONObject(args).getString("path"));
    }

    @Test public void truncatedAndOversizedFramesCannotBecomeExecutableCalls() throws Exception {
        assertThrows(Exception.class,()->ChatClient.readStream(bytes(frame(new JSONObject().put("content","partial"),null)),new Cancellation(5000),s->{}));
        assertThrows(Exception.class,()->ChatClient.readStream(bytes("data: "+"x".repeat(65537)+"\n\n"),new Cancellation(5000),s->{}));
    }

    @Test public void cancellationIsObservedBeforeReturningAnySettledMessage() throws Exception {
        Cancellation cancellation=new Cancellation(5000); cancellation.cancel("CANCELLED");
        assertThrows(InterruptedIOException.class,()->ChatClient.readStream(bytes(frame(new JSONObject().put("content","hello"),"stop")),cancellation,s->{}));
    }

    private InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes,int offset,int length) { return super.read(bytes,offset,Math.min(length,3)); }
        };
    }
    private String frame(JSONObject delta,String reason) throws Exception {
        return "data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("delta",delta)
                .put("finish_reason",reason==null?JSONObject.NULL:reason))).toString()+"\n\n";
    }
}
