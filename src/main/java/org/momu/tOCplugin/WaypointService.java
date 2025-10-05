package org.momu.tOCplugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.momu.tOCplugin.internal.Waypoint;
import org.momu.tOCplugin.util.KeyUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class WaypointService {
    private WaypointService() {}
    private static class Holder {
        private static final WaypointService INSTANCE = new WaypointService();
    }
    public static WaypointService getInstance() { return Holder.INSTANCE; }

    private final Map<String, Waypoint> waypoints = new HashMap<>();
    private File storeFile;

    /**
     * 初始化路标服务，加载waypoints.yml。
     * @param plugin 插件实例
     */
    public void init(TOCpluginNative plugin) {
        storeFile = new File(plugin.getDataFolder(), "waypoints.yml");
        load();
    }

    /**
     * 添加一个新的路标。
     * @param name 路标名
     * @param world 世界名
     * @param x x坐标
     * @param y y坐标
     * @param z z坐标
     * @return 添加成功返回true，否则false
     */
    public synchronized boolean add(String name, String world, double x, double y, double z) {
        if (name == null || name.trim().isEmpty()) return false;
        String key = KeyUtils.normalizeKey(name);
        if (waypoints.containsKey(key)) return false;
        World w = Bukkit.getWorld(world);
        if (w == null) return false;
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return false;
        Waypoint wp = new Waypoint(name, world, x, y, z);
        waypoints.put(key, wp);
        saveAsync();
        return true;
    }

    /**
     * 移除指定路标。
     * @param nameOrKey 路标名或key
     * @return 移除成功返回true，否则false
     */
    public synchronized boolean remove(String nameOrKey) {
        String key = KeyUtils.normalizeKey(nameOrKey);
        Waypoint removed = waypoints.remove(key);
        if (removed != null) { saveAsync(); return true; }
        return false;
    }

    /**
     * 重命名路标。
     * @param oldName 旧名
     * @param newName 新名
     * @return 成功返回true，否则false
     */
    public synchronized boolean rename(String oldName, String newName) {
        String oldKey = KeyUtils.normalizeKey(oldName);
        String newKey = KeyUtils.normalizeKey(newName);
        if (!waypoints.containsKey(oldKey) || waypoints.containsKey(newKey)) return false;
        Waypoint wp = waypoints.remove(oldKey);
        wp.setName(newName);
        waypoints.put(newKey, wp);
        saveAsync();
        return true;
    }

    /**
     * 设置路标字段。
     * @param name 路标名
     * @param field 字段名
     * @param value 字段值
     * @return 成功返回true，否则false
     */
    public synchronized boolean setField(String name, String field, String value) {
        String key = KeyUtils.normalizeKey(name);
        Waypoint wp = waypoints.get(key);
        if (wp == null) return false;
        switch (field.toLowerCase()) {
            case "x": wp.setX(parseDouble(value, wp.getX())); break;
            case "y": wp.setY(parseDouble(value, wp.getY())); break;
            case "z": wp.setZ(parseDouble(value, wp.getZ())); break;
            case "world":
                if (Bukkit.getWorld(value) == null) return false;
                wp.setWorld(value);
                break;
            default: return false;
        }
        saveAsync();
        return true;
    }

    /**
     * 获取指定路标。
     * @param nameOrKey 路标名或key
     * @return 路标对象
     */
    public synchronized Waypoint get(String nameOrKey) {
        return waypoints.get(KeyUtils.normalizeKey(nameOrKey));
    }

    /**
     * 列出所有路标，支持按世界过滤。
     * @param worldName 世界名，可为null
     * @return 路标列表
     */
    public synchronized List<Waypoint> list(String worldName) {
        List<Waypoint> list = new ArrayList<>(waypoints.values());
        if (worldName != null) {
            list.removeIf(w -> !w.getWorld().equals(worldName));
        }
        list.sort(Comparator.comparing(Waypoint::getName, String::compareToIgnoreCase));
        return list;
    }

    private void load() {
        waypoints.clear();
        if (storeFile == null || !storeFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storeFile);
        ConfigurationSection ws = yaml.getConfigurationSection("waypoints");
        if (ws == null) return;
        for (String key : ws.getKeys(false)) {
            ConfigurationSection c = ws.getConfigurationSection(key);
            if (c == null) continue;
            String name = c.getString("name", key);
            String world = c.getString("world", "world");
            double x = c.getDouble("x");
            double y = c.getDouble("y");
            double z = c.getDouble("z");
            Waypoint wp = new Waypoint(name, world, x, y, z);
            waypoints.put(wp.getKey(), wp);
        }
    }

    private void saveAsync() {
        TOCpluginNative plugin = TOCpluginNative.getInstance();
        if (plugin == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (storeFile == null) return;
                YamlConfiguration yaml = new YamlConfiguration();
                ConfigurationSection root = yaml.createSection("waypoints");
                for (Waypoint wp : waypoints.values()) {
                    ConfigurationSection c = root.createSection(wp.getKey());
                    c.set("name", wp.getName());
                    c.set("world", wp.getWorld());
                    c.set("x", wp.getX());
                    c.set("y", wp.getY());
                    c.set("z", wp.getZ());
                    c.set("createdAt", wp.getCreatedAt());
                    c.set("updatedAt", wp.getUpdatedAt());
                }
                yaml.save(storeFile);
            } catch (IOException ignored) {}
        });
    }

    private static double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s); } catch (Exception e) { return fallback; }
    }
}
