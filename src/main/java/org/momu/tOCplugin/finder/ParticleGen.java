package org.momu.tOCplugin.finder;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class ParticleGen {
    /**
     * 若粒子位置在水中，则自动上浮到水面。
     * @param location 原始位置
     * @return 调整后的粒子位置
     */
    public static Location adjustParticleLocationForWater(Location location) {
        if (location == null) return location;
        Location adjustedLoc = location.clone();
        Block currentBlock = adjustedLoc.getBlock();
        if (currentBlock.getType().name().contains("WATER")) {
            while (adjustedLoc.getBlockY() < adjustedLoc.getWorld().getMaxHeight() - 1) {
                adjustedLoc.add(0.0, 1.0, 0.0);
                Block checkBlock = adjustedLoc.getBlock();
                if (checkBlock.getType().name().contains("WATER") || !checkBlock.isPassable()) continue;
                adjustedLoc.add(0.0, -0.3, 0.0);
                break;
            }
        }
        return adjustedLoc;
    }

    /**
     * 绘制指定方块的粒子轮廓。
     * @param player 玩家
     * @param blockLoc 方块位置
     * @param color 粒子颜色
     * @param particleDensity 粒子密度（已废弃，自动根据颜色调整）
     */
    public static void drawBlockOutline(Player player, Location blockLoc, Color color, double particleDensity) {
        int blockX = blockLoc.getBlockX();
        int blockY = blockLoc.getBlockY();
        int blockZ = blockLoc.getBlockZ();
        double baseDensity = org.momu.tOCplugin.config.PathfinderConfig.PARTICLE_SPACING;
        double actualDensity = color.equals(Color.RED) ? baseDensity * 2.5 : baseDensity;
        int edgeCount = color.equals(Color.RED) ? 8 : 12;
        for (int j = 0; j < edgeCount; ++j) {
            if (color.equals(Color.RED) && j >= 8) continue;
            Location edgeStart, edgeEnd;
            if (j < 4) {
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY, blockZ + (j / 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j < 2 ? 1 - j % 2 : j % 2), blockY, blockZ + (j / 2));
            } else if (j < 8) {
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + 1, blockZ + ((j / 2) % 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j < 6 ? 1 - j % 2 : j % 2), blockY + 1, blockZ + ((j / 2) % 2));
            } else {
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + ((j / 2) % 2), blockZ + ((j / 2) % 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + (1 - (j / 2) % 2), blockZ + ((j / 2) % 2));
            }
            Vector direction = edgeEnd.toVector().subtract(edgeStart.toVector());
            for (double d = 0.0; d < 1.0; d += actualDensity) {
                Location particleLoc = edgeStart.clone().add(direction.clone().multiply(d));
                player.spawnParticle(Particle.DUST, particleLoc, 1, 0.0, 0.0, 0.0, 0.0,
                        (Object) new Particle.DustOptions(color, 0.9f));
            }
        }
    }
}