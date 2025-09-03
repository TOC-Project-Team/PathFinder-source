package org.momu.tOCplugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class NavCommands {
    private NavCommands() {
    }

    public static void handle(CommandSender sender, String[] args) {
        List<String> tokens = tokenize(args, 1);
        if (tokens.isEmpty()) {
            usage(sender);
            return;
        }
        String action = tokens.get(0).toLowerCase();
        try {
            switch (action) {
                case "add":
                    handleAdd(sender, tokens);
                    break;
                case "remove":
                    handleRemove(sender, tokens);
                    break;
                case "rename":
                    handleRename(sender, tokens);
                    break;
                case "set":
                    handleSet(sender, tokens);
                    break;
                case "start":
                    handleStart(sender, tokens);
                    break;
                case "go":
                    handleGo(sender, tokens);
                    break;
                case "stop":
                    handleStop(sender, tokens);
                    break;
                case "list":
                    handleList(sender, tokens);
                    break;
                case "view":
                    handleView(sender, tokens);
                    break;
                default:
                    error(sender, LanguageManager.getInstance().getString(
                            sender instanceof Player ? (Player) sender : null, "messages.nav-unknown-op", action));
                    usage(sender);
            }
        } catch (IllegalArgumentException ex) {
            error(sender, ex.getMessage());
        } catch (Exception ex) {
            error(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                    "messages.nav-error", ex.getMessage()));
        }
    }

    private static void requirePerm(CommandSender sender, String perm) {
        if (!(sender instanceof Player)) return; // 控制台默认放行
        Player p = (Player) sender;
        if (p.hasPermission(perm) || p.hasPermission("toc.nav.*") || p.hasPermission("toc.admin")) {
            return;
        }
        throw new IllegalArgumentException(LanguageManager.getInstance().getString(p, "messages.no-permission"));
    }

    private static void handleAdd(CommandSender sender, List<String> t) {
        requirePerm(sender, "toc.nav.add");
        // 新增：3) add <名称> （仅名称，使用执行者当前位置与世界）
        if (t.size() == 2 && sender instanceof Player p) {
            String nameOnly = t.get(1);
            if (nameOnly.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        LanguageManager.getInstance().getString(p, "messages.nav-name-empty"));
            }
            if (nameOnly.trim().length() > 32) {
                throw new IllegalArgumentException(
                        LanguageManager.getInstance().getString(p, "messages.nav-name-too-long"));
            }
            String worldName = p.getWorld().getName();
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                throw new IllegalArgumentException(
                        LanguageManager.getInstance().getString(p, "messages.nav-world-missing", worldName));
            }
            org.bukkit.Location loc = p.getLocation();
            double x = loc.getX(), y = loc.getY(), z = loc.getZ();
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                throw new IllegalArgumentException(LanguageManager.getInstance().getString(p,
                        "messages.nav-y-out-of-range", world.getMinHeight(), (world.getMaxHeight() - 1)));
            }
            if (WaypointService.getInstance().add(nameOnly, worldName, x, y, z)) {
                ok(sender, LanguageManager.getInstance().getString(p, "messages.nav-add-ok", nameOnly, worldName, x, y,
                        z));
            } else {
                throw new IllegalArgumentException(LanguageManager.getInstance().getString(p, "messages.nav-add-fail"));
            }
            return;
        }
        // 支持两种格式：
        // 1) add <名称> <x> <y> <z> [world]
        // 2) add <x> <y> <z> [world] （自动将名称设为 "x,y,z"）
        if (t.size() < 5 && !(t.size() >= 4 && sender instanceof Player)) {
            // 可能是数值优先格式（共4个参数：add x y z），此时允许缺省世界
            boolean numericFirst = (t.size() >= 4 && isNumeric(t.get(1)) && isNumeric(t.get(2)) && isNumeric(t.get(3))
                    && (t.size() == 4 || t.size() == 5));
            if (!numericFirst) {
                usageAdd(sender);
                return;
            }
        }

        String name;
        Double xD;
        Double yD;
        Double zD;
        String worldName;

        boolean numericFirst = (t.size() >= 4 && isNumeric(t.get(1)) && isNumeric(t.get(2)) && isNumeric(t.get(3))
                && (t.size() == 4 || t.size() == 5));
        if (numericFirst) {
            // 采用数值优先格式
            xD = toDouble(t.get(1));
            yD = toDouble(t.get(2));
            zD = toDouble(t.get(3));
            worldName = (t.size() >= 5 ? t.get(4) : (sender instanceof Player p ? p.getWorld().getName() : null));
            name = String.format("%s,%s,%s", trimTrailingZeros(xD), trimTrailingZeros(yD), trimTrailingZeros(zD));
        } else {
            // 采用名称优先格式
            name = t.get(1);
            xD = toDouble(t.get(2));
            yD = toDouble(t.get(3));
            zD = toDouble(t.get(4));
            worldName = (t.size() >= 6 ? t.get(5) : (sender instanceof Player p ? p.getWorld().getName() : null));
        }

        if (xD == null || yD == null || zD == null) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-coord-number"));
        }
        if (worldName == null) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-world-required"));
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalArgumentException(LanguageManager.getInstance().getString(
                    sender instanceof Player ? (Player) sender : null, "messages.nav-world-missing", worldName));
        }
        double x = xD, y = yD, z = zD;
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            throw new IllegalArgumentException(
                    LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                            "messages.nav-y-out-of-range", world.getMinHeight(), (world.getMaxHeight() - 1)));
        }
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-name-empty"));
        }
        if (name.trim().length() > 32) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-name-too-long"));
        }
        if (WaypointService.getInstance().add(name, worldName, x, y, z)) {
            ok(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                    "messages.nav-add-ok", name, worldName, x, y, z));
        } else {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-add-fail"));
        }
    }

    private static void handleRemove(CommandSender sender, List<String> t) {
        requirePerm(sender, "toc.nav.remove");
        if (t.size() < 2) {
            usageRemove(sender);
            return;
        }
        if (WaypointService.getInstance().remove(t.get(1))) {
            ok(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                    "messages.nav-remove-ok", t.get(1)));
        } else {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-missing"));
        }
    }

    private static void handleRename(CommandSender sender, List<String> t) {
        requirePerm(sender, "toc.nav.rename");
        if (t.size() < 3) {
            usageRename(sender);
            return;
        }
        String oldName = t.get(1);
        String newName = t.get(2);
        if (newName.trim().isEmpty() || newName.trim().length() > 32) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-name-invalid"));
        }
        if (WaypointService.getInstance().rename(oldName, newName)) {
            ok(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                    "messages.nav-rename-ok", oldName, newName));
        } else {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-rename-fail"));
        }
    }

    private static void handleSet(CommandSender sender, List<String> t) {
        requirePerm(sender, "toc.nav.set");
        if (t.size() < 4) {
            usageSet(sender);
            return;
        }
        String name = t.get(1);
        String field = t.get(2).toLowerCase();
        String value = t.get(3);
        if (field.equals("x") || field.equals("y") || field.equals("z")) {
            Double v = toDouble(value);
            if (v == null)
                throw new IllegalArgumentException(LanguageManager.getInstance()
                        .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-value-number"));
            if (field.equals("y")) {
                Waypoint wp = WaypointService.getInstance().get(name);
                if (wp != null) {
                    World w = Bukkit.getWorld(wp.getWorld());
                    if (w != null && (v < w.getMinHeight() || v >= w.getMaxHeight())) {
                        throw new IllegalArgumentException(LanguageManager.getInstance().getString(
                                sender instanceof Player ? (Player) sender : null, "messages.nav-y-out-of-range",
                                w.getMinHeight(), (w.getMaxHeight() - 1)));
                    }
                }
            }
        } else if (field.equals("world")) {
            World w = Bukkit.getWorld(value);
            if (w == null)
                throw new IllegalArgumentException(LanguageManager.getInstance().getString(
                        sender instanceof Player ? (Player) sender : null, "messages.nav-world-missing", value));
        } else {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-field-invalid"));
        }
        if (WaypointService.getInstance().setField(name, field, value)) {
            ok(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                    "messages.nav-set-ok", name, field, value));
        } else {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-set-fail"));
        }
    }

    private static void handleStart(CommandSender sender, List<String> t) {
        requirePerm(sender, "toc.nav.start");
        if (t.size() < 3) {
            usageStart(sender);
            return;
        }
        Player target = Bukkit.getPlayer(t.get(1));
        if (target == null || !target.isOnline()) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.unknown-player"));
        }
        Waypoint wp = WaypointService.getInstance().get(t.get(2));
        if (wp == null) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.nav-missing"));
        }
        if (!target.getWorld().getName().equals(wp.getWorld())) {
            throw new IllegalArgumentException(LanguageManager.getInstance()
                    .getString(sender instanceof Player ? (Player) sender : null, "messages.target-dimension"));
        }
        if (!PlayerTracker.getInstance().canPlayerUseNavigation(target.getUniqueId())) {
            throw new IllegalArgumentException(LanguageManager.getInstance().getString(
                    sender instanceof Player ? (Player) sender : null, "messages.global-navigation-disabled"));
        }
        PlayerTracker.getInstance().stopNavigation(target.getUniqueId());
        boolean noBar = false;
        if (t.size() >= 4) {
            for (int i = 3; i < t.size(); i++) {
                String opt = t.get(i);
                if ("-nobar".equalsIgnoreCase(opt)) {
                    noBar = true;
                    break;
                }
            }
        }
        NavigationService.getInstance().startForPlayer(target, wp.toLocation(), wp.getName());
        if (noBar) {
            PlayerTracker.getInstance().suppressActionBarForCurrentSession(target.getUniqueId());
        }
        ok(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-start-ok", wp.getName()));
    }

    private static void handleGo(CommandSender sender, List<String> t) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException(LanguageManager.getInstance().getString("messages.player-usage"));
        }
        requirePerm(player, "toc.nav.go");
        if (t.size() < 2) {
            usageGo(sender);
            return;
        }
        Waypoint wp = WaypointService.getInstance().get(t.get(1));
        if (wp == null) {
            throw new IllegalArgumentException(LanguageManager.getInstance().getString(player, "messages.nav-missing"));
        }
        if (!player.getWorld().getName().equals(wp.getWorld())) {
            throw new IllegalArgumentException(
                    LanguageManager.getInstance().getString(player, "messages.target-dimension"));
        }
        if (!PlayerTracker.getInstance().canPlayerUseNavigation(player.getUniqueId())) {
            throw new IllegalArgumentException(
                    LanguageManager.getInstance().getString(player, "messages.global-navigation-disabled"));
        }
        NavigationService.getInstance().startForPlayer(player, wp.toLocation(), wp.getName());
        ok(sender, LanguageManager.getInstance().getString(player, "messages.nav-go-started", wp.getName()));
    }

    private static void handleStop(CommandSender sender, List<String> t) {
        if (t.size() >= 2) {
            // 指定其他玩家，仅允许控制台或具有精确权限 toc.nav.stop.other 的玩家
            if (!(sender instanceof Player)) {
                // 控制台允许
            } else {
                Player p = (Player) sender;
                boolean hasOther = p.hasPermission("toc.nav.stop.other") || p.hasPermission("toc.nav.*");
                if (!hasOther) {
                    throw new IllegalArgumentException(LanguageManager.getInstance().getString(p, "messages.no-permission"));
                }
            }
            Player target = Bukkit.getPlayer(t.get(1));
            if (target == null || !target.isOnline()) {
                throw new IllegalArgumentException(LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null, "messages.unknown-player"));
            }
            java.util.UUID tid = target.getUniqueId();
            // 判断是否有导航
            boolean navigating = PlayerTracker.getInstance().isNavigating(tid);
            if (!navigating) {
                // 该玩家没有导航
                sender.sendMessage("§e" + LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null, "messages.nav-stop-other-none", target.getName()));
                return;
            }
            // 解析目的地名称：优先 玩家->玩家，其次 路标名，其次 信标/要塞
            String dest = null;
            java.util.UUID tgtPlayer = PlayerTracker.getInstance().getNavigationTarget(tid);
            if (tgtPlayer != null) {
                org.bukkit.entity.Player tgt = Bukkit.getPlayer(tgtPlayer);
                if (tgt != null) dest = tgt.getName();
            }
            if (dest == null) {
                String wpName = PlayerTracker.getInstance().getWaypointName(tid);
                if (wpName != null) dest = wpName;
            }
            if (dest == null && PlayerTracker.getInstance().getBeaconNavigation(tid) != null) {
                dest = LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null, "messages.beacon-block");
            }
            if (dest == null && PlayerTracker.getInstance().getStrongholdNavigation(tid) != null) {
                dest = LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null, "messages.stronghold-name");
            }
            PlayerTracker.getInstance().stopNavigation(tid);
            // 已停止玩家XXX目的地为XXX的导航
            sender.sendMessage("§a" + LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null, "messages.nav-stop-other-ok", target.getName(), (dest == null ? LanguageManager.getInstance().getString("messages.unknown") : dest)));
            return;
        }
        // 自己停止
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException(LanguageManager.getInstance().getString("messages.player-usage"));
        }
        requirePerm(player, "toc.nav.stop");
        PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
        ok(sender, LanguageManager.getInstance().getString(player, "messages.nav-stop-ok"));
    }

    @SuppressWarnings("deprecation")
    private static void handleList(CommandSender sender, List<String> t) {
        if (sender instanceof Player p) requirePerm(p, "toc.nav.list");
        String worldFilter = null;
        int page = 1;
        final int pageSize = 10;
        if (t.size() >= 2 && t.get(1).startsWith("--world=")) {
            worldFilter = t.get(1).substring("--world=".length());
            if (t.size() >= 3 && t.get(2).startsWith("--page=")) {
                try {
                    page = Math.max(1, Integer.parseInt(t.get(2).substring("--page=".length())));
                } catch (Exception ignore) {
                }
            }
        } else if (t.size() >= 2 && t.get(1).startsWith("--page=")) {
            try {
                page = Math.max(1, Integer.parseInt(t.get(1).substring("--page=".length())));
            } catch (Exception ignore) {
            }
        }
        List<Waypoint> list = WaypointService.getInstance().list(worldFilter);
        if (list.isEmpty()) {
            info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                    "messages.nav-list-empty"));
            return;
        }
        int total = list.size();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        if (page > totalPages)
            page = totalPages;
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(total, from + pageSize);

        Player plOrNull = sender instanceof Player ? (Player) sender : null;
        String headerMsg = LanguageManager.getInstance().getString(plOrNull, "messages.nav-list-header",
                (worldFilter == null ? "*" : worldFilter) + "  (" + page + "/" + Math.max(1, totalPages) + ")");
        headerMsg = headerMsg.replace(" @§", " §").replace(" @", " ");
        info(sender, headerMsg);
        for (int i = from; i < to; i++) {
            Waypoint wp = list.get(i);
            String nameCol = "§a" + wp.getName();
            String worldCol = "§b" + wp.getWorld();
            String xCol = "§e" + (int) wp.getX();
            String yCol = "§e" + (int) wp.getY();
            String zCol = "§e" + (int) wp.getZ();
            String line = LanguageManager.getInstance().getString(plOrNull, "messages.nav-list-item", nameCol, worldCol,
                    xCol, yCol, zCol);
            line = line.replace(" @§", " §").replace(" @", " ");
            sender.sendMessage("§7" + line);
        }

        if (sender instanceof Player pl && totalPages > 1) {
            String base = "/toc nav list" + (worldFilter != null ? " --world=" + worldFilter : "");
            String prevCmd = base + " --page=" + Math.max(1, page - 1);
            String nextCmd = base + " --page=" + Math.min(totalPages, page + 1);
            pl.spigot().sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText("§7« "));
            net.md_5.bungee.api.chat.TextComponent prev = new net.md_5.bungee.api.chat.TextComponent("§b« ");
            prev.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, prevCmd));
            net.md_5.bungee.api.chat.TextComponent sep = new net.md_5.bungee.api.chat.TextComponent("§7| ");
            net.md_5.bungee.api.chat.TextComponent next = new net.md_5.bungee.api.chat.TextComponent("§b»");
            next.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, nextCmd));
            pl.spigot().sendMessage(prev, sep, next);
        }
    }

    @SuppressWarnings("deprecation")
    private static void handleView(CommandSender sender, List<String> t) {
        if (sender instanceof Player p && !(p.hasPermission("toc.view") || p.isOp())) {
            throw new IllegalArgumentException(LanguageManager.getInstance().getString(p, "messages.no-permission"));
        }
        int page = 1;
        final int pageSize = 10;
        if (t.size() >= 2 && t.get(1).startsWith("--page=")) {
            try { page = Math.max(1, Integer.parseInt(t.get(1).substring("--page=".length()))); } catch (Exception ignore) {}
        }
        java.util.Set<java.util.UUID> navigating = PlayerTracker.getInstance().getAllNavigatingPlayers();
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (java.util.UUID navId : navigating) {
            org.bukkit.entity.Player nav = org.bukkit.Bukkit.getPlayer(navId);
            if (nav == null) continue;
            String left = "§a" + nav.getName();
            String right = null;
            java.util.UUID tgtId = PlayerTracker.getInstance().getNavigationTarget(navId);
            if (tgtId != null) {
                org.bukkit.entity.Player tgt = org.bukkit.Bukkit.getPlayer(tgtId);
                if (tgt != null) right = "§b" + tgt.getName();
            }
            if (right == null) {
                // 路标
                Waypoint wp = WaypointService.getInstance().get(PlayerTracker.getInstance().getWaypointName(navId) == null ? "" : PlayerTracker.getInstance().getWaypointName(navId));
                if (wp != null) right = "§b" + wp.getName();
            }
            if (right == null) {
                // 信标
                if (PlayerTracker.getInstance().getBeaconNavigation(navId) != null) right = "§b" + LanguageManager.getInstance().getString(nav, "messages.beacon-block");
            }
            if (right == null) {
                // 要塞
                if (PlayerTracker.getInstance().getStrongholdNavigation(navId) != null) right = "§b" + LanguageManager.getInstance().getString(nav, "messages.stronghold-name");
            }
            if (right == null) continue;
            String rowLine = LanguageManager.getInstance().getString(nav, "messages.view-line", left, right);
            rows.add(rowLine);
        }

        if (rows.isEmpty()) {
            info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null, "messages.none"));
            return;
        }
        int total = rows.size();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        if (page > totalPages) page = totalPages;
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(total, from + pageSize);

        String header = "§7" + LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null, "messages.view-header", (page + "/" + Math.max(1, totalPages)));
        sender.sendMessage(header);
        for (int i = from; i < to; i++) sender.sendMessage(rows.get(i));

        // 翻页提示
        if (sender instanceof Player pl && totalPages > 1) {
            String base = "/toc nav view";
            String prevCmd = base + " --page=" + Math.max(1, page - 1);
            String nextCmd = base + " --page=" + Math.min(totalPages, page + 1);
            pl.spigot().sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText("§7« "));
            net.md_5.bungee.api.chat.TextComponent prev = new net.md_5.bungee.api.chat.TextComponent("§b« ");
            prev.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, prevCmd));
            net.md_5.bungee.api.chat.TextComponent sep = new net.md_5.bungee.api.chat.TextComponent("§7| ");
            net.md_5.bungee.api.chat.TextComponent next = new net.md_5.bungee.api.chat.TextComponent("§b»");
            next.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, nextCmd));
            pl.spigot().sendMessage(prev, sep, next);
        }
    }

    private static List<String> tokenize(String[] args, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (i > startIndex)
                sb.append(' ');
            sb.append(args[i]);
        }
        String s = sb.toString();
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '"' || n == '\\') {
                    cur.append(n);
                    i++;
                } else {
                    cur.append(c);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0)
            out.add(cur.toString());
        return out;
    }

    private static Double toDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty())
            return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String trimTrailingZeros(Double d) {
        if (d == null)
            return "0";
        String s = new java.text.DecimalFormat("0.########").format(d);
        return s;
    }

    private static void usage(CommandSender sender) {
        info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-usage"));
    }

    private static void usageAdd(CommandSender sender) {
        info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-usage-add"));
    }

    private static void usageRemove(CommandSender sender) {
        info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-usage-remove"));
    }

    private static void usageRename(CommandSender sender) {
        info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-usage-rename"));
    }

    private static void usageSet(CommandSender sender) {
        info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-usage-set"));
    }

    private static void usageStart(CommandSender sender) {
        info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-usage-start"));
    }

    private static void usageGo(CommandSender sender) {
        info(sender, LanguageManager.getInstance().getString(sender instanceof Player ? (Player) sender : null,
                "messages.nav-usage-go"));
    }

    private static void ok(CommandSender sender, String msg) {
        sender.sendMessage("§a" + msg);
    }

    private static void info(CommandSender sender, String msg) {
        sender.sendMessage("§7" + msg);
    }

    private static void error(CommandSender sender, String msg) {
        sender.sendMessage("§c" + msg);
    }
}