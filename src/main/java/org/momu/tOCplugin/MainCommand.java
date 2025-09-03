package org.momu.tOCplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainCommand
        implements CommandExecutor,
        TabCompleter {
    private final TOCpluginNative plugin;

    public MainCommand(TOCpluginNative plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            String[] args) {

        if (!plugin.areCoreModulesEnabled()) {
            sender.sendMessage(Component.text("❌ Service temporarily unavailable", NamedTextColor.RED));
            return true;
        }

        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("reload") || subCommand.equals("admin")) {
                // OP玩家可以绕过native安全检查，使用标准权限系统
                if (!sender.isOp() && !plugin.canExecuteSensitiveCommands()) {
                    String noPermMsg = sender instanceof Player
                            ? LanguageManager.getInstance().getString((Player) sender, "messages.no-permission")
                            : LanguageManager.getInstance().getString("messages.no-permission");
                    sender.sendMessage(Component.text(noPermMsg, NamedTextColor.RED));
                    return true;
                }
            }
        }

        if (args.length == 0) {
            sendHelpMessage(sender, label);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload" -> handleReload(sender);
            case "cd" -> handlePlayerNavigation(sender);
            case "status" -> handleStatusCheck(sender);
            case "lang" -> handleLanguage(sender, args);
            case "admin" -> handleAdminMenu(sender);
            case "nav" -> {
                try {
                    NavCommands.handle(sender, args);
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Error: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            default -> {
                String unknownMsg = sender instanceof Player
                        ? LanguageManager.getInstance().getString((Player) sender, "messages.unknown-command", label)
                        : LanguageManager.getInstance().getString("messages.unknown-command", label);
                sender.sendMessage(Component.text(unknownMsg, NamedTextColor.RED));
            }
        }
        return true;
    }

    private void sendHelpMessage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("--- PathFinder v" + this.plugin.getPluginMeta().getVersion() + " ---",
                NamedTextColor.GOLD));

        // 所有玩家都能使用的命令（默认权限）
        if (sender.hasPermission("toc.cd")) {
            String cdMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.cd")
                    : LanguageManager.getInstance().getString("messages.cd");
            sender.sendMessage(Component.text("/" + label + " cd", NamedTextColor.AQUA).append(
                    Component.text(cdMsg, NamedTextColor.GRAY)));
        }

        // status和lang命令对所有人开放，无需权限
        String statusMsg = sender instanceof Player
                ? LanguageManager.getInstance().getString((Player) sender, "messages.status")
                : LanguageManager.getInstance().getString("messages.status");
        sender.sendMessage(Component.text("/" + label + " status", NamedTextColor.AQUA).append(
                Component.text(statusMsg, NamedTextColor.GRAY)));

        String langMsg = sender instanceof Player
                ? LanguageManager.getInstance().getString((Player) sender, "messages.lang")
                : LanguageManager.getInstance().getString("messages.lang");
        sender.sendMessage(Component.text("/" + label + " lang <language>", NamedTextColor.AQUA).append(
                Component.text(langMsg, NamedTextColor.GRAY)));

        // 管理员命令
        if (sender.hasPermission("toc.admin")) {
            String reloadMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.reload")
                    : LanguageManager.getInstance().getString("messages.reload");
            sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.AQUA).append(
                    Component.text(reloadMsg, NamedTextColor.GRAY)));

            String adminMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.admin")
                    : LanguageManager.getInstance().getString("messages.admin");
            sender.sendMessage(Component.text("/" + label + " admin", NamedTextColor.AQUA).append(
                    Component.text(adminMsg, NamedTextColor.GRAY)));
        }
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("toc.admin")) {
            String noPermMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.no-permission")
                    : LanguageManager.getInstance().getString("messages.no-permission");
            sender.sendMessage(Component.text(noPermMsg, NamedTextColor.RED));
            return;
        }

        try {
            plugin.reloadConfig();
            LanguageManager.getInstance().loadLanguage();

            String reloadSuccessMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.reload-success")
                    : LanguageManager.getInstance().getString("messages.reload-success");
            sender.sendMessage(Component.text(reloadSuccessMsg, NamedTextColor.GREEN));
        } catch (Exception e) {
            String reloadErrorMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.reload-error")
                    : LanguageManager.getInstance().getString("messages.reload-error");
            sender.sendMessage(Component.text(reloadErrorMsg, NamedTextColor.RED));
            plugin.getLogger().severe("Reload failed: " + e.getMessage());
        }
    }

    private void handlePlayerNavigation(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // 使用语言文件键值，支持国际化
            sender.sendMessage(Component.text(
                    LanguageManager.getInstance().getString("messages.console-cd-not-available"), NamedTextColor.RED));
            sender.sendMessage(Component.text(
                    LanguageManager.getInstance().getString("messages.console-cd-gui-required"), NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    LanguageManager.getInstance().getString("messages.console-cd-alternative"), NamedTextColor.GRAY));
            return;
        }

        if (!sender.hasPermission("toc.cd")) {
            sender.sendMessage(Component.text(LanguageManager.getInstance().getString(player, "messages.no-permission"),
                    NamedTextColor.RED));
            return;
        }

        // 打开导航GUI
        if (MasterListener.getGuiManager() != null) {
            MasterListener.getGuiManager().openPlayerNavigationMenu(player);
        } else {
            sender.sendMessage(Component.text(
                    LanguageManager.getInstance().getString(player, "messages.gui-unavailable"), NamedTextColor.RED));
        }
    }

    private void handleStatusCheck(CommandSender sender) {
        if (!sender.hasPermission("toc.admin")) {
            String noPermMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.no-permission")
                    : LanguageManager.getInstance().getString("messages.no-permission");
            sender.sendMessage(Component.text(noPermMsg, NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("═══════════════════════════════════", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("     PATHFINDER STATUS REPORT", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("═══════════════════════════════════", NamedTextColor.AQUA));

        // 基本状态信息（简化版）
        sender.sendMessage(
                Component.text("Plugin Version: " + plugin.getPluginMeta().getVersion(), NamedTextColor.WHITE));

        // 在线玩家信息
        int playerCount = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        sender.sendMessage(Component.text("Players Online: " + playerCount + "/" + maxPlayers, NamedTextColor.WHITE));

        // 服务器信息
        String serverVersion = Bukkit.getVersion();
        sender.sendMessage(Component.text("Server: " + serverVersion, NamedTextColor.WHITE));

        sender.sendMessage(Component.text("═══════════════════════════════════", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("", NamedTextColor.WHITE));
    }

    private void handleLanguage(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // 显示当前语言和可用语言
            if (sender instanceof Player player) {
                String currentLang = LanguageManager.getInstance().getPlayerLanguage(player.getUniqueId());
                if (currentLang != null) {
                    sender.sendMessage(Component.text(
                            LanguageManager.getInstance().getString(player, "messages.lang-current", currentLang),
                            NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(
                            Component.text(LanguageManager.getInstance().getString(player, "messages.lang-default",
                                    LanguageManager.getInstance().getCurrentLanguage()), NamedTextColor.GREEN));
                }
            } else {
                sender.sendMessage(
                        Component.text(LanguageManager.getInstance().getString("messages.lang-console-default",
                                LanguageManager.getInstance().getCurrentLanguage()), NamedTextColor.GREEN));
            }

            // 显示可用语言
            String[] availableLanguages = LanguageManager.getInstance().getAvailableLanguages();
            if (availableLanguages.length > 0) {
                String langMsg = sender instanceof Player
                        ? LanguageManager.getInstance().getString((Player) sender, "messages.lang-available",
                                String.join(", ", availableLanguages))
                        : LanguageManager.getInstance().getString("messages.lang-available",
                                String.join(", ", availableLanguages));
                sender.sendMessage(Component.text(langMsg, NamedTextColor.GRAY));
            }
            String usageMsg = sender instanceof Player
                    ? LanguageManager.getInstance().getString((Player) sender, "messages.lang-usage")
                    : LanguageManager.getInstance().getString("messages.lang-usage");
            sender.sendMessage(Component.text(usageMsg, NamedTextColor.YELLOW));
            return;
        }

        String language = args[1];

        if (language.equalsIgnoreCase("reset")) {
            // 重置玩家语言
            if (sender instanceof Player player) {
                if (!player.hasPermission("toc.lang")) {
                    // 无权限：强制使用默认语言（移除自定义语言）
                    LanguageManager.getInstance().removePlayerLanguage(player.getUniqueId());
                    sender.sendMessage(Component.text(
                            LanguageManager.getInstance().getString(player, "messages.no-permission"),
                            NamedTextColor.RED));
                    return;
                }
                LanguageManager.getInstance().removePlayerLanguage(player.getUniqueId());
                sender.sendMessage(Component.text(
                        LanguageManager.getInstance().getString(player, "messages.lang-reset"), NamedTextColor.GREEN));
            } else {
                String cannotResetMsg = LanguageManager.getInstance().getString("messages.lang-console-cannot-reset");
                sender.sendMessage(Component.text(cannotResetMsg, NamedTextColor.RED));
            }
            return;
        }

        // 设置语言
        if (sender instanceof Player player) {
            // 玩家执行：设置个人语言（需要 toc.lang 权限）
            if (!player.hasPermission("toc.lang")) {
                // 无权限：强制使用默认语言（移除自定义语言）
                LanguageManager.getInstance().removePlayerLanguage(player.getUniqueId());
                sender.sendMessage(Component.text(
                        LanguageManager.getInstance().getString(player, "messages.no-permission"),
                        NamedTextColor.RED));
                return;
            }
            boolean success = LanguageManager.getInstance().setPlayerLanguage(player.getUniqueId(), language);
            if (success) {
                // 直接使用目标语言显示成功消息
                String successMsg = LanguageManager.getInstance().getStringByLanguage(language, "messages.lang-set",
                        language);
                sender.sendMessage(Component.text(successMsg, NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text(
                        LanguageManager.getInstance().getString(player, "messages.lang-not-found", language),
                        NamedTextColor.RED));
            }
        } else {
            // 控制台执行：设置默认语言
            if (LanguageManager.getInstance().isLanguageAvailable(language)) {
                plugin.getConfig().set("language", language);
                plugin.saveConfig();
                LanguageManager.getInstance().loadLanguage();

                // 发送成功消息
                String successMsg = LanguageManager.getInstance().getString("messages.lang-default-set", language);
                sender.sendMessage(Component.text(successMsg, NamedTextColor.GREEN));
                plugin.getLogger().info("Default language changed to: " + language);

                // 重新显示主界面
                sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GREEN));
                String cmdCdMsg = LanguageManager.getInstance().getString("messages.c-command-cd");
                String cmdReloadMsg = LanguageManager.getInstance().getString("messages.c-command-reload");
                String cmdStatusMsg = LanguageManager.getInstance().getString("messages.c-command-status");
                String cmdAdminMsg = LanguageManager.getInstance().getString("messages.c-command-admin");

                sender.sendMessage(Component.text(cmdCdMsg, NamedTextColor.WHITE));
                sender.sendMessage(Component.text(cmdReloadMsg, NamedTextColor.WHITE));
                sender.sendMessage(Component.text(cmdStatusMsg, NamedTextColor.WHITE));
                sender.sendMessage(Component.text(cmdAdminMsg, NamedTextColor.WHITE));
                sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GREEN));

            } else {
                sender.sendMessage(
                        Component.text(LanguageManager.getInstance().getString("messages.lang-not-found", language),
                                NamedTextColor.RED));
            }
        }
    }

    private void handleAdminMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // 使用语言文件键值
            sender.sendMessage(
                    Component.text(LanguageManager.getInstance().getString("messages.console-admin-not-available"),
                            NamedTextColor.RED));
            sender.sendMessage(
                    Component.text(LanguageManager.getInstance().getString("messages.console-admin-gui-required"),
                            NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    LanguageManager.getInstance().getString("messages.console-admin-commands"), NamedTextColor.GRAY));
            sender.sendMessage(Component.text(LanguageManager.getInstance().getString("messages.console-admin-reload"),
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text(LanguageManager.getInstance().getString("messages.console-admin-status"),
                    NamedTextColor.GRAY));
            return;
        }

        if (!sender.hasPermission("toc.admin")) {
            sender.sendMessage(Component.text(LanguageManager.getInstance().getString(player, "messages.no-permission"),
                    NamedTextColor.RED));
            return;
        }

        // 打开管理员GUI
        if (MasterListener.getGuiManager() != null) {
            MasterListener.getGuiManager().openMainMenu(player);
        } else {
            sender.sendMessage(Component.text(
                    LanguageManager.getInstance().getString(player, "messages.gui-unavailable"), NamedTextColor.RED));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // 主命令补全
            List<String> subCommands = new ArrayList<>();

            // 所有人都能使用的命令
            subCommands.add("status");
            subCommands.add("lang");

            if (sender.hasPermission("toc.cd")) {
                subCommands.add("cd");
            }

            if (sender.hasPermission("toc.admin")) {
                subCommands.add("reload");
                subCommands.add("admin");
                subCommands.add("nav");
            } else {
                subCommands.add("nav");
            }

            String partial = args[0].toLowerCase();
            for (String cmd : subCommands) {
                if (cmd.startsWith(partial)) {
                    completions.add(cmd);
                }
            }
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("nav")) {
            // 简易 nav 子命令补全（与 Native onTabComplete 对齐）
            String partial = args[1].toLowerCase();
            java.util.List<String> subs = new java.util.ArrayList<>();
            java.util.function.Consumer<String> addIf = (perm) -> {
                if (sender.hasPermission(perm) || sender.hasPermission("toc.nav.*") || sender.hasPermission("toc.admin")
                        || !(sender instanceof Player) || ((Player) sender).isOp()) {
                    subs.add(perm.substring("toc.nav.".length()));
                }
            };
            addIf.accept("toc.nav.add");
            addIf.accept("toc.nav.remove");
            addIf.accept("toc.nav.rename");
            addIf.accept("toc.nav.set");
            addIf.accept("toc.nav.start");
            if (sender.hasPermission("toc.nav.go") || sender.hasPermission("toc.nav.*")
                    || !(sender instanceof Player))
                subs.add("go");
            if (sender.hasPermission("toc.nav.stop") || sender.hasPermission("toc.nav.*")
                    || !(sender instanceof Player))
                subs.add("stop");
            if (sender.hasPermission("toc.nav.list") || sender.hasPermission("toc.nav.*")
                    || !(sender instanceof Player))
                subs.add("list");
            if (sender.hasPermission("toc.view") || !(sender instanceof Player))
                subs.add("view");
            for (String c : subs)
                if (c.startsWith(partial))
                    completions.add(c);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            // 语言补全
            String[] availableLanguages = LanguageManager.getInstance().getAvailableLanguages();
            List<String> languages = new ArrayList<>(Arrays.asList(availableLanguages));
            languages.add("reset");

            String partial = args[1].toLowerCase();
            for (String lang : languages) {
                if (lang.startsWith(partial)) {
                    completions.add(lang);
                }
            }
        }

        return completions;
    }
}