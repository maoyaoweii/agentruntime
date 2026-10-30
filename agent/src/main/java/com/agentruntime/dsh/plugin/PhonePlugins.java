package com.agentruntime.dsh.plugin;

import com.agentruntime.AgentApp;
import com.agentruntime.runtime.AgentRuntime;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.ui.TaskRunner;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class PhonePlugins {
    private PhonePlugins() { }
    public static List<MobilePlugin> create(AgentApp app) throws Exception {
        MobilePlugin device=plugin("device","设备观察","读取电量、网络和系统环境；不读取其他应用正文",
                new MobilePlugin.Tool("device_status","读取当前手机的电量、网络、系统与权限状态",
                        PluginRegistry.schema(new JSONObject()),false,(args,token)->PluginRegistry.success("DEVICE_OBSERVED",app.envProbe().refresh().toJson())));
        MobilePlugin skills=plugin("phone-skills","手机技能","调用现有 Android 技能；高风险动作通过系统确认界面",
                new MobilePlugin.Tool("list_skills","列出手机技能及必填参数；调用 run_skill 前先查看",
                        PluginRegistry.schema(new JSONObject()),false,(args,token)-> {
                            JSONArray result=new JSONArray(); List<String> ids=app.skillEngine().skillIds(); Collections.sort(ids);
                            for(String id:ids) { SkillManifest manifest=app.skillEngine().manifest(id);
                                result.put(new JSONObject().put("id",id).put("args",new JSONArray(manifest.requiredArgs))
                                        .put("risk",manifest.risk.name()).put("target_package",manifest.targetPackage)); }
                            return PluginRegistry.success("SKILLS_LISTED",result);
                        }),
                new MobilePlugin.Tool("run_skill","执行已登记手机技能。禁止编造参数；高风险由用户确认。返回执行状态与审计摘要，不保证返回文件正文。",
                        PluginRegistry.schema(new JSONObject().put("skill_id",PluginRegistry.field("string","list_skills 返回的技能 ID"))
                                .put("args",PluginRegistry.field("object","该技能的参数对象")),"skill_id","args"),false,(args,token)-> {
                            token.check(); String skill=args.getString("skill_id");
                            if(!app.skillEngine().hasSkill(skill)) return PluginRegistry.failure("UNKNOWN_SKILL");
                            AgentRuntime.TaskResult result=TaskRunner.runSkill(skill,args.getJSONObject("args"),false,token.flag);
                            return new JSONObject().put("ok","SUCCESS".equals(result.status)).put("code",result.status).put("data",result.detail);
                        }));
        return Arrays.asList(device,new Workspace(new java.io.File(app.getFilesDir(),"dsh-workspace")),skills);
    }
    private static MobilePlugin plugin(String id,String title,String description,MobilePlugin.Tool... tools) {
        return new MobilePlugin() {
            @Override public String id() { return id; }
            @Override public String title() { return title; }
            @Override public String description() { return description; }
            @Override public List<Tool> tools() { return Arrays.asList(tools); }
        };
    }
}
