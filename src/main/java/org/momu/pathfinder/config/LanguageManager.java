package org.momu.pathfinder.config;

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
    private boolean isLanguageSwitch = false;
    
    private final Map<UUID, String> playerLanguages = new HashMap<>();
    private final Map<String, FileConfiguration> languageConfigs = new HashMap<>();
    private FileConfiguration playerLangConfig;

    private LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        copyAllLanguageFiles();
        loadLanguage();
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

    public void loadLanguage() {
        FileConfiguration config = plugin.getConfig();
        String newLang = config.getString("language", "en-US");

        if (newLang.equals(currentLang) && langConfig != null) {
            return;
        }

        isLanguageSwitch = !newLang.equals(currentLang);
        currentLang = newLang;

        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        File langFile = new File(langDir, currentLang + ".yml");

        if (!langFile.exists()) {
            try {
                plugin.saveResource("lang/" + currentLang + ".yml", false);
                plugin.getLogger().info("已创建默认语言文件: " + currentLang + ".yml");
            } catch (Exception e) {
                plugin.getLogger().warning("无法创建默认语言文件: " + e.getMessage());
                if (!currentLang.equals("en-US")) {
                    try {
                        plugin.saveResource("lang/en-US.yml", false);
                        plugin.getLogger().info("已创建备选语言文件: en-US.yml");
                        currentLang = "en-US";
                        langFile = new File(langDir, "en-US.yml");
                    } catch (Exception ex) {
                        plugin.getLogger().warning("无法创建备选语言文件: " + ex.getMessage());
                        langConfig = new YamlConfiguration();
                        plugin.getLogger().warning("使用空语言配置");
                        return;
                    }
                } else {
                    langConfig = new YamlConfiguration();
                    plugin.getLogger().warning("使用空语言配置");
                    return;
                }
            }
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        validateAndUpdateLanguageFile(langFile);

        plugin.getLogger().info("Language file loaded: " + currentLang);
    }

    public void reloadLanguage() {
        langConfig = null;
        languageConfigs.clear();
        
        int syncedFiles = syncAllLanguageFiles();
        if (syncedFiles > 0) {
            plugin.getLogger().info("Language files updated during reload: " + syncedFiles + " files synchronized");
        }
        
        loadLanguage();
    }

    public String getCurrentLanguage() {
        return currentLang;
    }

    private void copyAllLanguageFiles() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        String[] languageFiles = { "zh-CN.yml", "zh-TW.yml", "en-US.yml", "ru-RU.yml", "pt-PT.yml", "fr-FR.yml",
                "es-ES.yml", "de-DE.yml" };

        for (String fileName : languageFiles) {
            File langFile = new File(langDir, fileName);
            if (!langFile.exists()) {
                try {
                    plugin.saveResource("lang/" + fileName, false);
                } catch (Exception e) {
                    plugin.getLogger().warning("无法创建语言文件 " + fileName + ": " + e.getMessage());
                }
            }
        }
    }

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
        
        if (playerLangConfig.getConfigurationSection("players") != null) {
            for (String uuidStr : playerLangConfig.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String lang = playerLangConfig.getString("players." + uuidStr);
                    if (lang != null) {
                        playerLanguages.put(uuid, lang);
                        loadLanguageConfig(lang);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }
    
    private void savePlayerLanguages() {
        if (playerLangConfig == null) return;
        
        playerLangConfig.set("players", null);
        
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
    
    private void loadLanguageConfig(String language) {
        if (languageConfigs.containsKey(language)) {
            return;
        }
        
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        
        if (!langFile.exists()) {
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
    
    public boolean setPlayerLanguage(UUID playerId, String language) {
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists()) {
            return false;
        }
        
        try {
            boolean updated = syncLanguageFile(language);
            if (updated) {
                languageConfigs.remove(language);
            }
        } catch (Exception ignore) {}
        
        loadLanguageConfig(language);
        
        if (!languageConfigs.containsKey(language)) {
            return false;
        }
        
        playerLanguages.put(playerId, language);
        
        savePlayerLanguages();
        
        return true;
    }
    
    public String getPlayerLanguage(UUID playerId) {
        return playerLanguages.get(playerId);
    }
    
    public void removePlayerLanguage(UUID playerId) {
        playerLanguages.remove(playerId);
        savePlayerLanguages();
    }
    
    public String getString(String key) {
        String value = langConfig.getString(key);
        return value != null ? value : key;
    }
    
    public String getString(UUID playerId, String key) {
        if (playerId == null) {
            return getString(key);
        }
        
        String playerLang = playerLanguages.get(playerId);
        if (playerLang == null) {
            return getString(key);
        }
        
        FileConfiguration config = languageConfigs.get(playerLang);
        if (config == null) {
            loadLanguageConfig(playerLang);
            config = languageConfigs.get(playerLang);
            if (config == null) {
                return getString(key);
            }
        }
        
        String value = config.getString(key);
        if (value == null) {
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
            return getString(key);
        }
        
        return value;
    }
    
    public String getString(Player player, String key) {
        return player != null ? getString(player.getUniqueId(), key) : getString(key);
    }
    
    public String getStringByLanguage(String language, String key, Object... args) {
        if (!languageConfigs.containsKey(language)) {
            loadLanguageConfig(language);
        }
        
        FileConfiguration config = languageConfigs.get(language);
        if (config == null) {
            plugin.getLogger().warning("无法加载语言配置 " + language + "，使用默认语言");
            String pattern = getString(key);
            return formatMessage(pattern, key, args);
        }
        
        String pattern = config.getString(key);
        if (pattern == null) {
            pattern = getString(key);
        }
        
        return formatMessage(pattern, key, args);
    }
    
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
            languages[i] = fileName.substring(0, fileName.length() - 4);
        }
        
        return languages;
    }
    
    public boolean isLanguageAvailable(String language) {
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        return langFile.exists();
    }
    
    public void shutdown() {
        savePlayerLanguages();
    }
    

    
    public boolean syncLanguageFile(String language) {
        try {
            File langDir = new File(plugin.getDataFolder(), "lang");
            if (!langDir.exists()) {
                langDir.mkdirs();
            }
            
            File configLangFile = new File(langDir, language + ".yml");
            
            InputStream jarLangStream = plugin.getResource("lang/" + language + ".yml");
            if (jarLangStream == null) {
                return false;
            }
            
            FileConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarLangStream, StandardCharsets.UTF_8));
            
            FileConfiguration configFileConfig;
            
            if (!configLangFile.exists()) {
                plugin.saveResource("lang/" + language + ".yml", false);
                return true;
            } else {
                configFileConfig = YamlConfiguration.loadConfiguration(configLangFile);
            }
            
            boolean hasChanges = syncConfigSections(jarConfig, configFileConfig, "");
            
            if (hasChanges) {
                configFileConfig.save(configLangFile);
                
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
                if (!configFile.isConfigurationSection(currentPath)) {
                    configFile.createSection(currentPath);
                    hasChanges = true;
                }
                
                if (syncConfigSections(jarConfig, configFile, currentPath)) {
                    hasChanges = true;
                }
            } else {
                if (!configFile.contains(currentPath)) {
                    Object value = jarConfig.get(currentPath);
                    configFile.set(currentPath, value);
                    hasChanges = true;
                }
            }
        }
        
        return hasChanges;
    }
    
    public int syncAllLanguageFiles() {
        String[] availableLanguages = {"zh-CN", "zh-TW", "en-US", "ru-RU", "pt-PT", "fr-FR", "es-ES", "de-DE"};
        int updatedCount = 0;
        
        for (String language : availableLanguages) {
            InputStream jarStream = plugin.getResource("lang/" + language + ".yml");
            if (jarStream != null) {
                try {
                    jarStream.close();
                    boolean updated = syncLanguageFile(language);
                    if (updated) {
                        updatedCount++;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        
        return updatedCount;
    }
    

    public String getString(String key, Object... args) {
        String pattern = getString(key);
        return formatMessage(pattern, key, args);
    }
    
    public String getString(UUID playerId, String key, Object... args) {
        String pattern = getString(playerId, key);
        return formatMessage(pattern, key, args);
    }
    
    public String getString(Player player, String key, Object... args) {
        String pattern = getString(player, key);
        return formatMessage(pattern, key, args);
    }
    
    private String formatMessage(String pattern, String key, Object... args) {
        if (pattern.matches(".*\\{\\d+}.*")) {
            try {
                return MessageFormat.format(pattern, args);
            } catch (Exception ignored) {
            }
        }
        try {
            return String.format(pattern, args);
        } catch (Exception ignored) {
            plugin.getLogger().warning("Error formatting message for key: " + key);
            return pattern;
        }
    }

    private void validateAndUpdateLanguageFile(File langFile) {
        try {
            InputStream defaultLangStream = plugin.getResource("lang/" + currentLang + ".yml");
            if (defaultLangStream == null) {
                defaultLangStream = plugin.getResource("lang/en-US.yml");
                if (defaultLangStream == null) {
                    plugin.getLogger().warning("无法找到默认语言文件进行校对");
                    return;
                }
            }

            FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultLangStream, StandardCharsets.UTF_8));

            boolean hasChanges = false;

            if (isLanguageSwitch) {
                hasChanges = validateAndUpdateSection(defaultConfig, langConfig, "", true);
                plugin.getLogger().info("检测到语言切换，正在同步所有翻译键值");
            } else {
                hasChanges = validateAndUpdateSection(defaultConfig, langConfig, "", false);
            }

            if (hasChanges) {
                langConfig.save(langFile);
                if (isLanguageSwitch) {
                    plugin.getLogger().info("语言文件已更新，所有键值已同步到新语言");
                } else {
                    plugin.getLogger().info("语言文件已更新，补充了缺失的键值");
                }
            }

            isLanguageSwitch = false;
        } catch (Exception e) {
            plugin.getLogger().warning("校对语言文件时出错: " + e.getMessage());
        }
    }

    private boolean validateAndUpdateSection(FileConfiguration defaultConfig, FileConfiguration currentConfig,
            String path, boolean forceUpdate) {
        boolean hasChanges = false;

        Set<String> keys;
        if (path.isEmpty()) {
            keys = defaultConfig.getKeys(false);
        } else {
            keys = defaultConfig.getConfigurationSection(path).getKeys(false);
        }

        for (String key : keys) {
            String currentPath = path.isEmpty() ? key : path + "." + key;

            if (defaultConfig.isConfigurationSection(currentPath)) {
                if (!currentConfig.isConfigurationSection(currentPath)) {
                    currentConfig.createSection(currentPath);
                    hasChanges = true;
                }

                if (validateAndUpdateSection(defaultConfig, currentConfig, currentPath, forceUpdate)) {
                    hasChanges = true;
                }
            } else {
                if (!currentConfig.contains(currentPath) || forceUpdate) {
                    Object defaultValue = defaultConfig.get(currentPath);
                    Object currentValue = currentConfig.get(currentPath);

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