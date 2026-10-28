package com.agentruntime.notify;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知访问通道：订阅系统通知，供 Agent 回答"刚收到了什么"一类问题。
 *
 * <p>为什么用 {@link NotificationListenerService} 而不是读通知栏 UI：
 * 通知栏是系统界面，节点树里只有刚展开的一条；而监听服务拿到的是<b>结构化事件</b>
 * （包名、标题、正文、时间、是否常驻），不依赖当前前台是哪个界面，
 * 也不会因为通知被折叠而丢失。这是 Android 明确提供的系统级观察点。
 *
 * <p>权限边界：必须由用户在「设置 → 通知使用权」里手动勾选本应用，
 * 代码无法自行申请。未授权时 {@link #connected()} 为 false，
 * 执行层据此返回 {@code NEED_NOTIFICATION_ACCESS}，而不是返回"没有通知"这种误导结论。
 *
 * <p>只在内存里保留最近 {@value #MAX_ENTRIES} 条，进程结束即清空：
 * 通知内容是用户隐私，不做持久化，也不落审计日志正文。
 */
public final class AgentNotificationListener extends NotificationListenerService {

    private static final int MAX_ENTRIES = 100;

    /** 按 key 去重、按到达顺序淘汰；用 LinkedHashMap 的 eldest 淘汰，避免再写一遍 LRU。 */
    private static final Map<String, Note> STORE = new LinkedHashMap<String, Note>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Note> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private static volatile boolean connected;

    /** 系统是否已把本服务绑定为通知监听器（用户已授权且服务在跑）。 */
    public static boolean connected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Note e = Note.of(sbn);
        synchronized (STORE) {
            STORE.put(e.key, e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        synchronized (STORE) {
            STORE.remove(sbn.getKey());
        }
    }

    /**
     * 当前缓存的通知快照，按时间倒序。
     *
     * @param limit     最多返回条数（1..50）
     * @param pkgFilter 包名过滤（子串匹配），null/空表示不过滤
     */
    public static JSONObject snapshot(int limit, String pkgFilter) {
        List<Note> list;
        synchronized (STORE) {
            list = new ArrayList<>(STORE.values());
        }
        list.sort((a, b) -> Long.compare(b.postTime, a.postTime));

        int max = Math.max(1, Math.min(50, limit));
        String f = pkgFilter == null ? "" : pkgFilter.trim().toLowerCase();
        JSONArray arr = new JSONArray();
        for (Note e : list) {
            if (arr.length() >= max) break;
            if (!f.isEmpty() && !e.pkg.toLowerCase().contains(f)) continue;
            arr.put(e.toJson());
        }

        JSONObject out = new JSONObject();
        try {
            out.put("count", arr.length());
            out.put("total_cached", list.size());
            out.put("notifications", arr);
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** 一条通知的只读摘要。 */
    private static final class Note {
        final String key;
        final String pkg;
        final String title;
        final String text;
        final String category;
        final long postTime;
        final boolean ongoing;

        Note(String key, String pkg, String title, String text,
              String category, long postTime, boolean ongoing) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.category = category;
            this.postTime = postTime;
            this.ongoing = ongoing;
        }

        static Note of(StatusBarNotification sbn) {
            Notification n = sbn.getNotification();
            String title = extra(n, Notification.EXTRA_TITLE);
            String text = extra(n, Notification.EXTRA_TEXT);
            if (text.isEmpty()) text = extra(n, Notification.EXTRA_BIG_TEXT);
            if (text.isEmpty()) text = extra(n, Notification.EXTRA_SUB_TEXT);
            String category = n != null && n.category != null ? n.category : "";
            boolean ongoing = n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            return new Note(sbn.getKey(), String.valueOf(sbn.getPackageName()),
                    trim(title, 80), trim(text, 200), category, sbn.getPostTime(), ongoing);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("package", pkg);
                o.put("title", title);
                o.put("text", text);
                if (!category.isEmpty()) o.put("category", category);
                o.put("post_time", postTime);
                o.put("ongoing", ongoing);
                o.put("key", key);
            } catch (JSONException ignored) {
            }
            return o;
        }

        private static String extra(Notification n, String key) {
            if (n == null) return "";
            Bundle b = n.extras;
            if (b == null) return "";
            CharSequence cs = b.getCharSequence(key);
            return cs == null ? "" : cs.toString();
        }

        private static String trim(String s, int max) {
            if (s == null) return "";
            String t = s.trim();
            return t.length() > max ? t.substring(0, max) : t;
        }
    }
}
