package org.momu.pathfinder.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class PathfinderConfig {
    public static int MAX_SEARCH_RADIUS = 3000;
    public static double PARTICLE_SPACING = 0.5;
    public static int MAX_PARTICLE_DISTANCE = 30;
    public static float PARTICLE_SIZE = 1.0f;
    public static int PATH_REFRESH_TICKS = 15;
    public static double DIAGONAL_COST = 4.0;
    public static double STRAIGHT_COST = 2.0;
    public static double RIGHT_ANGLE_TURN_COST = 1.5;
    public static double DIAGONAL_TURN_COST = 0.5;
    public static double BREAK_BLOCK_COST = 100.0;
    public static double WATER_COST = 10.0;
    public static double DOOR_COST = 0.0;
    public static double TRAPDOOR_COST = 0.0;
    public static double SCAFFOLDING_COST = 0.0;
    public static double JUMP_COST = 0.0;
    public static double VERTICAL_COST = 1.0;
    public static double FALL_COST = 1.0;
    public static double BLOCK_JUMP_COST = 1.0;
    public static int MAX_BLOCK_JUMP_DISTANCE = 3;
    public static int MAX_SAFE_FALL_HEIGHT = 3;
    public static int MAX_ITERATIONS = 10000;
    public static boolean ENABLE_PATH_CACHING = false;

    public static void loadConfig(PathFinderPlugin plugin) {
        File pathfinderFile = new File(plugin.getDataFolder(), "pathfinder.yml");
        if (!pathfinderFile.exists()) {
            plugin.saveResource("pathfinder.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(pathfinderFile);
        InputStream defaultConfigStream = plugin.getResource("pathfinder.yml");
        if (defaultConfigStream != null) {
            try {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream));
                boolean configUpdated = false;
                for (String key : defaultConfig.getKeys(true)) {
                    if (!config.contains(key)) {
                        config.set(key, defaultConfig.get(key));
                        configUpdated = true;
                    }
                }
                if (configUpdated) {
                    config.save(pathfinderFile);
                }
            } catch (Exception ignored) {
            } finally {
                try { defaultConfigStream.close(); } catch (IOException ignored) {}
            }
        }
        MAX_SEARCH_RADIUS = config.getInt("max_search_radius", 3000);
        PARTICLE_SPACING = config.getDouble("particle_spacing", 0.5);
        MAX_PARTICLE_DISTANCE = config.getInt("max_particle_distance", 30);
        PARTICLE_SIZE = (float) config.getDouble("particle_size", 1.0);
        PATH_REFRESH_TICKS = config.getInt("path_refresh_ticks", 15);
        DIAGONAL_COST = config.getDouble("diagonal_cost", 4.0);
        STRAIGHT_COST = config.getDouble("straight_cost", 2.0);
        RIGHT_ANGLE_TURN_COST = config.getDouble("right_angle_turn_cost", 1.5);
        DIAGONAL_TURN_COST = config.getDouble("diagonal_turn_cost", 0.5);
        BREAK_BLOCK_COST = config.getDouble("break_block_cost", 100.0);
        WATER_COST = config.getDouble("water_cost", 10.0);
        DOOR_COST = config.getDouble("door_cost", 0.0);
        TRAPDOOR_COST = config.getDouble("trapdoor_cost", 0.0);
        SCAFFOLDING_COST = config.getDouble("scaffolding_cost", 0.0);
        JUMP_COST = config.getDouble("jump_cost", 0.0);
        VERTICAL_COST = config.getDouble("vertical_cost", 1.0);
        FALL_COST = config.getDouble("fall_cost", 1.0);
        BLOCK_JUMP_COST = config.getDouble("block_jump_cost", 1.0);
        MAX_BLOCK_JUMP_DISTANCE = config.getInt("max_block_jump_distance", 3);
        MAX_SAFE_FALL_HEIGHT = config.getInt("max_safe_fall_height", 3);
        MAX_ITERATIONS = config.getInt("max_iterations", 10000);
        ENABLE_PATH_CACHING = config.getBoolean("enable_path_caching", false);
    }
}

