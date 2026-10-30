package com.agentruntime.dsh.ui;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Original, density-independent line icons for the generated mobile design. */
public final class DshIcons extends Drawable {
    public enum Name { CHAT, SESSIONS, GRID, SETTINGS, PHONE, FOLDER, NOTE, PLUS, MORE, BACK, RIGHT, UP_RIGHT, DOWN, EYE, SHIELD }
    private final Name name;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean filled;
    private final int size;
    public DshIcons(Context context,Name name,int color,int dp,boolean filled) {
        this.name=name; this.filled=filled; size=UiKit.dp(context,dp); paint.setColor(color);
        paint.setStrokeWidth(1.75f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        setBounds(0,0,size,size);
    }
    public static ImageView view(Context context,Name name,int color,int size) {
        ImageView image=new ImageView(context); image.setImageDrawable(new DshIcons(context,name,color,size,false));
        image.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO); return image;
    }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void draw(Canvas canvas) {
        int save=canvas.save(); Rect bounds=getBounds(); canvas.translate(bounds.left,bounds.top); canvas.scale(bounds.width()/24f,bounds.height()/24f);
        paint.setStyle(Paint.Style.STROKE);
        switch(name) {
            case CHAT:
                Path chat=new Path(); chat.moveTo(5,19); chat.lineTo(3,22); chat.lineTo(3.5f,16);
                chat.cubicTo(0,10,4,3,12,3); chat.cubicTo(18,3,22,7,22,12); chat.cubicTo(22,18,15,23,5,19);
                if(filled) paint.setStyle(Paint.Style.FILL); canvas.drawPath(chat,paint);
                int chatColor=paint.getColor(); if(filled) paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.FILL); for(int x:new int[]{8,12,16}) canvas.drawCircle(x,12,.85f,paint); paint.setColor(chatColor); break;
            case SESSIONS: case NOTE:
                if(filled) paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(5,2,20,22,2,2,paint);
                int paperColor=paint.getColor(); if(filled) paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE);
                line(canvas,9,8,16,8); line(canvas,9,12,16,12); line(canvas,9,16,13,16); paint.setColor(paperColor); break;
            case GRID:
                if(filled) paint.setStyle(Paint.Style.FILL);
                for(int x:new int[]{3,14}) for(int y:new int[]{3,14}) canvas.drawRoundRect(x,y,x+7,y+7,1.6f,1.6f,paint); break;
            case SETTINGS:
                Path gear=new Path();
                for(int i=0;i<32;i++) { double angle=(i*2*Math.PI/32)-Math.PI/2; float radius=(i%4==0||i%4==3)?9.8f:7.8f;
                    float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius; if(i==0) gear.moveTo(x,y); else gear.lineTo(x,y); }
                gear.close(); if(filled) paint.setStyle(Paint.Style.FILL); canvas.drawPath(gear,paint);
                int gearColor=paint.getColor(); if(filled) paint.setColor(Color.WHITE); canvas.drawCircle(12,12,3,paint); paint.setColor(gearColor); break;
            case PHONE:
                canvas.drawRoundRect(6,1.5f,18,22.5f,2,2,paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12,19.3f,.8f,paint); break;
            case FOLDER:
                path(canvas,2,7,2,4,9,4,11,7,22,7,22,21,2,21,2,7,22,7); break;
            case PLUS: line(canvas,12,5,12,19); line(canvas,5,12,19,12); break;
            case MORE: paint.setStyle(Paint.Style.FILL); for(int y:new int[]{5,12,19}) canvas.drawCircle(12,y,1.3f,paint); break;
            case BACK: path(canvas,15,5,8,12,15,19); break;
            case RIGHT: path(canvas,9,5,16,12,9,19); break;
            case UP_RIGHT: path(canvas,6,18,18,6,8,6); line(canvas,18,6,18,16); break;
            case DOWN: path(canvas,6,9,12,15,18,9); break;
            case EYE:
                Path eye=new Path(); eye.moveTo(2,12); eye.cubicTo(7,4,17,4,22,12); eye.cubicTo(17,20,7,20,2,12); canvas.drawPath(eye,paint); canvas.drawCircle(12,12,3,paint); break;
            case SHIELD: path(canvas,12,2,21,6,20,15,17,19,12,22,7,19,4,15,3,6,12,2); path(canvas,8,12,11,15,16,9); break;
        }
        canvas.restoreToCount(save);
    }
    private void line(Canvas canvas,float x1,float y1,float x2,float y2) { canvas.drawLine(x1,y1,x2,y2,paint); }
    private void path(Canvas canvas,float... points) { Path p=new Path(); p.moveTo(points[0],points[1]); for(int i=2;i<points.length;i+=2) p.lineTo(points[i],points[i+1]); canvas.drawPath(p,paint); }
}
