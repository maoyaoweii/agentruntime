package com.agentruntime.ime;

import android.content.ComponentName;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import com.agentruntime.agent.R;

/**
 * Agent 输入法：把文本注入能力下沉到 InputConnection 层。
 *
 * <p>为什么需要它：{@code ACTION_SET_TEXT} 只能对标准 EditText 生效。
 * WebView 内部输入框、游戏输入、Flutter/Compose 自绘控件基本都不认这条无障碍动作，
 * 但<b>它们都认输入法</b>——因为那是系统级键盘通道。
 *
 * <p>代价是要用户手动启用并切换到这个输入法。所以它是「输入注入的兜底通道」，
 * 不是默认通道：默认仍走无障碍 {@code input_text}，失败或场景不支持时才建议用户切过来。
 *
 * <p>输入视图刻意做成一条 40dp 高的提示条，不是完整键盘——
 * 它不需要被用来打字，只需要让用户知道"当前是 Agent 输入法"，并且尽量少遮屏幕。
 *
 * <p><b>未在设备验证</b>：本环境无真机与模拟器。
 */
public final class AgentImeService extends InputMethodService {

    private static volatile AgentImeService instance;
    private static volatile InputConnection connection;

    public static boolean isRunning() {
        return instance != null;
    }

    /** 当前系统的默认输入法是不是本服务。 */
    public static boolean isSelected(Context context) {
        try {
            String current = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (current == null) return false;
            ComponentName selectedIme = ComponentName.unflattenFromString(current);
            return selectedIme != null && context.getPackageName().equals(selectedIme.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开输入法选择器，方便用户切过来。 */
    public static void showPicker(Context context) {
        try {
            InputMethodManager imm = (InputMethodManager)
                    context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 通过 InputConnection 提交文本。
     *
     * @return 失败原因；成功返回 null
     */
    public static String commitText(String text) {
        InputConnection ic = connection;
        if (ic == null) return "NO_INPUT_CONNECTION";
        try {
            ic.commitText(text == null ? "" : text, 1);
            return null;
        } catch (Throwable t) {
            return "COMMIT_FAILED: " + t.getClass().getSimpleName();
        }
    }

    /** 读取输入框当前内容（光标周围）。 */
    public static String readText(int maxChars) {
        InputConnection ic = connection;
        if (ic == null) return null;
        try {
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                    new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et == null || et.text == null) return "";
            String s = et.text.toString();
            return s.length() > maxChars ? s.substring(0, maxChars) : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 删除光标前的若干字符。 */
    public static String deleteBackward(int count) {
        InputConnection ic = connection;
        if (ic == null) return "NO_INPUT_CONNECTION";
        try {
            int n = Math.max(1, Math.min(500, count));
            ic.deleteSurroundingText(n, 0);
            return null;
        } catch (Throwable t) {
            return "DELETE_FAILED: " + t.getClass().getSimpleName();
        }
    }

    /** 发送一个编辑动作（回车/退格/删除）。 */
    public static String sendEditAction(String key) {
        InputConnection ic = connection;
        if (ic == null) return "NO_INPUT_CONNECTION";
        try {
            int action;
            int keyCode = 0;
            switch (key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "enter":
                    action = EditorInfo.IME_ACTION_DONE;
                    keyCode = android.view.KeyEvent.KEYCODE_ENTER;
                    break;
                case "delete":
                    action = EditorInfo.IME_ACTION_NONE;
                    keyCode = android.view.KeyEvent.KEYCODE_DEL;
                    break;
                case "search":
                    action = EditorInfo.IME_ACTION_SEARCH;
                    break;
                default:
                    return "BAD_IME_KEY";
            }
            if (keyCode != 0) {
                ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode));
                ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode));
            }
            return null;
        } catch (Throwable t) {
            return "IME_KEY_FAILED: " + t.getClass().getSimpleName();
        }
    }

    public static boolean hasConnection() {
        return connection != null;
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        connection = null;
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        // 当前 InputConnection 由框架持有，必须通过 getCurrentInputConnection() 取
        connection = getCurrentInputConnection();
    }

    @Override
    public void onFinishInput() {
        connection = null;
        super.onFinishInput();
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        // 有物理键盘时也保持可注入
        return true;
    }

    /**
     * 极简输入视图：一条提示带，不是键盘。
     *
     * <p>高度压到 40dp 是为了尽量不遮住 Agent 要操作的目标控件。
     */
    @Override
    public View onCreateInputView() {
        TextView bar = new TextView(this);
        bar.setText("AgentRuntime 输入法 · 供 Agent 注入文本");
        bar.setGravity(Gravity.CENTER);
        bar.setTextSize(11f);
        bar.setTextColor(getColor(R.color.text_on_brand));
        bar.setBackgroundColor(getColor(R.color.brand_dark));
        bar.setPadding(12, 8, 12, 8);
        bar.setHeight(Math.round(40 * getResources().getDisplayMetrics().density));
        return bar;
    }

    @Override
    public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);
        // 保持默认 insets 计算：注入文本时不应把目标界面推走，否则后续坐标会失效
    }

    /** 判断当前输入框是否为空（用于决定注入前要不要先清空）。 */
    public static boolean currentTextIsEmpty() {
        String t = readText(4096);
        return t == null || TextUtils.isEmpty(t.trim());
    }
}
