package org.momu.tOCplugin.finder;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.momu.tOCplugin.Pathfinder;

public class ParticleGen {
    public static Location adjustParticleLocationForWater(Location location) {
        if (location == null) {
            return location;
        }
        Location adjustedLoc = location.clone();
        Block currentBlock = adjustedLoc.getBlock();
        if (currentBlock.getType().name().contains("WATER")) {
            while (adjustedLoc.getBlockY() < adjustedLoc.getWorld().getMaxHeight() - 1) {
                adjustedLoc.add(0.0, 1.0, 0.0);
                Block checkBlock = adjustedLoc.getBlock();
                if (checkBlock.getType().name().contains("WATER") || !checkBlock.isPassable())
                    continue;
                adjustedLoc.add(0.0, -0.3, 0.0);
                break;
            }
        }
        return adjustedLoc;
    }

    public static void drawBlockOutline(Player player, Location blockLoc, Color color, double particleDensity) {
        int blockX = blockLoc.getBlockX();
        int blockY = blockLoc.getBlockY();
        int blockZ = blockLoc.getBlockZ();

        double baseDensity = Pathfinder.PARTICLE_SPACING;
        double actualDensity = color.equals(Color.RED) ? baseDensity * 2.5 : baseDensity;
        int edgeCount = color.equals(Color.RED) ? 8 : 12;

        for (int j = 0; j < edgeCount; ++j) {
            Location edgeEnd;
            Location edgeStart;
            if (color.equals(Color.RED) && j >= 8)
                continue;

            if (j < 4) { // 下方边缘
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY, blockZ + (j / 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j < 2 ? 1 - j % 2 : j % 2), blockY,
                        blockZ + (j / 2));
            } else if (j < 8) { // 上方边缘
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + 1, blockZ + (j / 2 % 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j < 6 ? 1 - j % 2 : j % 2), blockY + 1,
                        blockZ + (j / 2 % 2));
            } else { // 垂直边缘
                edgeStart = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + (j / 2 % 2),
                        blockZ + (j / 2 % 2));
                edgeEnd = new Location(blockLoc.getWorld(), blockX + (j % 2), blockY + (1 - j / 2 % 2),
                        blockZ + (j / 2 % 2));
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
