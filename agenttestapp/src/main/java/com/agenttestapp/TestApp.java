package com.agenttestapp;

import android.app.Application;
import android.content.Context;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 测试应用全局状态与消息存储。
 * 虚构测试数据：TestUser / TestUser2 / Admin；消息持久化供验证脚本核对。
 * 故障注入开关同步 Settings.Secure（adb 可控），保证验证可复现。
 */
public final class TestApp extends Application {

    public static final String KEY_INJECT_DIALOG = "agent_test_inject_dialog";
    public static final String KEY_PAGE_UPGRADE = "agent_test_page_upgrade";

    /** 故障注入：打开聊天页时弹出权限样式对话框（T10）。 */
    public static volatile boolean injectDialogOnChatOpen = false;
    /** 页面升级模拟：聊天页关键控件改用新 ID/新文案（T12 Self-Healing）。 */
    public static volatile boolean pageUpgradeMode = false;

    private static MessageStore store;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new MessageStore(this);
        injectDialogOnChatOpen = readFlag(KEY_INJECT_DIALOG);
        pageUpgradeMode = readFlag(KEY_PAGE_UPGRADE);
    }

    private boolean readFlag(String key) {
        try {
            return Settings.Secure.getInt(getContentResolver(), key, 0) == 1;
        } catch (SecurityException e) {
            return false;
        }
    }

    public static void setFlag(Context context, String key, boolean value) {
        if (KEY_INJECT_DIALOG.equals(key)) injectDialogOnChatOpen = value;
        if (KEY_PAGE_UPGRADE.equals(key)) pageUpgradeMode = value;
        try {
            Settings.Secure.putInt(context.getContentResolver(), key, value ? 1 : 0);
        } catch (SecurityException ignored) {
        }
    }

    public static MessageStore store() {
        return store;
    }

    public static final class MessageRecord {
        public final String userId;
        public final String alias;
        public final String type;
        public final String content;
        public final long ts;

        public MessageRecord(String userId, String alias, String type, String content, long ts) {
            this.userId = userId;
            this.alias = alias;
            this.type = type;
            this.content = content;
            this.ts = ts;
        }
    }

    public static final class MessageStore {
        private final File file;
        private final Object lock = new Object();

        MessageStore(Application app) {
            file = new File(app.getFilesDir(), "messages.json");
        }

        public void append(MessageRecord r) {
            synchronized (lock) {
                JSONArray arr = readAll();
                JSONObject o = new JSONObject();
                try {
                    o.put("user_id", r.userId);
                    o.put("alias", r.alias);
                    o.put("type", r.type);
                    o.put("content", r.content);
                    o.put("ts", r.ts);
                    arr.put(o);
                    try (FileOutputStream fos = new FileOutputStream(file, false)) {
                        fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                    }
                } catch (JSONException | IOException ignored) {
                }
            }
        }

        public JSONArray readAll() {
            synchronized (lock) {
                if (!file.exists()) return new JSONArray();
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[(int) file.length()];
                    int n = fis.read(buf);
                    if (n <= 0) return new JSONArray();
                    return new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (IOException | JSONException e) {
                    return new JSONArray();
                }
            }
        }
    }
}
