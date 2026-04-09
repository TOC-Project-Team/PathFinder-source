package org.momu.pathfinder.presentation.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.momu.pathfinder.navigation.state.PlayerTracker;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import org.momu.pathfinder.config.LanguageManager;
import org.momu.pathfinder.navigation.runtime.PathFinding;

public class MenuListener {
    @SuppressWarnings("deprecation")
    void handlePlayerNavigationClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        int slot = event.getSlot();
        switch (clicked.getType()) {
            case ARROW: {
                if (slot == 45) {
                    Integer currentPage = MasterListener.guiManager.getCurrentPage(player.getUniqueId());
                    if (currentPage != null && currentPage > 0) {
                        MasterListener.guiManager.openPlayerNavigationMenu(player, currentPage - 1);
                    }
                } else if (slot == 53) {
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
                    boolean canBypass = PathFinderPlugin.getInstance().canBypassNavigationRestrictions(player.getUniqueId());

                    if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                        player.sendMessage(ChatColor.YELLOW
                                + LanguageManager.getInstance().getString(player,
                                        "messages.cannot-navigate-spectator"));
                        MasterListener.guiManager.openPlayerNavigationMenu(player);
                        return;
                    }

                    if (PlayerTracker.getInstance().isNavigationBlockedByInvisibility(target)) {
                        player.sendMessage(ChatColor.YELLOW
                                + LanguageManager.getInstance().getString(player, "messages.target-hidden"));
                        MasterListener.guiManager.openPlayerNavigationMenu(player);
                        return;
                    }

                    if (isTargetHidden && !canBypass) {
                        player.sendMessage(ChatColor.RED
                                + LanguageManager.getInstance().getString(player, "messages.target-hidden"));
                        MasterListener.guiManager.openPlayerNavigationMenu(player);
                        return;
                    }
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
                    PathFinding.startPathfinding(player);
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
                Bukkit.getScheduler().runTask(PathFinderPlugin.getInstance(), () -> {
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
                        PathFinding.startPathfinding(player);
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
                                PlayerTracker.getInstance().setBeaconNavigation(player.getUniqueId(), beaconLoc);
                                player.sendMessage(ChatColor.GREEN
                                        + LanguageManager.getInstance().getString(player,
                                                "messages.beacon-navigation-set"));
                                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.0f);

                                if (!MasterListener.guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                                    MasterListener.guiManager.toggleParticleFeature(player.getUniqueId());
                                }

                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        PathFinding.startPathfinding(player);
                                    }
                                }.runTaskLater(PathFinderPlugin.getInstance(), 1L);
                            }
                        }.runTask(PathFinderPlugin.getInstance());
                    } else {
                        if (player.isOnline()) {
                            player.sendMessage(ChatColor.RED
                                    + LanguageManager.getInstance().getString(player, "messages.no-beacon-found"));
                        }
                    }
                });
                break;
            }
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

        if (slot == 13) {
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

    @SuppressWarnings("deprecation")
    void handleNavigationToggle(Player player) {
        if (!player.hasPermission("toc.admin")) {
            player.sendMessage(Component.text(LanguageManager.getInstance().getString(player, "messages.no-permission"))
                    .color(NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            MasterListener.guiManager.openMainMenu(player);
            return;
        }

        boolean newState = PlayerTracker.getInstance().toggleNavigationEnabled();

        if (newState) {
            player.sendMessage(
                    ChatColor.GREEN + LanguageManager.getInstance().getString(player, "messages.global-navi-open"));
        } else {
            player.sendMessage(
                    ChatColor.YELLOW + LanguageManager.getInstance().getString(player, "messages.global-navi-closed"));
        }

        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 1.0f, 1.0f);

        MasterListener.guiManager.openMainMenu(player);
    }
}
