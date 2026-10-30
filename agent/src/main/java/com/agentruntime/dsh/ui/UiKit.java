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
    public static final int BG=0xfff7f8fc,INK=0xff171e33,MUTED=0xff667085,BLUE=0xff4d6bfe,LINK=0xff3555d8,PRIMARY=0xff405ee8,TINT=0xffeef2ff,LINE=0xffe7eaf2,WHITE=Color.WHITE;
    private UiKit() { }
    public static int dp(Context c,float value) { return (int)(value*c.getResources().getDisplayMetrics().density+.5f); }
    public static LinearLayout column(Context c) { LinearLayout v=new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    public static LinearLayout row(Context c) { LinearLayout v=new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    public static TextView text(Context c,String value,float size,int color) {
        TextView v=new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setFontFeatureSettings("kern"); v.setLineSpacing(dp(c,3),1); return v;
    }
    public static TextView rich(Context c,CharSequence value,float size,int color) {
        TextView v=new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setFontFeatureSettings("kern"); v.setLineSpacing(dp(c,3),1); return v;
    }
    public static TextView bold(Context c,String value,float size,int color) { TextView v=text(c,value,size,color); v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return v; }
    public static TextView title(Context c,String value,float size) { TextView v=text(c,value,size,INK); v.setTypeface(Typeface.create("sans-serif",Typeface.BOLD)); v.setLetterSpacing(-.025f); return v; }
    public static GradientDrawable shape(Context c,int color,int stroke,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); if(stroke!=0) d.setStroke(dp(c,1),stroke); return d;
    }
    public static Button button(Context c,String title,boolean primary,Runnable action) {
        Button v=new Button(c); v.setText(title); v.setTextSize(14); v.setAllCaps(false); v.setMinWidth(0); v.setMinimumWidth(0);
        v.setTextColor(primary?WHITE:INK); v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        v.setBackground(ripple(c,primary?PRIMARY:WHITE,primary?0:LINE,14));
        v.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8)); v.setMinHeight(dp(c,48)); v.setStateListAnimator(null);
        v.setOnClickListener(view->action.run()); return v;
    }
    public static RippleDrawable ripple(Context c,int color,int stroke,int radius) { return new RippleDrawable(ColorStateList.valueOf(0x224d6bfe),shape(c,color,stroke,radius),null); }
    public static ImageButton iconButton(Context c,DshIcons.Name icon,String label,Runnable action) {
        ImageButton v=new ImageButton(c); v.setImageDrawable(new DshIcons(c,icon,MUTED,22,false)); v.setContentDescription(label);
        v.setBackground(ripple(c,Color.TRANSPARENT,0,24)); pad(v,12,12,12,12); v.setMinimumHeight(dp(c,48)); v.setMinimumWidth(dp(c,48)); v.setOnClickListener(view->action.run()); return v;
    }
    public static Button tonalButton(Context c,String label,DshIcons.Name icon,Runnable action) {
        Button v=button(c,label,false,action); v.setTextColor(LINK); v.setTextSize(13); v.setBackground(ripple(c,0xfff1f5ff,0,12));
        if(icon!=null) v.setCompoundDrawablesRelative(null,null,new DshIcons(c,icon,LINK,17,false),null); v.setCompoundDrawablePadding(dp(c,7)); return v;
    }
    public static void iconLabel(Button button,String label,DshIcons.Name icon,int color,int size) {
        android.text.SpannableString text=new android.text.SpannableString("\ufffc  "+label);
        text.setSpan(new android.text.style.ImageSpan(new DshIcons(button.getContext(),icon,color,size,false),android.text.style.ImageSpan.ALIGN_BOTTOM),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
    }
    public static Switch toggle(Context c,String label,boolean checked) {
        Switch v=new Switch(c); v.setContentDescription(label); v.setChecked(checked); v.setShowText(false); v.setSplitTrack(false); v.setMinHeight(dp(c,48)); v.setMinimumWidth(dp(c,48));
        v.setThumbTintList(ColorStateList.valueOf(WHITE)); v.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{BLUE,0xffcfd5e3})); return v;
    }
    public static FrameLayout iconBadge(Context c,DshIcons.Name name) {
        FrameLayout frame=new FrameLayout(c); frame.setBackground(shape(c,0xfff0f4ff,0,12));
        ImageView icon=DshIcons.view(c,name,LINK,23); FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(c,25),dp(c,25),Gravity.CENTER); frame.addView(icon,p); return frame;
    }
    public static void pad(View v,int left,int top,int right,int bottom) { v.setPadding(dp(v.getContext(),left),dp(v.getContext(),top),dp(v.getContext(),right),dp(v.getContext(),bottom)); }
    public static LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1,-2); }
    public static void gap(LinearLayout parent,int height) { View v=new View(parent.getContext()); parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),height))); }
    public static LinearLayout card(Context c) { LinearLayout v=column(c); pad(v,16,16,16,16); v.setBackground(shape(c,WHITE,LINE,18)); return v; }
    public static LinearLayout page(Activity activity,String title,String caption) {
        LinearLayout root=column(activity); root.setBackgroundColor(BG);
        LinearLayout bar=row(activity); pad(bar,14,2,14,0);
        Button back=button(activity,"返回",false,activity::finish); back.setTextColor(MUTED); back.setBackground(ripple(activity,Color.TRANSPARENT,0,14));
        back.setCompoundDrawablesRelative(new DshIcons(activity,DshIcons.Name.BACK,MUTED,19,false),null,null,null); back.setCompoundDrawablePadding(dp(activity,4));
        bar.addView(back,new LinearLayout.LayoutParams(-2,dp(activity,48))); root.addView(bar);
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content=column(activity); pad(content,20,6,20,24); scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content.addView(title(activity,title,30)); gap(content,4);
        if(caption!=null) { content.addView(text(activity,caption,14,MUTED)); }
        gap(content,22); DshNavigation.Section section=DshNavigation.section(activity);
        if(section!=null) {
            boolean wide=DshNavigation.isWide(activity); View nav=DshNavigation.mount(activity,root,section);
            Runnable updateCompact=()->{
                if(root.getHeight()==0) return;
                boolean compact=!wide&&root.getHeight()<dp(activity,300)&&root.findFocus() instanceof EditText;
                int visibility=compact?View.GONE:View.VISIBLE; if(nav.getVisibility()!=visibility) nav.setVisibility(visibility);
            };
            root.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->updateCompact.run());
            root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->updateCompact.run());
        }
        else activity.setContentView(root);
        return content;
    }
    public static EditText input(Context c,String hint,boolean multiline) {
        EditText v=new EditText(c); v.setTextSize(15); v.setTextColor(INK); v.setHintTextColor(MUTED); v.setHint(hint); v.setSingleLine(!multiline);
        v.setBackground(shape(c,WHITE,LINE,14)); pad(v,14,12,14,12); v.setMinHeight(dp(c,50)); return v;
    }
    public static void field(LinearLayout root,String title,View input) {
        TextView label=bold(root.getContext(),title,13,MUTED); View target=formControl(input); if(target.getId()==View.NO_ID) target.setId(View.generateViewId());
        label.setLabelFor(target.getId()); root.addView(label); gap(root,8); root.addView(input,matchWrap()); gap(root,16);
    }
    public static void formRow(LinearLayout root,String title,View input) {
        Context context=root.getContext();
        View target=formControl(input); if(target.getId()==View.NO_ID) target.setId(View.generateViewId());
        TextView label=text(context,title,13,MUTED); label.setLabelFor(target.getId());
        if(context.getResources().getConfiguration().screenWidthDp<360||context.getResources().getConfiguration().fontScale>1.15f) { field(root,title,input); return; }
        LinearLayout row=row(context); row.addView(label,new LinearLayout.LayoutParams(dp(context,76),-2)); row.addView(input,new LinearLayout.LayoutParams(0,-2,1)); root.addView(row,matchWrap()); gap(root,9);
    }
    private static View formControl(View view) {
        if(view instanceof EditText||view instanceof Spinner) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View child=((ViewGroup)view).getChildAt(i); View match=formControl(child);
            if(match instanceof EditText||match instanceof Spinner) return match;
        }
        return view;
    }
    public static void open(Activity a,Class<? extends Activity> target) { a.startActivity(new Intent(a,target)); }
    public static void toast(Context c,String message) { Toast.makeText(c,message,Toast.LENGTH_LONG).show(); }
    public static String visible(String value) {
        return value.replaceAll("[\\u202a-\\u202e\\u2066-\\u2069]","�");
    }
}
