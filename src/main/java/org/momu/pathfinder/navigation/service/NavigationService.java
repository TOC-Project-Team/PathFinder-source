package org.momu.pathfinder.navigation.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import org.momu.pathfinder.navigation.runtime.PathFinding;
import org.momu.pathfinder.presentation.listener.MasterListener;
import org.momu.pathfinder.navigation.state.PlayerTracker;

public final class NavigationService {
    private NavigationService() {}
    private static class Holder {
        private static final NavigationService INSTANCE = new NavigationService();
    }
    public static NavigationService getInstance() { return Holder.INSTANCE; }

    public void startForPlayer(Player player, Location target, String displayName) {
        if (player == null || target == null) return;
        PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
        PlayerTracker.getInstance().setWaypointNavigation(player.getUniqueId(), target, displayName);
        if (!MasterListener.getGuiManager().isParticleFeatureEnabled(player.getUniqueId())) {
            MasterListener.getGuiManager().toggleParticleFeature(player.getUniqueId());
        }
        Bukkit.getScheduler().runTask(PathFinderPlugin.getInstance(), () -> {
            PathFinding.startPathfinding(player);
        });
    }
}
