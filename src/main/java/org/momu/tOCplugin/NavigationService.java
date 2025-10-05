package org.momu.tOCplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.momu.tOCplugin.finder.PathFinding;
import org.momu.tOCplugin.listener.MasterListener;
import org.momu.tOCplugin.manager.PlayerTracker;

public final class NavigationService {
    private NavigationService() {}
    private static class Holder {
        private static final NavigationService INSTANCE = new NavigationService();
    }
    public static NavigationService getInstance() { return Holder.INSTANCE; }

    /**
     * 为玩家启动导航服务。
     * 1. 停止玩家当前导航。
     * 2. 设置新的导航目标。
     * 3. 启用粒子特效（如未启用）。
     * 4. 异步启动寻路。
     * @param player 玩家对象
     * @param target 目标位置
     * @param displayName 显示名称
     */
    public void startForPlayer(Player player, Location target, String displayName) {
        if (player == null || target == null) return;
        PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
        PlayerTracker.getInstance().setWaypointNavigation(player.getUniqueId(), target, displayName);
        if (!MasterListener.getGuiManager().isParticleFeatureEnabled(player.getUniqueId())) {
            MasterListener.getGuiManager().toggleParticleFeature(player.getUniqueId());
        }
        // 调用寻路
        Bukkit.getScheduler().runTask(TOCpluginNative.getInstance(), () -> {
            PathFinding.startPathfindingPublic(player);
        });
    }
}
