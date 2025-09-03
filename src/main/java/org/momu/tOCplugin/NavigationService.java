package org.momu.tOCplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.momu.tOCplugin.finder.PathFinding;
import org.momu.tOCplugin.listener.MasterListener;
import org.momu.tOCplugin.manager.PlayerTracker;

public final class NavigationService {
	private static final NavigationService INSTANCE = new NavigationService();
	private NavigationService() {}
	public static NavigationService getInstance() { return INSTANCE; }

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