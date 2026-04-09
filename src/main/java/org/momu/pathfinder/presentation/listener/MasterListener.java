package org.momu.pathfinder.presentation.listener;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.momu.pathfinder.presentation.gui.GuiManager;
import org.momu.pathfinder.navigation.state.PlayerTracker;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import org.momu.pathfinder.runtime.TaskManager;
import org.momu.pathfinder.config.LanguageManager;
import org.momu.pathfinder.navigation.runtime.PathFinding;

public class MasterListener
        implements Listener {
    static final GuiManager guiManager = new GuiManager();
    private static final MenuListener menuListener = new MenuListener();

    public static GuiManager getGuiManager() {
        return guiManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player player)) {
            return;
        }
        org.bukkit.inventory.Inventory topInventory;
        try {
            topInventory = event.getClickedInventory();
            if (topInventory == null) {
                topInventory = player.getOpenInventory().getTopInventory();
            }
        } catch (Exception e) {
            try {
                topInventory = player.getOpenInventory().getTopInventory();
            } catch (Exception fallbackException) {
                return;
            }
        }

        if (!guiManager.isPluginGui(topInventory)) {
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        GuiManager.GuiType guiType = guiManager.getGuiType(event.getView().getTopInventory());

        switch (guiType) {
            case MAIN_MENU:
                menuListener.handleMainMenuClick(event, player);
                break;
            case PLAYER_NAVIGATION:
                menuListener.handlePlayerNavigationClick(event, player);
                break;
            default:
                break;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Inventory inventory = event.getView().getTopInventory();
        if (guiManager.isPluginGui(inventory)) {
            guiManager.unregisterGui(inventory);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        if (event.getNewGameMode() == org.bukkit.GameMode.SPECTATOR) {
            if (PlayerTracker.getInstance().isNavigating(player.getUniqueId())) {
                PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                player.sendMessage(ChatColor.YELLOW
                        + LanguageManager.getInstance().getString(player, "messages.spectator-mode"));
            }
        }
    }

    private final Map<UUID, List<UUID>> deathNavigationTargets = new HashMap<>();

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerTracker tracker = PlayerTracker.getInstance();

        if (tracker.isNavigating(player.getUniqueId())) {
            tracker.stopNavigation(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW
                    + LanguageManager.getInstance().getString(player, "messages.death-navi"));
        }

        List<UUID> navigatingToDeadPlayer = new ArrayList<>();
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            UUID navigatingPlayerUUID = onlinePlayer.getUniqueId();

            if (tracker.isNavigating(navigatingPlayerUUID) &&
                    player.getUniqueId().equals(tracker.getNavigationTarget(navigatingPlayerUUID))) {

                navigatingToDeadPlayer.add(navigatingPlayerUUID);

                tracker.stopNavigation(navigatingPlayerUUID);
                onlinePlayer.sendMessage(ChatColor.YELLOW
                        + LanguageManager.getInstance().getString(onlinePlayer, "messages.death-navi-2"));
            }
        }

        if (!navigatingToDeadPlayer.isEmpty()) {
            deathNavigationTargets.put(player.getUniqueId(), navigatingToDeadPlayer);
        }

        if (Boolean.TRUE.equals(player.getWorld().getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY))) {
            return;
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (deathNavigationTargets.containsKey(playerUUID)) {
            BukkitTask respawnTask = new BukkitRunnable() {
                @SuppressWarnings("deprecation")
                @Override
                public void run() {
                    if (!PathFinderPlugin.getInstance().isEnabled()) {
                        return;
                    }
                    List<UUID> navigatingPlayers = deathNavigationTargets.get(playerUUID);
                    if (navigatingPlayers != null) {
                        for (UUID navigatingPlayerUUID : navigatingPlayers) {
                            Player navigatingPlayer = Bukkit.getPlayer(navigatingPlayerUUID);
                            if (navigatingPlayer != null && navigatingPlayer.isOnline()) {
                                if (PlayerTracker.getInstance().setNavigationTarget(navigatingPlayerUUID, playerUUID)) {
                                    navigatingPlayer
                                            .sendMessage(ChatColor.GREEN
                                                    + LanguageManager.getInstance().getString(navigatingPlayer,
                                                            "messages.respawn-navi"));
                                    PathFinding.startPathfinding(navigatingPlayer);
                                }
                            }
                        }
                        deathNavigationTargets.remove(playerUUID);
                    }
                    TaskManager.removeActiveTask("respawn_" + playerUUID.toString());
                }
            }.runTaskLater(PathFinderPlugin.getInstance(), 20L); // 20 ticks = 1 second

            TaskManager.addActiveTask("respawn_" + playerUUID.toString(), respawnTask);
        }
    }

    @SuppressWarnings("deprecation")
    @org.bukkit.event.EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        if (PlayerTracker.getInstance().isNavigating(player.getUniqueId())) {
            PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + LanguageManager.getInstance().getString(player, "messages.navigation-stopped"));
        }
    }

    @SuppressWarnings("deprecation")
    @org.bukkit.event.EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        org.bukkit.World world = event.getWorld();
        for (org.bukkit.entity.Player p : world.getPlayers()) {
            if (PlayerTracker.getInstance().isNavigating(p.getUniqueId())) {
                PlayerTracker.getInstance().stopNavigation(p.getUniqueId());
                p.sendMessage(ChatColor.YELLOW + LanguageManager.getInstance().getString(p, "messages.navigation-stopped"));
            }
        }
    }

}
