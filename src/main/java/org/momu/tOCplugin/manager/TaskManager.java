package org.momu.tOCplugin.manager;

import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TaskManager {
    // 任务管理器 - 跟踪所有异步任务
    public static final Map<String, BukkitTask> activeTasks = new HashMap<>();
    public static final Map<UUID, BukkitTask> playerNavigationTasks = new HashMap<>();
    public static final Map<UUID, BukkitTask> beaconSearchTasks = new HashMap<>();
    public static final Map<UUID, BukkitTask> beaconTimeoutTasks = new HashMap<>();
    public static final Map<UUID, Long> arrivalNotifyCooldownUntil = new java.util.concurrent.ConcurrentHashMap<>();

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
}
