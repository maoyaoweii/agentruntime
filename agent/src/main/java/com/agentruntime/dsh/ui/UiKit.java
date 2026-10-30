package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Small native view vocabulary shared by the mobile screens. No web view or remote assets. */
public final class UiKit {
    public static final int BG=0xfff7f8fc,INK=0xff202538,MUTED=0xff6c7488,BLUE=0xff4d6bfe,LINE=0xffe7eaf2,WHITE=Color.WHITE;
    private UiKit() { }
    public static int dp(Context c,float value) { return (int)(value*c.getResources().getDisplayMetrics().density+.5f); }
    public static LinearLayout column(Context c) { LinearLayout v=new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    public static LinearLayout row(Context c) { LinearLayout v=new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    public static TextView text(Context c,String value,float size,int color) {
        TextView v=new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setFontFeatureSettings("kern"); v.setLineSpacing(dp(c,3),1); return v;
    }
    public static TextView bold(Context c,String value,float size,int color) { TextView v=text(c,value,size,color); v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return v; }
    public static GradientDrawable shape(Context c,int color,int stroke,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); if(stroke!=0) d.setStroke(dp(c,1),stroke); return d;
    }
    public static Button button(Context c,String title,boolean primary,Runnable action) {
        Button v=new Button(c); v.setText(title); v.setTextSize(14); v.setAllCaps(false); v.setMinWidth(0); v.setMinimumWidth(0);
        v.setTextColor(primary?WHITE:INK); v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x224d6bfe),shape(c,primary?BLUE:WHITE,primary?0:LINE,15),null));
        v.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8)); v.setMinHeight(dp(c,48)); v.setStateListAnimator(null);
        v.setOnClickListener(view->action.run()); return v;
    }
    public static void pad(View v,int left,int top,int right,int bottom) { v.setPadding(dp(v.getContext(),left),dp(v.getContext(),top),dp(v.getContext(),right),dp(v.getContext(),bottom)); }
    public static LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1,-2); }
    public static void gap(LinearLayout parent,int height) { View v=new View(parent.getContext()); parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),height))); }
    public static LinearLayout card(Context c) { LinearLayout v=column(c); pad(v,18,18,18,18); v.setBackground(shape(c,WHITE,LINE,22)); return v; }
    public static LinearLayout page(Activity activity,String title,String caption) {
        LinearLayout root=column(activity); root.setBackgroundColor(BG);
        LinearLayout bar=row(activity); pad(bar,16,8,16,8);
        Button back=button(activity,"‹ 返回",false,activity::finish); bar.addView(back,new LinearLayout.LayoutParams(-2,dp(activity,48)));
        TextView name=bold(activity,title,21,INK); LinearLayout.LayoutParams label=new LinearLayout.LayoutParams(0,-2,1); label.setMarginStart(dp(activity,16)); bar.addView(name,label); root.addView(bar);
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content=column(activity); pad(content,20,12,20,28); scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(caption!=null) { content.addView(text(activity,caption,14,MUTED)); gap(content,22); }
        activity.setContentView(root); return content;
    }
    public static EditText input(Context c,String hint,boolean multiline) {
        EditText v=new EditText(c); v.setTextSize(15); v.setTextColor(INK); v.setHintTextColor(MUTED); v.setHint(hint); v.setSingleLine(!multiline);
        v.setBackground(shape(c,WHITE,LINE,14)); pad(v,14,12,14,12); v.setMinHeight(dp(c,50)); return v;
    }
    public static void field(LinearLayout root,String title,View input) {
        root.addView(bold(root.getContext(),title,13,MUTED)); gap(root,8); root.addView(input,matchWrap()); gap(root,20);
    }
    public static void open(Activity a,Class<? extends Activity> target) { a.startActivity(new Intent(a,target)); }
    public static void toast(Context c,String message) { Toast.makeText(c,message,Toast.LENGTH_LONG).show(); }
    public static String visible(String value) {
        return value.replaceAll("[\\u202a-\\u202e\\u2066-\\u2069]","�");
    }
}
