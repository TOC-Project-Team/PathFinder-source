package org.momu.tOCplugin;

import java.io.*;
import java.nio.file.*;

/**
 * 跨平台Native库加载器
 * 负责根据运行时操作系统自动选择和加载正确的PathFinder native验证库
 */
public class NativeLibraryLoader {
    private static boolean loaded = false;
    private static String loadedPlatform = null;
    
    // 支持的平台和对应的库文件
    private static final String[][] PLATFORM_MAPPINGS = {
        {"windows", "dll", "/native/pathfinder_native.dll"},
        {"linux", "so", "/native/pathfinder_native.so"}, 
        {"macos", "dylib", "/native/pathfinder_native.dylib"}
    };
    
    /**
     * 加载native库（自动检测平台并从JAR资源提取）
     * @return 是否加载成功
     */
    public static synchronized boolean loadNativeLibrary() {
        if (loaded) {
            return true;
        }
        
        // 检测当前平台
        PlatformInfo platform = detectPlatform();
        if (platform == null) {
            System.err.println("[PathFinder] Can't support this OS: " + System.getProperty("os.name"));
            return false;
        }

        // 尝试加载对应平台的库
        if (tryLoadFromResources(platform)) {
            loadedPlatform = platform.name;
            return true;
        }
        
        // 如果主库失败，尝试备用路径
        if (tryLoadFallback(platform)) {
            loadedPlatform = platform.name;
            return true;
        }
        
        System.err.println("[PathFinder] Failed to load for " + platform.name);
        return false;
    }

    /**
     * 从JAR资源中加载对应平台的库
     */
    private static boolean tryLoadFromResources(PlatformInfo platform) {
        try {
            String resourcePath = platform.resourcePath;
            InputStream is = NativeLibraryLoader.class.getResourceAsStream(resourcePath);
            
            if (is == null) {
                System.out.println("[PathFinder] Can't find the file: " + resourcePath);
                return false;
            }
            
            // 创建临时文件
            String tempSuffix = "." + platform.extension;
            File tempLib = File.createTempFile("pathfinder_native_" + platform.name + "_", tempSuffix);
            tempLib.deleteOnExit();
            
            // 将库文件从JAR提取到临时文件
            try (FileOutputStream fos = new FileOutputStream(tempLib)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            is.close();
            
            // 设置执行权限（Unix系统）
            if (!platform.name.equals("windows")) {
            tempLib.setExecutable(true);
            }
            
            // 加载库
            System.load(tempLib.getAbsolutePath());
            loaded = true;
            
            // 注册清理回调
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(tempLib.toPath());
                } catch (Exception e) {
                    // 忽略清理错误
                }
            }));
            
            return true;
            
        } catch (Exception e) {
            System.err.println("[PathFinder] Failed to load from resources: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 尝试备用加载路径（兼容旧版本）
     */
    private static boolean tryLoadFallback(PlatformInfo platform) {
        // 尝试旧的路径格式
        String[] fallbackPaths = {
            "/native/libpathfinder_native." + platform.extension,  // 旧格式
            "/libpathfinder_native." + platform.extension,          // 根目录
            "/native/" + platform.name + "/libpathfinder_native." + platform.extension  // 带lib前缀
        };
        
        for (String fallbackPath : fallbackPaths) {
            try {
                InputStream is = NativeLibraryLoader.class.getResourceAsStream(fallbackPath);
                if (is != null) {                    
                    String tempSuffix = "." + platform.extension;
                    File tempLib = File.createTempFile("pathfinder_native_fallback_", tempSuffix);
                    tempLib.deleteOnExit();
                    
                    try (FileOutputStream fos = new FileOutputStream(tempLib)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    is.close();
                    
                    if (!platform.name.equals("windows")) {
                        tempLib.setExecutable(true);
                    }
                    
                    System.load(tempLib.getAbsolutePath());
                    loaded = true;
                    
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            Files.deleteIfExists(tempLib.toPath());
                        } catch (Exception e) {
                            // 忽略清理错误
                        }
                    }));
                    
                    return true;
                }
            } catch (Exception e) {
                // 继续尝试下一个路径
            }
        }
        
        return false;
    }
    
    /**
     * 检测当前运行平台
     */
    private static PlatformInfo detectPlatform() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        
        for (String[] mapping : PLATFORM_MAPPINGS) {
            String platformName = mapping[0];
            String extension = mapping[1];
            String resourcePath = mapping[2];
            
            if ((platformName.equals("windows") && osName.contains("win")) ||
                (platformName.equals("linux") && osName.contains("linux")) ||
                (platformName.equals("macos") && (osName.contains("mac") || osName.contains("darwin")))) {
                
                return new PlatformInfo(platformName, extension, resourcePath, osArch);
            }
        }
        
        return null; // 不支持的平台
    }
    
    /**
     * 检查native库是否已加载
     */
    public static boolean isLoaded() {
        return loaded;
    }
    
    /**
     * 获取已加载的平台信息
     */
    public static String getLoadedPlatform() {
        return loadedPlatform;
    }
    
    /**
     * 获取详细的加载状态信息
     */
    public static String getLoadStatus() {
        if (loaded) {
            return "已加载 (" + loadedPlatform + " 平台)";
        } else {
            PlatformInfo platform = detectPlatform();
            return "未加载 (当前平台: " + (platform != null ? platform.name : "未知") + ")";
        }
    }
    
    /**
     * 获取当前系统信息
     */
    public static String getSystemInfo() {
        return String.format("操作系统: %s, 架构: %s, Java版本: %s", 
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            System.getProperty("java.version"));
    }
    
    /**
     * 平台信息类
     */
    private static class PlatformInfo {
        final String name;
        final String extension;
        final String resourcePath;
        PlatformInfo(String name, String extension, String resourcePath, String arch) {
            this.name = name;
            this.extension = extension;
            this.resourcePath = resourcePath;
        }
    }
} 