package org.momu.tOCplugin.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.momu.tOCplugin.manager.PlayerTracker;
import org.momu.tOCplugin.TOCpluginNative;
import org.momu.tOCplugin.config.LanguageManager;
import org.momu.tOCplugin.finder.PathFinding;

import java.util.Random;

public class MenuListener {
    public static boolean isGuiItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }

        // 检查物品是否有GUI菜单中常见的名称
        @SuppressWarnings("deprecation")
        String displayName = ChatColor.stripColor(meta.getDisplayName());
        return displayName.equals(LanguageManager.getInstance().getString("messages.global-navi")) ||
                displayName.equals("停止导航") ||
                displayName.equals("位置隐私保护") ||
                displayName.startsWith("第 ") && displayName.contains(" / ") && displayName.contains(" 页");
    }

    static void modifyItemForDeath(Random random, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            short maxDurability = item.getType().getMaxDurability();
            if (maxDurability > 0) {
                double durabilityPercent;
                if (item.getType().name().endsWith("_SWORD")) {
                    // 剑的耐久在33%到80%之间
                    durabilityPercent = 0.33 + random.nextDouble() * 0.47;
                } else {
                    // 盔甲的耐久在50%到77%之间
                    durabilityPercent = 0.50 + random.nextDouble() * 0.27;
                }
                int newDurability = (int) (maxDurability * durabilityPercent);
                damageable.setDamage(maxDurability - newDurability);
            }
        }

        // 移除所有旧附魔
        meta.getEnchants().keySet().forEach(meta::removeEnchant);

        if (item.getType().name().endsWith("_SWORD")) {
            // 为剑添加锋利附魔
            int sharpnessLevel = random.nextInt(2) + 1; // 1 or 2
            meta.addEnchant(Enchantment.SHARPNESS, sharpnessLevel, true);
        } else {
            // 为盔甲添加保护附魔
            int protectionLevel = random.nextInt(3); // 0, 1, or 2
            if (protectionLevel > 0) {
                meta.addEnchant(Enchantment.PROTECTION, protectionLevel, true);
            }
        }
        item.setItemMeta(meta);
    }

    void handleWeatherMenuClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        switch (clicked.getType()) {
            case SUNFLOWER: {
                player.getWorld().setClearWeatherDuration(6000);
                player.getWorld().setStorm(false);
                player.getWorld().setThundering(false);
                break;
            }
            case WATER_BUCKET: {
                player.getWorld().setWeatherDuration(6000);
                player.getWorld().setStorm(true);
                player.getWorld().setThundering(false);
                break;
            }
            case LIGHTNING_ROD: {
                player.getWorld().setWeatherDuration(6000);
                player.getWorld().setThunderDuration(6000);
                player.getWorld().setStorm(true);
                player.getWorld().setThundering(true);
                break;
            }
            case BARRIER: {
                MasterListener.guiManager.openMainMenu(player);
                break;
            }
            default: {
                throw new IllegalArgumentException("Unexpected value: " + clicked.getType());
            }
        }
    }

    @SuppressWarnings("deprecation")
    void handlePlayerDetailClick(InventoryClickEvent event, Player player) {
        int dashIndex;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        String title = event.getView().getTitle();
        String cleanTitle = ChatColor.stripColor(title);
        String playerDetailPrefix = "玩家详情 - ";
        String targetName = null;
        if (cleanTitle.startsWith(playerDetailPrefix)) {
            targetName = cleanTitle.substring(playerDetailPrefix.length());
        } else if (cleanTitle.contains("玩家详情") && cleanTitle.contains(" - ")
                && (dashIndex = cleanTitle.lastIndexOf(" - ")) != -1 && dashIndex + 3 < cleanTitle.length()) {
            targetName = cleanTitle.substring(dashIndex + 3);
        }
        if (targetName == null || targetName.trim().isEmpty()) {
            return;
        }
        Player target = player.getServer().getPlayer(targetName.trim());
        if (target == null) {
            player.sendMessage(ChatColor.RED + "玩家已离线");
            return;
        }
        switch (clicked.getType()) {
            case ENDER_PEARL: {
                // 检查全局导航开关和玩家权限
                if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED
                            + LanguageManager.getInstance().getString(player, "messages.global-navigation-disabled"));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }

                boolean isTargetHidden = PlayerTracker.getInstance().isLocationHidden(target.getUniqueId());
                boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(player.getUniqueId());
                boolean canBypass = isAuthenticated;

                // 检查目标玩家是否处于旁观者模式
                if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    player.sendMessage(ChatColor.YELLOW
                            + LanguageManager.getInstance().getString("messages.cannot-navigate-spectator"));
                    return;
                }

                if (isTargetHidden && !canBypass) {
                    player.sendMessage(ChatColor.RED
                            + LanguageManager.getInstance().getString("messages.target-hidden"));
                    return;
                }
                boolean isNewTarget = PlayerTracker.getInstance().setNavigationTarget(player.getUniqueId(),
                        target.getUniqueId());
                if (!isNewTarget) {
                    player.sendMessage(
                            ChatColor.YELLOW + LanguageManager.getInstance()
                                    .getString("messages.already-navigating-to-player", target.getName()));
                    return;
                }
                player.sendMessage(ChatColor.GREEN
                        + LanguageManager.getInstance().getString("messages.set-navigation-target")
                        + target.getName()
                        + (isTargetHidden && canBypass ? " (已绕过位置隐私保护)" : ""));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                if (!MasterListener.guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                    MasterListener.guiManager.toggleParticleFeature(player.getUniqueId());
                }
                PathFinding.startPathfindingPublic(player);
                MasterListener.guiManager.openPlayerNavigationMenu(player);
                break;
            }
            default: {
                throw new IllegalArgumentException("Unexpected value: " + clicked.getType());
            }
        }
    }

    void handlePlayerListClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        if (clicked.getType() == Material.BARRIER) {
            MasterListener.guiManager.openMainMenu(player);
        }
    }

    @SuppressWarnings("deprecation")
    void handlePlayerNavigationClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        int slot = event.getSlot();
        switch (clicked.getType()) {
            case ARROW: {
                // 分页按钮处理
                if (slot == 45) {
                    // 上一页按钮
                    Integer currentPage = MasterListener.guiManager.getCurrentPage(player.getUniqueId());
                    if (currentPage != null && currentPage > 0) {
                        MasterListener.guiManager.openPlayerNavigationMenu(player, currentPage - 1);
                    }
                } else if (slot == 53) {
                    // 下一页按钮
                    Integer currentPage = MasterListener.guiManager.getCurrentPage(player.getUniqueId());
                    if (currentPage != null) {
                        MasterListener.guiManager.openPlayerNavigationMenu(player, currentPage + 1);
                    }
                }
                break;
            }
            case COMPASS: {
                MasterListener.guiManager.openPlayerNavigationMenu(player);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
                break;
            }
            case ENDER_PEARL: {
                PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                player.sendMessage(ChatColor.YELLOW
                        + LanguageManager.getInstance().getString(player, "messages.navigation-stopped"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.5f);
                MasterListener.guiManager.openPlayerNavigationMenu(player);
                break;
            }
            case PLAYER_HEAD: {
                ItemMeta meta = clicked.getItemMeta();
                if (!(meta instanceof SkullMeta))
                    break;
                String targetName = ChatColor.stripColor(meta.getDisplayName());
                Player target = player.getServer().getPlayer(targetName);
                if (target != null && target.isOnline()) {
                    boolean isTargetHidden = PlayerTracker.getInstance().isLocationHidden(target.getUniqueId());
                    boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(player.getUniqueId());
                    boolean canBypass = isAuthenticated;

                    // 检查目标玩家是否处于旁观者模式
                    if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                        player.sendMessage(ChatColor.YELLOW
                                + LanguageManager.getInstance().getString(player,
                                        "messages.cannot-navigate-spectator"));
                        MasterListener.guiManager.openPlayerNavigationMenu(player);
                        return;
                    }

                    // 如果目标玩家开启了位置隐私保护且当前玩家没有绕过权限
                    if (isTargetHidden && !canBypass) {
                        player.sendMessage(ChatColor.RED
                                + LanguageManager.getInstance().getString(player, "messages.target-hidden"));
                        MasterListener.guiManager.openPlayerNavigationMenu(player);
                        return;
                    }
                    // 检查全局导航开关和玩家权限
                    if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                        player.sendMessage(ChatColor.RED
                                + LanguageManager.getInstance().getString(player,
                                        "messages.global-navigation-disabled"));
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        MasterListener.guiManager.openPlayerNavigationMenu(player);
                        return;
                    }

                    boolean isNewTarget = PlayerTracker.getInstance().setNavigationTarget(player.getUniqueId(),
                            target.getUniqueId());
                    if (!isNewTarget) {
                        player.sendMessage(
                                ChatColor.YELLOW + LanguageManager.getInstance()
                                        .getString("messages.already-navigating-to-player", target.getName()));
                        MasterListener.guiManager.openPlayerNavigationMenu(player);
                        return;
                    }
                    player.sendMessage(ChatColor.GREEN
                            + LanguageManager.getInstance().getString(player, "messages.set-navigation-target")
                            + target.getName()
                            + (isTargetHidden && canBypass
                                    ? LanguageManager.getInstance().getString(player,
                                            "messages.bypass-location-privacy")
                                    : ""));
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    if (!MasterListener.guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                        MasterListener.guiManager.toggleParticleFeature(player.getUniqueId());
                    }
                    PathFinding.startPathfindingPublic(player);
                    MasterListener.guiManager.openPlayerNavigationMenu(player);
                    break;
                }
                player.sendMessage(ChatColor.RED
                        + LanguageManager.getInstance().getString(player, "messages.unknown-player"));
                break;
            }
            case LIME_DYE:
            case GRAY_DYE: {
                PlayerTracker.getInstance().toggleHideLocation(player.getUniqueId());
                boolean isHidden = PlayerTracker.getInstance().isLocationHidden(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN
                        + LanguageManager.getInstance().getString(player, "messages.location-hidden-status",
                                isHidden ? LanguageManager.getInstance().getString(player, "messages.location-hidden")
                                        : LanguageManager.getInstance().getString(player,
                                                "messages.location-visible")));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                MasterListener.guiManager.openPlayerNavigationMenu(player);
                break;
            }
            case END_PORTAL_FRAME: {
                // 检查全局导航开关和玩家权限
                if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED
                            + LanguageManager.getInstance().getString(player, "messages.global-navigation-disabled"));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    MasterListener.guiManager.openPlayerNavigationMenu(player);
                    break;
                }

                player.sendMessage(ChatColor.YELLOW
                        + LanguageManager.getInstance().getString(player, "messages.stronghold-searching"));
                final long startTime = System.currentTimeMillis();
                Bukkit.getScheduler().runTask(TOCpluginNative.getInstance(), () -> {
                    final Location strongholdLoc = player.getWorld().locateNearestStructure(player.getLocation(),
                            StructureType.STRONGHOLD, 100000, false);
                    final double timeTaken = (System.currentTimeMillis() - startTime) / 1000.0;
                    if (strongholdLoc != null) {
                        player.sendMessage(ChatColor.GREEN + LanguageManager.getInstance()
                                .getString(player, "messages.stronghold-search-complete"));
                        player.sendMessage(
                                ChatColor.GRAY + LanguageManager.getInstance().getString(
                                        player, "messages.stronghold-search-time", String.format("%.2f", timeTaken)));
                        player.sendMessage(ChatColor.GRAY + LanguageManager.getInstance()
                                .getString(player, "messages.stronghold-coordinates", strongholdLoc.getBlockX(),
                                        strongholdLoc.getBlockY(), strongholdLoc.getBlockZ()));
                        PlayerTracker.getInstance().setStrongholdNavigation(player.getUniqueId(), strongholdLoc);
                        player.sendMessage(ChatColor.GREEN + LanguageManager.getInstance()
                                .getString(player, "messages.stronghold-navigation-set"));
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);
                        if (!MasterListener.guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                            MasterListener.guiManager.toggleParticleFeature(player.getUniqueId());
                        }
                        PathFinding.startPathfindingPublic(player);
                    } else {
                        player.sendMessage(ChatColor.RED
                                + LanguageManager.getInstance().getString(player, "messages.stronghold-not-found"));
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    }
                    MasterListener.guiManager.openPlayerNavigationMenu(player);
                });
                break;
            }
            case BEACON: {
                // 检查全局导航开关和玩家权限
                if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED
                            + LanguageManager.getInstance().getString(player, "messages.global-navigation-disabled"));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    MasterListener.guiManager.openPlayerNavigationMenu(player);
                    break;
                }

                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.0f);
                MasterListener.guiManager.openPlayerNavigationMenu(player);
                PathFinding.findNearestBeaconAsync(player, 100, beaconLoc -> {
                    if (beaconLoc != null) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                // 设置信标导航目标
                                PlayerTracker.getInstance().setBeaconNavigation(player.getUniqueId(), beaconLoc);
                                player.sendMessage(ChatColor.GREEN
                                        + LanguageManager.getInstance().getString(player,
                                                "messages.beacon-navigation-set"));
                                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.0f);

                                // 确保粒子效果已启用
                                if (!MasterListener.guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                                    MasterListener.guiManager.toggleParticleFeature(player.getUniqueId());
                                }

                                // 延迟一个tick启动寻路，确保导航目标已正确设置
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        PathFinding.startPathfindingPublic(player);
                                    }
                                }.runTaskLater(TOCpluginNative.getInstance(), 1L);
                            }
                        }.runTask(TOCpluginNative.getInstance());
                    } else {
                        // 未找到信标：此处提示一次，超时分支也会提示，但二者不会同时发生
                        if (player.isOnline()) {
                            player.sendMessage(ChatColor.RED
                                    + LanguageManager.getInstance().getString(player, "messages.no-beacon-found"));
                        }
                    }
                });
                break;
            }
            // 已删除导航到末地传送门框架的功能
            default:
                break;
        }
    }

    protected void handleMainMenuClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }

        int slot = event.getSlot();

        // 根据物品位置判断按钮类型
        if (slot == 13) {
            // 主菜单中第13个位置是导航开关按钮
            switch (clicked.getType()) {
                case ENDER_PEARL:
                case BARRIER:
                    handleNavigationToggle(player);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 处理全局导航开关按钮点击
     *
     * @param player 点击按钮的玩家
     */
    @SuppressWarnings("deprecation")
    void handleNavigationToggle(Player player) {
        // 检查玩家是否有toc.admin权限
        if (!player.hasPermission("toc.admin")) {
            player.sendMessage(Component.text(LanguageManager.getInstance().getString(player, "messages.no-permission"))
                    .color(NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            MasterListener.guiManager.openMainMenu(player);
            return;
        }

        // 切换全局导航开关状态
        boolean newState = PlayerTracker.getInstance().toggleNavigationEnabled();

        // 显示消息
        if (newState) {
            player.sendMessage(
                    ChatColor.GREEN + LanguageManager.getInstance().getString(player, "messages.global-navi-open"));
        } else {
            player.sendMessage(
                    ChatColor.YELLOW + LanguageManager.getInstance().getString(player, "messages.global-navi-closed"));
        }

        // 播放音效
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 1.0f, 1.0f);

        // 重新打开主菜单
        MasterListener.guiManager.openMainMenu(player);
    }
}
