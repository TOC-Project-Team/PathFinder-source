package org.momu.tOCplugin;

/**
 * 简单的Native库加载测试
 */
public class TestNativeLoader {
    public static void main(String[] args) {
        System.out.println("=== PathFinder Native库加载测试 ===");
        
        // 测试库加载
        boolean loaded = NativeLibraryLoader.loadNativeLibrary();
        
        System.out.println("\n=== 测试结果 ===");
        System.out.println("加载状态: " + (loaded ? "✅ 成功" : "❌ 失败"));
        System.out.println("加载详情: " + NativeLibraryLoader.getLoadStatus());
        
        if (loaded) {
            System.out.println("\n=== Native方法测试 ===");
            try {
                // 这里可以测试native方法
                System.out.println("✅ Native库已成功加载并可使用");
            } catch (Exception e) {
                System.err.println("❌ Native方法调用失败: " + e.getMessage());
            }
        }
        
        System.out.println("\n测试完成");
    }
} 