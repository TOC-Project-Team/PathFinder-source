package org.momu.pathfinder.runtime;

import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TaskManager {
    public static final Map<String, BukkitTask> activeTasks = new HashMap<>();
    public static final Map<UUID, BukkitTask> playerNavigationTasks = new HashMap<>();
    public static final Map<UUID, BukkitTask> beaconSearchTasks = new HashMap<>();
    public static final Map<UUID, BukkitTask> beaconTimeoutTasks = new HashMap<>();
    public static final Map<UUID, Long> arrivalNotifyCooldownUntil = new java.util.concurrent.ConcurrentHashMap<>();

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

    public static void cancelAllTasks() {
        for (BukkitTask task : activeTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        activeTasks.clear();

        for (BukkitTask task : playerNavigationTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        playerNavigationTasks.clear();

        for (BukkitTask task : beaconSearchTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        beaconSearchTasks.clear();

        for (BukkitTask task : beaconTimeoutTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        beaconTimeoutTasks.clear();
    }
}
