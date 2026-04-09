package org.momu.pathfinder.util;

public class KeyUtils {
    private KeyUtils() {}

    public static String normalizeKey(String key) {
        if (key == null) return null;
        return key.trim().toLowerCase();
    }
}

