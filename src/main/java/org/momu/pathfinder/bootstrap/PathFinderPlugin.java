package org.momu.pathfinder.bootstrap;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.momu.pathfinder.command.MainCommand;
import org.momu.pathfinder.config.LanguageManager;
import org.momu.pathfinder.navigation.algorithm.Pathfinder;
import org.momu.pathfinder.navigation.state.PlayerTracker;
import org.momu.pathfinder.presentation.listener.MasterListener;
import org.momu.pathfinder.waypoint.service.WaypointService;
import org.momu.pathfinder.runtime.TaskManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PathFinderPlugin extends JavaPlugin {

    private static PathFinderPlugin instance;

    private WatchService watchService;
    private ExecutorService watchExecutor;
    private final AtomicBoolean watchingEnabled = new AtomicBoolean(false);
    private Path dataFolderPath;
    private final Map<String, Long> lastConfigChangeMillis = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();

    public static PathFinderPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        try {
            ensureDataFiles();
            initializeServices();
            registerJavaEntrypoints();
            startConfigFileWatcher();
            getLogger().info("PathFinder enabled successfully.");
        } catch (Exception e) {
            getLogger().severe("Failed to enable PathFinder: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopConfigFileWatcher();
        try {
            PlayerTracker.getInstance().stopAllNavigations();
            PlayerTracker.getInstance().saveData(this);
        } catch (Exception e) {
            getLogger().warning("Failed to persist player tracker state: " + e.getMessage());
        }
        try {
            MasterListener.getGuiManager().shutdown();
        } catch (Exception e) {
            getLogger().warning("Failed to shutdown GUI manager: " + e.getMessage());
        }
        TaskManager.cancelAllTasks();
        instance = null;
        getLogger().info("PathFinder shutdown complete");
    }

    private void ensureDataFiles() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Unable to create plugin data folder: " + getDataFolder());
        }
        saveDefaultConfig();
        ensureBundledResource("pathfinder.yml");
    }

    private void ensureBundledResource(String fileName) {
        File target = new File(getDataFolder(), fileName);
        if (!target.exists()) {
            saveResource(fileName, false);
        }
    }

    private void initializeServices() {
        LanguageManager.getInstance(this);
        reloadConfig();
        Pathfinder.loadConfig(this);
        WaypointService.getInstance().init(this);
        PlayerTracker.getInstance().loadData(this);
    }

    private void registerJavaEntrypoints() {
        MainCommand mainCommand = new MainCommand(this);
        PluginCommand toc = getCommand("toc");
        if (toc == null) {
            throw new IllegalStateException("Command 'toc' is not defined in plugin.yml");
        }
        toc.setExecutor(mainCommand);
        toc.setTabCompleter(mainCommand);

        getServer().getPluginManager().registerEvents(new MasterListener(), this);
    }

    public void reloadConfigurations() {
        reloadConfig();
        LanguageManager.getInstance(this).reloadLanguage();
        Pathfinder.loadConfig(this);
        WaypointService.getInstance().init(this);
        PlayerTracker.getInstance().loadData(this);
        getLogger().info(LanguageManager.getInstance().getString("messages.config-reload-complete"));
    }

    public boolean canBypassNavigationRestrictions(Player player) {
        return player != null && (player.isOp() || player.hasPermission("toc.admin"));
    }

    public boolean canBypassNavigationRestrictions(java.util.UUID playerUuid) {
        return canBypassNavigationRestrictions(Bukkit.getPlayer(playerUuid));
    }

    private void startConfigFileWatcher() {
        try {
            dataFolderPath = getDataFolder().toPath();
            watchService = FileSystems.getDefault().newWatchService();
            watchedDirectories.clear();
            registerWatchDirectory(dataFolderPath);

            Path langDirectory = dataFolderPath.resolve("lang");
            if (Files.isDirectory(langDirectory)) {
                registerWatchDirectory(langDirectory);
            }

            watchExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PathFinder-ConfigReloadWatcher");
                t.setDaemon(true);
                return t;
            });

            watchingEnabled.set(true);
            watchExecutor.submit(this::watchConfigFiles);
            getLogger().info(LanguageManager.getInstance().getString("messages.watching-directory", dataFolderPath.toString()));
        } catch (Exception e) {
            getLogger().warning(LanguageManager.getInstance().getString("messages.config-watcher-error", e.getMessage()));
        }
    }

    private void stopConfigFileWatcher() {
        try {
            watchingEnabled.set(false);
            if (watchService != null) {
                watchService.close();
                getLogger().info(LanguageManager.getInstance().getString("messages.watch-service-closed"));
            }
            watchedDirectories.clear();
            if (watchExecutor != null) {
                watchExecutor.shutdownNow();
            }
        } catch (Exception e) {
            getLogger().warning(LanguageManager.getInstance().getString("messages.watch-service-close-error", e.getMessage()));
        }
    }

    private void registerWatchDirectory(Path directory) throws java.io.IOException {
        WatchKey watchKey = directory.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE);
        watchedDirectories.put(watchKey, directory);
    }

    private void watchConfigFiles() {
        while (watchingEnabled.get()) {
            try {
                WatchKey key = watchService.take();
                Path watchedDirectory = watchedDirectories.get(key);
                if (watchedDirectory == null) {
                    if (!key.reset()) {
                        break;
                    }
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path changedFile = (Path) event.context();
                    String fileName = toWatchedFileName(watchedDirectory, changedFile);
                    if (!shouldReloadForFile(fileName)) {
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    Long last = lastConfigChangeMillis.get(fileName);
                    if (last != null && (now - last) < 1500L) {
                        continue;
                    }
                    lastConfigChangeMillis.put(fileName, now);

                    getLogger().info(LanguageManager.getInstance().getString("messages.config-change-detected", fileName));
                    getServer().getScheduler().runTaskLater(this, () -> {
                        try {
                            getLogger().info(LanguageManager.getInstance().getString("messages.reloading-config"));
                            reloadConfigurations();
                            getLogger().info(LanguageManager.getInstance().getString("messages.config-reloaded"));
                        } catch (Exception e) {
                            getLogger().warning(LanguageManager.getInstance().getString("messages.config-watcher-error", e.getMessage()));
                        }
                    }, 20L);
                }

                if (!key.reset()) {
                    watchedDirectories.remove(key);
                    break;
                }
            } catch (InterruptedException e) {
                if (watchingEnabled.get()) {
                    getLogger().warning("Config file watcher interrupted: " + e.getMessage());
                }
                break;
            } catch (Exception e) {
                if (watchingEnabled.get()) {
                    getLogger().warning(LanguageManager.getInstance().getString("messages.config-watcher-error", e.getMessage()));
                }
            }
        }
    }

    private String toWatchedFileName(Path watchedDirectory, Path changedFile) {
        if (changedFile == null) {
            return "";
        }
        if (watchedDirectory == null || watchedDirectory.equals(dataFolderPath)) {
            return changedFile.toString();
        }
        return dataFolderPath.relativize(watchedDirectory.resolve(changedFile)).toString().replace('\\', '/');
    }

    private boolean shouldReloadForFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        if ("waypoints.yml".equals(fileName)) {
            return false;
        }
        if ("pathfinder.yml".equals(fileName) || "config.yml".equals(fileName)) {
            return true;
        }
        return fileName.startsWith("lang/") && fileName.endsWith(".yml");
    }
}
