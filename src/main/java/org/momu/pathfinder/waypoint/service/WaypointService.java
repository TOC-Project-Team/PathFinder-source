package org.momu.pathfinder.waypoint.service;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import org.momu.pathfinder.waypoint.model.Waypoint;
import org.momu.pathfinder.util.KeyUtils;

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

    public void init(PathFinderPlugin plugin) {
        storeFile = new File(plugin.getDataFolder(), "waypoints.yml");
        load();
    }

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

    public synchronized boolean remove(String nameOrKey) {
        String key = KeyUtils.normalizeKey(nameOrKey);
        Waypoint removed = waypoints.remove(key);
        if (removed != null) { saveAsync(); return true; }
        return false;
    }

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

    public synchronized Waypoint get(String nameOrKey) {
        return waypoints.get(KeyUtils.normalizeKey(nameOrKey));
    }

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
        PathFinderPlugin plugin = PathFinderPlugin.getInstance();
        if (plugin == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (storeFile == null) return;
                YamlConfiguration yaml = new YamlConfiguration();
                ConfigurationSection root = yaml.createSection("waypoints");
                for (Map.Entry<String, Waypoint> entry : waypoints.entrySet()) {
                    Waypoint wp = entry.getValue();
                    ConfigurationSection c = root.createSection(entry.getKey());
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
