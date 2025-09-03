package org.momu.tOCplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class Waypoint {
    private final String key;
    private String name;
    private String world;
    private double x;
    private double y;
    private double z;
    private long createdAt;
    private long updatedAt;

    public Waypoint(String name, String world, double x, double y, double z) {
        this.name = name;
        this.key = normalizeKey(name);
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static String normalizeKey(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase();
    }

    public String getKey() { return key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; touch(); }
    public String getWorld() { return world; }
    public void setWorld(String world) { this.world = world; touch(); }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; touch(); }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; touch(); }
    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; touch(); }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z);
    }

    private void touch() { this.updatedAt = System.currentTimeMillis(); }
} 