package org.momu.tOCplugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.momu.tOCplugin.command.MainCommand;
import org.momu.tOCplugin.config.LanguageManager;
import org.momu.tOCplugin.internal.NativeLibraryLoader;
import org.momu.tOCplugin.internal.Waypoint;
import org.momu.tOCplugin.manager.TaskManager;

import java.io.File;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TOCpluginNative extends JavaPlugin implements Listener {

    private static TOCpluginNative instance;
    private static volatile String nativeAuthToken;

    public static void setNativeAuthToken(String token) {
        try {
            nativeAuthToken = token;
        } catch (Exception ignore) {
        }
    }

    private static boolean verifyNativeToken(String token) {
        return token != null && token.equals(nativeAuthToken);
    }

    public static final int ACTION_ON_ENABLE = 1;
    public static final int ACTION_ON_DISABLE = 2;
    public static final int ACTION_COMMAND_EXECUTE = 3;
    public static final int ACTION_PERMISSION_CHECK = 4;
    public static final int ACTION_PLAYER_JOIN = 5;
    public static final int ACTION_GET_STATUS = 6;
    public static final int ACTION_PLAYER_QUIT = 7;

    // 配置文件监视器相关
    private WatchService watchService;
    private ExecutorService watchExecutor;
    private final AtomicBoolean watchingEnabled = new AtomicBoolean(false);
    private Path dataFolderPath;
    private final Map<String, Long> lastConfigChangeMillis = new ConcurrentHashMap<>();

    private native boolean nativePluginManager(int action, Object... params);

    @Deprecated
    public boolean performSecureInitialization() {
        return true;
    }

    private void startPeriodicValidationTask() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    boolean status = getPluginStatus();
                    if (!status) {
                        getLogger().warning("Plugin status check returned false - possible validation failure");
                    }
                } catch (Exception e) {
                    getLogger().warning("Periodic validation check failed: " + e.getMessage());
                }
            }
        }, 20L * 30, 20L * 30);
    }

    public static String getTranslatedMessage(String key, String defaultMessage) {
        try {
            if (LanguageManager.getInstance() != null) {
                return LanguageManager.getInstance().getString("messages." + key);
            }
        } catch (Exception e) {
        }
        return defaultMessage;
    }

    public static String getTranslatedMessage(String key, String defaultMessage, Object... args) {
        try {
            if (LanguageManager.getInstance() != null) {
                return LanguageManager.getInstance().getString("messages." + key, args);
            }
        } catch (Exception e) {
        }
        return String.format(defaultMessage, args);
    }

    public static String getTranslatedMessageForPlayer(String playerName, String key, String defaultMessage) {
        try {
            if (LanguageManager.getInstance() != null && playerName != null) {
                org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerName);
                if (player != null) {
                    return LanguageManager.getInstance().getString(player, "messages." + key);
                }
            }
        } catch (Exception e) {
        }
        return getTranslatedMessage(key, defaultMessage);
    }

    public static void sendMessageFromNative(String target, String message) {
        try {
            TOCpluginNative instance = getInstance();
            if (instance == null) {
                org.bukkit.Bukkit.getLogger().info("[PathFinder] " + message);
                return;
            }

            if ("CONSOLE".equals(target)) {
                String cleanMessage = convertMinecraftColorsToAnsi(message);
                instance.getLogger().info(cleanMessage);
            } else {
                org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(target);
                if (player != null && player.isOnline()) {
                    player.sendMessage(net.kyori.adventure.text.Component.text(message));
                } else {
                    instance.getLogger().warning("Player " + target + " not found, message: " + message);
                }
            }
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().severe("Error sending message: " + e.getMessage());
            org.bukkit.Bukkit.getLogger().info("Fallback message to " + target + ": " + message);
        }
    }

    @SuppressWarnings("unused")
    public static void handleSubcommandFromNative(String senderName, String[] args, String senderType, String token) {
        try {
            TOCpluginNative instance = getInstance();
            if (instance == null || !instance.getPluginStatus() || !verifyNativeToken(token)) {
                return;
            }
            if (instance == null) {
                org.bukkit.Bukkit.getLogger().warning(
                        "Plugin instance not available for subcommand with args: " + java.util.Arrays.toString(args));
                return;
            }

            org.bukkit.command.CommandSender sender;
            if ("CONSOLE".equals(senderType)) {
                sender = org.bukkit.Bukkit.getConsoleSender();
            } else {
                org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(senderName);
                if (player == null || !player.isOnline()) {
                    instance.getLogger().warning("Player " + senderName + " not found for subcommand with args: "
                            + java.util.Arrays.toString(args));
                    return;
                }
                sender = player;
            }

            MainCommand mainCommand = new MainCommand(instance);

            org.bukkit.command.Command command = instance.getCommand("toc");
            if (command != null) {
                mainCommand.onCommand(sender, command, "toc", args);
            } else {
                instance.getLogger().severe("Command 'toc' not found for subcommand handling");
            }

        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().severe(
                    "Error handling subcommand with args " + java.util.Arrays.toString(args) + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String convertMinecraftColorsToAnsi(String message) {
        if (message == null)
            return "";

        String cleaned = message
                .replaceAll("[🧭🔒📍🔧✅❌🚀]", "")
                .replace("§0", "\033[30m") // 黑色
                .replace("§1", "\033[34m") // 深蓝
                .replace("§2", "\033[32m") // 深绿
                .replace("§3", "\033[36m") // 深青
                .replace("§4", "\033[31m") // 深红
                .replace("§5", "\033[35m") // 深紫
                .replace("§6", "\033[33m") // 金色
                .replace("§7", "\033[37m") // 灰色
                .replace("§8", "\033[90m") // 深灰
                .replace("§9", "\033[94m") // 蓝色
                .replace("§a", "\033[92m") // 绿色
                .replace("§b", "\033[96m") // 青色
                .replace("§c", "\033[91m") // 红色
                .replace("§d", "\033[95m") // 粉红
                .replace("§e", "\033[93m") // 黄色
                .replace("§f", "\033[97m") // 白色
                .replace("§r", "\033[0m"); // 重置

        return cleaned + "\033[0m";
    }

    static {
        try {
            NativeLibraryLoader.loadNativeLibrary();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("PathFinder").severe("Failed to load native library: " + e.getMessage());
            throw new RuntimeException("Native library loading failed", e);
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }

            saveDefaultConfig();

            try {
                if (!new File(getDataFolder(), "validata.yml").exists()) {
                    saveResource("validata.yml", false);
                    getLogger().info("📋 Please replace placeholder cards with your real license cards");
                }

                if (!new File(getDataFolder(), "pathfinder.yml").exists()) {
                    saveResource("pathfinder.yml", false);
                }

            } catch (Exception e) {
                getLogger().warning("Failed to extract configuration files: " + e.getMessage());
            }

            boolean success = nativePluginManager(ACTION_ON_ENABLE,
                    getServer().getVersion(),
                    getDataFolder().getAbsolutePath(),
                    getServer().getMaxPlayers(),
                    getServer().getPort());

            if (!success) {
                getLogger().severe("❌ CRITICAL: Validation FAILED (handled in Native layer)");
                return;
            }

            // ✅ 仅在验证成功后再初始化语言、路径配置与文件监视
            LanguageManager.getInstance(this);
            Pathfinder.loadConfig(this);
            WaypointService.getInstance().init(this);
            startConfigFileWatcher();
        } catch (Exception e) {
            getLogger().severe("❌ Fatal error during startup: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // 停止配置文件监视器
        stopConfigFileWatcher();
        TaskManager.cancelAllTasks();

        if (instance != null) {
            try {
                nativePluginManager(ACTION_ON_DISABLE);
            } catch (Exception e) {
                getLogger().warning("Error during native cleanup: " + e.getMessage());
            }
        }

        try {
            WaypointService.getInstance();
        } catch (Exception ignore) {
        }

        instance = null;
        getLogger().info("PathFinder shutdown complete");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            // 特殊处理reload命令 - 直接在Java层处理
            if (!getPluginStatus()) {
                sender.sendMessage("PathFinder plugin is DISABLED.");
                return true;
            }
            if (command.getName().equalsIgnoreCase("toc") && args.length > 0) {
                if (args[0].equalsIgnoreCase("reload")) {
                    // 检查权限
                    if (!sender.hasPermission("toc.admin") && !sender.isOp()) {
                        sender.sendMessage(LanguageManager.getInstance()
                                .getString(sender instanceof Player ? (Player) sender : null,
                                        "messages.no-permission"));
                        return true;
                    }

                    // 执行配置重载
                    reloadConfigurations();

                    // 发送成功消息
                    sender.sendMessage(LanguageManager.getInstance()
                            .getString(sender instanceof Player ? (Player) sender : null, "messages.reload-success"));
                    return true;
                } else if (args[0].equalsIgnoreCase("reconfig")) {
                    // 恢复pathfinder.yml为内置默认配置
                    if (!sender.hasPermission("toc.admin") && !sender.isOp()) {
                        sender.sendMessage(LanguageManager.getInstance()
                                .getString(sender instanceof Player ? (Player) sender : null,
                                        "messages.no-permission"));
                        return true;
                    }
                    try {
                        // 覆盖写入默认配置
                        saveResource("pathfinder.yml", true);
                        // 立即重新加载到内存并清空缓存
                        reloadConfigurations();
                        sender.sendMessage(TOCpluginNative.getTranslatedMessage("reconfig-success",
                                "Pathfinder configuration has been restored to defaults and reloaded."));
                    } catch (Exception ex) {
                        getLogger().warning("Reconfig failed: " + ex.getMessage());
                        sender.sendMessage(TOCpluginNative.getTranslatedMessage("reconfig-failed",
                                "Failed to restore default pathfinder.yml: {0}", ex.getMessage()));
                    }
                    return true;
                } else if (args[0].equalsIgnoreCase("relang")) {
                    // 从jar包覆盖语言文件，并提示英文成功或失败
                    if (!sender.hasPermission("toc.admin") && !sender.isOp()) {
                        sender.sendMessage("You do not have permission to execute this command.");
                        return true;
                    }
                    try {
                        // 获取jar包中lang文件夹下的所有文件
                        String[] langs = new String[] {
                                "lang/zh-CN.yml",
                                "lang/en-US.yml",
                                "lang/de-DE.yml",
                                "lang/es-ES.yml",
                                "lang/fr-FR.yml",
                                "lang/ja-JP.yml",
                                "lang/ko-KR.yml",
                                "lang/ru-RU.yml"
                        };

                        int restoredCount = 0;
                        for (String res : langs) {
                            try {
                                saveResource(res, true);
                                restoredCount++;
                            } catch (Exception e) {
                                getLogger().info("Language file not found in jar: " + res);
                            }
                        }
                        LanguageManager.getInstance(TOCpluginNative.this);
                        sender.sendMessage("Language files have been restored. (" + restoredCount + " files)");
                    } catch (Exception ex) {
                        getLogger().warning("Relang failed: " + ex.getMessage());
                        sender.sendMessage("Failed to restore language files: " + ex.getMessage());
                    }
                    return true;
                }
            }

            // 其他命令继续由native代码处理
            return nativePluginManager(ACTION_COMMAND_EXECUTE,
                    sender.getName(),
                    command.getName(),
                    label,
                    args,
                    sender instanceof Player ? "PLAYER" : "CONSOLE",
                    sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "CONSOLE");
        } catch (Exception e) {
            getLogger().warning("Command execution error: " + e.getMessage());
            return false;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            nativePluginManager(ACTION_PLAYER_JOIN,
                    player.getName(),
                    player.getUniqueId().toString(),
                    player.getAddress().getAddress().getHostAddress(),
                    System.currentTimeMillis());
        } catch (Exception e) {
            getLogger().warning("Player join event error: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            Player player = event.getPlayer();
            handlePlayerQuit(player.getName(), player.getUniqueId().toString());
        } catch (Exception e) {
            getLogger().warning("Player quit event error: " + e.getMessage());
        }
    }

    public void handlePlayerQuit(String playerName, String playerUuid) {
        try {
            nativePluginManager(ACTION_PLAYER_QUIT, playerName, playerUuid);
        } catch (Exception e) {
            getLogger().warning("Player quit handling error: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        try {
            Player player = event.getPlayer();
            String message = event.getMessage();
            if (message.startsWith("/toc admin") || message.startsWith("/toc reload")) {
                if (!player.isOp()) {
                    boolean allowed = validateOperation("sensitive_command_" + message.split(" ")[1]);
                    if (!allowed) {
                        event.setCancelled(true);
                        player.sendMessage(LanguageManager.getInstance()
                                .getString(player, "messages.no-permission"));
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Command preprocess error: " + e.getMessage());
        }
    }

    public static TOCpluginNative getInstance() {
        return instance;
    }

    public boolean getPluginStatus() {
        try {
            return nativePluginManager(ACTION_GET_STATUS);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean areCoreModulesEnabled() {
        try {
            return nativePluginManager(ACTION_GET_STATUS);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean canExecuteSensitiveCommands() {
        try {
            return nativePluginManager(ACTION_PERMISSION_CHECK, "SYSTEM", "ADMIN", "SENSITIVE_COMMAND");
        } catch (Exception e) {
            return false;
        }
    }

    public String getValidationToken() {
        try {
            return "NATIVE_SECURED_TOKEN";
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] getEncryptionKey() {
        try {
            String token = getValidationToken();
            if (token != null) {
                return token.getBytes();
            }
            return "NATIVE_DEFAULT_KEY".getBytes();
        } catch (Exception e) {
            return "FALLBACK_KEY".getBytes();
        }
    }

    public long getSystemTimeOffset() {
        try {
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public boolean isPlayerAuthenticated(java.util.UUID playerUuid) {
        try {
            return nativePluginManager(ACTION_PERMISSION_CHECK,
                    "PLAYER",
                    playerUuid.toString(),
                    "AUTH_CHECK");
        } catch (Exception e) {
            return false;
        }
    }

    public void authenticatePlayer(java.util.UUID playerUuid) {
        try {
            nativePluginManager(ACTION_PERMISSION_CHECK,
                    "PLAYER",
                    playerUuid.toString(),
                    "AUTH_GRANT");
        } catch (Exception e) {
            getLogger().warning("Failed to authenticate player: " + playerUuid);
        }
    }

    // 撤销玩家认证
    public void revokePlayerAuthentication(java.util.UUID playerUuid) {
        try {
            nativePluginManager(ACTION_PERMISSION_CHECK,
                    "PLAYER",
                    playerUuid.toString(),
                    "AUTH_REVOKE");
        } catch (Exception e) {
            getLogger().warning("Failed to revoke authentication for player: " + playerUuid);
        }
    }

    // 操作验证
    public boolean validateOperation(String operation) {
        try {
            return nativePluginManager(ACTION_PERMISSION_CHECK,
                    "OPERATION",
                    operation,
                    "VALIDATE");
        } catch (Exception e) {
            getLogger().warning("Operation validation failed for: " + operation);
            return false; // 默认拒绝未知操作
        }
    }

    // 配置值验证（通过Native层）
    public boolean isConfigValueValid(String configValue) {
        try {
            return nativePluginManager(ACTION_PERMISSION_CHECK,
                    "CONFIG",
                    configValue,
                    "VALIDATE");
        } catch (Exception e) {
            getLogger().warning("Config validation failed for: " + configValue);
            return true; // 默认允许配置值（向后兼容）
        }
    }

    /**
     * 重载所有配置文件
     * 这个方法应该被reload命令调用
     */
    public void reloadConfigurations() {
        try {
            getLogger().info(LanguageManager.getInstance()
                    .getString("messages.reloading-config"));

            // 重载语言管理器配置
            LanguageManager.getInstance(this);

            // 重载寻路器配置 - 这是关键！
            Pathfinder.loadConfig(this);

            // 重新加载主配置（包含允许导航隐身玩家的开关）
            this.reloadConfig();

            // 重新加载路标
            WaypointService.getInstance().init(this);

            getLogger().info(LanguageManager.getInstance()
                    .getString("messages.config-reload-complete"));
        } catch (Exception e) {
            getLogger().severe("Configuration reload failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command,
            String alias,
            String[] args) {
        java.util.List<String> completions = new java.util.ArrayList<>();

        if (command.getName().equalsIgnoreCase("toc")) {
            if (args.length == 1) {
                java.util.List<String> subcommands = new java.util.ArrayList<>();

                // 插件禁用态不返回任何补全
                try {
                    if (!getPluginStatus()) {
                        return completions;
                    }
                } catch (Exception ignore) {
                }

                subcommands.add("lang");
                subcommands.add("nav");

                if (sender.hasPermission("toc.cd")) {
                    subcommands.add("cd");
                }
                if (sender.hasPermission("toc.admin") || sender.isOp()) {
                    subcommands.add("admin");
                    subcommands.add("status");
                    subcommands.add("reload");
                    subcommands.add("reconfig");
                    subcommands.add("relang");
                }

                String input = args[0].toLowerCase();
                for (String cmd : subcommands) {
                    if (cmd.toLowerCase().startsWith(input)) {
                        completions.add(cmd);
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
                try {
                    LanguageManager langManager = LanguageManager.getInstance();
                    if (langManager != null) {
                        String[] availableLanguages = langManager.getAvailableLanguages();
                        String input = args[1].toLowerCase();

                        for (String lang : availableLanguages) {
                            if (lang.toLowerCase().startsWith(input)) {
                                completions.add(lang);
                            }
                        }

                        if ("reset".startsWith(input)) {
                            completions.add("reset");
                        }
                    }
                } catch (Exception e) {
                    completions.add("zh-CN");
                    completions.add("en-US");
                    completions.add("reset");
                }
            } else if (args[0].equalsIgnoreCase("nav")) {
                try {
                    // nav 子命令补全
                    if (args.length == 2) {
                        java.util.List<String> subs = new java.util.ArrayList<>();
                        // 管理子命令 - 需要对应权限或 nav.* / admin / OP
                        java.util.function.Consumer<String> addIf = (perm) -> {
                            boolean isOp = (sender instanceof org.bukkit.entity.Player)
                                    && ((org.bukkit.entity.Player) sender).isOp();
                            if (sender.hasPermission(perm) || sender.hasPermission("toc.nav.*") || isOp) {
                                subs.add(perm.substring("toc.nav.".length()));
                            }
                        };
                        addIf.accept("toc.nav.add");
                        addIf.accept("toc.nav.remove");
                        addIf.accept("toc.nav.rename");
                        addIf.accept("toc.nav.set");
                        addIf.accept("toc.nav.start");
                        // 玩家子命令
                        if (sender.hasPermission("toc.nav.go") || sender.hasPermission("toc.nav.*")
                                || !(sender instanceof org.bukkit.entity.Player))
                            subs.add("go");
                        if (sender.hasPermission("toc.nav.stop") || sender.hasPermission("toc.nav.*")
                                || !(sender instanceof org.bukkit.entity.Player))
                            subs.add("stop");
                        if (sender.hasPermission("toc.nav.list") || sender.hasPermission("toc.nav.*")
                                || !(sender instanceof org.bukkit.entity.Player))
                            subs.add("list");
                        if (sender.hasPermission("toc.view") || !(sender instanceof org.bukkit.entity.Player))
                            subs.add("view");
                        String p = args[1].toLowerCase();
                        for (String s : subs)
                            if (s.startsWith(p))
                                completions.add(s);
                    } else if (args.length >= 3) {
                        String sub = args[1].toLowerCase();
                        String p = args[args.length - 1];
                        java.util.function.Consumer<String> addIfMatch = (s) -> {
                            if (s.toLowerCase().startsWith(p.toLowerCase()))
                                completions.add(s);
                        };
                        if (sub.equals("remove") || sub.equals("rename") || sub.equals("go")) {
                            // 没有 toc.nav.list 权限时，go 不提供任何补全
                            if (sub.equals("go")
                                    && !(sender.hasPermission("toc.nav.list") || sender.hasPermission("toc.nav.*")
                                            || !(sender instanceof org.bukkit.entity.Player))) {
                                return completions;
                            }
                            java.util.List<Waypoint> wps = WaypointService.getInstance().list(null);
                            if (sub.equals("go") && sender instanceof org.bukkit.entity.Player pl) {
                                String world = pl.getWorld().getName();
                                wps.removeIf(wp -> !wp.getWorld().equals(world));
                            }
                            for (Waypoint wp : wps)
                                addIfMatch.accept(wp.getName());
                        } else if (sub.equals("set")) {
                            {
                                if (!(sender.hasPermission("toc.nav.set") || sender.hasPermission("toc.nav.*")
                                        || sender.hasPermission("toc.admin")
                                        || !(sender instanceof org.bukkit.entity.Player))) {
                                    return completions;
                                }
                            }
                            if (args.length == 3) {
                                for (Waypoint wp : WaypointService.getInstance().list(null))
                                    addIfMatch.accept(wp.getName());
                            } else if (args.length == 4) {
                                for (String f : new String[] { "x", "y", "z", "world" })
                                    addIfMatch.accept(f);
                            } else if (args.length == 5) {
                                if ("world".equalsIgnoreCase(args[3])) {
                                    for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds())
                                        addIfMatch.accept(w.getName());
                                }
                            }
                        } else if (sub.equals("start")) {
                            {
                                if (!(sender.hasPermission("toc.nav.start") || sender.hasPermission("toc.nav.*")
                                        || sender.hasPermission("toc.admin")
                                        || !(sender instanceof org.bukkit.entity.Player))) {
                                    return completions;
                                }
                            }
                            if (args.length == 3) {
                                for (org.bukkit.entity.Player pl : org.bukkit.Bukkit.getOnlinePlayers())
                                    addIfMatch.accept(pl.getName());
                            } else if (args.length == 4) {
                                for (Waypoint wp : WaypointService.getInstance().list(null))
                                    addIfMatch.accept(wp.getName());
                            }
                        } else if (sub.equals("stop")) {
                            {
                                // 只有具备停止他人权限或为控制台时，才给出玩家名补全
                                if (!(sender.hasPermission("toc.nav.stop.other")
                                        || !(sender instanceof org.bukkit.entity.Player))) {
                                    return completions;
                                }
                            }
                            if (args.length == 3) {
                                for (org.bukkit.entity.Player pl : org.bukkit.Bukkit.getOnlinePlayers())
                                    addIfMatch.accept(pl.getName());
                            }
                        } else if (sub.equals("add")) {
                            {
                                if (!(sender.hasPermission("toc.nav.add") || sender.hasPermission("toc.nav.*")
                                        || sender.hasPermission("toc.admin")
                                        || !(sender instanceof org.bukkit.entity.Player))) {
                                    return completions;
                                }
                            }
                            // /toc nav add <name> <x> <y> <z> [world]
                            if (args.length == 7) {
                                for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds())
                                    addIfMatch.accept(w.getName());
                            }
                        } else if (sub.equals("list")) {
                            if (!(sender.hasPermission("toc.nav.list") || sender.hasPermission("toc.nav.*")
                                    || sender.hasPermission("toc.admin")
                                    || !(sender instanceof org.bukkit.entity.Player))) {
                                return completions;
                            }
                            // 支持 --world= 和 --page=
                            if (p.startsWith("--world=")) {
                                String pref = p.substring("--world=".length()).toLowerCase();
                                for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
                                    String opt = "--world=" + w.getName();
                                    if (opt.toLowerCase().startsWith("--world=" + pref))
                                        completions.add(opt);
                                }
                            } else if ("--world=".startsWith(p.toLowerCase())) {
                                completions.add("--world=");
                            }
                            if (p.startsWith("--page=")) {
                                for (int i = 1; i <= 5; i++)
                                    completions.add("--page=" + i);
                            } else if ("--page=".startsWith(p.toLowerCase())) {
                                completions.add("--page=");
                            }
                        } else if (sub.equals("view")) {
                            if (!(sender.hasPermission("toc.view") || !(sender instanceof org.bukkit.entity.Player))) {
                                return completions;
                            }
                            if (p.startsWith("--page=")) {
                                for (int i = 1; i <= 5; i++)
                                    completions.add("--page=" + i);
                            } else if ("--page=".startsWith(p.toLowerCase())) {
                                completions.add("--page=");
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            }
        }

        return completions;
    }

    /**
     * 启动配置文件监视器
     */
    private void startConfigFileWatcher() {
        try {
            // 初始化数据文件夹路径
            dataFolderPath = getDataFolder().toPath();

            // 创建WatchService
            watchService = FileSystems.getDefault().newWatchService();

            // 注册监视事件：文件修改
            dataFolderPath.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);

            // 创建执行器线程池
            watchExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PathFinder-ConfigWatcher");
                t.setDaemon(true);
                return t;
            });

            watchingEnabled.set(true);

            // 提交监视任务
            watchExecutor.submit(this::watchConfigFiles);

            getLogger().info(LanguageManager.getInstance()
                    .getString("messages.watching-directory", dataFolderPath.toString()));

        } catch (Exception e) {
            getLogger().warning(LanguageManager.getInstance()
                    .getString("messages.config-watcher-error", e.getMessage()));
        }
    }

    /**
     * 停止配置文件监视器
     */
    private void stopConfigFileWatcher() {
        try {
            watchingEnabled.set(false);

            if (watchService != null) {
                watchService.close();
                getLogger().info(LanguageManager.getInstance()
                        .getString("messages.watch-service-closed"));
            }

            if (watchExecutor != null) {
                watchExecutor.shutdownNow();
            }

        } catch (Exception e) {
            getLogger().warning(LanguageManager.getInstance()
                    .getString("messages.watch-service-close-error", e.getMessage()));
        }
    }

    /**
     * 配置文件监视循环
     */
    private void watchConfigFiles() {
        while (watchingEnabled.get()) {
            try {
                WatchKey key = watchService.take(); // 阻塞等待事件

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    // 忽略overflow事件
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Path changedFile = (Path) event.context();
                    String fileName = changedFile.toString();

                    // 仅处理需要热重载的配置文件，排除运行期数据文件（如 waypoints.yml）
                    if (shouldReloadForFile(fileName)) {

                        // 简单去抖：1.5 秒内对同一文件的重复事件忽略
                        long now = System.currentTimeMillis();
                        Long last = lastConfigChangeMillis.get(fileName);
                        if (last != null && (now - last) < 1500L) {
                            continue;
                        }
                        lastConfigChangeMillis.put(fileName, now);

                        getLogger().info(LanguageManager.getInstance()
                                .getString("messages.config-change-detected", fileName));

                        // 延迟重载，避免文件正在写入时读取
                        getServer().getScheduler().runTaskLater(this, () -> {
                            try {
                                getLogger().info(LanguageManager.getInstance()
                                        .getString("messages.reloading-config"));

                                reloadConfigurations();

                                getLogger().info(LanguageManager.getInstance()
                                        .getString("messages.config-reloaded"));

                            } catch (Exception e) {
                                getLogger().warning(LanguageManager.getInstance()
                                        .getString("messages.config-watcher-error", e.getMessage()));
                            }
                        }, 20L); // 1秒延迟
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    break;
                }

            } catch (InterruptedException e) {
                if (watchingEnabled.get()) {
                    getLogger().warning("Config file watcher interrupted: " + e.getMessage());
                }
                break;
            } catch (Exception e) {
                if (watchingEnabled.get()) {
                    getLogger().warning(LanguageManager.getInstance()
                            .getString("messages.config-watcher-error", e.getMessage()));
                }
            }
        }
    }

    // 仅对核心配置文件变更触发重载；排除运行期数据文件（如 waypoints.yml）
    private boolean shouldReloadForFile(String fileName) {
        if (fileName == null)
            return false;
        // 排除 waypoints.yml（由运行时命令写入，不应触发全局重载）
        if ("waypoints.yml".equals(fileName))
            return false;
        // 允许的热重载文件列表
        if ("pathfinder.yml".equals(fileName))
            return true;
        if ("config.yml".equals(fileName))
            return true;
        // 如未来需要，可在此扩展其它白名单文件
        return false;
    }
}