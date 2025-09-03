package org.momu.tOCplugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LanguageManager {
    private static LanguageManager instance;
    private final JavaPlugin plugin;
    private FileConfiguration langConfig;
    private String currentLang = "en-US";
    private boolean isLanguageSwitch = false; // 标记是否为语言切换
    
    // 玩家个人语言设置
    private final Map<UUID, String> playerLanguages = new HashMap<>();
    private final Map<String, FileConfiguration> languageConfigs = new HashMap<>();
    private FileConfiguration playerLangConfig;

    private LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        // 首次启动时复制所有内置语言文件
        copyAllLanguageFiles();
        loadLanguage();
        // 初始化玩家语言配置
        loadPlayerLanguages();
    }

    public static LanguageManager getInstance(JavaPlugin plugin) {
        if (instance == null) {
            instance = new LanguageManager(plugin);
        }
        return instance;
    }

    public static LanguageManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("LanguageManager has not been initialized yet!");
        }
        return instance;
    }

    /**
     * 加载语言文件
     */
    public void loadLanguage() {
        // 从主配置文件获取当前语言设置
        FileConfiguration config = plugin.getConfig();
        String newLang = config.getString("language", "en-US");

        // 如果语言没有改变，直接返回
        if (newLang.equals(currentLang) && langConfig != null) {
            return;
        }

        // 检测是否为语言切换（无论是否第一次加载都检测语言变化）
        isLanguageSwitch = !newLang.equals(currentLang);
        currentLang = newLang;

        // 确保lang目录存在
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        // 加载语言文件
        File langFile = new File(langDir, currentLang + ".yml");

        if (!langFile.exists()) {
            // 语言文件不存在，尝试从JAR中复制默认文件
            try {
                plugin.saveResource("lang/" + currentLang + ".yml", false);
                plugin.getLogger().info("已创建默认语言文件: " + currentLang + ".yml");
            } catch (Exception e) {
                plugin.getLogger().warning("无法创建默认语言文件: " + e.getMessage());
                // 尝试使用english.yml作为备选
                if (!currentLang.equals("en-US")) {
                    try {
                        plugin.saveResource("lang/en-US.yml", false);
                        plugin.getLogger().info("已创建备选语言文件: en-US.yml");
                        currentLang = "en-US";
                        langFile = new File(langDir, "en-US.yml");
                    } catch (Exception ex) {
                        plugin.getLogger().warning("无法创建备选语言文件: " + ex.getMessage());
                        // 创建一个空配置
                        langConfig = new YamlConfiguration();
                        plugin.getLogger().warning("使用空语言配置");
                        return;
                    }
                } else {
                    // 创建一个空配置
                    langConfig = new YamlConfiguration();
                    plugin.getLogger().warning("使用空语言配置");
                    return;
                }
            }
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // 校对语言文件，确保所有键值都存在
        validateAndUpdateLanguageFile(langFile);

        plugin.getLogger().info("Language file loaded: " + currentLang);
    }

    /**
     * 强制重载语言文件（忽略缓存）
     */
    public void reloadLanguage() {
        langConfig = null; // 清除缓存
        languageConfigs.clear(); // 清除所有语言配置缓存
        
        // 自动同步语言文件
        int syncedFiles = syncAllLanguageFiles();
        if (syncedFiles > 0) {
            plugin.getLogger().info("Language files updated during reload: " + syncedFiles + " files synchronized");
        }
        
        loadLanguage();
    }

    /**
     * 获取当前语言名称
     * 
     * @return 当前语言名称
     */
    public String getCurrentLanguage() {
        return currentLang;
    }

    /**
     * 复制所有内置语言文件到lang目录
     */
    private void copyAllLanguageFiles() {
        // 确保lang目录存在
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        // 定义所有内置语言文件
        String[] languageFiles = { "zh-CN.yml", "zh-TW.yml", "en-US.yml", "ru-RU.yml", "pt-PT.yml", "fr-FR.yml",
                "es-ES.yml", "de-DE.yml" };

        for (String fileName : languageFiles) {
            File langFile = new File(langDir, fileName);
            // 只有文件不存在时才复制
            if (!langFile.exists()) {
                try {
                    plugin.saveResource("lang/" + fileName, false);
                } catch (Exception e) {
                    plugin.getLogger().warning("无法创建语言文件 " + fileName + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * 加载玩家语言配置文件
     */
    private void loadPlayerLanguages() {
        File playerLangFile = new File(plugin.getDataFolder(), "player-languages.yml");
        if (!playerLangFile.exists()) {
            try {
                playerLangFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create player-languages.yml: " + e.getMessage());
                return;
            }
        }
        
        playerLangConfig = YamlConfiguration.loadConfiguration(playerLangFile);
        
        // 加载玩家语言设置
        if (playerLangConfig.getConfigurationSection("players") != null) {
            for (String uuidStr : playerLangConfig.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String lang = playerLangConfig.getString("players." + uuidStr);
                    if (lang != null) {
                        playerLanguages.put(uuid, lang);
                        // 预加载语言配置
                        loadLanguageConfig(lang);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 忽略无效的UUID
                }
            }
        }
    }
    
    /**
     * 保存玩家语言配置
     */
    private void savePlayerLanguages() {
        if (playerLangConfig == null) return;
        
        // 清空现有配置
        playerLangConfig.set("players", null);
        
        // 保存所有玩家语言设置
        for (Map.Entry<UUID, String> entry : playerLanguages.entrySet()) {
            playerLangConfig.set("players." + entry.getKey().toString(), entry.getValue());
        }
        
        try {
            File playerLangFile = new File(plugin.getDataFolder(), "player-languages.yml");
            playerLangConfig.save(playerLangFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save player-languages.yml: " + e.getMessage());
        }
    }
    
    /**
     * 加载指定语言的配置文件
     */
    private void loadLanguageConfig(String language) {
        if (languageConfigs.containsKey(language)) {
            return; // 已经加载过
        }
        
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        
        if (!langFile.exists()) {
            // 尝试从JAR资源中复制文件
            try {
                plugin.saveResource("lang/" + language + ".yml", false);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to copy language file: " + language + ".yml");
                return;
            }
        }
        
        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(langFile);
            languageConfigs.put(language, config);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load language config: " + language);
        }
    }
    
    /**
     * 设置玩家的个人语言
     */
    public boolean setPlayerLanguage(UUID playerId, String language) {
        // 检查语言文件是否存在
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists()) {
            return false;
        }
        
        // 同步并确保语言文件包含最新键值
        try {
            boolean updated = syncLanguageFile(language);
            if (updated) {
                languageConfigs.remove(language);
            }
        } catch (Exception ignore) {}
        
        // 加载语言配置
        loadLanguageConfig(language);
        
        // 验证配置是否成功加载
        if (!languageConfigs.containsKey(language)) {
            return false;
        }
        
        // 设置玩家语言
        playerLanguages.put(playerId, language);
        
        // 保存配置
        savePlayerLanguages();
        
        return true;
    }
    
    /**
     * 获取玩家的个人语言
     */
    public String getPlayerLanguage(UUID playerId) {
        return playerLanguages.get(playerId);
    }
    
    /**
     * 移除玩家的个人语言设置，恢复为默认语言
     */
    public void removePlayerLanguage(UUID playerId) {
        playerLanguages.remove(playerId);
        savePlayerLanguages();
    }
    
    /**
     * 获取翻译文本
     * 
     * @param key 翻译键
     * @return 翻译后的文本，如果不存在则返回键名
     */
    public String getString(String key) {
        String value = langConfig.getString(key);
        return value != null ? value : key;
    }
    
    /**
     * 获取玩家特定的翻译文本
     * 
     * @param playerId 玩家UUID
     * @param key 翻译键
     * @return 翻译后的文本，如果不存在则返回键名
     */
    public String getString(UUID playerId, String key) {
        if (playerId == null) {
            return getString(key);
        }
        
        String playerLang = playerLanguages.get(playerId);
        if (playerLang == null) {
            // 玩家没有设置个人语言，使用默认语言
            return getString(key);
        }
        
        FileConfiguration config = languageConfigs.get(playerLang);
        if (config == null) {
            // 语言配置未加载，尝试加载
            loadLanguageConfig(playerLang);
            config = languageConfigs.get(playerLang);
            if (config == null) {
                // 仍然无法加载，使用默认语言
                return getString(key);
            }
        }
        
        String value = config.getString(key);
        if (value == null) {
            // 尝试同步一次该语言文件以补齐缺失键
            try {
                boolean updated = syncLanguageFile(playerLang);
                if (updated) {
                    languageConfigs.remove(playerLang);
                    loadLanguageConfig(playerLang);
                    FileConfiguration reloaded = languageConfigs.get(playerLang);
                    if (reloaded != null) {
                        value = reloaded.getString(key);
                    }
                }
            } catch (Exception ignore) {}
        }
        if (value == null) {
            // 仍缺失则回退到默认语言
            return getString(key);
        }
        
        return value;
    }
    
    /**
     * 获取玩家特定的翻译文本（便捷方法）
     */
    public String getString(Player player, String key) {
        return player != null ? getString(player.getUniqueId(), key) : getString(key);
    }
    
    /**
     * 直接使用指定语言获取翻译文本
     * 
     * @param language 语言代码
     * @param key 翻译键
     * @param args 参数
     * @return 翻译后的文本
     */
    public String getStringByLanguage(String language, String key, Object... args) {
        // 确保语言配置已加载
        if (!languageConfigs.containsKey(language)) {
            loadLanguageConfig(language);
        }
        
        FileConfiguration config = languageConfigs.get(language);
        if (config == null) {
            // 回退到默认语言
            plugin.getLogger().warning("无法加载语言配置 " + language + "，使用默认语言");
            String pattern = getString(key);
            return formatMessage(pattern, key, args);
        }
        
        String pattern = config.getString(key);
        if (pattern == null) {
            // 如果目标语言中没有该键，回退到默认语言
            pattern = getString(key);
        }
        
        return formatMessage(pattern, key, args);
    }
    
    /**
     * 获取可用的语言列表
     */
    public String[] getAvailableLanguages() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() || !langDir.isDirectory()) {
            return new String[0];
        }
        
        File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return new String[0];
        }
        
        String[] languages = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            String fileName = files[i].getName();
            languages[i] = fileName.substring(0, fileName.length() - 4); // 移除.yml扩展名
        }
        
        return languages;
    }
    
    /**
     * 检查语言是否可用
     */
    public boolean isLanguageAvailable(String language) {
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        return langFile.exists();
    }
    
    /**
     * 关闭语言管理器，保存所有配置
     */
    public void shutdown() {
        savePlayerLanguages();
    }
    

    
    /**
     * 智能同步语言文件：只添加缺失的键，保留现有的自定义翻译
     */
    public boolean syncLanguageFile(String language) {
        try {
            File langDir = new File(plugin.getDataFolder(), "lang");
            if (!langDir.exists()) {
                langDir.mkdirs();
            }
            
            File configLangFile = new File(langDir, language + ".yml");
            
            // 从JAR加载默认语言文件
            InputStream jarLangStream = plugin.getResource("lang/" + language + ".yml");
            if (jarLangStream == null) {
                return false;
            }
            
            FileConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarLangStream, StandardCharsets.UTF_8));
            
            FileConfiguration configFileConfig;
            
            if (!configLangFile.exists()) {
                // 文件不存在，直接复制
                plugin.saveResource("lang/" + language + ".yml", false);
                return true;
            } else {
                // 文件存在，加载现有配置
                configFileConfig = YamlConfiguration.loadConfiguration(configLangFile);
            }
            
            // 比较并添加缺失的键
            boolean hasChanges = syncConfigSections(jarConfig, configFileConfig, "");
            
            if (hasChanges) {
                // 保存更新后的文件
                configFileConfig.save(configLangFile);
                
                // 清除缓存并重新加载
                languageConfigs.remove(language);
                loadLanguageConfig(language);
                
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to sync language file: " + language);
            return false;
        }
    }
    
    /**
     * 递归同步配置节点
     */
    private boolean syncConfigSections(FileConfiguration jarConfig, FileConfiguration configFile, String path) {
        boolean hasChanges = false;
        
        Set<String> keys;
        if (path.isEmpty()) {
            keys = jarConfig.getKeys(false);
        } else {
            if (!jarConfig.isConfigurationSection(path)) {
                return false;
            }
            keys = jarConfig.getConfigurationSection(path).getKeys(false);
        }
        
        for (String key : keys) {
            String currentPath = path.isEmpty() ? key : path + "." + key;
            
            if (jarConfig.isConfigurationSection(currentPath)) {
                // 是配置节点，确保目标文件中也有此节点
                if (!configFile.isConfigurationSection(currentPath)) {
                    configFile.createSection(currentPath);
                    hasChanges = true;
                }
                
                // 递归处理子节点
                if (syncConfigSections(jarConfig, configFile, currentPath)) {
                    hasChanges = true;
                }
            } else {
                // 是普通键值，检查是否缺失
                if (!configFile.contains(currentPath)) {
                    Object value = jarConfig.get(currentPath);
                    configFile.set(currentPath, value);
                    hasChanges = true;
                }
                // 注意：如果键已存在，我们不覆盖它，保留用户的自定义翻译
            }
        }
        
        return hasChanges;
    }
    
    /**
     * 同步所有可用的语言文件
     */
    public int syncAllLanguageFiles() {
        String[] availableLanguages = {"zh-CN", "zh-TW", "en-US", "ru-RU", "pt-PT", "fr-FR", "es-ES", "de-DE"};
        int updatedCount = 0;
        
        for (String language : availableLanguages) {
            // 检查JAR中是否有这个语言文件
            InputStream jarStream = plugin.getResource("lang/" + language + ".yml");
            if (jarStream != null) {
                try {
                    jarStream.close();
                    boolean updated = syncLanguageFile(language);
                    if (updated) {
                        updatedCount++;
                    }
                } catch (IOException ignored) {
                    // 静默处理
                }
            }
        }
        
        return updatedCount;
    }
    


    /**
     * 获取带参数的翻译文本
     * 
     * @param key  翻译键
     * @param args 参数
     * @return 翻译后的文本，如果不存在则返回键名
     */
    public String getString(String key, Object... args) {
        String pattern = getString(key);
        return formatMessage(pattern, key, args);
    }
    
    /**
     * 获取玩家特定的带参数翻译文本
     */
    public String getString(UUID playerId, String key, Object... args) {
        String pattern = getString(playerId, key);
        return formatMessage(pattern, key, args);
    }
    
    /**
     * 获取玩家特定的带参数翻译文本（便捷方法）
     */
    public String getString(Player player, String key, Object... args) {
        String pattern = getString(player, key);
        return formatMessage(pattern, key, args);
    }
    
    /**
     * 格式化消息文本
     */
    private String formatMessage(String pattern, String key, Object... args) {
        // 先尝试使用 MessageFormat 占位符 {0} 方式
        if (pattern.matches(".*\\{\\d+}.*")) {
            try {
                return MessageFormat.format(pattern, args);
            } catch (Exception ignored) {
                // 如果失败，继续尝试 String.format
            }
        }
        // 若包含 % 占位符或 MessageFormat 失败，则尝试 String.format
        try {
            return String.format(pattern, args);
        } catch (Exception ignored) {
            // 两种方式均失败，返回原始字符串
            plugin.getLogger().warning("Error formatting message for key: " + key);
            return pattern;
        }
    }

    /**
     * 校对并更新语言文件，确保所有默认键值都存在
     * 
     * @param langFile 当前语言文件
     */
    private void validateAndUpdateLanguageFile(File langFile) {
        try {
            // 加载JAR包中的默认语言文件
            InputStream defaultLangStream = plugin.getResource("lang/" + currentLang + ".yml");
            if (defaultLangStream == null) {
                // 如果找不到当前语言的默认文件，尝试使用en-US.yml
                defaultLangStream = plugin.getResource("lang/en-US.yml");
                if (defaultLangStream == null) {
                    plugin.getLogger().warning("无法找到默认语言文件进行校对");
                    return;
                }
            }

            // 加载默认语言文件配置
            FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultLangStream, StandardCharsets.UTF_8));

            // 检查是否有缺失或需要更新的键值
            boolean hasChanges = false;

            // 根据是否为语言切换决定更新策略
            if (isLanguageSwitch) {
                // 语言切换时，强制更新所有键值以确保语言切换完整
                hasChanges = validateAndUpdateSection(defaultConfig, langConfig, "", true);
                plugin.getLogger().info("检测到语言切换，正在同步所有翻译键值");
            } else {
                // 普通重载时，只补充缺失的键值，保护用户自定义翻译
                hasChanges = validateAndUpdateSection(defaultConfig, langConfig, "", false);
            }

            // 如果有变更，保存文件
            if (hasChanges) {
                langConfig.save(langFile);
                if (isLanguageSwitch) {
                    plugin.getLogger().info("语言文件已更新，所有键值已同步到新语言");
                } else {
                    plugin.getLogger().info("语言文件已更新，补充了缺失的键值");
                }
            }

            // 重置语言切换标记
            isLanguageSwitch = false;
        } catch (Exception e) {
            plugin.getLogger().warning("校对语言文件时出错: " + e.getMessage());
        }
    }

    /**
     * 递归校对配置节点
     * 
     * @param defaultConfig 默认配置
     * @param currentConfig 当前配置
     * @param path          当前路径
     * @param forceUpdate   是否强制更新所有键值
     * @return 是否有变更
     */
    private boolean validateAndUpdateSection(FileConfiguration defaultConfig, FileConfiguration currentConfig,
            String path, boolean forceUpdate) {
        boolean hasChanges = false;

        // 获取当前路径下的所有键
        Set<String> keys;
        if (path.isEmpty()) {
            keys = defaultConfig.getKeys(false);
        } else {
            keys = defaultConfig.getConfigurationSection(path).getKeys(false);
        }

        // 检查每个键
        for (String key : keys) {
            String currentPath = path.isEmpty() ? key : path + "." + key;

            // 如果是配置节点，递归检查
            if (defaultConfig.isConfigurationSection(currentPath)) {
                // 确保当前配置中存在此节点
                if (!currentConfig.isConfigurationSection(currentPath)) {
                    currentConfig.createSection(currentPath);
                    hasChanges = true;
                }

                // 递归检查子节点
                if (validateAndUpdateSection(defaultConfig, currentConfig, currentPath, forceUpdate)) {
                    hasChanges = true;
                }
            } else {
                // 是普通键值，检查是否需要更新
                if (!currentConfig.contains(currentPath) || forceUpdate) {
                    // 不存在或强制更新，从默认配置复制
                    Object defaultValue = defaultConfig.get(currentPath);
                    Object currentValue = currentConfig.get(currentPath);

                    // 只有值不同时才更新
                    if (!defaultValue.equals(currentValue)) {
                        currentConfig.set(currentPath, defaultValue);
                        if (!currentConfig.contains(currentPath)) {
                            plugin.getLogger().info("添加缺失的语言键: " + currentPath);
                        } else {
                            plugin.getLogger().info("更新语言键: " + currentPath);
                        }
                        hasChanges = true;
                    }
                }
            }
        }

        return hasChanges;
    }
}