package org.momu.pathfinder.navigation.state;

import org.bukkit.Location;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import org.momu.pathfinder.config.LanguageManager;
import org.momu.pathfinder.presentation.listener.MasterListener;

import java.io.File;
import java.io.IOException;

public class PlayerTracker {
    private final Set<UUID> hiddenPlayers = new HashSet<>();
    private final Set<UUID> navigatingPlayers = new HashSet<>();
    private final java.util.Map<UUID, UUID> navigationTargets = new java.util.HashMap<>();
    private final java.util.Map<UUID, Location> strongholdNavigations = new java.util.HashMap<>();
    private final java.util.Map<UUID, Location> beaconNavigations = new java.util.HashMap<>();
    private final java.util.Map<UUID, Location> waypointNavigations = new java.util.HashMap<>();
    private final java.util.Map<UUID, String> waypointNames = new java.util.HashMap<>();
    private boolean navigationEnabled = true;
    private final Set<UUID> actionBarSuppressedPlayers = new HashSet<>();

    private PlayerTracker() {
    }

    public static PlayerTracker getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        private static final PlayerTracker INSTANCE = new PlayerTracker();
    }

    public boolean toggleHideLocation(UUID playerUUID) {
        boolean hidden;
        if (hiddenPlayers.contains(playerUUID)) {
            hiddenPlayers.remove(playerUUID);
            hidden = false;
        } else {
            hiddenPlayers.add(playerUUID);
            hidden = true;
        }
        saveData(PathFinderPlugin.getInstance());
        return hidden;
    }

    public boolean isLocationHidden(UUID playerUUID) {
        return hiddenPlayers.contains(playerUUID);
    }

    public boolean isNavigationBlockedByInvisibility(org.bukkit.entity.Player targetPlayer) {
        if (targetPlayer == null || !targetPlayer.isOnline()) {
            return false;
        }
        try {
            boolean allowInvisible = PathFinderPlugin.getInstance().getConfig()
                    .getBoolean("allow_navigation_to_invisible", false);
            boolean isInvisible = targetPlayer.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)
                    || targetPlayer.isInvisible();
            return !allowInvisible && isInvisible;
        } catch (Exception ignore) {
            return false;
        }
    }

    public boolean setNavigationTarget(UUID playerUUID, UUID targetUUID) {
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }

        UUID currentTarget = navigationTargets.get(playerUUID);
        if (targetUUID != null && targetUUID.equals(currentTarget)) {
            return false;
        }

        try {
            org.bukkit.entity.Player targetPlayerCheck = org.bukkit.Bukkit.getPlayer(targetUUID);
            if (isNavigationBlockedByInvisibility(targetPlayerCheck)) {
                return false;
            }
        } catch (Exception ignore) {}

        if (isLocationHidden(targetUUID)) {
            boolean canBypass = PathFinderPlugin.getInstance().canBypassNavigationRestrictions(playerUUID);

            if (!canBypass) {
                return false;
            }
        }

        stopNavigation(playerUUID);

        navigationTargets.put(playerUUID, targetUUID);
        navigatingPlayers.add(playerUUID);

        org.bukkit.entity.Player targetPlayer = org.bukkit.Bukkit.getPlayer(targetUUID);
        org.bukkit.entity.Player navPlayer = org.bukkit.Bukkit.getPlayer(playerUUID);
        if (targetPlayer != null && navPlayer != null && targetPlayer.isOnline()) {
            boolean shouldShowNotification = true;

            if (isLocationHidden(targetUUID)) {
                boolean canBypass = PathFinderPlugin.getInstance().canBypassNavigationRestrictions(playerUUID);
                if (canBypass) {
                    shouldShowNotification = false;
                }
            }

            if (!navigationEnabled) {
                boolean canBypass = PathFinderPlugin.getInstance().canBypassNavigationRestrictions(playerUUID);
                if (canBypass) {
                    shouldShowNotification = false;
                }
            }

            if (shouldShowNotification) {
                org.bukkit.boss.BossBar bossBar = org.bukkit.Bukkit
                        .createBossBar(
                                LanguageManager.getInstance().getString(targetPlayer,
                                        "messages.navigating-player-to-you",
                                        navPlayer.getName()),
                                org.bukkit.boss.BarColor.BLUE, org.bukkit.boss.BarStyle.SOLID);
                bossBar.setProgress(1.0);
                bossBar.addPlayer(targetPlayer);
                bossBar.setVisible(true);
                targetPlayer
                        .sendMessage("§a" + LanguageManager.getInstance().getString(targetPlayer,
                                "messages.navigating-player-to-you",
                                navPlayer.getName()));
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override
                    public void run() {
                        bossBar.removeAll();
                    }
                }.runTaskLater(PathFinderPlugin.getInstance(), 100L);
            }
        }
        return true;
    }

    public boolean canPlayerUseNavigation(UUID playerUUID) {
        if (!navigationEnabled) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUUID);
            if (player == null)
                return false;

            return PathFinderPlugin.getInstance().canBypassNavigationRestrictions(playerUUID);
        }

        return true;
    }

    public void stopNavigation(UUID playerUUID) {
        UUID targetUUID = getNavigationTarget(playerUUID);
        org.bukkit.entity.Player navPlayer = org.bukkit.Bukkit.getPlayer(playerUUID);

        navigationTargets.remove(playerUUID);
        strongholdNavigations.remove(playerUUID);
        beaconNavigations.remove(playerUUID);
        waypointNavigations.remove(playerUUID);
        waypointNames.remove(playerUUID);
        navigatingPlayers.remove(playerUUID);
        actionBarSuppressedPlayers.remove(playerUUID);

        if (targetUUID != null && navPlayer != null) {
            org.bukkit.entity.Player targetPlayer = org.bukkit.Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null && targetPlayer.isOnline()) {
                boolean shouldShowNotification = true;

                if (isLocationHidden(targetUUID)) {
                    boolean canBypass = PathFinderPlugin.getInstance().canBypassNavigationRestrictions(playerUUID);
                    if (canBypass) {
                        shouldShowNotification = false;
                    }
                }

                if (!navigationEnabled) {
                    boolean canBypass = PathFinderPlugin.getInstance().canBypassNavigationRestrictions(playerUUID);
                    if (canBypass) {
                        shouldShowNotification = false;
                    }
                }

                if (shouldShowNotification) {
                    org.bukkit.boss.BossBar bossBar = org.bukkit.Bukkit
                            .createBossBar(
                                    LanguageManager.getInstance().getString(targetPlayer,
                                            "messages.navigating-player-to-you-cancelled", navPlayer.getName()),
                                    org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SOLID);
                    bossBar.setProgress(1.0);
                    bossBar.addPlayer(targetPlayer);
                    bossBar.setVisible(true);
                    targetPlayer
                            .sendMessage("§a" + LanguageManager.getInstance().getString(targetPlayer,
                                    "messages.navigating-player-to-you-cancelled",
                                    navPlayer.getName()));
                    new org.bukkit.scheduler.BukkitRunnable() {
                        @Override
                        public void run() {
                            bossBar.removeAll();
                        }
                    }.runTaskLater(PathFinderPlugin.getInstance(), 100L);
                }
            }
        }
        MasterListener.getGuiManager().removeParticleTask(playerUUID);
    }

    public void suppressActionBarForCurrentSession(UUID playerUUID) {
        if (playerUUID != null) actionBarSuppressedPlayers.add(playerUUID);
    }

    public boolean isActionBarSuppressed(UUID playerUUID) {
        return playerUUID != null && actionBarSuppressedPlayers.contains(playerUUID);
    }

    public boolean isNavigating(UUID playerUUID) {
        return navigatingPlayers.contains(playerUUID) || strongholdNavigations.containsKey(playerUUID) || waypointNavigations.containsKey(playerUUID);
    }

    public UUID getNavigationTarget(UUID playerUUID) {
        return navigationTargets.get(playerUUID);
    }

    public boolean setStrongholdNavigation(UUID playerUUID, Location strongholdLocation) {
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }

        navigationTargets.remove(playerUUID);
        beaconNavigations.remove(playerUUID);

        strongholdNavigations.put(playerUUID, strongholdLocation);
        navigatingPlayers.add(playerUUID);
        return true;
    }

    public Location getStrongholdNavigation(UUID playerUUID) {
        return strongholdNavigations.get(playerUUID);
    }

    public boolean setBeaconNavigation(UUID playerUUID, Location beaconLocation) {
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }

        stopNavigation(playerUUID);

        beaconNavigations.put(playerUUID, beaconLocation);
        navigatingPlayers.add(playerUUID);
        return true;
    }

    public Location getBeaconNavigation(UUID playerUUID) {
        return beaconNavigations.get(playerUUID);
    }

    public boolean setWaypointNavigation(UUID playerUUID, Location location, String name) {
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }
        navigationTargets.remove(playerUUID);
        strongholdNavigations.remove(playerUUID);
        beaconNavigations.remove(playerUUID);
        waypointNavigations.put(playerUUID, location);
        if (name != null) waypointNames.put(playerUUID, name);
        navigatingPlayers.add(playerUUID);
        return true;
    }

    public Location getWaypointNavigation(UUID playerUUID) {
        return waypointNavigations.get(playerUUID);
    }

    public String getWaypointName(UUID playerUUID) {
        return waypointNames.get(playerUUID);
    }

    public void clearAllNavigations() {
        navigationTargets.clear();
        strongholdNavigations.clear();
        beaconNavigations.clear();
        navigatingPlayers.clear();
    }

    public java.util.Set<UUID> getAllNavigatingPlayers() {
        java.util.Set<UUID> set = new java.util.HashSet<>(navigatingPlayers);
        set.addAll(navigationTargets.keySet());
        set.addAll(strongholdNavigations.keySet());
        set.addAll(beaconNavigations.keySet());
        set.addAll(waypointNavigations.keySet());
        return set;
    }

    public void stopAllNavigations() {
        Set<UUID> allNavigatingPlayers = new HashSet<>(navigatingPlayers);

        for (UUID playerUUID : allNavigatingPlayers) {
            stopNavigation(playerUUID);
        }

        clearAllNavigations();
    }

    public boolean toggleNavigationEnabled() {
        navigationEnabled = !navigationEnabled;
        if (!navigationEnabled) {
            stopNonPrivilegedNavigations();
        }
        saveData(PathFinderPlugin.getInstance());
        return navigationEnabled;
    }

    public boolean isNavigationEnabled() {
        return navigationEnabled;
    }

    private void stopNonPrivilegedNavigations() {
        Set<UUID> allNavigatingPlayers = new HashSet<>(navigatingPlayers);

        for (UUID playerUUID : allNavigatingPlayers) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUUID);
            if (player == null)
                continue;

            if (!PathFinderPlugin.getInstance().canBypassNavigationRestrictions(playerUUID)) {
                stopNavigation(playerUUID);
            }
        }
    }

    private static final String DATA_FILE_NAME = "playerdata.yml";

    public void loadData(JavaPlugin plugin) {
        if (plugin == null)
            return;
        File file = new File(plugin.getDataFolder(), DATA_FILE_NAME);
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String uuidStr : yaml.getStringList("hiddenPlayers")) {
            try {
                hiddenPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {
            }
        }

        navigationEnabled = yaml.getBoolean("navigationEnabled", true);
    }

    public void saveData(JavaPlugin plugin) {
        if (plugin == null)
            return;
        File file = new File(plugin.getDataFolder(), DATA_FILE_NAME);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("hiddenPlayers", hiddenPlayers.stream().map(UUID::toString).toList());

        yaml.set("navigationEnabled", navigationEnabled);

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning(
                    LanguageManager.getInstance().getString("messages.pathfinder-config-update-error", e.getMessage()));
        }
    }

}
