package com.agentruntime.dsh.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

public interface ModelTransport {
    JSONObject complete(ProviderConfig.Snapshot provider,JSONArray messages,JSONArray tools,
                        Cancellation cancellation,Consumer<String> textDelta) throws Exception;
}
