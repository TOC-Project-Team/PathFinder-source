package org.momu.tOCplugin.manager;

import net.kyori.adventure.text.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.momu.tOCplugin.TOCpluginNative;
import org.momu.tOCplugin.config.LanguageManager;

public class GuiManager {
    // 将常量更新为 Component 类型
    public static final Component GUI_TITLE = Component.text("PathFinder").color(NamedTextColor.BLUE); 
    // 验证状态跟踪
    private volatile boolean guiSystemEnabled = false;
    private volatile long lastGuiValidation = 0;

    // 移除静态常量，改为动态获取方法
    public static Component getPlayerNavigationTitle(Player player) {
        return Component
                .text(LanguageManager.getInstance().getString(player, "messages.player-navigation-cd"))
                .color(NamedTextColor.LIGHT_PURPLE);
    }

    private BukkitTask refreshTask;
    private final java.util.Map<java.util.UUID, BukkitTask> particleTasks = new java.util.HashMap<>();
    private final java.util.Set<java.util.UUID> particleFeatureEnabled = new java.util.HashSet<>();
    private final java.util.Map<java.util.UUID, Inventory> playerNavigationMenus = new java.util.HashMap<>();
    
    // 跟踪插件创建的所有GUI，避免依赖标题文字判断
    private final java.util.Set<Inventory> pluginInventories = new java.util.HashSet<>();
    
    // GUI类型枚举
    public enum GuiType {
        MAIN_MENU, PLAYER_NAVIGATION, WEATHER_CONTROL, PLAYER_LIST, PLAYER_DETAIL
    }
    
    // 跟踪每个GUI的类型
    private final java.util.Map<Inventory, GuiType> inventoryTypes = new java.util.HashMap<>();
    
    /**
     * 注册一个插件GUI
     */
    private void registerGui(Inventory inventory, GuiType type) {
        pluginInventories.add(inventory);
        inventoryTypes.put(inventory, type);
    }
    
    /**
     * 检查Inventory是否是插件的GUI
     */
    public boolean isPluginGui(Inventory inventory) {
        return pluginInventories.contains(inventory);
    }
    
    /**
     * 获取GUI类型
     */
    public GuiType getGuiType(Inventory inventory) {
        return inventoryTypes.get(inventory);
    }
    
    /**
     * 清理GUI引用（当GUI关闭时调用）
     */
    public void unregisterGui(Inventory inventory) {
        pluginInventories.remove(inventory);
        inventoryTypes.remove(inventory);
        playerNavigationMenus.values().removeIf(inv -> inv.equals(inventory));
    }
    
    /**
     * 深度嵌入验证 - 检查GUI系统是否可用
     */
    private boolean isGuiSystemAvailable() {
        // 获取插件实例并检查验证状态
        TOCpluginNative plugin = TOCpluginNative.getInstance();
        if (plugin == null) {
            return false;
        }
        
        // 简化：监控系统已删除，基本检查
        // (GUI系统现在由核心验证控制)
        
        // 多层验证检查
        if (!plugin.areCoreModulesEnabled()) {
            guiSystemEnabled = false;
            return false;
        }
        
        // 验证操作权限
        if (!plugin.validateOperation("gui_system")) {
            guiSystemEnabled = false;
            return false;
        }
        
        // 检查加密密钥
        byte[] encKey = plugin.getEncryptionKey();
        if (encKey == null || encKey.length < 16) {
            guiSystemEnabled = false;
            return false;
        }
        
        // 监控器检查已简化，直接通过
        
        // 更新GUI系统状态
        guiSystemEnabled = true;
        lastGuiValidation = System.currentTimeMillis();
        
        // 生成GUI加密种子
        generateGuiCryptoSeed(plugin);
        
        return true;
    }
    
    /**
     * 生成GUI加密种子（用于保护GUI创建）
     */
    private void generateGuiCryptoSeed(TOCpluginNative plugin) {
        try {
            byte[] encKey = plugin.getEncryptionKey();
            if (encKey != null) {
                String seedSource = new String(encKey) + System.currentTimeMillis() + "gui_protection";
                
                // DNA嵌入：使用插件的系统时间偏移影响GUI种子
                // 这看起来像是正常的时间同步，但实际上验证系统完整性
                long timeOffset = plugin.getSystemTimeOffset();
                if (timeOffset > 900) { // 如果时间偏移异常大，说明验证失败
                    
                } else {
                    String.valueOf((seedSource + timeOffset).hashCode());
                }
            }
        } catch (Exception e) {
        }
    }
    
    /**
     * 获取玩家当前的导航菜单页码
     */
    public Integer getCurrentPage(UUID playerId) {
        return playerNavigationPages.get(playerId);
    }
    
    /**
     * 公共方法 - 检查GUI系统是否可用（供其他类调用）
     */
    public boolean isGuiSystemOperational() {
        return isGuiSystemAvailable() && guiSystemEnabled && 
               (System.currentTimeMillis() - lastGuiValidation) < 300000;
    }
    
    /**
     * 强制重置GUI系统状态（在验证失败时调用）
     */
    public void disableGuiSystem() {
        guiSystemEnabled = false;
        lastGuiValidation = 0;
        
        // 关闭所有打开的插件GUI
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isPluginGui(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
                player.sendMessage(Component.text("系统连接已断开").color(NamedTextColor.RED));
            }
        }
    }

    public void openMainMenu(Player player) {
        if (player == null)
            return;

        // 简化验证 - 直接创建正常的GUI
        Inventory gui = Bukkit.createInventory(player, 27, GUI_TITLE);
        registerGui(gui, GuiType.MAIN_MENU);

        // 全局导航开关按钮
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
        
        // 简化验证逻辑 - 只对关键系统进行基本检查
        TOCpluginNative plugin = TOCpluginNative.getInstance();
        
        // 对于填充物品（灰色玻璃板且没有名字），直接创建，不做验证
        if (material == Material.GRAY_STAINED_GLASS_PANE && name == Component.empty()) {
            return new ItemStack(material, 1);
        }
        
        // 对于功能性物品，进行基本的系统检查
        if (plugin == null) {
            // 插件为空时，返回错误提示物品
            ItemStack errorItem = new ItemStack(Material.BARRIER);
            ItemMeta errorMeta = errorItem.getItemMeta();
            if (errorMeta != null) {
                errorMeta.displayName(Component.text("系统错误").color(NamedTextColor.RED));
                errorItem.setItemMeta(errorMeta);
            }
            return errorItem;
        }
        
        // 正常创建物品
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

    /**
     * 打开玩家导航菜单
     * 
     * @param player 打开菜单的玩家
     */
    // 存储玩家导航菜单的当前页码
    private final Map<UUID, Integer> playerNavigationPages = new HashMap<>();

    public void openPlayerNavigationMenu(Player player) {
        openPlayerNavigationMenu(player, 0);
    }

    public void openPlayerNavigationMenu(Player player, int page) {
        // 简化验证 - 直接创建正常的导航菜单
        Inventory gui = Bukkit.createInventory(player, 54, getPlayerNavigationTitle(player));
        registerGui(gui, GuiType.PLAYER_NAVIGATION);

        // 添加所有在线玩家的头颅
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        onlinePlayers.sort(Comparator.comparing(Player::getName));

        // 计算总页数
        int playersPerPage = 36; // 每页显示的玩家数量
        int totalPlayers = (int) onlinePlayers.stream()
                .filter(p -> !p.getUniqueId().equals(player.getUniqueId()))
                .count();
        int totalPages = (int) Math.ceil((double) totalPlayers / playersPerPage);

        // 确保页码有效
        if (page < 0)
            page = 0;
        if (totalPages > 0 && page >= totalPages)
            page = totalPages - 1;

        // 保存当前页码
        playerNavigationPages.put(player.getUniqueId(), page);

        // 计算当前页的起始索引
        int startIndex = page * playersPerPage;
        int endIndex = Math.min(startIndex + playersPerPage, totalPlayers);

        // 添加玩家头颅
        int slot = 0;
        int currentIndex = 0;
        for (Player target : onlinePlayers) {
            // 跳过自己
            if (target.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

            // 只显示当前页的玩家
            if (currentIndex < startIndex || currentIndex >= endIndex) {
                currentIndex++;
                continue;
            }

            if (slot >= 36)
                break; // 防止溢出，留出底部9格用于控制按钮

            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            if (playerHead.getItemMeta() instanceof SkullMeta meta) {
                meta.setPlayerProfile(target.getPlayerProfile());
                meta.displayName(Component.text(target.getName()).color(NamedTextColor.YELLOW));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player"))
                        .color(NamedTextColor.GRAY));

                // 添加距离信息
                if (player.getWorld().equals(target.getWorld())) {
                    double distance = player.getLocation().distance(target.getLocation());
                    lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player-distance"))
                            .color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(String.format("%.1fm", distance)).color(NamedTextColor.AQUA)));
                } else {
                    lore.add(Component.text("维度: ").color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(getWorldDisplayName(player, target.getWorld())).color(NamedTextColor.GOLD)));
                }
                // 添加方向信息
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

        // 添加分页按钮
        if (totalPages > 1) {
            // 上一页按钮
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

            // 下一页按钮
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

            // 页码指示器
            ItemStack pageIndicator = new ItemStack(Material.PAPER);
            ItemMeta pageMeta = pageIndicator.getItemMeta();
            if (pageMeta != null) {
                pageMeta.displayName(
                        Component.text(LanguageManager.getInstance().getString(player, "messages.page-indicator", (page + 1), totalPages)).color(NamedTextColor.GOLD));
                pageIndicator.setItemMeta(pageMeta);
            }
            gui.setItem(49, pageIndicator);
        }

        // 创建位置隐私按钮
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

        // 如果没有分页，将位置隐私按钮放在中间位置，否则放在左下角
        if (totalPages <= 1) {
            gui.setItem(49, privacyButton);
        } else {
            gui.setItem(46, privacyButton);
        }

        // 添加导航到末地要塞按钮 - 仅限TOCC验证玩家
        if (TOCpluginNative.getInstance().isPlayerAuthenticated(player.getUniqueId())) {
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

        // 添加导航到最近信标按钮 - 仅限OP玩家可见
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

            // 已删除导航到末地传送门框架按钮
        }

        // 如果玩家正在导航，添加停止导航按钮
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

        // 填充空白处
        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < 54; i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, filler);
            }
        }

        // 保存菜单引用以便更新
        playerNavigationMenus.put(player.getUniqueId(), gui);

        player.openInventory(gui);
    }

    /**
     * 更新玩家导航菜单
     * 
     * @param player    玩家
     * @param inventory 菜单库存
     */
    public void updatePlayerNavigationMenu(Player player, Inventory inventory) {
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        onlinePlayers.sort(Comparator.comparing(Player::getName));

        // 更新所有玩家头颅的信息
        int slot = 0;
        for (Player target : onlinePlayers) {
            // 跳过自己
            if (target.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

            if (slot >= 45)
                break; // 防止溢出

            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                if (item.getItemMeta() instanceof SkullMeta meta) {
                    List<Component> lore = new ArrayList<>();
                    lore.add(Component.text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player"))
                            .color(NamedTextColor.GRAY));

                    // 更新距离信息
                    double distance = player.getLocation().distance(target.getLocation());
                    lore.add(Component
                            .text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player-distance"))
                            .color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(String.format("%.1fm", distance)).color(NamedTextColor.AQUA)));

                    // 更新方向信息
                    String direction = getDirectionFromViewer(player, target);
                    lore.add(Component
                            .text(LanguageManager.getInstance().getString(player, "messages.navigate-to-player-direction"))
                            .color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(direction).color(NamedTextColor.GOLD)));

                    meta.lore(lore);
                    item.setItemMeta(meta);
                }
            }
            slot++;
        }

        // 更新位置隐私按钮
        ItemStack privacyButton = inventory.getItem(49);
        if (privacyButton != null) {
            boolean isLocationHidden = PlayerTracker.getInstance().isLocationHidden(player.getUniqueId());
            ItemStack newPrivacyButton = new ItemStack(isLocationHidden ? Material.LIME_DYE : Material.GRAY_DYE);
            newPrivacyButton.setItemMeta(privacyButton.getItemMeta());
            inventory.setItem(inventory.first(privacyButton), newPrivacyButton);
            ItemMeta meta = privacyButton.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(Component
                        .text(LanguageManager.getInstance().getString(player, "messages.player-navigation-privacy-desc"))
                        .color(NamedTextColor.GRAY));
                lore.add(isLocationHidden
                        ? Component
                                .text(LanguageManager.getInstance()
                                        .getString(player, "messages.player-navigation-privacy-hidden"))
                                .color(NamedTextColor.GREEN)
                        : Component
                                .text(LanguageManager.getInstance()
                                        .getString(player, "messages.player-navigation-privacy-visible"))
                                .color(NamedTextColor.RED));
                meta.lore(lore);
                privacyButton.setItemMeta(meta);
            }
        }

        // 更新停止导航按钮
        ItemStack currentItem = inventory.getItem(45);
        if (currentItem != null && currentItem.getType() == Material.ENDER_PEARL) {
            inventory.setItem(45, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, Component.empty()));
        }
    }

    public void updatePlayerMenu(Player viewer, Inventory menu) {
        for (int i = 0; i < menu.getSize(); i++) {
            ItemStack item = menu.getItem(i);
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                if (item.getItemMeta() instanceof SkullMeta meta) {
                    Component displayName = meta.displayName();
                    if (displayName == null)
                        continue;
                    String targetName = ((TextComponent) displayName).content();
                    Player target = Bukkit.getPlayer(targetName);

                    if (target != null && target.isOnline()) {
                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.text("点击查看详细信息").color(NamedTextColor.GRAY));
                        lore.add(Component.text("生命值: ").color(NamedTextColor.DARK_GRAY)
                                .append(Component.text(String.format("%.1f", target.getHealth()), NamedTextColor.RED)));
                        lore.add(Component.text("饱食度: ").color(NamedTextColor.DARK_GRAY)
                                .append(Component.text(String.valueOf(target.getFoodLevel()), NamedTextColor.GREEN)));

                        meta.lore(lore);
                        item.setItemMeta(meta);
                    }
                }
            }
        }
    }

    public boolean canUseLightning(Player target) {
        World world = target.getWorld();
        boolean isStormy = world.hasStorm() || world.isThundering();
        boolean hasClearSky = world.getHighestBlockYAt(target.getLocation()) <= target.getLocation().getY();
        return isStormy && hasClearSky;
    }

    /**
     * 获取世界的显示名称
     * 
     * @param world 世界
     * @return 显示名称
     */
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

    public void stopMenuRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private String getDirectionFromViewer(Player viewer, Player target) {
        return getDirectionToLocation(viewer, target.getLocation());
    }

    public void toggleParticleFeature(java.util.UUID playerId) {
        if (particleFeatureEnabled.contains(playerId)) {
            particleFeatureEnabled.remove(playerId);
            // 如果有正在运行的粒子任务，取消它
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

    /**
     * 关闭所有GUI相关的任务，用于插件禁用时
     */
    public void shutdown() {
        stopMenuRefresh();

        // 清空所有粒子任务
        for (BukkitTask task : particleTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        particleTasks.clear();

        // 清空粒子功能启用状态
        particleFeatureEnabled.clear();

        // 清空导航菜单引用
        playerNavigationMenus.clear();
    }

    /**
     * 获取从玩家视角看目标位置的时钟方向
     * 
     * @param player 玩家
     * @param target 目标位置
     * @return 时钟方向描述
     */
    private String getDirectionToLocation(Player player, Location target) {
        Location playerLoc = player.getLocation();

        double deltaX = target.getX() - playerLoc.getX();
        double deltaZ = target.getZ() - playerLoc.getZ();

        // 旋转矢量以匹配玩家视角
        double yawRad = Math.toRadians(playerLoc.getYaw());
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);

        double rotatedX = deltaX * cosYaw + deltaZ * sinYaw;
        double rotatedZ = -deltaX * sinYaw + deltaZ * cosYaw;

        // 计算相对角度
        double relativeAngle = Math.toDegrees(Math.atan2(-rotatedX, rotatedZ));
        if (relativeAngle < 0) {
            relativeAngle += 360;
        }

        // 转换为时钟方向
        int clockDirection = (int) Math.round(relativeAngle / 30.0);
        if (clockDirection == 0 || clockDirection == 12) {
            clockDirection = 12;
        } else {
            clockDirection = clockDirection % 12;
        }

        return clockDirection + LanguageManager.getInstance().getString(player, "messages.clock-direction");
    }
}
