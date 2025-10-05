package org.momu.tOCplugin.util;

public class KeyUtils {
    private KeyUtils() {}

    /**
     * 规范化字符串key，去除前后空格并转为小写
     */
    public static String normalizeKey(String key) {
        if (key == null) return null;
        return key.trim().toLowerCase();
    }
}

