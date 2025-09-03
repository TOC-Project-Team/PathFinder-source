package org.momu.tOCplugin.config;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.momu.tOCplugin.Pathfinder;
import org.momu.tOCplugin.TOCpluginNative;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置文件监听器，用于监听配置文件变化并自动重载插件
 */
public class ConfigWatcher implements Runnable {
    private final JavaPlugin plugin;
    private final WatchService watchService;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private final Path pluginFolder;
    private boolean running = true;

    /**
     * 创建一个新的配置文件监听器
     * 
     * @param plugin 插件实例
     * @throws IOException 如果创建WatchService失败
     */
    public ConfigWatcher(JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.pluginFolder = plugin.getDataFolder().toPath();

        // 注册插件文件夹
        registerDirectory(pluginFolder);
        
        // 注册语言文件夹
        Path langFolder = pluginFolder.resolve("lang");
        if (Files.exists(langFolder) && Files.isDirectory(langFolder)) {
            registerDirectory(langFolder);
        }
    }

    /**
     * 注册目录以监听文件变化
     * 
     * @param dir 要监听的目录
     * @throws IOException 如果注册失败
     */
    private void registerDirectory(Path dir) throws IOException {
        WatchKey key = dir.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
        keys.put(key, dir);
        plugin.getLogger().info(LanguageManager.getInstance().getString("messages.watching-directory", dir));
    }

    @Override
    public void run() {
        plugin.getLogger().info(LanguageManager.getInstance().getString("messages.config-watcher-started"));

        try {
            while (running) {
                WatchKey key;
                try {
                    key = watchService.take(); // 阻塞直到有事件发生
                } catch (InterruptedException e) {
                    if (!running) {
                        break;
                    }
                    continue;
                } catch (ClosedWatchServiceException e) {
                    plugin.getLogger()
                            .warning(LanguageManager.getInstance().getString("messages.watch-service-closed"));
                    running = false;
                    break;
                }

                Path dir = keys.get(key);
                if (dir == null) {
                    plugin.getLogger().warning(LanguageManager.getInstance().getString("messages.unknown-directory"));
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    // 忽略OVERFLOW事件
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    // 获取文件名
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    // 检查是否是配置文件
                    String fileNameStr = fileName.toString();
                    // 检查是否是语言文件目录
                    if (dir.endsWith("lang") && fileNameStr.endsWith(".yml")) {
                        plugin.getLogger().info(
                                LanguageManager.getInstance().getString("messages.config-change-detected",
                                        "lang/" + fileNameStr));

                        // 延迟一点时间确保文件写入完成
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            plugin.getLogger()
                                    .info(LanguageManager.getInstance().getString("messages.reloading-language"));

                            // 重新加载语言文件
                            LanguageManager.getInstance().loadLanguage();
                            plugin.getLogger()
                                    .info(LanguageManager.getInstance().getString("messages.language-reloaded"));
                        }, 20L); // 延迟1秒
                    } else if (fileNameStr.equals("config.yml") || fileNameStr.equals("pathfinder.yml")) {
                        plugin.getLogger().info(
                                LanguageManager.getInstance().getString("messages.config-change-detected",
                                        fileNameStr));

                        // 延迟一点时间确保文件写入完成
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            plugin.getLogger()
                                    .info(LanguageManager.getInstance().getString("messages.reloading-config"));

                            // 重载配置
                            if (fileNameStr.equals("config.yml")) {
                                // 获取当前语言设置
                                String oldLanguage = LanguageManager.getInstance().getCurrentLanguage();
                                
                                plugin.reloadConfig();
                                
                                // 获取新的语言设置
                                String newLanguage = plugin.getConfig().getString("language", "message");
                                
                                // 如果语言设置发生变化，强制重载语言文件
                                if (!oldLanguage.equals(newLanguage)) {
                                    plugin.getLogger().info(
                                        LanguageManager.getInstance().getString("messages.reloading-language"));
                                    LanguageManager.getInstance().reloadLanguage();
                                    plugin.getLogger().info(
                                        LanguageManager.getInstance().getString("messages.language-reloaded"));
                                } else {
                                    // 重新加载语言文件，确保语言文件校对生效
                                    LanguageManager.getInstance().loadLanguage();
                                }
                                
                                plugin.getLogger()
                                        .info(LanguageManager.getInstance().getString("messages.config-reloaded"));
                            }

                            // 重载寻路配置
                            if (fileNameStr.equals("pathfinder.yml")) {
                                Pathfinder.loadConfig(TOCpluginNative.getInstance());
                                plugin.getLogger()
                                        .info(LanguageManager.getInstance().getString("messages.pathfinder-reloaded"));
                            }

                            plugin.getLogger()
                                    .info(LanguageManager.getInstance().getString("messages.config-reload-complete"));
                        }, 20L); // 延迟1秒
                    }
                }

                // 重置key，如果无效则移除
                boolean valid = key.reset();
                if (!valid) {
                    keys.remove(key);
                    if (keys.isEmpty()) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger()
                    .severe(LanguageManager.getInstance().getString("messages.config-watcher-error", e.getMessage()));
            e.printStackTrace();
        } finally {
            try {
                watchService.close();
            } catch (IOException e) {
                plugin.getLogger().severe(
                        LanguageManager.getInstance().getString("messages.watch-service-close-error", e.getMessage()));
            }
        }
    }

    /**
     * 停止监听
     */
    public void stop() {
        // 先设置running为false，确保线程可以正常退出
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            plugin.getLogger().severe(
                    LanguageManager.getInstance().getString("messages.watch-service-close-error", e.getMessage()));
        }
    }

    /**
     * 关闭监听器（与stop相同）
     */
    public void shutdown() {
        stop();
    }
}