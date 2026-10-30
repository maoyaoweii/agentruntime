package com.agentruntime.dsh.runtime;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;
import com.agentruntime.dsh.ui.DshActivity;

/** Keeps a user-started model/tool turn alive while another phone app is in front. No restart/replay. */
public final class DshTurnService extends Service {
    public static final String JOB="dsh_job",STOP="dsh_stop";
    private static final int NOTIFICATION=7801;
    private String owned;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        DshController controller=AgentApp.get().dsh().controller;
        String id=intent==null?null:intent.getStringExtra(JOB);
        if(intent!=null&&STOP.equals(intent.getAction())) {
            if(id!=null) controller.cancel(id,"USER_CANCELLED");
            if(controller.active()==null) stopSelf();
            return START_NOT_STICKY;
        }
        DshController.View active=controller.active();
        if(active==null||id==null||!id.equals(active.id)) { if(owned==null) stopSelf(); return START_NOT_STICKY; }
        if(owned!=null) return START_NOT_STICKY;
        owned=id;
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("dsh_turns","DSH 回复与手机任务",NotificationManager.IMPORTANCE_LOW));
            Intent open=conversationIntent(this,active.session);
            Intent stop=new Intent(this,DshTurnService.class).setAction(STOP).setData(android.net.Uri.parse("dsh://turn/"+id)).putExtra(JOB,id);
            Notification notification=new Notification.Builder(this,"dsh_turns").setSmallIcon(R.drawable.ic_bot)
                    .setContentTitle("DSH Mobile 正在工作").setContentText("正在生成回复或运行你发起的手机任务")
                    .setContentIntent(PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
                    .addAction(new Notification.Action.Builder(null,"停止",PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build())
                    .setOngoing(true).setOnlyAlertOnce(true).build();
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION,notification);
            if(!controller.launch(id,()->{ owned=null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); })) {
                owned=null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }
        } catch(RuntimeException e) {
            controller.abandon(id); owned=null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
        return START_NOT_STICKY;
    }
    static Intent conversationIntent(Context context,String session) {
        return new Intent(context,DshActivity.class).putExtra("session",session)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    @Override public void onDestroy() {
        if(owned!=null) AgentApp.get().dsh().controller.cancel(owned,"SERVICE_STOPPED");
        super.onDestroy();
    }
}
