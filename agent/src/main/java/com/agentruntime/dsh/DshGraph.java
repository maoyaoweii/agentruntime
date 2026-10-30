package com.agentruntime.dsh;

import com.agentruntime.AgentApp;
import com.agentruntime.dsh.model.*;
import com.agentruntime.dsh.plugin.*;
import com.agentruntime.dsh.runtime.*;
import com.agentruntime.dsh.store.SessionStore;
import java.io.File;

/** Native mobile composition root; adapters can be replaced without changing the agent loop. */
public final class DshGraph {
    public final SessionStore sessions;
    public final ProviderConfig provider;
    public final PluginRegistry plugins;
    public final DshController controller;
    public final Workspace workspace;
    public DshGraph(AgentApp app) throws Exception {
        sessions=new SessionStore(app); sessions.recoverInterrupted();
        provider=new ProviderConfig(app,new CredentialVault(app));
        plugins=new PluginRegistry(app.getSharedPreferences("dsh_plugins",0),(summary,token)->
                app.confirmations().requestConfirmation("dsh_tool",summary,token.flag));
        plugins.onRevoked(token->{
            token.cancel("PLUGIN_DISABLED");
            com.agentruntime.ui.TaskRunner.cancel(token.flag);
            app.confirmations().cancelPending(token.flag);
        });
        for(MobilePlugin plugin:PhonePlugins.create(app)) plugins.register(plugin);
        workspace=new Workspace(new File(app.getFilesDir(),"dsh-workspace"));
        controller=new DshController(app,sessions,provider,plugins,new DshRuntime(sessions,new ChatClient()));
    }
}
