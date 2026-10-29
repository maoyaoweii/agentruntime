package com.agentruntime.quick;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.agentruntime.agent.R;
import com.agentruntime.overlay.OverlayService;
import com.agentruntime.runtime.TaskBus;
import com.agentruntime.ui.ConsoleActivity;

/**
 * 快捷设置磁贴：从下拉通知栏一键进入任务控制台。
 *
 * <p>这是四种唤起通道里最快的一条——不用找图标、不用开通知栏抽屉。
 * 磁贴的副标题会显示当前执行进度，扫一眼就知道 Agent 在不在忙。
 */
public final class AgentTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        render();
    }

    @Override
    public void onClick() {
        super.onClick();
        // 悬浮球开着就顺便提示一下，让用户知道还有更快的方式
        if (!OverlayService.isRunning()) {
            TaskBus.get().info("tile", "磁贴唤起控制台");
        }
        Intent intent = new Intent(this, ConsoleActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 10, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            openLegacy(intent);
        }
    }

    // This overload is only used below Android 14; the PendingIntent path is used on 34+.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openLegacy(Intent intent) {
        startActivityAndCollapse(intent);
    }

    private void render() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean running = TaskBus.get().running();
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Agent 执行");
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(running ? TaskBus.get().progressText() : "点击打开控制台");
        }
        try {
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_bot));
        } catch (Throwable ignored) {
        }
        tile.updateTile();
    }
}
