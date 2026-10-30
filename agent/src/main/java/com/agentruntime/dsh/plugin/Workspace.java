package com.agentruntime.dsh.plugin;

import com.agentruntime.dsh.model.Cancellation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** An app-private workspace, isolated from credentials, databases, models and legacy task files. */
public final class Workspace implements MobilePlugin {
    private final Path root;
    public Workspace(File root) throws Exception {
        Files.createDirectories(root.toPath()); this.root=root.toPath().toRealPath();
    }
    @Override public String id() { return "workspace"; }
    @Override public String title() { return "本机工作区"; }
    @Override public String description() { return "查看、读取和保存文本文件；写入前向你确认"; }
    @Override public List<Tool> tools() {
        try {
            JSONObject path=new JSONObject().put("path",PluginRegistry.field("string","工作区内的相对文件路径"));
            return Arrays.asList(
                    new Tool("workspace_list","列出本机工作区文件",PluginRegistry.schema(new JSONObject()),false,(args,token)->list()),
                    new Tool("workspace_read","读取工作区内文本文件，最多 16 KiB",PluginRegistry.schema(path,"path"),false,(args,token)->read(args.getString("path"))),
                    new Tool("workspace_write","保存或覆盖工作区文本文件；需要用户确认",
                            PluginRegistry.schema(new JSONObject(path.toString()).put("content",PluginRegistry.field("string","完整文本内容，最多 16 KiB")),"path","content"),
                            true,(args,token)->write(args.getString("path"),args.getString("content"),token)));
        } catch(Exception e) { throw new IllegalStateException("Workspace schema invalid",e); }
    }
    public Path resolve(String relative) throws Exception {
        if(relative==null||relative.isEmpty()||relative.length()>240||relative.contains("\\")||relative.contains(":")) throw new IllegalArgumentException("Invalid workspace path");
        Path value=root.resolve(relative).normalize();
        if(new File(relative).isAbsolute()||!value.startsWith(root)||value.equals(root)) throw new IllegalArgumentException("Invalid workspace path");
        Path existing=value;
        while(existing!=null&&!Files.exists(existing,java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing=existing.getParent();
        if(existing==null||!existing.toRealPath().startsWith(root)) throw new IllegalArgumentException("Invalid workspace path");
        if(Files.exists(value)&&!value.toRealPath().startsWith(root)) throw new IllegalArgumentException("Invalid workspace path");
        return value;
    }
    public JSONObject list() throws Exception {
        JSONArray files=new JSONArray();
        try(java.util.stream.Stream<Path> stream=Files.walk(root,4)) {
            java.util.Iterator<Path> iterator=stream.iterator();
            while(iterator.hasNext()&&files.length()<100) {
                Path path=iterator.next();
                if(!Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().startsWith(root)) continue;
                files.put(new JSONObject().put("path",root.relativize(path).toString().replace('\\','/')).put("bytes",Files.size(path)));
            }
        }
        return PluginRegistry.success("FILES_LISTED",files);
    }
    public JSONObject read(String relative) throws Exception {
        Path path=resolve(relative);
        if(!Files.isRegularFile(path)||Files.size(path)>16384) return PluginRegistry.failure("FILE_MISSING_OR_TOO_LARGE");
        try(java.io.InputStream input=Files.newInputStream(path); java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int count;
            while((count=input.read(buffer))!=-1) { if(bytes.size()+count>16384) return PluginRegistry.failure("FILE_TOO_LARGE"); bytes.write(buffer,0,count); }
            return PluginRegistry.success("FILE_READ",new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    public JSONObject write(String relative,String text,Cancellation cancellation) throws Exception {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8); if(bytes.length>16384) return PluginRegistry.failure("FILE_TOO_LARGE");
        Path path=resolve(relative); cancellation.check(); Files.createDirectories(path.getParent());
        path=resolve(relative); cancellation.check();
        android.util.AtomicFile file=new android.util.AtomicFile(path.toFile()); java.io.FileOutputStream output=null;
        try { output=file.startWrite(); cancellation.check(); output.write(bytes); cancellation.check(); file.finishWrite(output); output=null; }
        finally { if(output!=null) file.failWrite(output); }
        return PluginRegistry.success("FILE_WRITTEN",new JSONObject().put("path",relative).put("bytes",bytes.length));
    }
}
