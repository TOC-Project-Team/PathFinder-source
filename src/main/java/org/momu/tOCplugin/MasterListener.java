package org.momu.tOCplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.StructureType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.WorldUnloadEvent;

public class MasterListener
        implements Listener {
    private static final GuiManager guiManager = new GuiManager();
    private static final Map<UUID, List<?>> lastPlayerPaths = new HashMap<>();
    private static final Map<UUID, Location> lastPlayerPositions = new HashMap<>();
    private static final Map<UUID, Location> lastTargetPositions = new HashMap<>();

    // 任务管理器 - 跟踪所有异步任务
    private static final Map<String, BukkitTask> activeTasks = new HashMap<>();
    private static final Map<UUID, BukkitTask> playerNavigationTasks = new HashMap<>();
    private static final Map<UUID, BukkitTask> beaconSearchTasks = new HashMap<>();
    private static final Map<UUID, BukkitTask> beaconTimeoutTasks = new HashMap<>();
    private static final java.util.Map<UUID, Long> arrivalNotifyCooldownUntil = new java.util.concurrent.ConcurrentHashMap<>();

    public static GuiManager getGuiManager() {
        return guiManager;
    }

    // 任务管理方法
    public static void addActiveTask(String taskId, BukkitTask task) {
        activeTasks.put(taskId, task);
    }

    public static void removeActiveTask(String taskId) {
        BukkitTask task = activeTasks.remove(taskId);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    public static void addPlayerNavigationTask(UUID playerId, BukkitTask task) {
        // 取消之前的导航任务
        BukkitTask oldTask = playerNavigationTasks.put(playerId, task);
        if (oldTask != null && !oldTask.isCancelled()) {
            oldTask.cancel();
        }
    }

    public static void removePlayerNavigationTask(UUID playerId) {
        BukkitTask task = playerNavigationTasks.remove(playerId);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    public static void addBeaconSearchTask(UUID playerId, BukkitTask searchTask, BukkitTask timeoutTask) {
        // 取消之前的搜索任务
        BukkitTask oldSearchTask = beaconSearchTasks.put(playerId, searchTask);
        if (oldSearchTask != null && !oldSearchTask.isCancelled()) {
            oldSearchTask.cancel();
        }

        BukkitTask oldTimeoutTask = beaconTimeoutTasks.put(playerId, timeoutTask);
        if (oldTimeoutTask != null && !oldTimeoutTask.isCancelled()) {
            oldTimeoutTask.cancel();
        }
    }

    public static void removeBeaconSearchTask(UUID playerId) {
        BukkitTask searchTask = beaconSearchTasks.remove(playerId);
        if (searchTask != null && !searchTask.isCancelled()) {
            searchTask.cancel();
        }

        BukkitTask timeoutTask = beaconTimeoutTasks.remove(playerId);
        if (timeoutTask != null && !timeoutTask.isCancelled()) {
            timeoutTask.cancel();
        }
    }

    // 清理所有任务 - 用于插件关闭时
    public static void cancelAllTasks() {
        // 取消所有活动任务
        for (BukkitTask task : activeTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        activeTasks.clear();

        // 取消所有导航任务
        for (BukkitTask task : playerNavigationTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        playerNavigationTasks.clear();

        // 取消所有信标搜索任务
        for (BukkitTask task : beaconSearchTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        beaconSearchTasks.clear();

        // 取消所有信标超时任务
        for (BukkitTask task : beaconTimeoutTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        beaconTimeoutTasks.clear();
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
        if (!(humanEntity instanceof Player)) {
            return;
        }
        Player player = (Player) humanEntity;
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
                this.handleMainMenuClick(event, player);
                break;
            case PLAYER_NAVIGATION:
                this.handlePlayerNavigationClick(event, player);
                break;
            case WEATHER_CONTROL:
                this.handleWeatherMenuClick(event, player);
                break;
            case PLAYER_LIST:
                this.handlePlayerListClick(event, player);
                break;
            case PLAYER_DETAIL:
                this.handlePlayerDetailClick(event, player);
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
                player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
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
            player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
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
                onlinePlayer.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
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
                    modifyItemForDeath(item); // 修改盔甲和剑
                } else if (isGuiItem(item)) {
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

    private boolean isGuiItem(ItemStack item) {
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

    private void modifyItemForDeath(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable) {
            org.bukkit.inventory.meta.Damageable damageable = (org.bukkit.inventory.meta.Damageable) meta;
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
                                            .sendMessage(String.valueOf((Object) ChatColor.GREEN)
                                                    + LanguageManager.getInstance().getString(navigatingPlayer,
                                                            "messages.respawn-navi"));
                                    startPathfinding(navigatingPlayer);
                                }
                            }
                        }
                        // 清除记录
                        deathNavigationTargets.remove(playerUUID);
                    }
                    // 任务完成后从管理器中移除
                    removeActiveTask("respawn_" + playerUUID.toString());
                }
            }.runTaskLater(TOCpluginNative.getInstance(), 20L); // 20 ticks = 1 second

            // 将任务添加到管理器中
            addActiveTask("respawn_" + playerUUID.toString(), respawnTask);
        }
    }

    private void handleMainMenuClick(InventoryClickEvent event, Player player) {
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
                    this.handleNavigationToggle(player);
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
    private void handleNavigationToggle(Player player) {
        // 检查玩家是否有toc.admin权限
        if (!player.hasPermission("toc.admin")) {
            player.sendMessage(Component.text(LanguageManager.getInstance().getString(player, "messages.no-permission"))
                    .color(NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            guiManager.openMainMenu(player);
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
        guiManager.openMainMenu(player);
    }

    // 统一入口：公开包装以避免反射
    public static void startPathfindingPublic(Player player) {
        MasterListener ml = new MasterListener();
        ml.startPathfinding(player);
    }

    private void startPathfinding(final Player player) {
        new BukkitRunnable() {
            @SuppressWarnings("deprecation")
            public void run() {
                boolean isStronghold;
                Location finalTargetLoc;
                PlayerTracker tracker;
                boolean isBeaconNav = false;
                boolean isWaypointNav = false;
                block15: {
                    int verticalDistance;
                    block16: {
                        block14: {
                            tracker = PlayerTracker.getInstance();
                            finalTargetLoc = null;
                            isStronghold = false;

                            // 检查是否是信标导航
                            if (tracker.getBeaconNavigation(player.getUniqueId()) != null) {
                                finalTargetLoc = tracker.getBeaconNavigation(player.getUniqueId()).clone();
                                isBeaconNav = true;
                            }
                            // 检查是否是自定义路标导航
                            else if (tracker.getWaypointNavigation(player.getUniqueId()) != null) {
                                finalTargetLoc = tracker.getWaypointNavigation(player.getUniqueId()).clone();
                                isWaypointNav = true;
                            }
                            // 检查是否是末地要塞导航
                            else if (tracker.getStrongholdNavigation(player.getUniqueId()) != null) {
                                finalTargetLoc = tracker.getStrongholdNavigation(player.getUniqueId()).clone();
                                finalTargetLoc.setX((double) finalTargetLoc.getBlockX());
                                finalTargetLoc.setY((double) finalTargetLoc.getBlockY());
                                finalTargetLoc.setZ((double) finalTargetLoc.getBlockZ());
                                isStronghold = true;
                            } else {
                                if (tracker.getNavigationTarget(player.getUniqueId()) != null) {
                                    UUID targetUUID = tracker.getNavigationTarget(player.getUniqueId());
                                    if (targetUUID == null) {
                                        player.sendMessage(
                                                String.valueOf((Object) ChatColor.RED) + LanguageManager.getInstance()
                                                        .getString("messages.target-not-exist"));
                                        return;
                                    }
                                    Player target = Bukkit.getPlayer((UUID) targetUUID);
                                    if (target != null && target.isOnline()) {
                                        // 配置禁止导航隐身玩家时，若目标隐身则直接切断
                                        try {
                                            boolean allowInvisible = TOCpluginNative.getInstance().getConfig().getBoolean("allow_navigation_to_invisible", false);
                                            boolean isInvisible = target.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY) || target.isInvisible();
                                            if (!allowInvisible && isInvisible) {
                                                player.sendMessage(String.valueOf((Object) ChatColor.YELLOW) + LanguageManager.getInstance().getString(player, "messages.target-hidden"));
                                                tracker.stopNavigation(player.getUniqueId());
                                                return;
                                            }
                                        } catch (Exception ignore) {}
                                        boolean isTargetHidden = tracker.isLocationHidden(target.getUniqueId());
                                        boolean isAuthenticated = TOCpluginNative.getInstance()
                                                .isPlayerAuthenticated(player.getUniqueId());
                                        boolean canBypass = isAuthenticated;
                                        if (isTargetHidden && !canBypass) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                            .getInstance().getString("messages.target-hidden"));
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        finalTargetLoc = target.getLocation().clone();
                                        break block14;
                                    } else {
                                        player.sendMessage(
                                                String.valueOf((Object) ChatColor.RED) + LanguageManager.getInstance()
                                                        .getString("messages.target-offline"));
                                        return;
                                    }
                                }
                                player.sendMessage(String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                        .getInstance().getString(player, "messages.stronghold-searching-pathfinding"));
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        final Location strongholdLoc = player.getWorld().locateNearestStructure(
                                                player.getLocation(), StructureType.STRONGHOLD, 10000, false);
                                        if (strongholdLoc == null) {
                                            player.sendMessage(String.valueOf((Object) ChatColor.RED)
                                                    + LanguageManager.getInstance().getString(player,
                                                            "messages.stronghold-not-found-large-range"));
                                            guiManager.toggleParticleFeature(player.getUniqueId());
                                            if (TOCpluginNative.getInstance().isEnabled()) {
                                                guiManager.openMainMenu(player);
                                            }
                                            return;
                                        }
                                        Location strongholdLocation = strongholdLoc.clone();
                                        strongholdLocation.setX((double) strongholdLocation.getBlockX());
                                        strongholdLocation.setY((double) strongholdLocation.getBlockY());
                                        strongholdLocation.setZ((double) strongholdLocation.getBlockZ());
                                        PlayerTracker tracker = PlayerTracker.getInstance();
                                        tracker.setStrongholdNavigation(player.getUniqueId(), strongholdLocation);
                                        startPathfinding(player);
                                    }
                                }.runTask((Plugin) TOCpluginNative.getInstance()); // 改为在主线程执行
                                return;
                            }
                        }
                        verticalDistance = Math
                                .abs((int) (player.getLocation().getBlockY() - finalTargetLoc.getBlockY()));
                        int horizontalDistance = (int) Math.sqrt((double) (Math.pow(
                                (double) (player.getLocation().getBlockX() - finalTargetLoc.getBlockX()), (double) 2.0)
                                + Math.pow((double) (player.getLocation().getBlockZ() - finalTargetLoc.getBlockZ()),
                                        (double) 2.0)));
                        if (verticalDistance <= 30 || horizontalDistance >= 10)
                            break block15;
                        if (player.getLocation().getBlockY() <= finalTargetLoc.getBlockY())
                            break block16;
                        Location waterLanding = MasterListener.this.findWaterLanding(player.getLocation(),
                                finalTargetLoc);
                        if (waterLanding != null) {
                            Location safeSpot = MasterListener.this.findSafeLandingNearWater(waterLanding);
                            if (safeSpot != null) {
                                finalTargetLoc = safeSpot;
                                break block15;
                            } else {
                                finalTargetLoc = waterLanding.clone().add(0.0, 1.0, 0.0);
                            }
                            break block15;
                        }
                        break block15;
                    }
                    if (verticalDistance > 50) {
                        finalTargetLoc = new Location(finalTargetLoc.getWorld(), finalTargetLoc.getX(),
                                Math.max((double) finalTargetLoc.getY(), (double) (player.getLocation().getY() - 50.0)),
                                finalTargetLoc.getZ());
                    }
                }
                final Location finalTargetLocCopy = finalTargetLoc.clone();
                final boolean isStrongholdNav = isStronghold;
                final boolean isBeaconNavigation = isBeaconNav;
                final boolean isWaypointNavigation = isWaypointNav;

                removePlayerNavigationTask(player.getUniqueId());

                BukkitTask pathfindingTask = new BukkitRunnable() {
                    public void run() {
                        if (!TOCpluginNative.getInstance().isEnabled()) {
                            this.cancel();
                            return;
                        }
                        List<Pathfinder.Node> path;
                        Location targetLoc;
                        Location currentTarget;
                        block33: {
                            block32: {
                                if (!player.isOnline() || !guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                                    this.cancel();
                                    guiManager.removeParticleTask(player.getUniqueId());
                                    return;
                                }

                                // 检查导航者自己是否已死亡
                                if (player.isDead()) {
                                    this.cancel();
                                    guiManager.removeParticleTask(player.getUniqueId());
                                    tracker.stopNavigation(player.getUniqueId());
                                    return;
                                }
                                // 若为末地要塞/末地门导航，则动态读取最新的导航坐标，避免使用过期值
                                if (isStrongholdNav) {
                                    Location navTarget = tracker.getStrongholdNavigation(player.getUniqueId());
                                    if (navTarget != null) {
                                        currentTarget = navTarget.clone();
                                    } else {
                                        currentTarget = finalTargetLocCopy.clone();
                                    }
                                } else if (isBeaconNavigation) {
                                    Location navTarget = tracker.getBeaconNavigation(player.getUniqueId());
                                    if (navTarget != null) {
                                        currentTarget = navTarget.clone();
                                    } else {
                                        currentTarget = finalTargetLocCopy.clone();
                                    }
                                } else if (isWaypointNavigation) {
                                    Location navTarget = tracker.getWaypointNavigation(player.getUniqueId());
                                    if (navTarget != null) {
                                        currentTarget = navTarget.clone();
                                    } else {
                                        currentTarget = finalTargetLocCopy.clone();
                                    }
                                } else {
                                    currentTarget = finalTargetLocCopy.clone();
                                }
                                if (!isStrongholdNav && !isBeaconNavigation && !isWaypointNavigation) {
                                    UUID targetId = tracker.getNavigationTarget(player.getUniqueId());
                                    if (targetId == null) {
                                        player.sendMessage(String.valueOf((Object) ChatColor.RED)
                                                + LanguageManager.getInstance().getString(player,
                                                        "messages.target-not-exist"));
                                        this.cancel();
                                        guiManager.removeParticleTask(player.getUniqueId());
                                        return;
                                    }
                                    Player target = Bukkit.getPlayer((UUID) targetId);
                                    if (target != null && target.isOnline()) {
                                        boolean isTargetHidden = tracker.isLocationHidden(target.getUniqueId());
                                        boolean isAuthenticated = TOCpluginNative.getInstance()
                                                .isPlayerAuthenticated(player.getUniqueId());
                                        boolean canBypass = isAuthenticated;

                                        // 检查目标玩家是否处于旁观者模式
                                        if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                                            .getInstance().getString("messages.target-spectator"));
                                            this.cancel();
                                            guiManager.removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }

                                        // 检查目标玩家是否已死亡
                                        if (target.isDead()) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                                            .getInstance().getString("messages.target-dead"));
                                            this.cancel();
                                            guiManager.removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        // 检查双方是否在同一世界
                                        if (!player.getWorld().equals(target.getWorld())) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                            .getInstance().getString("messages.target-dimension"));
                                            this.cancel();
                                            guiManager.removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }

                                        if (isTargetHidden && !canBypass) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                            .getInstance().getString("messages.target-hidden-2"));
                                            this.cancel();
                                            guiManager.removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        currentTarget = target.getLocation().clone();
                                    } else {
                                        player.sendMessage(String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                .getInstance().getString("messages.target-offline"));
                                        this.cancel();
                                        guiManager.removeParticleTask(player.getUniqueId());
                                        return;
                                    }
                                }
                                if (isBeaconNavigation) {
                                    Block beaconBlock = currentTarget.getBlock();
                                    if (beaconBlock.getType() != Material.BEACON) {
                                        player.sendMessage(String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                .getInstance().getString("messages.target-beacon-dissappear"));
                                        this.cancel();
                                        guiManager.removeParticleTask(player.getUniqueId());
                                        tracker.stopNavigation(player.getUniqueId());
                                        return;
                                    }
                                }
                                double distance = player.getLocation().distance(currentTarget);
                                // 已删除自动导航到末地传送门框架的功能
                                if (isStrongholdNav) {
                                    targetLoc = currentTarget.clone();
                                    targetLoc.setX((double) targetLoc.getBlockX());
                                    targetLoc.setY((double) targetLoc.getBlockY());
                                    targetLoc.setZ((double) targetLoc.getBlockZ());
                                } else if (distance > Pathfinder.getMaxSearchRadius()) {
                                    Vector direction = currentTarget.toVector()
                                            .subtract(player.getLocation().toVector()).normalize();
                                    targetLoc = player.getLocation().clone()
                                            .add(direction.multiply(Pathfinder.getMaxSearchRadius()));
                                    targetLoc.setX((double) targetLoc.getBlockX());
                                    targetLoc.setY((double) targetLoc.getBlockY());
                                    targetLoc.setZ((double) targetLoc.getBlockZ());
                                } else {
                                    targetLoc = currentTarget.clone();
                                }
                                if (targetLoc.getBlock().getType().name().contains((CharSequence) "WATER")) {
                                    Location safeLanding = MasterListener.this.findSafeLandingNearWater(targetLoc);
                                    if (safeLanding != null) {
                                        targetLoc = safeLanding;
                                    } else {
                                        Location waterSurface = targetLoc.clone();
                                        int maxIterations = 50;
                                        int iterations = 0;
                                        while (waterSurface.getBlock().getType().name().contains((CharSequence) "WATER")
                                                && waterSurface.getBlockY() < waterSurface.getWorld().getMaxHeight() - 1
                                                && iterations < maxIterations) {
                                            waterSurface.add(0.0, 1.0, 0.0);
                                            iterations++;
                                        }
                                        if (!waterSurface.getBlock().getType().name()
                                                .contains((CharSequence) "WATER")) {
                                            targetLoc = waterSurface;
                                            if (waterSurface.clone().add(0.0, -1.0, 0.0).getBlock().getType().name()
                                                    .contains((CharSequence) "WATER")) {
                                                targetLoc.setY((double) waterSurface.getBlockY());
                                            }
                                        } else {
                                            // 减小搜索半径，避免过多方块检查
                                            int radius = 3; // 从5减小到3
                                            boolean found = false;
                                            // 简化嵌套循环，添加迭代计数
                                            int checkedLocations = 0;
                                            int maxLocationsToCheck = 50; // 最多检查50个位置

                                            block1: for (int r = 1; r <= radius && !found
                                                    && checkedLocations < maxLocationsToCheck; ++r) {
                                                int dx = -r;
                                                while (true) {
                                                    if (dx > r || found || checkedLocations >= maxLocationsToCheck)
                                                        continue block1;
                                                    for (int dz = -r; dz <= r && !found
                                                            && checkedLocations < maxLocationsToCheck; ++dz) {
                                                        checkedLocations++;
                                                        Location checkLoc;
                                                        if (Math.abs((int) dx) != r && Math.abs((int) dz) != r
                                                                || (checkLoc = targetLoc.clone().add((double) dx, 0.0,
                                                                        (double) dz)).getBlock().getType().name()
                                                                        .contains((CharSequence) "WATER")
                                                                || !MasterListener.this.isSafeLanding(checkLoc))
                                                            continue;
                                                        targetLoc = checkLoc;
                                                        found = true;
                                                        break;
                                                    }
                                                    ++dx;
                                                }
                                            }
                                        }
                                    }
                                }
                                Location playerBlockLoc = player.getLocation().clone();
                                playerBlockLoc.setX((double) playerBlockLoc.getBlockX());
                                playerBlockLoc.setY((double) playerBlockLoc.getBlockY());
                                playerBlockLoc.setZ((double) playerBlockLoc.getBlockZ());
                                UUID playerUUID = player.getUniqueId();
                                List<?> lastPath = lastPlayerPaths.get(playerUUID);
                                Location lastPlayerPos = lastPlayerPositions.get(playerUUID);
                                Location lastTargetPos = lastTargetPositions.get(playerUUID);
                                path = null;
                                if (Pathfinder.isPlayerInAir(player))
                                    break block32;
                                boolean needRecalculate = true;
                                if (Pathfinder.isPathCachingEnabled() && lastPath != null && !lastPath.isEmpty()
                                        && lastPlayerPos != null && lastTargetPos != null) {
                                    double playerMoveDist = playerBlockLoc.distance(lastPlayerPos);
                                    double targetMoveDist = targetLoc.distance(lastTargetPos);
                                    if (playerMoveDist < 3.0 && targetMoveDist < 0.1) {
                                        boolean nearPath = false;
                                        double minDistToPath = Double.MAX_VALUE;
                                        for (Object nodeObj : lastPath) {
                                            Pathfinder.Node node = (Pathfinder.Node) nodeObj;
                                            double dist = playerBlockLoc.distance(node.location);
                                            if (dist < minDistToPath) {
                                                minDistToPath = dist;
                                            }
                                            if (!(dist < 2.0))
                                                continue;
                                            nearPath = true;
                                            break;
                                        }
                                        if (nearPath || minDistToPath < 3.0) {
                                            int nearestNodeIndex = 0;
                                            double nearestDist = Double.MAX_VALUE;
                                            for (int i = 0; i < lastPath.size(); ++i) {
                                                Pathfinder.Node node = (Pathfinder.Node) lastPath.get(i);
                                                double dist = playerBlockLoc.distance(node.location);
                                                if (!(dist < nearestDist))
                                                    continue;
                                                nearestDist = dist;
                                                nearestNodeIndex = i;
                                            }
                                            if (nearestNodeIndex < lastPath.size() - 1) {
                                                path = new ArrayList<Pathfinder.Node>();
                                                for (int j = nearestNodeIndex; j < lastPath.size(); j++) {
                                                    path.add((Pathfinder.Node) lastPath.get(j));
                                                }
                                                needRecalculate = false;
                                            }
                                        }
                                    }
                                }
                                if (needRecalculate) {
                                    path = Pathfinder.findPath(playerBlockLoc, targetLoc, player);
                                    if (Pathfinder.isPathCachingEnabled() && path != null && !path.isEmpty()) {
                                        List<Pathfinder.Node> pathCopy = new ArrayList<>();
                                        for (Pathfinder.Node node : path) {
                                            pathCopy.add(node);
                                        }
                                        lastPlayerPaths.put(playerUUID, pathCopy);
                                        lastPlayerPositions.put(playerUUID, playerBlockLoc.clone());
                                        lastTargetPositions.put(playerUUID, targetLoc.clone());
                                    }
                                }
                                break block33;
                            }
                            path = null;
                        }
                        final Location finalTargetLoc = targetLoc;
                        final Location finalCurrentTarget = currentTarget;
                        final List<?> finalPath = path;
                        new BukkitRunnable() {
                            public void run() {
                                if (!TOCpluginNative.getInstance().isEnabled()) {
                                    return;
                                }
                                if (!guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                                    return;
                                }

                                // 检查导航者自己是否已死亡
                                if (player.isDead()) {
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            if (!TOCpluginNative.getInstance().isEnabled()) {
                                                return;
                                            }
                                            PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                                            // 不发送消息，因为玩家死亡后可能看不到
                                        }
                                    }.runTask((Plugin) TOCpluginNative.getInstance());
                                    return;
                                }

                                // 检查目标玩家是否已死亡（仅针对玩家导航）
                                if (!isStrongholdNav && !isBeaconNavigation && !isWaypointNavigation) {
                                    UUID targetId = tracker.getNavigationTarget(player.getUniqueId());
                                    if (targetId != null) {
                                        Player target = Bukkit.getPlayer(targetId);
                                        if (target != null && target.isDead()) {
                                            new BukkitRunnable() {
                                                @Override
                                                public void run() {
                                                    PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                                                    player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                                                            + LanguageManager.getInstance()
                                                                    .getString("messages.target-dead"));
                                                }
                                            }.runTask((Plugin) TOCpluginNative.getInstance());
                                            return;
                                        }
                                    }
                                }

                                // 检测玩家是否为旁观者模式，如果是则关闭导航
                                if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                                            .getInstance().getString("messages.spectator-mode"));
                                        }
                                    }.runTask((Plugin) TOCpluginNative.getInstance());
                                    return;
                                }

                                MasterListener.this.updateNavigationInfo(player, finalTargetLoc, finalCurrentTarget);

                                // 已删除自动导航到末地传送门框架的功能
                                Location realTarget = finalCurrentTarget;

                                double distanceToRealTarget = player.getLocation().distance(realTarget);

                                boolean shouldCancel = false;
                                // 统一取消导航的距离判断
                                if (distanceToRealTarget <= 3.0) {
                                    shouldCancel = true;
                                }

                                if (shouldCancel) {
                                    final java.util.UUID pid = player.getUniqueId();
                                    final long now = System.currentTimeMillis();
                                    final long[] prev = new long[]{0L};
                                    final long[] newExpiry = new long[]{0L};
                                    arrivalNotifyCooldownUntil.compute(pid, (k, oldVal) -> {
                                        long prevVal = (oldVal == null ? 0L : oldVal.longValue());
                                        prev[0] = prevVal;
                                        long expiry = (prevVal <= now) ? (now + 3000L) : prevVal; // 3秒冷却
                                        newExpiry[0] = expiry;
                                        return expiry;
                                    });
                                    boolean canNotify = prev[0] <= now;
                                    this.cancel();
                                    guiManager.removeParticleTask(pid);
                                    PlayerTracker.getInstance().stopNavigation(pid);
                                    if (canNotify) {
                                        new BukkitRunnable() {
                                            @Override
                                            public void run() {
                                                player.sendMessage(String.valueOf((Object) ChatColor.GREEN)
                                                        + LanguageManager.getInstance()
                                                                .getString(player, "messages.arrive-destination"));
                                                player.playSound(player.getLocation(),
                                                        Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
                                            }
                                        }.runTask((Plugin) TOCpluginNative.getInstance());
                                        // 3秒后清理冷却（若期间未被更新）
                                        long expirySnapshot = newExpiry[0];
                                        new BukkitRunnable() {
                                            @Override
                                            public void run() {
                                                arrivalNotifyCooldownUntil.compute(pid, (k, v) -> (v != null && v == expirySnapshot) ? null : v);
                                            }
                                        }.runTaskLater((Plugin) TOCpluginNative.getInstance(), 60L);
                                    }
                                    return;
                                }

                                if (finalPath != null && !finalPath.isEmpty()) {
                                    int maxDrawNodes = Math.min((int) finalPath.size(),
                                            (int) Pathfinder.MAX_PARTICLE_DISTANCE);
                                    for (int i = 0; i < maxDrawNodes - 1; ++i) {
                                        Pathfinder.Node currentNode = (Pathfinder.Node) finalPath.get(i);
                                        Pathfinder.Node nextNode = (Pathfinder.Node) finalPath.get(i + 1);

                                        // 如果当前节点是跳跃类型，寻找跳跃序列的终点
                                        if (currentNode.moveType == 5) { // MOVE_BLOCK_JUMP
                                            // 寻找跳跃序列的终点
                                            int jumpEndIndex = i + 1;
                                            while (jumpEndIndex < maxDrawNodes - 1 &&
                                                    ((Pathfinder.Node) finalPath.get(jumpEndIndex)).moveType == 5) {
                                                jumpEndIndex++;
                                            }

                                            // 生成从跳跃起点到跳跃终点的抛物线
                                            Pathfinder.Node jumpStartNode = currentNode;
                                            Pathfinder.Node jumpEndNode = (Pathfinder.Node) finalPath.get(jumpEndIndex);

                                            Location jumpStart = new Location(
                                                    jumpStartNode.location.getWorld(),
                                                    jumpStartNode.location.getBlockX() + 0.5,
                                                    jumpStartNode.location.getBlockY() + 0.5,
                                                    jumpStartNode.location.getBlockZ() + 0.5);
                                            Location jumpEnd = new Location(
                                                    jumpEndNode.location.getWorld(),
                                                    jumpEndNode.location.getBlockX() + 0.5,
                                                    jumpEndNode.location.getBlockY() + 0.5,
                                                    jumpEndNode.location.getBlockZ() + 0.5);

                                            // 生成跳跃抛物线粒子
                                            MasterListener.this.generateJumpParabola(player, jumpStart, jumpEnd);

                                            // 跳过已处理的跳跃节点
                                            i = jumpEndIndex - 1;
                                            continue;
                                        }
                                        // 精确计算粒子的起点和终点位置，确保它们在方块的中心
                                        Location start = new Location(
                                                currentNode.location.getWorld(),
                                                currentNode.location.getBlockX() + 0.5,
                                                currentNode.location.getBlockY() + 0.5,
                                                currentNode.location.getBlockZ() + 0.5);
                                        Location end = new Location(
                                                nextNode.location.getWorld(),
                                                nextNode.location.getBlockX() + 0.5,
                                                nextNode.location.getBlockY() + 0.5,
                                                nextNode.location.getBlockZ() + 0.5);
                                        double distance = start.distance(end);
                                        Particle particleType = Particle.DUST;
                                        Color particleColor = Color.WHITE;
                                        float particleSize = Pathfinder.PARTICLE_SIZE;
                                        switch (currentNode.moveType) {
                                            case 3: {
                                                particleColor = Color.YELLOW;
                                                break;
                                            }
                                            case 4: {
                                                particleColor = Color.PURPLE;
                                                break;
                                            }
                                            case 1:
                                            case 2: {
                                                particleColor = Color.AQUA;
                                                break;
                                            }
                                            case 5: { // MOVE_BLOCK_JUMP
                                                particleColor = Color.AQUA;
                                                break;
                                            }
                                        }
                                        if (currentNode.toBreak) {
                                            Location blockLoc = currentNode.location.clone();
                                            MasterListener.this.drawBlockOutline(player, blockLoc, Color.RED, 0.2);
                                            MasterListener.this.drawBlockOutline(player,
                                                    blockLoc.clone().add(0.0, 1.0, 0.0), Color.RED, 0.2);
                                            MasterListener.this.drawBlockOutline(player,
                                                    blockLoc.clone().add(0.0, -1.0, 0.0), Color.RED, 0.2); // 标记下方方块
                                        } else {
                                            Block block = currentNode.location.getBlock();
                                            if (Pathfinder.isLadder(block)) {
                                                Location blockLoc = currentNode.location.clone();
                                                MasterListener.this.drawBlockOutline(player, blockLoc, Color.GREEN,
                                                        0.2);
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isLadder(blockAbove)) {
                                                    MasterListener.this.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                            } else if (Pathfinder.isScaffolding(block)) {
                                                Location blockLoc = currentNode.location.clone();
                                                MasterListener.this.drawBlockOutline(player, blockLoc, Color.ORANGE,
                                                        0.2);
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isScaffolding(blockAbove)) {
                                                    MasterListener.this.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.ORANGE, 0.2);
                                                }
                                            } else if (Pathfinder.isTrapdoor(block)) {
                                                MasterListener.this.drawBlockOutline(player,
                                                        currentNode.location.clone(), Color.GREEN, 0.2);
                                            } else if (currentNode.isFenceGate) {
                                                // 栅栏门绘制绿色框
                                                Location blockLoc = currentNode.location.clone();
                                                MasterListener.this.drawBlockOutline(player, blockLoc, Color.GREEN,
                                                        0.2);
                                                // 检查上下方是否也有栅栏门
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isFenceGate(blockAbove)) {
                                                    MasterListener.this.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                                Block blockBelow = block.getRelative(0, -1, 0);
                                                if (Pathfinder.isFenceGate(blockBelow)) {
                                                    MasterListener.this.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, -1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                            } else if (currentNode.isDoor) {
                                                // 门绘制绿色框（无论开关状态）
                                                Location blockLoc = currentNode.location.clone();
                                                MasterListener.this.drawBlockOutline(player, blockLoc, Color.GREEN,
                                                        0.2);
                                                // 检查上下方是否也有门
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isDoor(blockAbove)) {
                                                    MasterListener.this.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                                Block blockBelow = block.getRelative(0, -1, 0);
                                                if (Pathfinder.isDoor(blockBelow)) {
                                                    MasterListener.this.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, -1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                            } else if (currentNode.isBanner) {
                                                // 旗帜不绘制任何轮廓，因为它们被视为空气方块
                                                // 这里故意留空，让旗帜路径完全透明
                                            }
                                        }
                                        if (!(distance > Pathfinder.PARTICLE_SPACING)) {
                                            // 短距离段：直接在终点生成一个加粗粒子
                                            player.spawnParticle(particleType, end, 1, 0.0, 0.0, 0.0, 0.0,
                                                    (Object) new Particle.DustOptions(particleColor,
                                                            particleSize * 1.5f));
                                            continue;
                                        }

                                        // 普通移动使用直线粒子效果（跳跃已在上面单独处理）
                                        {
                                            // 普通移动使用直线粒子效果
                                            double totalDistance = start.distance(end);
                                            int steps = Math.max(
                                                    (int) Math.ceil(totalDistance / Pathfinder.PARTICLE_SPACING), 1);

                                            // 先在起点生成一个粒子，确保起点被标记
                                            player.spawnParticle(particleType, start, 1, 0.0, 0.0, 0.0, 0.0,
                                                    (Object) new Particle.DustOptions(particleColor,
                                                            particleSize * 1.5f));

                                            // 生成中间的连线粒子，确保最后一个粒子正好在终点
                                            for (int step = 1; step < steps; ++step) {
                                                double ratio = (double) step / (double) steps;
                                                if (ratio >= 1.0)
                                                    break;
                                                Location intermediateLoc = start.clone().add(
                                                        (end.getX() - start.getX()) * ratio,
                                                        (end.getY() - start.getY()) * ratio,
                                                        (end.getZ() - start.getZ()) * ratio);
                                                Location particleLoc = MasterListener.this
                                                        .adjustParticleLocationForWater(intermediateLoc);
                                                float size = particleSize * 1.2f;
                                                player.spawnParticle(particleType, particleLoc, 1, 0.0, 0.0, 0.0, 0.0,
                                                        (Object) new Particle.DustOptions(particleColor, size));
                                            }
                                            // 非抛物线情况下才生成终点粒子，抛物线已经在循环中包含了终点
                                            player.spawnParticle(particleType, end, 1, 0.0, 0.0, 0.0, 0.0,
                                                    (Object) new Particle.DustOptions(particleColor,
                                                            particleSize * 1.5f));
                                        }
                                    }

                                    // 单独处理最后一个节点的粒子
                                    if (finalPath.size() > 0) {
                                        Pathfinder.Node lastNode = (Pathfinder.Node) finalPath
                                                .get(finalPath.size() - 1);
                                        Location finalLocation = new Location(
                                                lastNode.location.getWorld(),
                                                lastNode.location.getBlockX() + 0.5,
                                                lastNode.location.getBlockY() + 0.5,
                                                lastNode.location.getBlockZ() + 0.5);

                                        // 终点粒子统一使用白色，便于区分终点位置
                                        Color particleColor = Color.WHITE;

                                        // 在最终目标位置生成一个特别大的白色终点粒子
                                        player.spawnParticle(Particle.DUST, finalLocation, 1, 0.0, 0.0, 0.0, 0.0,
                                                (Object) new Particle.DustOptions(particleColor, 2.0f));
                                    }

                                    if (!isStrongholdNav) {
                                        // empty if block
                                    }
                                }
                            }
                        }.runTask((Plugin) TOCpluginNative.getInstance());
                    }
                }.runTaskTimerAsynchronously((Plugin) TOCpluginNative.getInstance(), 0L, Pathfinder.PATH_REFRESH_TICKS);
                if (TOCpluginNative.getInstance().isEnabled()) {
                    guiManager.addParticleTask(player.getUniqueId(), pathfindingTask);
                    // 将任务添加到导航任务管理器中
                    addPlayerNavigationTask(player.getUniqueId(), pathfindingTask);
                }
            }
        }.runTask((Plugin) TOCpluginNative.getInstance());
    }

    private Location adjustParticleLocationForWater(Location location) {
        if (location == null) {
            return location;
        }
        Location adjustedLoc = location.clone();
        Block currentBlock = adjustedLoc.getBlock();
        if (currentBlock.getType().name().contains((CharSequence) "WATER")) {
            while (adjustedLoc.getBlockY() < adjustedLoc.getWorld().getMaxHeight() - 1) {
                adjustedLoc.add(0.0, 1.0, 0.0);
                Block checkBlock = adjustedLoc.getBlock();
                if (checkBlock.getType().name().contains((CharSequence) "WATER") || !checkBlock.isPassable())
                    continue;
                adjustedLoc.add(0.0, -0.3, 0.0);
                break;
            }
        }
        return adjustedLoc;
    }

    @SuppressWarnings("deprecation")
    private void updateNavigationInfo(Player player, Location intermediateTarget, Location finalTarget) {
        Player targetPlayer;
        Location playerLoc = player.getLocation().clone();
        double distance = playerLoc.distance(finalTarget);
        double horizontalDistance = Math
                .sqrt((double) (Math.pow((double) (playerLoc.getX() - finalTarget.getX()), (double) 2.0)
                        + Math.pow((double) (playerLoc.getZ() - finalTarget.getZ()), (double) 2.0)));
        double verticalDistance = finalTarget.getY() - playerLoc.getY();
        Vector direction = finalTarget.toVector().subtract(playerLoc.toVector());
        float playerYaw = playerLoc.getYaw();
        double angle = Math.toDegrees((double) Math.atan2((double) (-direction.getX()), (double) direction.getZ()));
        double relativeAngle = (angle - (double) playerYaw + 360.0) % 360.0;
        String directionString = this.getDirectionString(player, relativeAngle);
        String verticalDirection = verticalDistance > 0.0
                ? LanguageManager.getInstance().getString(player, "messages.up")
                : LanguageManager.getInstance().getString(player, "messages.down");
        String targetName = LanguageManager.getInstance().getString(player, "messages.unknown-target");
        PlayerTracker tracker = PlayerTracker.getInstance();

        // 检查是否是信标导航
        if (tracker.getBeaconNavigation(player.getUniqueId()) != null) {
            targetName = LanguageManager.getInstance().getString(player, "messages.beacon-block");
        }
        // 检查是否是末地要塞导航
        else if (tracker.getStrongholdNavigation(player.getUniqueId()) != null) {
            final Location strongholdLoc = tracker.getStrongholdNavigation(player.getUniqueId());
            if (strongholdLoc.getBlock().getType() != Material.END_PORTAL_FRAME
                    && player.getLocation().distance(strongholdLoc) < 300.0) {
                findNearestPortalFrameAsync(strongholdLoc, 150, portalFrame -> {
                    if (!TOCpluginNative.getInstance().isEnabled()) {
                        return;
                    }
                    if (portalFrame != null) {
                        PlayerTracker.getInstance().setStrongholdNavigation(player.getUniqueId(), portalFrame);
                        player.sendMessage("§e"
                                + LanguageManager.getInstance().getString(player, "messages.end-portal-frame-coords",
                                        portalFrame.getBlockX(), portalFrame.getBlockY(), portalFrame.getBlockZ()));
                        MasterListener.this.updateActionBarWithNewTarget(player, portalFrame,
                                LanguageManager.getInstance().getString(player, "messages.end-portal-frame"));
                    } else {
                        player.sendMessage("§c" + LanguageManager.getInstance().getString(player,
                                "messages.end-portal-frame-not-found"));
                        // 取消导航
                        PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                    }
                });
                return;
            } else {
                targetName = strongholdLoc.getBlock().getType() == Material.END_PORTAL_FRAME
                        ? LanguageManager.getInstance().getString(player, "messages.end-portal-frame")
                        : LanguageManager.getInstance().getString(player, "messages.stronghold-name");
            }
        }
        // 检查是否是自定义路标导航
        else if (tracker.getWaypointNavigation(player.getUniqueId()) != null) {
            String wpName = tracker.getWaypointName(player.getUniqueId());
            targetName = (wpName != null && !wpName.isEmpty()) ? wpName
                    : LanguageManager.getInstance().getString(player, "messages.unknown-target");
        }
        // 检查是否是玩家导航
        else if (tracker.getNavigationTarget(player.getUniqueId()) != null && (targetPlayer = Bukkit
                .getPlayer((UUID) (tracker.getNavigationTarget(player.getUniqueId())))) != null) {
            targetName = targetPlayer.getName();
        }

        if (PlayerTracker.getInstance().isActionBarSuppressed(player.getUniqueId())) {
            return;
        }
        String message = LanguageManager.getInstance().getString(player, "messages.action-bar",
                targetName, String.format("%.1f", distance), directionString, (int) relativeAngle + "°",
                String.format("%.1f", horizontalDistance), String.format("%.1f", Math.abs(verticalDistance)),
                verticalDirection);

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                (BaseComponent) new net.md_5.bungee.api.chat.TextComponent(message));
    }

    @SuppressWarnings("deprecation")
    private void updateActionBarWithNewTarget(Player player, Location newTarget, String newTargetName) {
        Location playerLoc = player.getLocation().clone();
        double distance = playerLoc.distance(newTarget);
        double horizontalDistance = Math.sqrt(Math.pow(playerLoc.getX() - newTarget.getX(), 2.0)
                + Math.pow(playerLoc.getZ() - newTarget.getZ(), 2.0));
        double verticalDistance = newTarget.getY() - playerLoc.getY();
        Vector direction = newTarget.toVector().subtract(playerLoc.toVector());
        float playerYaw = playerLoc.getYaw();
        double angle = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        double relativeAngle = (angle - playerYaw + 360.0) % 360.0;
        String directionString = getDirectionString(player, relativeAngle);
        String verticalDirection = verticalDistance > 0.0
                ? LanguageManager.getInstance().getString(player, "messages.up")
                : LanguageManager.getInstance().getString(player, "messages.down");
        if (PlayerTracker.getInstance().isActionBarSuppressed(player.getUniqueId())) {
            return;
        }
        String message = LanguageManager.getInstance().getString(player, "messages.action-bar",
                newTargetName, String.format("%.1f", distance), directionString, (int) relativeAngle + "°",
                String.format("%.1f", horizontalDistance), String.format("%.1f", Math.abs(verticalDistance)),
                verticalDirection);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
    }

    private void drawBlockOutline(Player player, Location blockLoc, Color color, double particleDensity) {
        int blockX = blockLoc.getBlockX();
        int blockY = blockLoc.getBlockY();
        int blockZ = blockLoc.getBlockZ();

        double baseDensity = Pathfinder.PARTICLE_SPACING;
        double actualDensity = color.equals((Object) Color.RED) ? baseDensity * 2.5 : baseDensity;
        int edgeCount = color.equals((Object) Color.RED) ? 8 : 12;

        for (int j = 0; j < edgeCount; ++j) {
            Location edgeEnd;
            Location edgeStart;
            if (color.equals((Object) Color.RED) && j >= 8)
                continue;

            if (j < 4) { // 下方边缘
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY, blockZ + (j / 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j < 2 ? 1 - j % 2 : j % 2), blockY,
                        blockZ + (j / 2));
            } else if (j < 8) { // 上方边缘
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + 1, blockZ + (j / 2 % 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j < 6 ? 1 - j % 2 : j % 2), blockY + 1,
                        blockZ + (j / 2 % 2));
            } else { // 垂直边缘
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + (j / 2 % 2),
                        blockZ + (j / 2 % 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + (1 - j / 2 % 2),
                        blockZ + (j / 2 % 2));
            }

            Vector direction = edgeEnd.toVector().subtract(edgeStart.toVector());
            for (double d = 0.0; d < 1.0; d += actualDensity) {
                Location particleLoc = edgeStart.clone().add(direction.clone().multiply(d));
                player.spawnParticle(Particle.DUST, particleLoc, 1, 0.0, 0.0, 0.0, 0.0,
                        (Object) new Particle.DustOptions(color, 0.9f));
            }
        }
    }

    /**
     * 同步查找最近的末地门框架
     * 
     * @param center 中心位置
     * @param radius 搜索半径
     * @return 最近的末地门框架位置，如果未找到则返回null
     * @deprecated 使用异步版本
     *             {@link #findNearestPortalFrameAsync(Location, int, Consumer)}
     */
    @Deprecated
    private Location findNearestPortalFrame(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        Location nearestFrame = null;
        double minDistance = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; ++x) {
            for (int y = -radius; y <= radius; ++y) {
                for (int z = -radius; z <= radius; ++z) {
                    Block block = world.getBlockAt(center.getBlockX() + x, center.getBlockY() + y,
                            center.getBlockZ() + z);
                    if (block.getType() != Material.END_PORTAL_FRAME)
                        continue;

                    // 计算与中心点的距离
                    Location frameLoc = block.getLocation();
                    double distance = frameLoc.distance(center);

                    // 更新最近的末地门框架
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearestFrame = frameLoc;
                    }
                }
            }
        }

        return nearestFrame;
    }

    /**
     * 异步查找最近的末地门框架
     * 
     * @param center   中心位置
     * @param radius   搜索半径
     * @param callback 回调函数，接收找到的末地门框架位置
     */
    private void findNearestPortalFrameAsync(Location center, int radius, Consumer<Location> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 检查插件是否仍然启用
                if (!TOCpluginNative.getInstance().isEnabled()) {
                    callback.accept(null);
                    return;
                }

                // 在异步线程中执行搜索
                Location nearestFrame = findNearestPortalFrame(center, radius);

                // 在主线程中执行回调
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        callback.accept(nearestFrame);
                    }
                }.runTask(TOCpluginNative.getInstance());
            }
        }.runTaskAsynchronously(TOCpluginNative.getInstance());
    }

    private String getDirectionString(Player player, double angle) {
        if ((angle = (angle % 360.0 + 360.0) % 360.0) >= 337.5 || angle < 22.5) {
            return LanguageManager.getInstance().getString(player, "messages.front");
        }
        if (angle < 67.5) {
            return LanguageManager.getInstance().getString(player, "messages.front-right");
        }
        if (angle < 112.5) {
            return LanguageManager.getInstance().getString(player, "messages.right");
        }
        if (angle < 157.5) {
            return LanguageManager.getInstance().getString(player, "messages.right-rear");
        }
        if (angle < 202.5) {
            return LanguageManager.getInstance().getString(player, "messages.back");
        }
        if (angle < 247.5) {
            return LanguageManager.getInstance().getString(player, "messages.left-rear");
        }
        if (angle < 292.5) {
            return LanguageManager.getInstance().getString(player, "messages.left");
        }
        if (angle < 337.5) {
            return LanguageManager.getInstance().getString(player, "messages.left-front");
        }
        return "";
    }

    private void handleWeatherMenuClick(InventoryClickEvent event, Player player) {
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
                guiManager.openMainMenu(player);
                break;
            }
            default: {
                throw new IllegalArgumentException("Unexpected value: " + String.valueOf((Object) clicked.getType()));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void handlePlayerDetailClick(InventoryClickEvent event, Player player) {
        int dashIndex;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        String title = event.getView().getTitle();
        String cleanTitle = ChatColor.stripColor((String) title);
        String playerDetailPrefix = "玩家详情 - ";
        String targetName = null;
        if (cleanTitle.startsWith(playerDetailPrefix)) {
            targetName = cleanTitle.substring(playerDetailPrefix.length());
        } else if (cleanTitle.contains((CharSequence) "玩家详情") && cleanTitle.contains((CharSequence) " - ")
                && (dashIndex = cleanTitle.lastIndexOf(" - ")) != -1 && dashIndex + 3 < cleanTitle.length()) {
            targetName = cleanTitle.substring(dashIndex + 3);
        }
        if (targetName == null || targetName.trim().isEmpty()) {
            return;
        }
        Player target = player.getServer().getPlayer(targetName.trim());
        if (target == null) {
            player.sendMessage(String.valueOf((Object) ChatColor.RED) + "玩家已离线");
            return;
        }
        switch (clicked.getType()) {
            case ENDER_PEARL: {
                // 检查全局导航开关和玩家权限
                if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                    player.sendMessage(String.valueOf((Object) ChatColor.RED)
                            + LanguageManager.getInstance().getString(player, "messages.global-navigation-disabled"));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }

                boolean isTargetHidden = PlayerTracker.getInstance().isLocationHidden(target.getUniqueId());
                boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(player.getUniqueId());
                boolean canBypass = isAuthenticated;

                // 检查目标玩家是否处于旁观者模式
                if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                            + LanguageManager.getInstance().getString("messages.cannot-navigate-spectator"));
                    return;
                }

                if (isTargetHidden && !canBypass) {
                    player.sendMessage(String.valueOf((Object) ChatColor.RED)
                            + LanguageManager.getInstance().getString("messages.target-hidden"));
                    return;
                }
                boolean isNewTarget = PlayerTracker.getInstance().setNavigationTarget(player.getUniqueId(),
                        target.getUniqueId());
                if (!isNewTarget) {
                    player.sendMessage(
                            String.valueOf((Object) ChatColor.YELLOW) + LanguageManager.getInstance()
                                    .getString("messages.already-navigating-to-player", target.getName()));
                    return;
                }
                player.sendMessage(String.valueOf((Object) ChatColor.GREEN)
                        + LanguageManager.getInstance().getString("messages.set-navigation-target")
                        + target.getName()
                        + (isTargetHidden && canBypass ? " (已绕过位置隐私保护)" : ""));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                if (!guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                    guiManager.toggleParticleFeature(player.getUniqueId());
                }
                this.startPathfinding(player);
                guiManager.openPlayerNavigationMenu(player);
                break;
            }
            default: {
                throw new IllegalArgumentException("Unexpected value: " + String.valueOf((Object) clicked.getType()));
            }
        }
    }

    private void handlePlayerListClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        if (clicked.getType() == Material.BARRIER) {
            guiManager.openMainMenu(player);
            return;
        }
    }

    @SuppressWarnings("deprecation")
    private void handlePlayerNavigationClick(InventoryClickEvent event, Player player) {
        ItemStack clicked = event.getCurrentItem();
        int slot = event.getSlot();
        switch (clicked.getType()) {
            case ARROW: {
                // 分页按钮处理
                if (slot == 45) {
                    // 上一页按钮
                    Integer currentPage = guiManager.getCurrentPage(player.getUniqueId());
                    if (currentPage != null && currentPage > 0) {
                        guiManager.openPlayerNavigationMenu(player, currentPage - 1);
                    }
                } else if (slot == 53) {
                    // 下一页按钮
                    Integer currentPage = guiManager.getCurrentPage(player.getUniqueId());
                    if (currentPage != null) {
                        guiManager.openPlayerNavigationMenu(player, currentPage + 1);
                    }
                }
                break;
            }
            case COMPASS: {
                guiManager.openPlayerNavigationMenu(player);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
                break;
            }
            case ENDER_PEARL: {
                PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                        + LanguageManager.getInstance().getString(player, "messages.navigation-stopped"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.5f);
                guiManager.openPlayerNavigationMenu(player);
                break;
            }
            case PLAYER_HEAD: {
                ItemMeta meta = clicked.getItemMeta();
                if (!(meta instanceof SkullMeta))
                    break;
                String targetName = ChatColor.stripColor((String) meta.getDisplayName());
                Player target = player.getServer().getPlayer(targetName);
                if (target != null && target.isOnline()) {
                    boolean isTargetHidden = PlayerTracker.getInstance().isLocationHidden(target.getUniqueId());
                    boolean isAuthenticated = TOCpluginNative.getInstance().isPlayerAuthenticated(player.getUniqueId());
                    boolean canBypass = isAuthenticated;

                    // 检查目标玩家是否处于旁观者模式
                    if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                        player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                                + LanguageManager.getInstance().getString(player,
                                        "messages.cannot-navigate-spectator"));
                        guiManager.openPlayerNavigationMenu(player);
                        return;
                    }

                    // 如果目标玩家开启了位置隐私保护且当前玩家没有绕过权限
                    if (isTargetHidden && !canBypass) {
                        player.sendMessage(String.valueOf((Object) ChatColor.RED)
                                + LanguageManager.getInstance().getString(player, "messages.target-hidden"));
                        guiManager.openPlayerNavigationMenu(player);
                        return;
                    }
                    // 检查全局导航开关和玩家权限
                    if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                        player.sendMessage(String.valueOf((Object) ChatColor.RED)
                                + LanguageManager.getInstance().getString(player,
                                        "messages.global-navigation-disabled"));
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        guiManager.openPlayerNavigationMenu(player);
                        return;
                    }

                    boolean isNewTarget = PlayerTracker.getInstance().setNavigationTarget(player.getUniqueId(),
                            target.getUniqueId());
                    if (!isNewTarget) {
                        player.sendMessage(
                                String.valueOf((Object) ChatColor.YELLOW) + LanguageManager.getInstance()
                                        .getString("messages.already-navigating-to-player", target.getName()));
                        guiManager.openPlayerNavigationMenu(player);
                        return;
                    }
                    player.sendMessage(String.valueOf((Object) ChatColor.GREEN)
                            + LanguageManager.getInstance().getString(player, "messages.set-navigation-target")
                            + target.getName()
                            + (isTargetHidden && canBypass
                                    ? LanguageManager.getInstance().getString(player,
                                            "messages.bypass-location-privacy")
                                    : ""));
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    if (!guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                        guiManager.toggleParticleFeature(player.getUniqueId());
                    }
                    this.startPathfinding(player);
                    guiManager.openPlayerNavigationMenu(player);
                    break;
                }
                player.sendMessage(String.valueOf((Object) ChatColor.RED)
                        + LanguageManager.getInstance().getString(player, "messages.unknown-player"));
                break;
            }
            case LIME_DYE:
            case GRAY_DYE: {
                PlayerTracker.getInstance().toggleHideLocation(player.getUniqueId());
                boolean isHidden = PlayerTracker.getInstance().isLocationHidden(player.getUniqueId());
                player.sendMessage(String.valueOf((Object) ChatColor.GREEN)
                        + LanguageManager.getInstance().getString(player, "messages.location-hidden-status",
                                isHidden ? LanguageManager.getInstance().getString(player, "messages.location-hidden")
                                        : LanguageManager.getInstance().getString(player,
                                                "messages.location-visible")));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                guiManager.openPlayerNavigationMenu(player);
                break;
            }
            case END_PORTAL_FRAME: {
                // 检查全局导航开关和玩家权限
                if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                    player.sendMessage(String.valueOf((Object) ChatColor.RED)
                            + LanguageManager.getInstance().getString(player, "messages.global-navigation-disabled"));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    guiManager.openPlayerNavigationMenu(player);
                    break;
                }

                player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                        + LanguageManager.getInstance().getString(player, "messages.stronghold-searching"));
                final long startTime = System.currentTimeMillis();
                Bukkit.getScheduler().runTask(TOCpluginNative.getInstance(), () -> {
                    final Location strongholdLoc = player.getWorld().locateNearestStructure(player.getLocation(),
                            StructureType.STRONGHOLD, 100000, false);
                    final double timeTaken = (System.currentTimeMillis() - startTime) / 1000.0;
                    if (strongholdLoc != null) {
                        player.sendMessage(String.valueOf((Object) ChatColor.GREEN) + LanguageManager.getInstance()
                                .getString(player, "messages.stronghold-search-complete"));
                        player.sendMessage(
                                String.valueOf((Object) ChatColor.GRAY) + LanguageManager.getInstance().getString(
                                        player, "messages.stronghold-search-time", String.format("%.2f", timeTaken)));
                        player.sendMessage(String.valueOf((Object) ChatColor.GRAY) + LanguageManager.getInstance()
                                .getString(player, "messages.stronghold-coordinates", strongholdLoc.getBlockX(),
                                        strongholdLoc.getBlockY(), strongholdLoc.getBlockZ()));
                        PlayerTracker.getInstance().setStrongholdNavigation(player.getUniqueId(), strongholdLoc);
                        player.sendMessage(String.valueOf((Object) ChatColor.GREEN) + LanguageManager.getInstance()
                                .getString(player, "messages.stronghold-navigation-set"));
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);
                        if (!guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                            guiManager.toggleParticleFeature(player.getUniqueId());
                        }
                        MasterListener.this.startPathfinding(player);
                    } else {
                        player.sendMessage(String.valueOf((Object) ChatColor.RED)
                                + LanguageManager.getInstance().getString(player, "messages.stronghold-not-found"));
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    }
                    guiManager.openPlayerNavigationMenu(player);
                });
                break;
            }
            case BEACON: {
                // 检查全局导航开关和玩家权限
                if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
                    player.sendMessage(String.valueOf((Object) ChatColor.RED)
                            + LanguageManager.getInstance().getString(player, "messages.global-navigation-disabled"));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    guiManager.openPlayerNavigationMenu(player);
                    break;
                }

                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.0f);
                guiManager.openPlayerNavigationMenu(player);
                this.findNearestBeaconAsync(player, 100, beaconLoc -> {
                    if (beaconLoc != null) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                // 设置信标导航目标
                                PlayerTracker.getInstance().setBeaconNavigation(player.getUniqueId(), beaconLoc);
                                player.sendMessage(String.valueOf((Object) ChatColor.GREEN)
                                        + LanguageManager.getInstance().getString(player,
                                                "messages.beacon-navigation-set"));
                                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.0f);

                                // 确保粒子效果已启用
                                if (!guiManager.isParticleFeatureEnabled(player.getUniqueId())) {
                                    guiManager.toggleParticleFeature(player.getUniqueId());
                                }

                                // 延迟一个tick启动寻路，确保导航目标已正确设置
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        startPathfinding(player);
                                    }
                                }.runTaskLater(TOCpluginNative.getInstance(), 1L);
                            }
                        }.runTask(TOCpluginNative.getInstance());
                    } else {
                        // 未找到信标：此处提示一次，超时分支也会提示，但二者不会同时发生
                        if (player.isOnline()) {
                            player.sendMessage(String.valueOf((Object) ChatColor.RED)
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

    /**
     * 用于存储信标搜索结果的包装类，解决lambda表达式中的变量必须是final的问题
     */
    private static class BeaconSearchResult {
        Location nearestBeacon = null;
        double minDistance = Double.MAX_VALUE;
    }

    /**
     * 异步查找最近的信标
     * 
     * @param player    玩家
     * @param maxRadius 最大搜索半径（方块数）
     * @param callback  回调函数，参数为找到的信标位置，如果没有找到则为null
     */
    @SuppressWarnings("deprecation")
    private void findNearestBeaconAsync(Player player, int maxRadius, Consumer<Location> callback) {
        Location playerLocation = player.getLocation().clone();
        World world = playerLocation.getWorld();
        if (world == null) {
            callback.accept(null);
            return;
        }

        UUID playerId = player.getUniqueId();

        // 取消之前的搜索任务（如果有）
        removeBeaconSearchTask(playerId);

        // 生成随机四位数编号
        int taskId = (int) (Math.random() * 9000) + 1000; // 生成1000-9999之间的随机数

        // 显示正在搜索的消息
        player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                + LanguageManager.getInstance().getString(player, "messages.searching-for-beacon", taskId));
        player.sendMessage(String.valueOf((Object) ChatColor.GRAY)
                + LanguageManager.getInstance().getString(player, "messages.search-radius", maxRadius)
                + "，"
                + LanguageManager.getInstance().getString(player, "messages.player-location",
                        playerLocation.getBlockX(), playerLocation.getBlockY(), playerLocation.getBlockZ()));

        // 用于标记是否已经调用过callback的标志
        final boolean[] callbackCalled = { false };

        // 创建异步任务
        BukkitTask searchTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 使用包装类来存储可变结果，解决lambda表达式中的变量必须是final的问题
                final BeaconSearchResult result = new BeaconSearchResult();

                // 获取玩家位置的整数坐标
                int playerX = playerLocation.getBlockX();
                int playerY = playerLocation.getBlockY();
                int playerZ = playerLocation.getBlockZ();

                // 计算搜索范围的边界
                int minX = playerX - maxRadius;
                int maxX = playerX + maxRadius;
                int minZ = playerZ - maxRadius;
                int maxZ = playerZ + maxRadius;

                // 计算垂直搜索范围，优先搜索玩家高度附近
                int minY = Math.max(world.getMinHeight(), playerY - 50);
                int maxY = Math.min(world.getMaxHeight() - 1, playerY + 50);

                // 从玩家位置开始，逐步扩大搜索范围
                for (int radius = 0; radius <= maxRadius; radius++) {
                    // 检查任务是否被取消（时间限制到达）
                    if (this.isCancelled()) {
                        break;
                    }

                    boolean foundInThisRadius = false;

                    // 搜索当前半径的所有位置
                    for (int x = playerX - radius; x <= playerX + radius; x++) {
                        // 检查任务是否被取消（时间限制到达）
                        if (this.isCancelled()) {
                            break;
                        }

                        for (int z = playerZ - radius; z <= playerZ + radius; z++) {
                            // 检查任务是否被取消（时间限制到达）
                            if (this.isCancelled()) {
                                break;
                            }

                            // 确保在搜索范围内
                            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                                continue;
                            }

                            // 只搜索边界上的点（避免重复搜索内部区域）
                            if (radius > 0 && x > playerX - radius && x < playerX + radius &&
                                    z > playerZ - radius && z < playerZ + radius) {
                                continue;
                            }

                            searchBeaconAtPosition(world, x, z, playerLocation, minY, maxY,
                                    (loc, dist, belowBlock) -> {
                                        if (dist < result.minDistance) {
                                            result.minDistance = dist;
                                            result.nearestBeacon = loc;
                                        }
                                    });

                            // 如果找到信标，标记这个半径层
                            if (result.nearestBeacon != null) {
                                foundInThisRadius = true;
                            }
                        }
                    }

                    if (foundInThisRadius && radius > 10) {
                        break;
                    }
                }

                // 返回主线程处理结果
                final Location finalNearestBeacon = result.nearestBeacon;
                // 检查任务是否已被取消（时间限制到达）
                if (!this.isCancelled()) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!TOCpluginNative.getInstance().isEnabled()) {
                                return;
                            }

                            // 确保callback只被调用一次
                            synchronized (callbackCalled) {
                                if (callbackCalled[0]) {
                                    return;
                                }
                                callbackCalled[0] = true;
                            }

                            // 清理任务记录
                            removeBeaconSearchTask(playerId);

                            if (finalNearestBeacon != null) {
                                // 在聊天栏打印最近信标的坐标以及信标下的方块
                                int x = finalNearestBeacon.getBlockX();
                                int y = finalNearestBeacon.getBlockY();
                                int z = finalNearestBeacon.getBlockZ();
                                String beaconCoords = String.format("x: %d, y: %d, z: %d", x, y, z);
                                double distance = playerLocation
                                        .distance(finalNearestBeacon.clone().add(0.5, 0.5, 0.5));

                                player.sendMessage(String.valueOf((Object) ChatColor.AQUA)
                                        + LanguageManager.getInstance().getString(player, "messages.beacon-coords",
                                                beaconCoords,
                                                String.format("%.1f", distance)));

                                // 为导航添加0.5的偏移，使粒子路径指向信标中心
                                Location navLocation = finalNearestBeacon.clone().add(0.5, 0.5, 0.5);
                                callback.accept(navLocation);
                            } else {
                                callback.accept(null);
                            }
                        }
                    }.runTask(TOCpluginNative.getInstance());
                }
            }
        }.runTaskAsynchronously(TOCpluginNative.getInstance());

        // 设置20秒后自动取消搜索任务
        BukkitTask timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!TOCpluginNative.getInstance().isEnabled()) {
                    searchTask.cancel();
                    return;
                }

                if (!searchTask.isCancelled()) {
                    searchTask.cancel();
                    // 在主线程通知玩家搜索超时
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!TOCpluginNative.getInstance().isEnabled()) {
                                return;
                            }

                            // 确保callback只被调用一次
                            synchronized (callbackCalled) {
                                if (callbackCalled[0]) {
                                    return;
                                }
                                callbackCalled[0] = true;
                            }

                            // 清理任务记录
                            removeBeaconSearchTask(playerId);

                            if (player.isOnline()) {
                                player.sendMessage(
                                        String.valueOf((Object) ChatColor.RED)
                                                + LanguageManager.getInstance()
                                                        .getString(player, "messages.beacon-search-timeout", taskId));
                                callback.accept(null);
                            }
                        }
                    }.runTask(TOCpluginNative.getInstance());
                }
            }
        }.runTaskLaterAsynchronously(TOCpluginNative.getInstance(), 20 * 20); // 20秒 = 20 ticks/秒 * 20秒

        // 将任务添加到管理器中
        addBeaconSearchTask(playerId, searchTask, timeoutTask);
    }

    /**
     * 在指定的X和Z坐标位置搜索信标
     * 
     * @param world          世界
     * @param x              X坐标
     * @param z              Z坐标
     * @param playerLocation 玩家位置
     * @param minY           最小Y坐标
     * @param maxY           最大Y坐标
     * @param resultHandler  处理找到的信标的回调
     */
    private void searchBeaconAtPosition(World world, int x, int z, Location playerLocation,
            int minY, int maxY, BeaconResultHandler resultHandler) {
        // 从玩家高度开始，向上下扩展搜索
        int playerY = playerLocation.getBlockY();

        // 先检查玩家高度
        checkForBeacon(world, x, playerY, z, playerLocation, resultHandler);

        // 然后交替检查玩家上方和下方的方块
        for (int yOffset = 1; yOffset <= Math.max(playerY - minY, maxY - playerY); yOffset++) {
            // 检查上方
            int upperY = playerY + yOffset;
            if (upperY <= maxY) {
                checkForBeacon(world, x, upperY, z, playerLocation, resultHandler);
            }

            // 检查下方
            int lowerY = playerY - yOffset;
            if (lowerY >= minY) {
                checkForBeacon(world, x, lowerY, z, playerLocation, resultHandler);
            }

            // 如果已经超出了搜索范围，退出循环
            if (upperY > maxY && lowerY < minY) {
                break;
            }
        }
    }

    /**
     * 检查指定位置是否为信标
     * 
     * @param world          世界
     * @param x              X坐标
     * @param y              Y坐标
     * @param z              Z坐标
     * @param playerLocation 玩家位置
     * @param resultHandler  处理找到的信标的回调
     */
    private void checkForBeacon(World world, int x, int y, int z, Location playerLocation,
            BeaconResultHandler resultHandler) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == Material.BEACON) {
            // 获取精确的方块位置，不添加0.5偏移
            Location beaconLoc = block.getLocation().clone();
            // 计算距离时使用中心点
            Location beaconCenter = beaconLoc.clone().add(0.5, 0.5, 0.5);
            double distanceSquared = playerLocation.distanceSquared(beaconCenter);

            // 获取信标下方的方块
            Block blockBelow = world.getBlockAt(x, y - 1, z);

            // 调用回调处理结果
            resultHandler.handleResult(beaconLoc, distanceSquared, blockBelow);
        }
    }

    /**
     * 处理找到的信标的回调接口
     */
    private interface BeaconResultHandler {
        /**
         * 处理找到的信标
         * 
         * @param location        信标位置
         * @param distanceSquared 距离的平方
         * @param blockBelow      信标下方的方块
         */
        void handleResult(Location location, double distanceSquared, Block blockBelow);
    }

    private Location findWaterLanding(Location playerLoc, Location targetLoc) {
        int startY = playerLoc.getBlockY();
        int endY = targetLoc.getBlockY();
        int x = targetLoc.getBlockX();
        int z = targetLoc.getBlockZ();
        Location safeLanding = this.findSafeLandingNearWater(targetLoc);
        if (safeLanding != null) {
            return safeLanding;
        }
        for (int y = startY; y > endY && y > 0; --y) {
            Location aboveLoc;
            Location checkLoc = new Location(playerLoc.getWorld(), (double) x, (double) y, (double) z);
            if (!checkLoc.getBlock().getType().name().contains((CharSequence) "WATER")
                    || (aboveLoc = checkLoc.clone().add(0.0, 1.0, 0.0)).getBlock().getType().name()
                            .contains((CharSequence) "WATER")
                    || !aboveLoc.getBlock().isPassable())
                continue;
            boolean hasSpace = true;
            for (int checkY = y + 1; checkY < y + 3; ++checkY) {
                Location spaceCheckLoc = new Location(playerLoc.getWorld(), (double) x, (double) checkY, (double) z);
                if (spaceCheckLoc.getBlock().isPassable())
                    continue;
                hasSpace = false;
                break;
            }
            if (!hasSpace)
                continue;
            int depth = 1;
            Location belowLoc = checkLoc.clone();
            while (belowLoc.getBlockY() > 0) {
                belowLoc.subtract(0.0, 1.0, 0.0);
                if (!belowLoc.getBlock().getType().name().contains((CharSequence) "WATER"))
                    break;
                ++depth;
            }
            if (depth < 3)
                continue;
            return aboveLoc;
        }
        int radius = 10;
        for (int r = 1; r <= radius; ++r) {
            for (int dx = -r; dx <= r; ++dx) {
                for (int dz = -r; dz <= r; ++dz) {
                    if (Math.abs((int) dx) != r && Math.abs((int) dz) != r)
                        continue;
                    Location nearbyTarget = new Location(playerLoc.getWorld(), (double) (x + dx), (double) endY,
                            (double) (z + dz));
                    Location nearbySafeLanding = this.findSafeLandingNearWater(nearbyTarget);
                    if (nearbySafeLanding != null) {
                        return nearbySafeLanding;
                    }
                    Location checkLoc = new Location(playerLoc.getWorld(), (double) (x + dx), (double) endY,
                            (double) (z + dz));
                    for (int y = endY; y < startY; ++y) {
                        Location aboveLoc;
                        checkLoc.setY((double) y);
                        if (!checkLoc.getBlock().getType().name().contains((CharSequence) "WATER")
                                || (aboveLoc = checkLoc.clone().add(0.0, 1.0, 0.0)).getBlock().getType().name()
                                        .contains((CharSequence) "WATER")
                                || !aboveLoc.getBlock().isPassable())
                            continue;
                        int depth = 1;
                        Location belowLoc = checkLoc.clone();
                        while (belowLoc.getBlockY() > 0) {
                            belowLoc.subtract(0.0, 1.0, 0.0);
                            if (!belowLoc.getBlock().getType().name().contains((CharSequence) "WATER"))
                                break;
                            ++depth;
                        }
                        if (depth < 3)
                            continue;
                        return aboveLoc;
                    }
                }
            }
        }
        return null;
    }

    private Location findSafeLandingNearWater(Location waterLoc) {
        int z;
        int y;
        int x;
        if (waterLoc == null) {
            return null;
        }
        World world = waterLoc.getWorld();
        Location aboveWater = new Location(world, (double) (x = waterLoc.getBlockX()),
                (double) ((y = waterLoc.getBlockY()) + 1), (double) (z = waterLoc.getBlockZ()));
        if (this.isSafeLanding(aboveWater)) {
            return aboveWater;
        }

        // 减小搜索半径，从8减小到5，以减少方块检查次数
        int maxRadius = 5;
        // 添加迭代计数器，限制最大检查次数
        int checkedLocations = 0;
        int maxLocationsToCheck = 100; // 最多检查100个位置

        for (int r = 1; r <= maxRadius && checkedLocations < maxLocationsToCheck; ++r) {
            for (int dx = -r; dx <= r && checkedLocations < maxLocationsToCheck; ++dx) {
                for (int dz = -r; dz <= r && checkedLocations < maxLocationsToCheck; ++dz) {
                    if (Math.abs((int) dx) != r && Math.abs((int) dz) != r)
                        continue;
                    for (int dy = -2; dy <= 2 && checkedLocations < maxLocationsToCheck; ++dy) {
                        checkedLocations++;
                        Location checkLoc = new Location(world, (double) (x + dx), (double) (y + dy),
                                (double) (z + dz));
                        if (!this.isSafeLanding(checkLoc)
                                || checkLoc.getBlock().getType().name().contains((CharSequence) "WATER"))
                            continue;
                        return checkLoc;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafeLanding(Location loc) {
        if (loc == null) {
            return false;
        }
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        boolean feetPassable = feet.isPassable() || feet.getType().name().contains((CharSequence) "DOOR")
                || feet.getType().name().contains((CharSequence) "TRAPDOOR")
                || feet.getType().name().contains((CharSequence) "LADDER")
                || feet.getType().name().contains((CharSequence) "SCAFFOLDING");
        boolean headPassable = head.isPassable() || head.getType().name().contains((CharSequence) "DOOR")
                || head.getType().name().contains((CharSequence) "TRAPDOOR")
                || head.getType().name().contains((CharSequence) "LADDER")
                || head.getType().name().contains((CharSequence) "SCAFFOLDING");
        boolean groundSolid = ground.getType().isSolid() || ground.getType().name().contains((CharSequence) "LADDER")
                || ground.getType().name().contains((CharSequence) "SCAFFOLDING");
        boolean isDangerous = feet.getType().name().contains((CharSequence) "LAVA")
                || feet.getType().name().contains((CharSequence) "FIRE")
                || head.getType().name().contains((CharSequence) "LAVA")
                || head.getType().name().contains((CharSequence) "FIRE")
                || ground.getType().name().contains((CharSequence) "LAVA")
                || ground.getType().name().contains((CharSequence) "FIRE");
        return feetPassable && headPassable && groundSolid && !isDangerous;
    }

    private void generateJumpParabola(Player player, Location jumpStart, Location jumpEnd) {
        double horizontalDistance = Math
                .sqrt(Math.pow(jumpEnd.getX() - jumpStart.getX(), 2) + Math.pow(jumpEnd.getZ() - jumpStart.getZ(), 2));
        double verticalDistance = jumpEnd.getY() - jumpStart.getY();
        int steps = Math.max((int) Math.ceil(horizontalDistance / (Pathfinder.PARTICLE_SPACING * 0.2)), 15);

        double maxHeight = 0.5;

        Particle particleType = Particle.DUST;
        Color particleColor = Color.AQUA;
        float particleSize = Pathfinder.PARTICLE_SIZE;

        // 生成抛物线
        for (int step = 0; step <= steps; ++step) {
            double t = (double) step / (double) steps;

            // 水平位置线性插值
            double x = jumpStart.getX() + (jumpEnd.getX() - jumpStart.getX()) * t;
            double z = jumpStart.getZ() + (jumpEnd.getZ() - jumpStart.getZ()) * t;

            // 抛物线轨迹 y = y0 + vt - 0.5*g*t^2
            // y = startY + verticalDistance*t + maxHeight*4*t*(1-t)
            double y = jumpStart.getY() + verticalDistance * t + maxHeight * 4 * t * (1 - t);

            Location intermediateLoc = new Location(jumpStart.getWorld(), x, y, z);
            Location particleLoc = this.adjustParticleLocationForWater(intermediateLoc);

            float size = (step == 0 || step == steps) ? particleSize * 1.5f : particleSize * 1.2f;
            player.spawnParticle(particleType, particleLoc, 1, 0.0, 0.0, 0.0, 0.0,
                    (Object) new Particle.DustOptions(particleColor, size));
        }
    }

    @SuppressWarnings("deprecation")
    @org.bukkit.event.EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        if (PlayerTracker.getInstance().isNavigating(player.getUniqueId())) {
            PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
            player.sendMessage(String.valueOf((Object) ChatColor.YELLOW) + LanguageManager.getInstance().getString(player, "messages.navigation-stopped"));
        }
    }

    @SuppressWarnings("deprecation")
    @org.bukkit.event.EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        org.bukkit.World world = event.getWorld();
        for (org.bukkit.entity.Player p : world.getPlayers()) {
            if (PlayerTracker.getInstance().isNavigating(p.getUniqueId())) {
                PlayerTracker.getInstance().stopNavigation(p.getUniqueId());
                p.sendMessage(String.valueOf((Object) ChatColor.YELLOW) + LanguageManager.getInstance().getString(p, "messages.navigation-stopped"));
            }
        }
    }

}
