package com.agentruntime.dsh.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Durable session facts are append-only; live streaming text is settled once per response. */
public final class SessionStore extends SQLiteOpenHelper {
    public static final String DATABASE = "dsh_sessions.db";
    public static final int MAX_INPUT = 16000;

    public static final class Session {
        public final String id, title;
        public final long updated;
        Session(String id, String title, long updated) { this.id=id; this.title=title; this.updated=updated; }
    }

    public static final class Event {
        public final long sequence;
        public final String turnId, type;
        public final JSONObject payload;
        Event(long sequence, String turnId, String type, JSONObject payload) {
            this.sequence=sequence; this.turnId=turnId; this.type=type; this.payload=payload;
        }
    }

    public SessionStore(Context context) { super(context.getApplicationContext(), DATABASE, null, 1); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE turns(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                + "state TEXT NOT NULL,mode TEXT NOT NULL,started INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX single_active_turn ON turns(state) WHERE state='running'");
        db.execSQL("CREATE INDEX session_turns ON turns(session_id,started)");
        db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                + "turn_id TEXT NOT NULL REFERENCES turns(id) ON DELETE CASCADE,type TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX session_events ON events(session_id,seq)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported session database upgrade");
    }

    public synchronized Session createSession() {
        String id=UUID.randomUUID().toString(); long now=System.currentTimeMillis();
        ContentValues values=new ContentValues(); values.put("id",id); values.put("title","新对话"); values.put("updated",now);
        getWritableDatabase().insertOrThrow("sessions",null,values);
        return new Session(id,"新对话",now);
    }

    public synchronized List<Session> sessions() {
        List<Session> result=new ArrayList<>();
        try (Cursor cursor=getReadableDatabase().rawQuery("SELECT id,title,updated FROM sessions ORDER BY updated DESC,rowid DESC LIMIT 100",null)) {
            while(cursor.moveToNext()) result.add(new Session(cursor.getString(0),cursor.getString(1),cursor.getLong(2)));
        }
        return result;
    }

    public synchronized Session session(String id) {
        if(id==null) return null;
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT id,title,updated FROM sessions WHERE id=?",new String[]{id})) {
            return cursor.moveToFirst()?new Session(cursor.getString(0),cursor.getString(1),cursor.getLong(2)):null;
        }
    }

    public synchronized void rename(String id,String title) {
        if(session(id)==null) throw new IllegalArgumentException("Session not found");
        String normalized=title==null?"":title.trim();
        if(normalized.isEmpty()||normalized.length()>80) throw new IllegalArgumentException("Title length must be 1..80");
        ContentValues values=new ContentValues(); values.put("title",normalized);
        getWritableDatabase().update("sessions",values,"id=?",new String[]{id});
    }

    public synchronized void delete(String id) {
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT 1 FROM turns WHERE session_id=? AND state='running'",new String[]{id})) {
            if(cursor.moveToFirst()) throw new IllegalStateException("Stop the active turn before deleting this session");
        }
        getWritableDatabase().delete("sessions","id=?",new String[]{id});
    }

    public synchronized String beginTurn(String sessionId,String text,String mode,String model) throws JSONException {
        if(session(sessionId)==null) throw new IllegalArgumentException("Session not found");
        if(!"chat".equals(mode)&&!"tools".equals(mode)) throw new IllegalArgumentException("Invalid mode");
        if(text==null||text.trim().isEmpty()||text.length()>MAX_INPUT) throw new IllegalArgumentException("Message length must be 1..16000");
        String turn=UUID.randomUUID().toString(); SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values=new ContentValues(); values.put("id",turn); values.put("session_id",sessionId);
            values.put("state","running"); values.put("mode",mode); values.put("started",System.currentTimeMillis());
            db.insertOrThrow("turns",null,values);
            appendInternal(db,sessionId,turn,"turn_started",new JSONObject().put("mode",mode).put("model",model));
            appendInternal(db,sessionId,turn,"user_message",new JSONObject().put("role","user").put("content",text));
            ContentValues updated=new ContentValues(); updated.put("updated",System.currentTimeMillis());
            if("新对话".equals(session(sessionId).title)) {
                String title=text.replaceAll("\\s+"," ").trim(); updated.put("title",title.substring(0,Math.min(36,title.length())));
            }
            db.update("sessions",updated,"id=?",new String[]{sessionId});
            db.setTransactionSuccessful(); return turn;
        } finally { db.endTransaction(); }
    }

    public synchronized void append(String session,String turn,String type,JSONObject payload) {
        if(!"running".equals(boundState(session,turn))) throw new IllegalStateException("Turn is already settled");
        appendInternal(getWritableDatabase(),session,turn,type,payload);
    }

    private void appendInternal(SQLiteDatabase db,String session,String turn,String type,JSONObject payload) {
        ContentValues values=new ContentValues(); values.put("session_id",session); values.put("turn_id",turn);
        values.put("type",type); values.put("payload",payload.toString()); values.put("created",System.currentTimeMillis());
        db.insertOrThrow("events",null,values);
    }

    /** 记录"这一轮被自动续跑"。写在回合结束之后，因此不走 {@link #append} 的 running 校验。 */
    public synchronized void markContinued(String session,String turn,String next) {
        try {
            appendInternal(getWritableDatabase(),session,turn,"turn_continued",new JSONObject().put("next",next));
        } catch(Exception ignored) { }
    }

    public synchronized void finish(String session,String turn,String state,String code) throws JSONException {
        if(!"running".equals(boundState(session,turn))) return;
        if(!java.util.Arrays.asList("completed","failed","cancelled","interrupted").contains(state)) throw new IllegalArgumentException("Invalid turn state");
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if(!"completed".equals(state)) closeInterruptedProtocol(db,session,turn,state,code);
            appendInternal(db,session,turn,"turn_finished",new JSONObject().put("state",state).put("code",code));
            ContentValues values=new ContentValues(); values.put("state",state);
            db.update("turns",values,"id=? AND session_id=?",new String[]{turn,session});
            ContentValues updated=new ContentValues(); updated.put("updated",System.currentTimeMillis());
            db.update("sessions",updated,"id=?",new String[]{session});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Closing protocol gaps records uncertainty; it never repeats an admitted action. */
    private void closeInterruptedProtocol(SQLiteDatabase db,String session,String turn,String state,String code) throws JSONException {
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        try(Cursor cursor=db.rawQuery("SELECT type,payload FROM events WHERE session_id=? AND turn_id=? ORDER BY seq",new String[]{session,turn})) {
            while(cursor.moveToNext()) {
                JSONObject message=new JSONObject(cursor.getString(1));
                if("assistant_message".equals(cursor.getString(0))) {
                    JSONArray calls=message.optJSONArray("tool_calls");
                    if(calls!=null) for(int i=0;i<calls.length();i++) pending.add(calls.getJSONObject(i).getString("id"));
                } else if("tool_result".equals(cursor.getString(0))) pending.remove(message.optString("tool_call_id"));
            }
        }
        for(String id:pending) appendInternal(db,session,turn,"tool_result",new JSONObject().put("role","tool").put("tool_call_id",id)
                .put("content",new JSONObject().put("ok",false).put("code","RESULT_UNAVAILABLE")
                        .put("message","Execution was interrupted. The action may have taken effect; verify the current state before any retry.").toString()));
        boolean budget=("failed".equals(state)&&("MODEL_STEP_LIMIT".equals(code)||"TOOL_LIMIT".equals(code)))
                ||("cancelled".equals(state)&&"TURN_TIMEOUT".equals(code));
        String next=budget?"Continue only if the app explicitly starts a continuation of this request. Do not repeat rejected or uncertain actions; verify their outcomes first."
                :"Do not resume or repeat this task without a new user request.";
        appendInternal(db,session,turn,"assistant_message",new JSONObject().put("role","assistant").put("reasoning_content","")
                .put("content","[DSH Runtime] This turn ended: "+state+" ("+code+"). Completed tool results remain valid. "+next));
    }

    public synchronized void recoverInterrupted() throws JSONException {
        List<String[]> pending=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT session_id,id FROM turns WHERE state='running'",null)) {
            while(cursor.moveToNext()) pending.add(new String[]{cursor.getString(0),cursor.getString(1)});
        }
        for(String[] pair:pending) finish(pair[0],pair[1],"interrupted","PROCESS_RESTARTED");
    }

    /** 该回合结束时的原因码；未结束时返回空串。 */
    public synchronized String turnCode(String turn) {
        try(Cursor cursor=getReadableDatabase().rawQuery(
                "SELECT payload FROM events WHERE turn_id=? AND type='turn_finished' ORDER BY seq DESC LIMIT 1",
                new String[]{turn})) {
            if(!cursor.moveToFirst()) return "";
            return new JSONObject(cursor.getString(0)).optString("code","");
        } catch(Exception e) {
            return "";
        }
    }

    /** Refuse automatic continuation when an action was rejected or may already have taken effect. */
    public synchronized boolean hasUnsafeToolOutcome(String session,String turn) {
        try(Cursor cursor=getReadableDatabase().rawQuery(
                "SELECT payload FROM events WHERE session_id=? AND turn_id=? AND type='tool_result' ORDER BY seq",
                new String[]{session,turn})) {
            while(cursor.moveToNext()) {
                JSONObject payload=new JSONObject(cursor.getString(0));
                JSONObject result=new JSONObject(payload.getString("content"));
                String code=result.optString("code","");
                if("RESULT_UNAVAILABLE".equals(code)||"USER_REJECTED".equals(code)||"CONFIRMATION_NOT_OBTAINED".equals(code)
                        ||result.optBoolean("outcome_uncertain",false)) return true;
            }
            return false;
        } catch(Exception e) { return true; }
    }

    public synchronized String turnState(String turn) {
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT state FROM turns WHERE id=?",new String[]{turn})) {
            return cursor.moveToFirst()?cursor.getString(0):"missing";
        }
    }

    private String boundState(String session,String turn) {
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT state FROM turns WHERE id=? AND session_id=?",new String[]{turn,session})) {
            if(!cursor.moveToFirst()) throw new IllegalArgumentException("Turn does not belong to this session");
            return cursor.getString(0);
        }
    }

    public synchronized List<Event> events(String session) throws JSONException {
        List<Event> result=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT seq,turn_id,type,payload FROM events WHERE session_id=? ORDER BY seq DESC LIMIT 300",new String[]{session})) {
            while(cursor.moveToNext()) result.add(new Event(cursor.getLong(0),cursor.getString(1),cursor.getString(2),new JSONObject(cursor.getString(3))));
        }
        Collections.reverse(result); return result;
    }

    /** Only whole, settled turns enter later requests; interrupted tool protocols are closed above. */
    public synchronized JSONArray history(String session,int maxCharacters) throws JSONException {
        LinkedHashMap<String,List<JSONObject>> byTurn=new LinkedHashMap<>();
        List<String> ids=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT id FROM turns WHERE session_id=? AND state!='running' ORDER BY started DESC,rowid DESC LIMIT 12",new String[]{session})) {
            while(cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        for(String turn:ids) {
            List<JSONObject> messages=new ArrayList<>();
            try(Cursor cursor=getReadableDatabase().rawQuery("SELECT payload FROM events WHERE session_id=? AND turn_id=? AND type IN ('user_message','assistant_message','tool_result') ORDER BY seq DESC",new String[]{session,turn})) {
                while(cursor.moveToNext()) messages.add(new JSONObject(cursor.getString(0)));
            }
            byTurn.put(turn,messages);
        }
        List<List<JSONObject>> selected=new ArrayList<>(); int size=0;
        for(List<JSONObject> turn:byTurn.values()) {
            int count=0; for(JSONObject message:turn) count+=message.toString().length();
            if(size+count>maxCharacters) {
                if(!selected.isEmpty()) break;
                // A long most-recent turn must not disappear completely during automatic continuation.
                // Retain its protocol and actions, compact only tool result bodies, and fail closed if
                // even the essential messages cannot fit.
                turn=compactLatestTurn(turn); count=0;
                for(JSONObject message:turn) count+=message.toString().length();
                if(count>maxCharacters) throw new JSONException("CONTEXT_LIMIT");
            }
            Collections.reverse(turn); selected.add(turn); size+=count;
            if(selected.size()==12) break;
        }
        Collections.reverse(selected); JSONArray result=new JSONArray();
        for(List<JSONObject> turn:selected) for(JSONObject message:turn) result.put(message);
        return result;
    }

    /** Input is newest first; original events remain untouched in the database. */
    private static List<JSONObject> compactLatestTurn(List<JSONObject> messages) throws JSONException {
        List<JSONObject> compacted=new ArrayList<>(); boolean keptRecentDetail=false;
        for(JSONObject message:messages) {
            JSONObject copy=new JSONObject(message.toString());
            if("tool".equals(copy.optString("role"))) {
                try {
                    JSONObject original=new JSONObject(copy.getString("content")),result=new JSONObject();
                    for(String field:new String[]{"ok","code","outcome_uncertain","replayed"})
                        if(original.has(field)) result.put(field,original.get(field));
                    for(String field:new String[]{"message","notice"})
                        if(original.has(field)) result.put(field,historyClip(original.optString(field),500));
                    String output=original.optString("output","");
                    Object data=original.opt("data");
                    String detail=!output.isEmpty()?output:data==null||data==JSONObject.NULL?"":data.toString();
                    int limit=!keptRecentDetail&&detail.length()>1000?4000:1000;
                    if(limit==4000) keptRecentDetail=true;
                    if(!output.isEmpty()) result.put("output",historyClip(output,limit));
                    else if(original.has("data")) {
                        data=original.get("data"); String text=data instanceof String?(String)data:data.toString();
                        if(text.length()<=limit) result.put("data",data); else result.put("data_excerpt",historyClip(text,limit));
                    }
                    result.put("history_compacted",true);
                    result.put("history_note","较早工具结果已压缩；完整结果仍保存在本机会话中。");
                    copy.put("content",result.toString());
                } catch(JSONException ignored) { /* A malformed result remains intact; the size check fails closed if needed. */ }
            }
            compacted.add(copy);
        }
        return compacted;
    }

    private static String historyClip(String value,int limit) {
        if(value.length()<=limit) return value;
        int head=limit*3/4,tail=value.length()-(limit-head);
        if(head>0&&Character.isHighSurrogate(value.charAt(head-1))) head--;
        if(tail<value.length()&&Character.isLowSurrogate(value.charAt(tail))) tail++;
        return value.substring(0,head)+"\n…（内容已压缩）…\n"+value.substring(tail);
    }
}
