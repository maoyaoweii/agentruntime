package com.agentruntime.dsh.plugin;

import com.agentruntime.AgentApp;
import com.agentruntime.runtime.AgentRuntime;
import com.agentruntime.skill.SkillManifest;
import com.agentruntime.ui.TaskRunner;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class PhonePlugins {
    private static final int OUTPUT_LIMIT=20000;
    /** 这些技能不碰别的应用窗口，执行前不需要把聊天界面让到后台。 */
    private static final Set<String> KEEP_FOREGROUND=new HashSet<>(Arrays.asList(
            "sys.observe","sys.open_clock","sys.tool_smoke","sys.coordinate_smoke",
            "sys.file_ops","sys.file_io_probe","sys.file_delete","sys.media_control",
            "sys.notifications","sys.open_settings_page","sys.close_dialogs",
            "sys.clipboard_copy","notes.create_note"));
    private PhonePlugins() { }
    public static List<MobilePlugin> create(AgentApp app) throws Exception {
        MobilePlugin device=plugin("device","设备观察","读取电量、网络和系统环境；不读取其他应用正文",
                new MobilePlugin.Tool("device_status","读取当前手机的电量、网络、系统与无障碍真实可用状态",
                        PluginRegistry.schema(new JSONObject()),false,(args,token)->PluginRegistry.success("DEVICE_OBSERVED",app.envProbe().refresh().toJson())));
        MobilePlugin skills=plugin("phone-skills","手机技能","调用现有 Android 技能；高风险动作通过系统确认界面",
                new MobilePlugin.Tool("list_skills","列出手机技能、必填参数、风险与用途说明；调用 run_skill 前先查看",
                        PluginRegistry.schema(new JSONObject()),false,(args,token)-> {
                            JSONArray result=new JSONArray(); List<String> ids=app.skillEngine().skillIds(); Collections.sort(ids);
                            for(String id:ids) { SkillManifest manifest=app.skillEngine().manifest(id);
                                JSONObject entry=new JSONObject().put("id",id).put("args",new JSONArray(manifest.requiredArgs))
                                        .put("risk",manifest.risk.name()).put("target_package",manifest.targetPackage);
                                // 用途说明让模型不必靠猜技能名：sys.observe 是设备状态，不是读屏。
                                if(manifest.recoveryHint!=null&&!manifest.recoveryHint.isEmpty())
                                    entry.put("note",clip(manifest.recoveryHint,240));
                                result.put(entry); }
                            return PluginRegistry.success("SKILLS_LISTED",result);
                        }),
                new MobilePlugin.Tool("run_skill","执行已登记手机技能。禁止编造参数；高风险由用户确认。返回 output（读屏文字、网页正文、执行说明）与结构化的 steps。",
                        PluginRegistry.schema(new JSONObject().put("skill_id",PluginRegistry.field("string","list_skills 返回的技能 ID"))
                                .put("args",PluginRegistry.field("object","该技能的参数对象")),"skill_id","args"),false,true,(args,token)-> {
                            token.check(); String skill=args.getString("skill_id");
                            if(!app.skillEngine().hasSkill(skill)) return PluginRegistry.failure("UNKNOWN_SKILL");
                            // 聊天界面占着前台时，无障碍只能看到 DSH 自己的窗口（连"热门"都会从自己气泡里命中）。
                            // 跨应用动作开始前先把聊天退到后台，把前台让给真正要操作的应用。
                            if(!KEEP_FOREGROUND.contains(skill)) com.agentruntime.ui.AppWindow.yieldDevice(450);
                            AgentRuntime.TaskResult result=TaskRunner.runSkill(skill,args.getJSONObject("args"),false,token.flag);
                            return new JSONObject().put("ok","SUCCESS".equals(result.status)).put("code",result.status)
                                    .put("output",observations(result.detail)).put("data",result.detail);
                        }));
        return Arrays.asList(device,new Workspace(new java.io.File(app.getFilesDir(),"dsh-workspace")),skills);
    }

    /**
     * 把每个动作的观察内容拼成一段人话，放在结果顶层。
     *
     * <p>模型直接读 output 比在 steps[].actions[].result.detail 里挖要稳得多，
     * 也避免了嵌套过深被截断时"只看到状态码"。
     */
    private static String observations(JSONObject detail) {
        StringBuilder out=new StringBuilder();
        JSONArray steps=detail.optJSONArray("steps");
        if(steps!=null) for(int i=0;i<steps.length();i++) {
            JSONObject step=steps.optJSONObject(i); if(step==null) continue;
            JSONArray actions=step.optJSONArray("actions"); if(actions==null) continue;
            for(int j=0;j<actions.length();j++) {
                JSONObject action=actions.optJSONObject(j); if(action==null) continue;
                JSONObject result=action.optJSONObject("result");
                String text=result==null?"":result.optString("detail","");
                String code=result==null?"":result.optString("code","");
                if(text.isEmpty()) continue;
                if(out.length()>0) out.append("\n\n");
                out.append("[").append(action.optString("action","action")).append(" · ").append(code).append("]\n").append(text);
                if(out.length()>OUTPUT_LIMIT) return clip(out.toString(),OUTPUT_LIMIT)+"\n…（输出过长，后面部分已省略）";
            }
        }
        if(out.length()==0) {
            String status=detail.optString("status","");
            int failed=detail.optInt("failed_steps",0);
            out.append("没有可读的返回内容。status=").append(status);
            if(failed>0) out.append("，失败步骤 ").append(failed);
            out.append("。请查看 data.steps 里的 code；若 code 是 A11Y_ROOT_UNAVAILABLE 或 NODE_NOT_INTERACTABLE，说明无障碍没有真正读到界面。");
        }
        return out.toString();
    }

    private static String clip(String value,int max) {
        if(value==null) return "";
        return value.length()<=max?value:value.substring(0,max);
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
