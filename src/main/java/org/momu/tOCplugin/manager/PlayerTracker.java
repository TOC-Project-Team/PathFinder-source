package org.momu.tOCplugin.manager;

import org.bukkit.Location;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.momu.tOCplugin.TOCpluginNative;
import org.momu.tOCplugin.config.LanguageManager;
import org.momu.tOCplugin.listener.MasterListener;

import java.io.File;
import java.io.IOException;

/**
 * 管理玩家位置隐私设置和导航功能
 */
public class PlayerTracker {
    private final Set<UUID> hiddenPlayers = new HashSet<>(); // 不暴露位置的玩家
    private final Set<UUID> navigatingPlayers = new HashSet<>(); // 正在导航的玩家
    private final java.util.Map<UUID, UUID> navigationTargets = new java.util.HashMap<>(); // 导航目标 <玩家UUID, 目标玩家UUID>
    private final java.util.Map<UUID, Location> strongholdNavigations = new java.util.HashMap<>(); // 末地要塞导航 <玩家UUID,
                                                                                                   // 要塞位置>
    private final java.util.Map<UUID, Location> beaconNavigations = new java.util.HashMap<>(); // 信标导航 <玩家UUID, 信标位置>
    private final java.util.Map<UUID, Location> waypointNavigations = new java.util.HashMap<>(); // 自定义路标导航 <玩家UUID, 位置>
    private final java.util.Map<UUID, String> waypointNames = new java.util.HashMap<>(); // 自定义路标名称 <玩家UUID, 名称>
    private boolean navigationEnabled = true; // 全局导航开关，默认开启
    // 本次导航期间抑制 ActionBar 显示的玩家集合（会在 stopNavigation 时自动清除）
    private final Set<UUID> actionBarSuppressedPlayers = new HashSet<>();

    private PlayerTracker() {
        // 私有构造函数
    }

    public static PlayerTracker getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        private static final PlayerTracker INSTANCE = new PlayerTracker();
    }

    /**
     * 切换玩家位置隐私设置
     * 
     * @param playerUUID 玩家UUID
     * @return 切换后的状态，true表示隐藏位置，false表示显示位置
     */
    public boolean toggleHideLocation(UUID playerUUID) {
        boolean hidden;
        if (hiddenPlayers.contains(playerUUID)) {
            hiddenPlayers.remove(playerUUID);
            hidden = false;
        } else {
            hiddenPlayers.add(playerUUID);
            hidden = true;
        }
        // 持久化保存隐藏状态
        saveData(TOCpluginNative.getInstance());
        return hidden;
    }

    /**
     * 检查玩家是否隐藏位置
     * 
     * @param playerUUID 玩家UUID
     * @return 如果玩家隐藏位置则返回true，否则返回false
     */
    public boolean isLocationHidden(UUID playerUUID) {
        return hiddenPlayers.contains(playerUUID);
    }

    /**
     * 设置玩家的导航目标
     * 
     * @param playerUUID 导航的玩家UUID
     * @param targetUUID 目标玩家UUID
     * @return 如果成功设置导航目标返回true，如果已经有相同的导航目标或导航功能已关闭返回false
     */
    public boolean setNavigationTarget(UUID playerUUID, UUID targetUUID) {
        // 检查全局导航开关和玩家权限
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }

        // 隐身玩家规则：当配置不允许时，禁止导航到隐身玩家
        try {
            org.bukkit.entity.Player targetPlayerCheck = org.bukkit.Bukkit.getPlayer(targetUUID);
            if (targetPlayerCheck != null && targetPlayerCheck.isOnline()) {
                boolean allowInvisible = TOCpluginNative.getInstance().getConfig().getBoolean("allow_navigation_to_invisible", false);
                boolean isInvisible = targetPlayerCheck.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY) || targetPlayerCheck.isInvisible();
                if (!allowInvisible && isInvisible) {
                    return false;
                }
            }
        } catch (Exception ignore) {}

        // 检查目标玩家的位置隐私保护
        if (isLocationHidden(targetUUID)) {
            // 检查当前玩家是否有权限绕过位置隐私保护
            boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(playerUUID);
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUUID);
            boolean canBypass = isAuthenticated || isSpecialPlayer(player);

            if (!canBypass) {
                return false; // 非特权玩家无法导航到隐藏位置的玩家
            }
        }

        // 停止当前所有导航
        stopNavigation(playerUUID);

        // 设置新的导航目标
        navigationTargets.put(playerUUID, targetUUID);
        navigatingPlayers.add(playerUUID);

        // BossBar提示：通知被导航玩家
        org.bukkit.entity.Player targetPlayer = org.bukkit.Bukkit.getPlayer(targetUUID);
        org.bukkit.entity.Player navPlayer = org.bukkit.Bukkit.getPlayer(playerUUID);
        if (targetPlayer != null && navPlayer != null && targetPlayer.isOnline()) {
            // 检查是否应该显示BossBar提示
            boolean shouldShowNotification = true;

            // 规则1：若目标玩家启用"禁止被导航"功能，但导航玩家绕过了限制，则不显示提示
            if (isLocationHidden(targetUUID)) {
                boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(playerUUID);
                boolean canBypass = isAuthenticated || isSpecialPlayer(navPlayer) || navPlayer.isOp();
                if (canBypass) {
                    shouldShowNotification = false;
                }
            }

            // 规则2：若全局导航功能已关闭，且导航玩家绕过了系统限制，则不显示提示
            if (!navigationEnabled) {
                boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(playerUUID);
                boolean canBypass = isAuthenticated
                        || navPlayer.isOp();
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
                // 给被导航玩家发送消息
                targetPlayer
                        .sendMessage("§a" + LanguageManager.getInstance().getString(targetPlayer,
                                "messages.navigating-player-to-you",
                                navPlayer.getName()));
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override
                    public void run() {
                        bossBar.removeAll();
                    }
                }.runTaskLater(TOCpluginNative.getInstance(), 100L); // 5秒
            }
        }
        return true;
    }

    /**
     * 检查玩家是否可以使用导航功能
     * 
     * @param playerUUID 玩家UUID
     * @return 如果玩家可以使用导航功能返回true，否则返回false
     */
    public boolean canPlayerUseNavigation(UUID playerUUID) {
        // 如果全局导航开关关闭，只有特殊玩家、OP玩家和已验证玩家可以使用导航
        if (!navigationEnabled) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUUID);
            if (player == null)
                return false;

            // 特殊玩家、OP玩家或已验证玩家可以使用导航
            return player.isOp() ||
                    TOCpluginNative.getInstance().isPlayerAuthenticated(playerUUID);
        }

        // 全局导航开关开启，所有玩家都可以使用导航
        return true;
    }

    /**
     * 停止玩家的导航
     * 
     * @param playerUUID 玩家UUID
     */
    public void stopNavigation(UUID playerUUID) {
        // BossBar提示：通知被导航玩家取消（在移除导航目标之前获取）
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
                // 检查是否应该显示BossBar提示
                boolean shouldShowNotification = true;

                // 规则1：若目标玩家启用"禁止被导航"功能，但导航玩家绕过了限制，则不显示提示
                if (isLocationHidden(targetUUID)) {
                    boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(playerUUID);
                    boolean canBypass = isAuthenticated || isSpecialPlayer(navPlayer) || navPlayer.isOp();
                    if (canBypass) {
                        shouldShowNotification = false;
                    }
                }

                // 规则2：若全局导航功能已关闭，且导航玩家绕过了系统限制，则不显示提示
                if (!navigationEnabled) {
                    boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(playerUUID);
                    boolean canBypass = isAuthenticated
                            || navPlayer.isOp();
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
                    // 发送玩家消息
                    targetPlayer
                            .sendMessage("§a" + LanguageManager.getInstance().getString(targetPlayer,
                                    "messages.navigating-player-to-you-cancelled",
                                    navPlayer.getName()));
                    new org.bukkit.scheduler.BukkitRunnable() {
                        @Override
                        public void run() {
                            bossBar.removeAll();
                        }
                    }.runTaskLater(TOCpluginNative.getInstance(), 100L); // 5秒
                }
            }
        }
        // 取消旧的粒子任务，防止导航冲突
        MasterListener.getGuiManager().removeParticleTask(playerUUID);
    }

    /**
     * 为当前导航会话抑制 ActionBar（仅单次有效，在 stopNavigation 时清除）
     */
    public void suppressActionBarForCurrentSession(UUID playerUUID) {
        if (playerUUID != null) actionBarSuppressedPlayers.add(playerUUID);
    }

    /**
     * 检查该玩家是否抑制 ActionBar
     */
    public boolean isActionBarSuppressed(UUID playerUUID) {
        return playerUUID != null && actionBarSuppressedPlayers.contains(playerUUID);
    }

    /**
     * 手动清除 ActionBar 抑制标记
     */
    public void clearActionBarSuppression(UUID playerUUID) {
        if (playerUUID != null) actionBarSuppressedPlayers.remove(playerUUID);
    }

    /**
     * 检查玩家是否正在导航
     * 
     * @param playerUUID 玩家UUID
     * @return 如果玩家正在导航则返回true，否则返回false
     */
    public boolean isNavigating(UUID playerUUID) {
        return navigatingPlayers.contains(playerUUID) || strongholdNavigations.containsKey(playerUUID) || waypointNavigations.containsKey(playerUUID);
    }

    /**
     * 获取玩家的导航目标
     * 
     * @param playerUUID 玩家UUID
     * @return 目标玩家UUID，如果没有则返回null
     */
    public UUID getNavigationTarget(UUID playerUUID) {
        return navigationTargets.get(playerUUID);
    }

    /**
     * 设置玩家末地要塞导航
     * 
     * @param playerUUID         玩家UUID
     * @param strongholdLocation 末地要塞位置
     * @return 如果成功设置导航目标返回true，如果导航功能已关闭返回false
     */
    public boolean setStrongholdNavigation(UUID playerUUID, Location strongholdLocation) {
        // 检查全局导航开关和玩家权限
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }

        // 移除其他类型导航，但保留末地要塞导航更新
        navigationTargets.remove(playerUUID);
        beaconNavigations.remove(playerUUID);
        // 保留粒子任务，确保导航持续显示

        // 设置新的末地要塞导航
        strongholdNavigations.put(playerUUID, strongholdLocation);
        navigatingPlayers.add(playerUUID);
        return true;
    }

    /**
     * 获取玩家末地要塞导航位置
     * 
     * @param playerUUID 玩家UUID
     * @return 末地要塞位置，如果没有导航则返回null
     */
    public Location getStrongholdNavigation(UUID playerUUID) {
        return strongholdNavigations.get(playerUUID);
    }

    /**
     * 设置玩家信标导航
     * 
     * @param playerUUID     玩家UUID
     * @param beaconLocation 信标位置
     * @return 如果成功设置导航目标返回true，如果导航功能已关闭返回false
     */
    public boolean setBeaconNavigation(UUID playerUUID, Location beaconLocation) {
        // 检查全局导航开关和玩家权限
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }

        // 停止当前所有导航
        stopNavigation(playerUUID);

        // 设置新的信标导航
        beaconNavigations.put(playerUUID, beaconLocation);
        navigatingPlayers.add(playerUUID);
        return true;
    }

    /**
     * 获取玩家信标导航位置
     * 
     * @param playerUUID 玩家UUID
     * @return 信标位置，如果没有导航则返回null
     */
    public Location getBeaconNavigation(UUID playerUUID) {
        return beaconNavigations.get(playerUUID);
    }

    /**
     * 设置自定义路标导航
     */
    public boolean setWaypointNavigation(UUID playerUUID, Location location, String name) {
        if (!canPlayerUseNavigation(playerUUID)) {
            return false;
        }
        // 移除其它类型导航
        navigationTargets.remove(playerUUID);
        strongholdNavigations.remove(playerUUID);
        beaconNavigations.remove(playerUUID);
        // 设置路标导航
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

    /**
     * 清除所有导航数据（不取消粒子任务）
     */
    public void clearAllNavigations() {
        navigationTargets.clear();
        strongholdNavigations.clear();
        beaconNavigations.clear();
        navigatingPlayers.clear();
    }

    /**
     * 获取当前正在进行「玩家→玩家」导航的记录
     * 返回条目为 "navigatorUUID -> targetUUID"
     */
    public java.util.Map<UUID, UUID> getActivePlayerNavigations() {
        return new java.util.HashMap<>(navigationTargets);
    }

    /**
     * 获取所有正在导航的玩家（包含玩家→玩家、路标、信标、要塞）
     */
    public java.util.Set<UUID> getAllNavigatingPlayers() {
        java.util.Set<UUID> set = new java.util.HashSet<>(navigatingPlayers);
        set.addAll(navigationTargets.keySet());
        set.addAll(strongholdNavigations.keySet());
        set.addAll(beaconNavigations.keySet());
        set.addAll(waypointNavigations.keySet());
        return set;
    }

    /**
     * 停止所有玩家的导航，用于插件禁用时
     */
    public void stopAllNavigations() {
        // 获取所有正在导航的玩家UUID
        Set<UUID> allNavigatingPlayers = new HashSet<>(navigatingPlayers);

        // 停止每个玩家的导航
        for (UUID playerUUID : allNavigatingPlayers) {
            stopNavigation(playerUUID);
        }

        // 清除所有导航数据
        clearAllNavigations();
    }

    /**
     * 切换全局导航开关
     * 
     * @return 切换后的状态，true表示开启导航，false表示关闭导航
     */
    public boolean toggleNavigationEnabled() {
        navigationEnabled = !navigationEnabled;
        // 如果关闭导航，停止所有非特权玩家的导航
        if (!navigationEnabled) {
            stopNonPrivilegedNavigations();
        }
        // 持久化保存设置
        saveData(TOCpluginNative.getInstance());
        return navigationEnabled;
    }

    /**
     * 获取全局导航开关状态
     * 
     * @return 如果导航功能已开启返回true，否则返回false
     */
    public boolean isNavigationEnabled() {
        return navigationEnabled;
    }

    /**
     * 停止所有非特权玩家的导航
     */
    private void stopNonPrivilegedNavigations() {
        // 获取所有正在导航的玩家UUID
        Set<UUID> allNavigatingPlayers = new HashSet<>(navigatingPlayers);

        // 停止每个非特权玩家的导航
        for (UUID playerUUID : allNavigatingPlayers) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUUID);
            if (player == null)
                continue;

            // 如果不是特殊玩家、OP玩家或已验证玩家，停止导航
            if (!player.isOp() &&
                    !TOCpluginNative.getInstance().isPlayerAuthenticated(playerUUID)) {
                stopNavigation(playerUUID);
            }
        }
    }

    // 数据持久化相关 -----------------------
    private static final String DATA_FILE_NAME = "playerdata.yml";

    /**
     * 从磁盘加载玩家隐私数据和导航设置
     */
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

        // 加载全局导航开关设置
        navigationEnabled = yaml.getBoolean("navigationEnabled", true);
    }

    /**
     * 将玩家隐私数据和导航设置保存到磁盘
     */
    public void saveData(JavaPlugin plugin) {
        if (plugin == null)
            return;
        File file = new File(plugin.getDataFolder(), DATA_FILE_NAME);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("hiddenPlayers", hiddenPlayers.stream().map(UUID::toString).toList());

        // 保存全局导航开关设置
        yaml.set("navigationEnabled", navigationEnabled);

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning(
                    LanguageManager.getInstance().getString("messages.pathfinder-config-update-error", e.getMessage()));
        }
    }

    /**
     * 判断玩家是否为特殊玩家（OP或指定用户名）
     * 
     * @param player 玩家对象
     * @return 如果是特殊玩家返回true，否则返回false
     */
    private boolean isSpecialPlayer(org.bukkit.entity.Player player) {
        if (player == null)
            return false;
        return player.isOp();
    }

    /**
     * 获取正在导航到指定玩家的所有玩家列表
     * 
     * @param targetPlayer 目标玩家
     * @return 正在导航到该玩家的玩家列表
     */
    public java.util.List<org.bukkit.entity.Player> getPlayersNavigatingTo(org.bukkit.entity.Player targetPlayer) {
        java.util.List<org.bukkit.entity.Player> result = new java.util.ArrayList<>();
        UUID targetUUID = targetPlayer.getUniqueId();

        for (java.util.Map.Entry<UUID, UUID> entry : navigationTargets.entrySet()) {
            if (entry.getValue().equals(targetUUID)) {
                org.bukkit.entity.Player navigatingPlayer = org.bukkit.Bukkit.getPlayer(entry.getKey());
                if (navigatingPlayer != null && navigatingPlayer.isOnline()) {
                    result.add(navigatingPlayer);
                }
            }
        }

        return result;
    }
}

