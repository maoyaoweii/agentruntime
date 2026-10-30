package com.agentruntime.dsh;

import android.app.Application;
import android.content.Context;
import com.agentruntime.dsh.model.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,application=Application.class)
public class ProviderProtocolTest {
    @Test public void encryptsCredentialsAndPreservesThemOnBlankFormSave() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=context.getSharedPreferences("vault_fixture",0);
        CredentialVault vault=new CredentialVault(prefs,()->new SecretKeySpec(new byte[32],"AES"));
        ProviderConfig provider=new ProviderConfig(context,vault);
        provider.save("https://api.deepseek.com","deepseek-flash","synthetic-api-key-fixture",false);
        assertFalse(prefs.getAll().toString().contains("synthetic-api-key-fixture"));
        provider.save("https://api.deepseek.com/v1","custom-model","",true);
        assertEquals("synthetic-api-key-fixture",provider.snapshot().apiKey);
        assertEquals("https://api.deepseek.com/v1/chat/completions",provider.snapshot().endpoint);
        assertEquals("custom-model",provider.snapshot().model);
        prefs.edit().putString("ciphertext","tampered").commit();
        assertThrows(Exception.class,vault::load);
    }

    @Test public void refusesRemoteCleartextCredentialsInUrlsAndDangerousSchemes() {
        for(String value:new String[]{"http://example.com","http://10.0.2.2","file:///data/secret","https://user:password@example.com","https://example.com/?key=secret","https://example.com/#secret"}) {
            assertThrows(IllegalArgumentException.class,()->ProviderConfig.endpoint(value));
        }
        assertEquals("http://127.0.0.1:3080/v1/chat/completions",ProviderConfig.endpoint("http://127.0.0.1:3080/v1/"));
    }

    @Test public void aDelayedSaveCannotUndoALaterClearAfterActivityRecreation() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        CredentialVault vault=new CredentialVault(context.getSharedPreferences("edit_revision",0),()->new SecretKeySpec(new byte[32],"AES"));
        ProviderConfig provider=new ProviderConfig(context,vault);
        provider.save("https://api.deepseek.com","fixture","synthetic-key",false);
        long delayedEdit=provider.reserveEdit();
        provider.clearKey();
        assertThrows(IllegalStateException.class,()->provider.save("https://api.deepseek.com","fixture","stale-key",false,delayedEdit));
        assertFalse(provider.configured());
    }

    @Test public void preservesReasoningAndRejectsTruncatedOrRepeatedToolCalls() throws Exception {
        JSONObject call=new JSONObject().put("id","call_1").put("function",new JSONObject().put("name","device_status").put("arguments","{}"));
        JSONObject message=new JSONObject().put("content","").put("reasoning_content","provider-reasoning-fixture")
                .put("tool_calls",new JSONArray().put(call));
        assertEquals("provider-reasoning-fixture",ChatProtocol.validate(message,"tool_calls").getString("reasoning_content"));
        assertThrows(Exception.class,()->ChatProtocol.validate(message,"length"));
        assertThrows(Exception.class,()->ChatProtocol.validate(message,"stop"));
        message.getJSONArray("tool_calls").put(call);
        assertThrows(Exception.class,()->ChatProtocol.validate(message,"tool_calls"));
    }
}
