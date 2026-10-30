package com.agentruntime.dsh.plugin;

import com.agentruntime.dsh.model.Cancellation;
import org.json.JSONObject;
import java.util.List;

/** Android extension contract; native plugins are compiled into the APK, not downloaded code. */
public interface MobilePlugin {
    String id();
    String title();
    String description();
    List<Tool> tools();

    interface Handler { JSONObject execute(JSONObject arguments,Cancellation cancellation) throws Exception; }
    final class Tool {
        public final String name,description;
        public final JSONObject parameters;
        public final boolean confirm;
        public final Handler handler;
        public Tool(String name,String description,JSONObject parameters,boolean confirm,Handler handler) {
            this.name=name; this.description=description; this.parameters=parameters; this.confirm=confirm; this.handler=handler;
        }
    }
}
