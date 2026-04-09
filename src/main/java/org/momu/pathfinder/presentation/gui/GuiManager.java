package org.momu.pathfinder.presentation.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.momu.pathfinder.config.LanguageManager;
import org.momu.pathfinder.navigation.state.PlayerTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GuiManager {
    public static final Component GUI_TITLE = Component.text("PathFinder").color(NamedTextColor.BLUE);

    public static Component getPlayerNavigationTitle(Player player) {
        return Component
                .text(LanguageManager.getInstance().getString(player, "messages.player-navigation-cd"))
                .color(NamedTextColor.LIGHT_PURPLE);
    }

    private final java.util.Map<java.util.UUID, BukkitTask> particleTasks = new java.util.HashMap<>();
    private final java.util.Set<java.util.UUID> particleFeatureEnabled = new java.util.HashSet<>();

    private final java.util.Set<Inventory> pluginInventories = new java.util.HashSet<>();

    public enum GuiType {
        MAIN_MENU, PLAYER_NAVIGATION
    }

    private final java.util.Map<Inventory, GuiType> inventoryTypes = new java.util.HashMap<>();

    private void registerGui(Inventory inventory, GuiType type) {
        pluginInventories.add(inventory);
        inventoryTypes.put(inventory, type);
    }
    
    public boolean isPluginGui(Inventory inventory) {
        return pluginInventories.contains(inventory);
    }
    
    public GuiType getGuiType(Inventory inventory) {
        return inventoryTypes.get(inventory);
    }
    
    public void unregisterGui(Inventory inventory) {
        pluginInventories.remove(inventory);
        inventoryTypes.remove(inventory);
    }

    public Integer getCurrentPage(UUID playerId) {
        return playerNavigationPages.get(playerId);
    }

    public void openMainMenu(Player player) {
        if (player == null)
            return;

        Inventory gui = Bukkit.createInventory(player, 27, GUI_TITLE);
        registerGui(gui, GuiType.MAIN_MENU);

        boolean navigationEnabled = PlayerTracker.getInstance().isNavigationEnabled();
        gui.setItem(13, createGuiItem(
                navigationEnabled ? Material.ENDER_PEARL : Material.BARRIER,
                Component.text(LanguageManager.getInstance().getString(player, "messages.global-navi"))
                        .color(navigationEnabled ? NamedTextColor.GREEN : NamedTextColor.RED),
                Component.text(LanguageManager.getInstance().getString(player, "messages.current-status") + ": " + 
                        (navigationEnabled ? LanguageManager.getInstance().getString(player, "messages.enabled") : 
                         LanguageManager.getInstance().getString(player, "messages.disabled"))).color(NamedTextColor.GRAY)));

        player.openInventory(gui);
    }

    public ItemStack createGuiItem(Material material, Component name, Component... lore) {
        if (material == null) {
            return new ItemStack(Material.AIR);
        }

        if (material == Material.GRAY_STAINED_GLASS_PANE && name == Component.empty()) {
            return new ItemStack(material, 1);
        }
        
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(name);
            }
            if (lore.length > 0) {
                meta.lore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private final Map<UUID, Integer> playerNavigationPages = new HashMap<>();

    public void openPlayerNavigationMenu(Player player) {
        openPlayerNavigationMenu(player, 0);
    }

    public void openPlayerNavigationMenu(Player player, int page) {
        Inventory gui = Bukkit.createInventory(player, 54, getPlayerNavigationTitle(player));
        registerGui(gui, GuiType.PLAYER_NAVIGATION);

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        onlinePlayers.sort(Comparator.comparing(Player::getName));

        int playersPerPage = 36;
        int totalPlayers = (int) onlinePlayers.stream()
                .filter(p -> !p.getUniqueId().equals(player.getUniqueId()))
                .count();
        int totalPages = (int) Math.ceil((double) totalPlayers / playersPerPage);

        if (page < 0)
            page = 0;
        if (totalPages > 0 && page >= totalPages)
            page = totalPages - 1;

        playerNavigationPages.put(player.getUniqueId(), page);

        int startIndex = page * playersPerPage;
        int endIndex = Math.min(startIndex + playersPerPage, totalPlayers);

        int slot = 0;
        int currentIndex = 0;
        for (Player target : onlinePlayers) {
            if (target.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

            if (currentIndex < startIndex || currentIndex >= endIndex) {
                currentIndex++;
                continue;
            }

            if (slot >= 36)
                break;

            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            if (playerHead.getItemMeta() instanceof SkullMeta meta) {
                meta.setPlayerProfile(target.getPlayerProfile());
                meta.displayName(Component.text(target.getName()).color(NamedTextColor.YELLOW));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player"))
                        .color(NamedTextColor.GRAY));

                if (player.getWorld().equals(target.getWorld())) {
                    double distance = player.getLocation().distance(target.getLocation());
                    lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player-distance"))
                            .color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(String.format("%.1fm", distance)).color(NamedTextColor.AQUA)));
                } else {
                    lore.add(Component.text("维度: ").color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(getWorldDisplayName(player, target.getWorld())).color(NamedTextColor.GOLD)));
                }
                String direction = getDirectionFromViewer(player, target);
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player-direction"))
                        .color(NamedTextColor.DARK_GRAY)
                        .append(Component.text(direction).color(NamedTextColor.GOLD)));

                meta.lore(lore);
                playerHead.setItemMeta(meta);
            }
            gui.setItem(slot++, playerHead);
            currentIndex++;
        }

        if (totalPages > 1) {
            if (page > 0) {
                ItemStack prevButton = new ItemStack(Material.ARROW);
                ItemMeta prevMeta = prevButton.getItemMeta();
                if (prevMeta != null) {
                    prevMeta.displayName(Component.text(LanguageManager.getInstance().getString(player, "messages.prev-page")).color(NamedTextColor.YELLOW));
                    prevMeta.lore(List.of(Component.text(LanguageManager.getInstance().getString(player, "messages.prev-page-desc")).color(NamedTextColor.GRAY)));
                    prevButton.setItemMeta(prevMeta);
                }
                gui.setItem(45, prevButton);
            }

            if (page < totalPages - 1) {
                ItemStack nextButton = new ItemStack(Material.ARROW);
                ItemMeta nextMeta = nextButton.getItemMeta();
                if (nextMeta != null) {
                    nextMeta.displayName(Component.text(LanguageManager.getInstance().getString(player, "messages.next-page")).color(NamedTextColor.YELLOW));
                    nextMeta.lore(List.of(Component.text(LanguageManager.getInstance().getString(player, "messages.next-page-desc")).color(NamedTextColor.GRAY)));
                    nextButton.setItemMeta(nextMeta);
                }
                gui.setItem(53, nextButton);
            }

            ItemStack pageIndicator = new ItemStack(Material.PAPER);
            ItemMeta pageMeta = pageIndicator.getItemMeta();
            if (pageMeta != null) {
                pageMeta.displayName(
                        Component.text(LanguageManager.getInstance().getString(player, "messages.page-indicator", (page + 1), totalPages)).color(NamedTextColor.GOLD));
                pageIndicator.setItemMeta(pageMeta);
            }
            gui.setItem(49, pageIndicator);
        }

        boolean isLocationHidden = PlayerTracker.getInstance().isLocationHidden(player.getUniqueId());
        ItemStack privacyButton = new ItemStack(isLocationHidden ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta privacyMeta = privacyButton.getItemMeta();
        if (privacyMeta != null) {
            privacyMeta.displayName(
                    Component.text(LanguageManager.getInstance().getString(player, "messages.player-navigation-privacy"))
                            .color(NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.player-navigation-privacy-desc"))
                    .color(NamedTextColor.GRAY));
            lore.add(isLocationHidden
                    ? Component
                            .text(LanguageManager.getInstance().getString(player, "messages.player-navigation-privacy-hidden"))
                            .color(NamedTextColor.GREEN)
                    : Component
                            .text(LanguageManager.getInstance().getString(player, "messages.player-navigation-privacy-visible"))
                            .color(NamedTextColor.RED));
            privacyMeta.lore(lore);
            privacyButton.setItemMeta(privacyMeta);
        }

        if (totalPages <= 1) {
            gui.setItem(49, privacyButton);
        } else {
            gui.setItem(46, privacyButton);
        }

        if (player.isOp() || player.hasPermission("toc.admin")) {
            ItemStack strongholdButton = new ItemStack(Material.END_PORTAL_FRAME);
            ItemMeta strongholdMeta = strongholdButton.getItemMeta();
            if (strongholdMeta != null) {
                strongholdMeta.displayName(Component.text(LanguageManager.getInstance().getString(player, "messages.stronghold-navigate")).color(NamedTextColor.LIGHT_PURPLE));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.stronghold-desc")).color(NamedTextColor.GRAY));
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.stronghold-desc-detail")).color(NamedTextColor.GRAY));
                strongholdMeta.lore(lore);
                strongholdButton.setItemMeta(strongholdMeta);
            }
            gui.setItem(47, strongholdButton);
        }

        if (player.isOp()) {
            ItemStack beaconButton = new ItemStack(Material.BEACON);
            ItemMeta beaconMeta = beaconButton.getItemMeta();
            if (beaconMeta != null) {
                beaconMeta.displayName(Component.text(LanguageManager.getInstance().getString(player, "messages.beacon"))
                        .color(NamedTextColor.AQUA));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.beacon-desc"))
                        .color(NamedTextColor.GRAY));
                lore.add(Component.empty());
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.beacon-desc-op"))
                        .color(NamedTextColor.RED));
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.beacon-desc-radius"))
                        .color(NamedTextColor.YELLOW));

                beaconMeta.lore(lore);
                beaconButton.setItemMeta(beaconMeta);
            }
            gui.setItem(48, beaconButton);

        }

        if (PlayerTracker.getInstance().isNavigating(player.getUniqueId())) {
            ItemStack stopButton = new ItemStack(Material.ENDER_PEARL);
            ItemMeta stopMeta = stopButton.getItemMeta();
            if (stopMeta != null) {
                stopMeta.displayName(Component.text(LanguageManager.getInstance().getString(player, "messages.stop"))
                        .color(NamedTextColor.RED));
                stopMeta.lore(List.of(Component.text(LanguageManager.getInstance().getString(player, "messages.stop-desc"))
                        .color(NamedTextColor.GRAY)));
                stopButton.setItemMeta(stopMeta);
            }
            gui.setItem(50, stopButton);
        }

        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < 54; i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, filler);
            }
        }

        player.openInventory(gui);
    }

    private String getWorldDisplayName(Player player, World world) {
        if (world == null)
            return LanguageManager.getInstance().getString(player, "messages.unknown");

        Environment env = world.getEnvironment();
        switch (env) {
            case NORMAL:
                return LanguageManager.getInstance().getString(player, "messages.normal-world");
            case NETHER:
                return LanguageManager.getInstance().getString(player, "messages.nether-world");
            case THE_END:
                return LanguageManager.getInstance().getString(player, "messages.end-world");
            default:
                return world.getName();
        }
    }

    private String getDirectionFromViewer(Player viewer, Player target) {
        return getDirectionToLocation(viewer, target.getLocation());
    }

    public void toggleParticleFeature(java.util.UUID playerId) {
        if (particleFeatureEnabled.contains(playerId)) {
            particleFeatureEnabled.remove(playerId);
            if (particleTasks.containsKey(playerId)) {
                particleTasks.get(playerId).cancel();
                particleTasks.remove(playerId);
            }
        } else {
            particleFeatureEnabled.add(playerId);
        }
    }

    public boolean isParticleFeatureEnabled(java.util.UUID playerId) {
        return particleFeatureEnabled.contains(playerId);
    }

    public void addParticleTask(java.util.UUID playerId, BukkitTask task) {
        particleTasks.put(playerId, task);
    }

    public void removeParticleTask(java.util.UUID playerId) {
        if (particleTasks.containsKey(playerId)) {
            particleTasks.get(playerId).cancel();
            particleTasks.remove(playerId);
        }
    }

    public void shutdown() {
        for (BukkitTask task : particleTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        particleTasks.clear();

        particleFeatureEnabled.clear();
    }

    private String getDirectionToLocation(Player player, Location target) {
        Location playerLoc = player.getLocation();

        double deltaX = target.getX() - playerLoc.getX();
        double deltaZ = target.getZ() - playerLoc.getZ();

        double yawRad = Math.toRadians(playerLoc.getYaw());
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);

        double rotatedX = deltaX * cosYaw + deltaZ * sinYaw;
        double rotatedZ = -deltaX * sinYaw + deltaZ * cosYaw;

        double relativeAngle = Math.toDegrees(Math.atan2(-rotatedX, rotatedZ));
        if (relativeAngle < 0) {
            relativeAngle += 360;
        }

        int clockDirection = (int) Math.round(relativeAngle / 30.0);
        if (clockDirection == 0 || clockDirection == 12) {
            clockDirection = 12;
        } else {
            clockDirection = clockDirection % 12;
        }

        return clockDirection + LanguageManager.getInstance().getString(player, "messages.clock-direction");
    }
}
