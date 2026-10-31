package com.agentruntime.dsh.plugin;

import com.agentruntime.runtime.AgentRuntime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PhonePluginOutcomeTest {
    @Test public void rejectedActionIsReportedAsUserRejection() throws Exception {
        JSONObject detail=new JSONObject().put("steps",new JSONArray().put(new JSONObject().put("code","RISK_REJECTED")));
        assertEquals("CONFIRMATION_NOT_OBTAINED",PhonePlugins.outcomeCode(new AgentRuntime.TaskResult("fixture","FAILED",detail)));
        assertEquals("SUCCESS",PhonePlugins.outcomeCode(new AgentRuntime.TaskResult("fixture","SUCCESS",new JSONObject())));
    }
}
