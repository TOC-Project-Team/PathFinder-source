package org.momu.pathfinder.navigation.runtime;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.momu.pathfinder.navigation.algorithm.Pathfinder;
import org.momu.pathfinder.config.PathfinderConfig;
import org.momu.pathfinder.navigation.effect.ParticleGen;
import org.momu.pathfinder.navigation.state.PlayerTracker;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import org.momu.pathfinder.runtime.TaskManager;
import org.momu.pathfinder.config.LanguageManager;
import org.momu.pathfinder.presentation.listener.MasterListener;
import java.util.*;
import java.util.function.Consumer;
import static org.momu.pathfinder.runtime.TaskManager.arrivalNotifyCooldownUntil;

public class PathFinding {
    public static final Map<UUID, List<?>> lastPlayerPaths = new HashMap<>();
    public static final Map<UUID, Location> lastPlayerPositions = new HashMap<>();
    public static final Map<UUID, Location> lastTargetPositions = new HashMap<>();

    public static void startPathfinding(final Player player) {
        new BukkitRunnable() {
            @SuppressWarnings("deprecation")
            public void run() {
                boolean isStronghold;
                Location finalTargetLoc;
                PlayerTracker tracker;
                boolean isBeaconNav = false;
                boolean isWaypointNav = false;
                block15: {
                    int verticalDistance;
                    block16: {
                        block14: {
                            tracker = PlayerTracker.getInstance();
                            finalTargetLoc = null;
                            isStronghold = false;

                            if (tracker.getBeaconNavigation(player.getUniqueId()) != null) {
                                finalTargetLoc = tracker.getBeaconNavigation(player.getUniqueId()).clone();
                                isBeaconNav = true;
                            }
                            else if (tracker.getWaypointNavigation(player.getUniqueId()) != null) {
                                finalTargetLoc = tracker.getWaypointNavigation(player.getUniqueId()).clone();
                                isWaypointNav = true;
                            }
                            else if (tracker.getStrongholdNavigation(player.getUniqueId()) != null) {
                                finalTargetLoc = tracker.getStrongholdNavigation(player.getUniqueId()).clone();
                                finalTargetLoc.setX(finalTargetLoc.getBlockX());
                                finalTargetLoc.setY(finalTargetLoc.getBlockY());
                                finalTargetLoc.setZ(finalTargetLoc.getBlockZ());
                                isStronghold = true;
                            } else {
                                if (tracker.getNavigationTarget(player.getUniqueId()) != null) {
                                    UUID targetUUID = tracker.getNavigationTarget(player.getUniqueId());
                                    if (targetUUID == null) {
                                        player.sendMessage(
                                                ChatColor.RED + LanguageManager.getInstance()
                                                        .getString("messages.target-not-exist"));
                                        tracker.stopNavigation(player.getUniqueId());
                                        return;
                                    }
                                    Player target = Bukkit.getPlayer(targetUUID);
                                    if (target != null && target.isOnline()) {
                                        if (tracker.isNavigationBlockedByInvisibility(target)) {
                                            player.sendMessage(ChatColor.YELLOW + LanguageManager.getInstance()
                                                    .getString(player, "messages.target-hidden"));
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        boolean isTargetHidden = tracker.isLocationHidden(target.getUniqueId());
                                        boolean canBypass = PathFinderPlugin.getInstance()
                                                .canBypassNavigationRestrictions(player.getUniqueId());
                                        if (isTargetHidden && !canBypass) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                            .getInstance().getString("messages.target-hidden"));
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        finalTargetLoc = target.getLocation().clone();
                                        break block14;
                                    } else {
                                        player.sendMessage(
                                                String.valueOf((Object) ChatColor.RED) + LanguageManager.getInstance()
                                                        .getString("messages.target-offline"));
                                        tracker.stopNavigation(player.getUniqueId());
                                        return;
                                    }
                                }
                                player.sendMessage(String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                        .getInstance().getString(player, "messages.stronghold-searching-pathfinding"));
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        final Location strongholdLoc = player.getWorld().locateNearestStructure(
                                                player.getLocation(), StructureType.STRONGHOLD, 10000, false);
                                        if (strongholdLoc == null) {
                                            player.sendMessage(String.valueOf((Object) ChatColor.RED)
                                                    + LanguageManager.getInstance().getString(player,
                                                            "messages.stronghold-not-found-large-range"));
                                            MasterListener.getGuiManager().toggleParticleFeature(player.getUniqueId());
                                            if (PathFinderPlugin.getInstance().isEnabled()) {
                                                MasterListener.getGuiManager().openMainMenu(player);
                                            }
                                            return;
                                        }
                                        Location strongholdLocation = strongholdLoc.clone();
                                        strongholdLocation.setX((double) strongholdLocation.getBlockX());
                                        strongholdLocation.setY((double) strongholdLocation.getBlockY());
                                        strongholdLocation.setZ((double) strongholdLocation.getBlockZ());
                                        PlayerTracker tracker = PlayerTracker.getInstance();
                                        tracker.setStrongholdNavigation(player.getUniqueId(), strongholdLocation);
                                        startPathfinding(player);
                                    }
                                }.runTask((Plugin) PathFinderPlugin.getInstance());
                                return;
                            }
                        }
                        verticalDistance = Math
                                .abs((int) (player.getLocation().getBlockY() - finalTargetLoc.getBlockY()));
                        int horizontalDistance = (int) Math.sqrt((double) (Math.pow(
                                (double) (player.getLocation().getBlockX() - finalTargetLoc.getBlockX()), (double) 2.0)
                                + Math.pow((double) (player.getLocation().getBlockZ() - finalTargetLoc.getBlockZ()),
                                        (double) 2.0)));
                        if (verticalDistance <= 30 || horizontalDistance >= 10)
                            break block15;
                        if (player.getLocation().getBlockY() <= finalTargetLoc.getBlockY())
                            break block16;
                        Location waterLanding = findWaterLanding(player.getLocation(),
                                finalTargetLoc);
                        if (waterLanding != null) {
                            Location safeSpot = findSafeLandingNearWater(waterLanding);
                            if (safeSpot != null) {
                                finalTargetLoc = safeSpot;
                                break block15;
                            } else {
                                finalTargetLoc = waterLanding.clone().add(0.0, 1.0, 0.0);
                            }
                            break block15;
                        }
                        break block15;
                    }
                    if (verticalDistance > 50) {
                        finalTargetLoc = new Location(finalTargetLoc.getWorld(), finalTargetLoc.getX(),
                                Math.max((double) finalTargetLoc.getY(), (double) (player.getLocation().getY() - 50.0)),
                                finalTargetLoc.getZ());
                    }
                }
                final Location finalTargetLocCopy = finalTargetLoc.clone();
                final boolean isStrongholdNav = isStronghold;
                final boolean isBeaconNavigation = isBeaconNav;
                final boolean isWaypointNavigation = isWaypointNav;

                TaskManager.removePlayerNavigationTask(player.getUniqueId());

                BukkitTask pathfindingTask = new BukkitRunnable() {
                    public void run() {
                        if (!PathFinderPlugin.getInstance().isEnabled()) {
                            this.cancel();
                            return;
                        }
                        List<Pathfinder.Node> path;
                        Location targetLoc;
                        Location currentTarget;
                        block33: {
                            block32: {
                                if (!player.isOnline() || !MasterListener.getGuiManager().isParticleFeatureEnabled(player.getUniqueId())) {
                                    this.cancel();
                                    MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                    return;
                                }

                                if (player.isDead()) {
                                    this.cancel();
                                    MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                    tracker.stopNavigation(player.getUniqueId());
                                    return;
                                }
                                if (isStrongholdNav) {
                                    Location navTarget = tracker.getStrongholdNavigation(player.getUniqueId());
                                    if (navTarget != null) {
                                        currentTarget = navTarget.clone();
                                    } else {
                                        currentTarget = finalTargetLocCopy.clone();
                                    }
                                } else if (isBeaconNavigation) {
                                    Location navTarget = tracker.getBeaconNavigation(player.getUniqueId());
                                    if (navTarget != null) {
                                        currentTarget = navTarget.clone();
                                    } else {
                                        currentTarget = finalTargetLocCopy.clone();
                                    }
                                } else if (isWaypointNavigation) {
                                    Location navTarget = tracker.getWaypointNavigation(player.getUniqueId());
                                    if (navTarget != null) {
                                        currentTarget = navTarget.clone();
                                    } else {
                                        currentTarget = finalTargetLocCopy.clone();
                                    }
                                } else {
                                    currentTarget = finalTargetLocCopy.clone();
                                }
                                if (!isStrongholdNav && !isBeaconNavigation && !isWaypointNavigation) {
                                    UUID targetId = tracker.getNavigationTarget(player.getUniqueId());
                                    if (targetId == null) {
                                        player.sendMessage(String.valueOf((Object) ChatColor.RED)
                                                + LanguageManager.getInstance().getString(player,
                                                        "messages.target-not-exist"));
                                        this.cancel();
                                        MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                        tracker.stopNavigation(player.getUniqueId());
                                        return;
                                    }
                                    Player target = Bukkit.getPlayer((UUID) targetId);
                                    if (target != null && target.isOnline()) {
                                        if (tracker.isNavigationBlockedByInvisibility(target)) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                                            .getInstance().getString(player, "messages.target-hidden"));
                                            this.cancel();
                                            MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        boolean isTargetHidden = tracker.isLocationHidden(target.getUniqueId());
                                        boolean canBypass = PathFinderPlugin.getInstance()
                                                .canBypassNavigationRestrictions(player.getUniqueId());

                                        if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                                            .getInstance().getString("messages.target-spectator"));
                                            this.cancel();
                                            MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }

                                        if (target.isDead()) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                                            .getInstance().getString("messages.target-dead"));
                                            this.cancel();
                                            MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        if (!player.getWorld().equals(target.getWorld())) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                            .getInstance().getString("messages.target-dimension"));
                                            this.cancel();
                                            MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }

                                        if (isTargetHidden && !canBypass) {
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                            .getInstance().getString("messages.target-hidden-2"));
                                            this.cancel();
                                            MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                            tracker.stopNavigation(player.getUniqueId());
                                            return;
                                        }
                                        currentTarget = target.getLocation().clone();
                                    } else {
                                        player.sendMessage(String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                .getInstance().getString("messages.target-offline"));
                                        this.cancel();
                                        MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                        tracker.stopNavigation(player.getUniqueId());
                                        return;
                                    }
                                }
                                if (isBeaconNavigation) {
                                    Block beaconBlock = currentTarget.getBlock();
                                    if (beaconBlock.getType() != Material.BEACON) {
                                        player.sendMessage(String.valueOf((Object) ChatColor.RED) + LanguageManager
                                                .getInstance().getString("messages.target-beacon-dissappear"));
                                        this.cancel();
                                        MasterListener.getGuiManager().removeParticleTask(player.getUniqueId());
                                        tracker.stopNavigation(player.getUniqueId());
                                        return;
                                    }
                                }
                                double distance = player.getLocation().distance(currentTarget);
                                if (isStrongholdNav) {
                                    targetLoc = currentTarget.clone();
                                    targetLoc.setX((double) targetLoc.getBlockX());
                                    targetLoc.setY((double) targetLoc.getBlockY());
                                    targetLoc.setZ((double) targetLoc.getBlockZ());
                                } else if (distance > Pathfinder.getMaxSearchRadius()) {
                                    Vector direction = currentTarget.toVector()
                                            .subtract(player.getLocation().toVector()).normalize();
                                    targetLoc = player.getLocation().clone()
                                            .add(direction.multiply(Pathfinder.getMaxSearchRadius()));
                                    targetLoc.setX((double) targetLoc.getBlockX());
                                    targetLoc.setY((double) targetLoc.getBlockY());
                                    targetLoc.setZ((double) targetLoc.getBlockZ());
                                } else {
                                    targetLoc = currentTarget.clone();
                                }
                                if (targetLoc.getBlock().getType().name().contains((CharSequence) "WATER")) {
                                    Location safeLanding = findSafeLandingNearWater(targetLoc);
                                    if (safeLanding != null) {
                                        targetLoc = safeLanding;
                                    } else {
                                        Location waterSurface = targetLoc.clone();
                                        int maxIterations = 50;
                                        int iterations = 0;
                                        while (waterSurface.getBlock().getType().name().contains((CharSequence) "WATER")
                                                && waterSurface.getBlockY() < waterSurface.getWorld().getMaxHeight() - 1
                                                && iterations < maxIterations) {
                                            waterSurface.add(0.0, 1.0, 0.0);
                                            iterations++;
                                        }
                                        if (!waterSurface.getBlock().getType().name()
                                                .contains((CharSequence) "WATER")) {
                                            targetLoc = waterSurface;
                                            if (waterSurface.clone().add(0.0, -1.0, 0.0).getBlock().getType().name()
                                                    .contains((CharSequence) "WATER")) {
                                                targetLoc.setY((double) waterSurface.getBlockY());
                                            }
                                        } else {
                                            int radius = 3;
                                            boolean found = false;
                                            int checkedLocations = 0;
                                            int maxLocationsToCheck = 50;

                                            block1: for (int r = 1; r <= radius && !found
                                                    && checkedLocations < maxLocationsToCheck; ++r) {
                                                int dx = -r;
                                                while (true) {
                                                    if (dx > r || found || checkedLocations >= maxLocationsToCheck)
                                                        continue block1;
                                                    for (int dz = -r; dz <= r && !found
                                                            && checkedLocations < maxLocationsToCheck; ++dz) {
                                                        checkedLocations++;
                                                        Location checkLoc;
                                                        if (Math.abs((int) dx) != r && Math.abs((int) dz) != r
                                                                || (checkLoc = targetLoc.clone().add((double) dx, 0.0,
                                                                        (double) dz)).getBlock().getType().name()
                                                                        .contains((CharSequence) "WATER")
                                                                || !isSafeLanding(checkLoc))
                                                            continue;
                                                        targetLoc = checkLoc;
                                                        found = true;
                                                        break;
                                                    }
                                                    ++dx;
                                                }
                                            }
                                        }
                                    }
                                }
                                Location playerBlockLoc = player.getLocation().clone();
                                playerBlockLoc.setX((double) playerBlockLoc.getBlockX());
                                playerBlockLoc.setY((double) playerBlockLoc.getBlockY());
                                playerBlockLoc.setZ((double) playerBlockLoc.getBlockZ());
                                UUID playerUUID = player.getUniqueId();
                                List<?> lastPath = lastPlayerPaths.get(playerUUID);
                                Location lastPlayerPos = lastPlayerPositions.get(playerUUID);
                                Location lastTargetPos = lastTargetPositions.get(playerUUID);
                                path = null;
                                if (Pathfinder.isPlayerInAir(player))
                                    break block32;
                                boolean needRecalculate = true;
                                if (Pathfinder.isPathCachingEnabled() && lastPath != null && !lastPath.isEmpty()
                                        && lastPlayerPos != null && lastTargetPos != null) {
                                    double playerMoveDist = playerBlockLoc.distance(lastPlayerPos);
                                    double targetMoveDist = targetLoc.distance(lastTargetPos);
                                    if (playerMoveDist < 3.0 && targetMoveDist < 0.1) {
                                        boolean nearPath = false;
                                        double minDistToPath = Double.MAX_VALUE;
                                        for (Object nodeObj : lastPath) {
                                            Pathfinder.Node node = (Pathfinder.Node) nodeObj;
                                            double dist = playerBlockLoc.distance(node.location);
                                            if (dist < minDistToPath) {
                                                minDistToPath = dist;
                                            }
                                            if (!(dist < 2.0))
                                                continue;
                                            nearPath = true;
                                            break;
                                        }
                                        if (nearPath || minDistToPath < 3.0) {
                                            int nearestNodeIndex = 0;
                                            double nearestDist = Double.MAX_VALUE;
                                            for (int i = 0; i < lastPath.size(); ++i) {
                                                Pathfinder.Node node = (Pathfinder.Node) lastPath.get(i);
                                                double dist = playerBlockLoc.distance(node.location);
                                                if (!(dist < nearestDist))
                                                    continue;
                                                nearestDist = dist;
                                                nearestNodeIndex = i;
                                            }
                                            if (nearestNodeIndex < lastPath.size() - 1) {
                                                path = new ArrayList<Pathfinder.Node>();
                                                for (int j = nearestNodeIndex; j < lastPath.size(); j++) {
                                                    path.add((Pathfinder.Node) lastPath.get(j));
                                                }
                                                needRecalculate = false;
                                            }
                                        }
                                    }
                                }
                                if (needRecalculate) {
                                    path = Pathfinder.findPath(playerBlockLoc, targetLoc, player);
                                    if (Pathfinder.isPathCachingEnabled() && path != null && !path.isEmpty()) {
                                        List<Pathfinder.Node> pathCopy = new ArrayList<>();
                                        for (Pathfinder.Node node : path) {
                                            pathCopy.add(node);
                                        }
                                        lastPlayerPaths.put(playerUUID, pathCopy);
                                        lastPlayerPositions.put(playerUUID, playerBlockLoc.clone());
                                        lastTargetPositions.put(playerUUID, targetLoc.clone());
                                    }
                                }
                                break block33;
                            }
                            path = null;
                        }
                        final Location finalTargetLoc = targetLoc;
                        final Location finalCurrentTarget = currentTarget;
                        final List<?> finalPath = path;
                        new BukkitRunnable() {
                            public void run() {
                                if (!PathFinderPlugin.getInstance().isEnabled()) {
                                    return;
                                }
                                if (!MasterListener.getGuiManager().isParticleFeatureEnabled(player.getUniqueId())) {
                                    return;
                                }

                                if (player.isDead()) {
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            if (!PathFinderPlugin.getInstance().isEnabled()) {
                                                return;
                                            }
                                            PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                                        }
                                    }.runTask((Plugin) PathFinderPlugin.getInstance());
                                    return;
                                }

                                if (!isStrongholdNav && !isBeaconNavigation && !isWaypointNavigation) {
                                    UUID targetId = tracker.getNavigationTarget(player.getUniqueId());
                                    if (targetId != null) {
                                        Player target = Bukkit.getPlayer(targetId);
                                        if (target != null && target.isDead()) {
                                            new BukkitRunnable() {
                                                @Override
                                                public void run() {
                                                    PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                                                    player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                                                            + LanguageManager.getInstance()
                                                                    .getString("messages.target-dead"));
                                                }
                                            }.runTask((Plugin) PathFinderPlugin.getInstance());
                                            return;
                                        }
                                    }
                                }

                                if (player.getGameMode() == GameMode.SPECTATOR) {
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                                            player.sendMessage(
                                                    String.valueOf((Object) ChatColor.YELLOW) + LanguageManager
                                                            .getInstance().getString("messages.spectator-mode"));
                                        }
                                    }.runTask((Plugin) PathFinderPlugin.getInstance());
                                    return;
                                }

                                updateNavigationInfo(player, finalTargetLoc, finalCurrentTarget);

                                Location realTarget = finalCurrentTarget;

                                double distanceToRealTarget = player.getLocation().distance(realTarget);

                                boolean shouldCancel = false;
                                if (distanceToRealTarget <= 3.0) {
                                    shouldCancel = true;
                                }

                                if (shouldCancel) {
                                    final UUID pid = player.getUniqueId();
                                    final long now = System.currentTimeMillis();
                                    final long[] prev = new long[]{0L};
                                    final long[] newExpiry = new long[]{0L};
                                    arrivalNotifyCooldownUntil.compute(pid, (k, oldVal) -> {
                                        long prevVal = (oldVal == null ? 0L : oldVal.longValue());
                                        prev[0] = prevVal;
                                        long expiry = (prevVal <= now) ? (now + 3000L) : prevVal;
                                        newExpiry[0] = expiry;
                                        return expiry;
                                    });
                                    boolean canNotify = prev[0] <= now;
                                    this.cancel();
                                    MasterListener.getGuiManager().removeParticleTask(pid);
                                    PlayerTracker.getInstance().stopNavigation(pid);
                                    if (canNotify) {
                                        new BukkitRunnable() {
                                            @Override
                                            public void run() {
                                                player.sendMessage(String.valueOf((Object) ChatColor.GREEN)
                                                        + LanguageManager.getInstance()
                                                                .getString(player, "messages.arrive-destination"));
                                                player.playSound(player.getLocation(),
                                                        Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
                                            }
                                        }.runTask((Plugin) PathFinderPlugin.getInstance());
                                        long expirySnapshot = newExpiry[0];
                                        new BukkitRunnable() {
                                            @Override
                                            public void run() {
                                                arrivalNotifyCooldownUntil.compute(pid, (k, v) -> (v != null && v == expirySnapshot) ? null : v);
                                            }
                                        }.runTaskLater((Plugin) PathFinderPlugin.getInstance(), 60L);
                                    }
                                    return;
                                }

                                if (finalPath != null && !finalPath.isEmpty()) {
                                    int maxDrawNodes = Math.min((int) finalPath.size(),
                                            (int) PathfinderConfig.MAX_PARTICLE_DISTANCE);
                                    for (int i = 0; i < maxDrawNodes - 1; ++i) {
                                        Pathfinder.Node currentNode = (Pathfinder.Node) finalPath.get(i);
                                        Pathfinder.Node nextNode = (Pathfinder.Node) finalPath.get(i + 1);

                                        if (currentNode.moveType == 5) { // MOVE_BLOCK_JUMP
                                            int jumpEndIndex = i + 1;
                                            while (jumpEndIndex < maxDrawNodes - 1 &&
                                                    ((Pathfinder.Node) finalPath.get(jumpEndIndex)).moveType == 5) {
                                                jumpEndIndex++;
                                            }

                                            Pathfinder.Node jumpStartNode = currentNode;
                                            Pathfinder.Node jumpEndNode = (Pathfinder.Node) finalPath.get(jumpEndIndex);

                                            Location jumpStart = new Location(
                                                    jumpStartNode.location.getWorld(),
                                                    jumpStartNode.location.getBlockX() + 0.5,
                                                    jumpStartNode.location.getBlockY() + 0.5,
                                                    jumpStartNode.location.getBlockZ() + 0.5);
                                            Location jumpEnd = new Location(
                                                    jumpEndNode.location.getWorld(),
                                                    jumpEndNode.location.getBlockX() + 0.5,
                                                    jumpEndNode.location.getBlockY() + 0.5,
                                                    jumpEndNode.location.getBlockZ() + 0.5);

                                            generateJumpParabola(player, jumpStart, jumpEnd);

                                            i = jumpEndIndex - 1;
                                            continue;
                                        }
                                        Location start = new Location(
                                                currentNode.location.getWorld(),
                                                currentNode.location.getBlockX() + 0.5,
                                                currentNode.location.getBlockY() + 0.5,
                                                currentNode.location.getBlockZ() + 0.5);
                                        Location end = new Location(
                                                nextNode.location.getWorld(),
                                                nextNode.location.getBlockX() + 0.5,
                                                nextNode.location.getBlockY() + 0.5,
                                                nextNode.location.getBlockZ() + 0.5);
                                        double distance = start.distance(end);
                                        Particle particleType = Particle.DUST;
                                        Color particleColor = Color.WHITE;
                                        float particleSize = PathfinderConfig.PARTICLE_SIZE;
                                        switch (currentNode.moveType) {
                                            case 3: {
                                                particleColor = Color.YELLOW;
                                                break;
                                            }
                                            case 4: {
                                                particleColor = Color.PURPLE;
                                                break;
                                            }
                                            case 1:
                                            case 2: {
                                                particleColor = Color.AQUA;
                                                break;
                                            }
                                            case 5: { // MOVE_BLOCK_JUMP
                                                particleColor = Color.AQUA;
                                                break;
                                            }
                                        }
                                        if (currentNode.toBreak) {
                                            Location blockLoc = currentNode.location.clone();
                                            ParticleGen.drawBlockOutline(player, blockLoc, Color.RED, 0.2);
                                            ParticleGen.drawBlockOutline(player,
                                                    blockLoc.clone().add(0.0, 1.0, 0.0), Color.RED, 0.2);
                                            ParticleGen.drawBlockOutline(player,
                                                    blockLoc.clone().add(0.0, -1.0, 0.0), Color.RED, 0.2);
                                        } else {
                                            Block block = currentNode.location.getBlock();
                                            if (Pathfinder.isLadder(block)) {
                                                Location blockLoc = currentNode.location.clone();
                                                ParticleGen.drawBlockOutline(player, blockLoc, Color.GREEN,
                                                        0.2);
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isLadder(blockAbove)) {
                                                    ParticleGen.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                            } else if (Pathfinder.isScaffolding(block)) {
                                                Location blockLoc = currentNode.location.clone();
                                                ParticleGen.drawBlockOutline(player, blockLoc, Color.ORANGE,
                                                        0.2);
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isScaffolding(blockAbove)) {
                                                    ParticleGen.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.ORANGE, 0.2);
                                                }
                                            } else if (Pathfinder.isTrapdoor(block)) {
                                                ParticleGen.drawBlockOutline(player,
                                                        currentNode.location.clone(), Color.GREEN, 0.2);
                                            } else if (currentNode.isFenceGate) {
                                                Location blockLoc = currentNode.location.clone();
                                                ParticleGen.drawBlockOutline(player, blockLoc, Color.GREEN,
                                                        0.2);
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isFenceGate(blockAbove)) {
                                                    ParticleGen.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                                Block blockBelow = block.getRelative(0, -1, 0);
                                                if (Pathfinder.isFenceGate(blockBelow)) {
                                                    ParticleGen.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, -1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                            } else if (currentNode.isDoor) {
                                                Location blockLoc = currentNode.location.clone();
                                                ParticleGen.drawBlockOutline(player, blockLoc, Color.GREEN,
                                                        0.2);
                                                Block blockAbove = block.getRelative(0, 1, 0);
                                                if (Pathfinder.isDoor(blockAbove)) {
                                                    ParticleGen.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, 1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                                Block blockBelow = block.getRelative(0, -1, 0);
                                                if (Pathfinder.isDoor(blockBelow)) {
                                                    ParticleGen.drawBlockOutline(player,
                                                            blockLoc.clone().add(0.0, -1.0, 0.0), Color.GREEN, 0.2);
                                                }
                                            } else if (currentNode.isBanner) {
                                            }
                                        }
                                        if (!(distance > PathfinderConfig.PARTICLE_SPACING)) {
                                            player.spawnParticle(particleType, end, 1, 0.0, 0.0, 0.0, 0.0,
                                                    (Object) new Particle.DustOptions(particleColor,
                                                            particleSize * 1.5f));
                                            continue;
                                        }

                                        {
                                            double totalDistance = start.distance(end);
                                            int steps = Math.max(
                                                    (int) Math.ceil(totalDistance / PathfinderConfig.PARTICLE_SPACING), 1);

                                            player.spawnParticle(particleType, start, 1, 0.0, 0.0, 0.0, 0.0,
                                                    (Object) new Particle.DustOptions(particleColor,
                                                            particleSize * 1.5f));

                                            for (int step = 1; step < steps; ++step) {
                                                double ratio = (double) step / (double) steps;
                                                if (ratio >= 1.0)
                                                    break;
                                                Location intermediateLoc = start.clone().add(
                                                        (end.getX() - start.getX()) * ratio,
                                                        (end.getY() - start.getY()) * ratio,
                                                        (end.getZ() - start.getZ()) * ratio);
                                                Location particleLoc = ParticleGen
                                                        .adjustParticleLocationForWater(intermediateLoc);
                                                float size = particleSize * 1.2f;
                                                player.spawnParticle(particleType, particleLoc, 1, 0.0, 0.0, 0.0, 0.0,
                                                        (Object) new Particle.DustOptions(particleColor, size));
                                            }
                                            player.spawnParticle(particleType, end, 1, 0.0, 0.0, 0.0, 0.0,
                                                    (Object) new Particle.DustOptions(particleColor,
                                                            particleSize * 1.5f));
                                        }
                                    }

                                    if (finalPath.size() > 0) {
                                        Pathfinder.Node lastNode = (Pathfinder.Node) finalPath
                                                .get(finalPath.size() - 1);
                                        Location finalLocation = new Location(
                                                lastNode.location.getWorld(),
                                                lastNode.location.getBlockX() + 0.5,
                                                lastNode.location.getBlockY() + 0.5,
                                                lastNode.location.getBlockZ() + 0.5);

                                        Color particleColor = Color.WHITE;

                                        player.spawnParticle(Particle.DUST, finalLocation, 1, 0.0, 0.0, 0.0, 0.0,
                                                (Object) new Particle.DustOptions(particleColor, 2.0f));
                                    }

                                    if (!isStrongholdNav) {
                                        // empty if block
                                    }
                                }
                            }
                        }.runTask((Plugin) PathFinderPlugin.getInstance());
                    }
                }.runTaskTimerAsynchronously((Plugin) PathFinderPlugin.getInstance(), 0L, PathfinderConfig.PATH_REFRESH_TICKS);
                if (PathFinderPlugin.getInstance().isEnabled()) {
                    MasterListener.getGuiManager().addParticleTask(player.getUniqueId(), pathfindingTask);
                    TaskManager.addPlayerNavigationTask(player.getUniqueId(), pathfindingTask);
                }
            }
        }.runTask((Plugin) PathFinderPlugin.getInstance());
    }

    @SuppressWarnings("deprecation")
    public static void updateNavigationInfo(Player player, Location intermediateTarget, Location finalTarget) {
        Player targetPlayer;
        Location playerLoc = player.getLocation().clone();
        Location infoTarget = finalTarget != null ? finalTarget : intermediateTarget;
        if (infoTarget == null) {
            return;
        }

        double distance = playerLoc.distance(infoTarget);
        double horizontalDistance = Math
                .sqrt((double) (Math.pow((double) (playerLoc.getX() - infoTarget.getX()), (double) 2.0)
                        + Math.pow((double) (playerLoc.getZ() - infoTarget.getZ()), (double) 2.0)));
        double verticalDistance = infoTarget.getY() - playerLoc.getY();
        Vector direction = infoTarget.toVector().subtract(playerLoc.toVector());
        float playerYaw = playerLoc.getYaw();
        double angle = Math.toDegrees((double) Math.atan2((double) (-direction.getX()), (double) direction.getZ()));
        double relativeAngle = (angle - (double) playerYaw + 360.0) % 360.0;
        String directionString = getDirectionString(player, relativeAngle);
        String verticalDirection = verticalDistance > 0.0
                ? LanguageManager.getInstance().getString(player, "messages.up")
                : LanguageManager.getInstance().getString(player, "messages.down");
        String targetName = LanguageManager.getInstance().getString(player, "messages.unknown-target");
        PlayerTracker tracker = PlayerTracker.getInstance();

        if (tracker.getBeaconNavigation(player.getUniqueId()) != null) {
            targetName = LanguageManager.getInstance().getString(player, "messages.beacon-block");
        }
        else if (tracker.getStrongholdNavigation(player.getUniqueId()) != null) {
            final Location strongholdLoc = tracker.getStrongholdNavigation(player.getUniqueId());
            if (strongholdLoc.getBlock().getType() != Material.END_PORTAL_FRAME
                    && player.getLocation().distance(strongholdLoc) < 300.0) {
                findNearestPortalFrameAsync(strongholdLoc, 150, portalFrame -> {
                    if (!PathFinderPlugin.getInstance().isEnabled()) {
                        return;
                    }
                    if (portalFrame != null) {
                        PlayerTracker.getInstance().setStrongholdNavigation(player.getUniqueId(), portalFrame);
                        player.sendMessage("§e"
                                + LanguageManager.getInstance().getString(player, "messages.end-portal-frame-coords",
                                        portalFrame.getBlockX(), portalFrame.getBlockY(), portalFrame.getBlockZ()));
                        updateActionBarWithNewTarget(player, portalFrame,
                                LanguageManager.getInstance().getString(player, "messages.end-portal-frame"));
                    } else {
                        player.sendMessage("§c" + LanguageManager.getInstance().getString(player,
                                "messages.end-portal-frame-not-found"));
                        PlayerTracker.getInstance().stopNavigation(player.getUniqueId());
                    }
                });
                return;
            } else {
                targetName = strongholdLoc.getBlock().getType() == Material.END_PORTAL_FRAME
                        ? LanguageManager.getInstance().getString(player, "messages.end-portal-frame")
                        : LanguageManager.getInstance().getString(player, "messages.stronghold-name");
            }
        }
        else if (tracker.getWaypointNavigation(player.getUniqueId()) != null) {
            String wpName = tracker.getWaypointName(player.getUniqueId());
            targetName = (wpName != null && !wpName.isEmpty()) ? wpName
                    : LanguageManager.getInstance().getString(player, "messages.unknown-target");
        }
        else if (tracker.getNavigationTarget(player.getUniqueId()) != null && (targetPlayer = Bukkit
                .getPlayer((UUID) (tracker.getNavigationTarget(player.getUniqueId())))) != null) {
            targetName = targetPlayer.getName();
        }

        if (PlayerTracker.getInstance().isActionBarSuppressed(player.getUniqueId())) {
            return;
        }
        String message = LanguageManager.getInstance().getString(player, "messages.action-bar",
                targetName, String.format("%.1f", distance), directionString, (int) relativeAngle + "°",
                String.format("%.1f", horizontalDistance), String.format("%.1f", Math.abs(verticalDistance)),
                verticalDirection);

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                (BaseComponent) new net.md_5.bungee.api.chat.TextComponent(message));
    }

    @Deprecated
    public static Location findNearestPortalFrame(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        Location nearestFrame = null;
        double minDistance = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; ++x) {
            for (int y = -radius; y <= radius; ++y) {
                for (int z = -radius; z <= radius; ++z) {
                    Block block = world.getBlockAt(center.getBlockX() + x, center.getBlockY() + y,
                            center.getBlockZ() + z);
                    if (block.getType() != Material.END_PORTAL_FRAME)
                        continue;

                    Location frameLoc = block.getLocation();
                    double distance = frameLoc.distance(center);

                    if (distance < minDistance) {
                        minDistance = distance;
                        nearestFrame = frameLoc;
                    }
                }
            }
        }

        return nearestFrame;
    }

    public static void findNearestPortalFrameAsync(Location center, int radius, Consumer<Location> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!PathFinderPlugin.getInstance().isEnabled()) {
                    callback.accept(null);
                    return;
                }

                Location nearestFrame = findNearestPortalFrame(center, radius);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        callback.accept(nearestFrame);
                    }
                }.runTask(PathFinderPlugin.getInstance());
            }
        }.runTaskAsynchronously(PathFinderPlugin.getInstance());
    }

    @SuppressWarnings("deprecation")
    public static void findNearestBeaconAsync(Player player, int maxRadius, Consumer<Location> callback) {
        Location playerLocation = player.getLocation().clone();
        World world = playerLocation.getWorld();
        if (world == null) {
            callback.accept(null);
            return;
        }

        UUID playerId = player.getUniqueId();

        TaskManager.removeBeaconSearchTask(playerId);

        int taskId = (int) (Math.random() * 9000) + 1000;

        player.sendMessage(String.valueOf((Object) ChatColor.YELLOW)
                + LanguageManager.getInstance().getString(player, "messages.searching-for-beacon", taskId));
        player.sendMessage(String.valueOf((Object) ChatColor.GRAY)
                + LanguageManager.getInstance().getString(player, "messages.search-radius", maxRadius)
                + "，"
                + LanguageManager.getInstance().getString(player, "messages.player-location",
                        playerLocation.getBlockX(), playerLocation.getBlockY(), playerLocation.getBlockZ()));

        final boolean[] callbackCalled = { false };

        BukkitTask searchTask = new BukkitRunnable() {
            @Override
            public void run() {
                final BeaconSearchResult result = new BeaconSearchResult();

                int playerX = playerLocation.getBlockX();
                int playerY = playerLocation.getBlockY();
                int playerZ = playerLocation.getBlockZ();

                int minX = playerX - maxRadius;
                int maxX = playerX + maxRadius;
                int minZ = playerZ - maxRadius;
                int maxZ = playerZ + maxRadius;

                int minY = Math.max(world.getMinHeight(), playerY - 50);
                int maxY = Math.min(world.getMaxHeight() - 1, playerY + 50);

                for (int radius = 0; radius <= maxRadius; radius++) {
                    if (this.isCancelled()) {
                        break;
                    }

                    boolean foundInThisRadius = false;

                    for (int x = playerX - radius; x <= playerX + radius; x++) {
                        if (this.isCancelled()) {
                            break;
                        }

                        for (int z = playerZ - radius; z <= playerZ + radius; z++) {
                            if (this.isCancelled()) {
                                break;
                            }

                            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                                continue;
                            }

                            if (radius > 0 && x > playerX - radius && x < playerX + radius &&
                                    z > playerZ - radius && z < playerZ + radius) {
                                continue;
                            }

                            searchBeaconAtPosition(world, x, z, playerLocation, minY, maxY,
                                    (loc, dist, belowBlock) -> {
                                        if (dist < result.minDistance) {
                                            result.minDistance = dist;
                                            result.nearestBeacon = loc;
                                        }
                                    });

                            if (result.nearestBeacon != null) {
                                foundInThisRadius = true;
                            }
                        }
                    }

                    if (foundInThisRadius && radius > 10) {
                        break;
                    }
                }

                final Location finalNearestBeacon = result.nearestBeacon;
                if (!this.isCancelled()) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!PathFinderPlugin.getInstance().isEnabled()) {
                                return;
                            }

                            synchronized (callbackCalled) {
                                if (callbackCalled[0]) {
                                    return;
                                }
                                callbackCalled[0] = true;
                            }

                            TaskManager.removeBeaconSearchTask(playerId);

                            if (finalNearestBeacon != null) {
                                int x = finalNearestBeacon.getBlockX();
                                int y = finalNearestBeacon.getBlockY();
                                int z = finalNearestBeacon.getBlockZ();
                                String beaconCoords = String.format("x: %d, y: %d, z: %d", x, y, z);
                                double distance = playerLocation
                                        .distance(finalNearestBeacon.clone().add(0.5, 0.5, 0.5));

                                player.sendMessage(String.valueOf((Object) ChatColor.AQUA)
                                        + LanguageManager.getInstance().getString(player, "messages.beacon-coords",
                                                beaconCoords,
                                                String.format("%.1f", distance)));

                                Location navLocation = finalNearestBeacon.clone().add(0.5, 0.5, 0.5);
                                callback.accept(navLocation);
                            } else {
                                callback.accept(null);
                            }
                        }
                    }.runTask(PathFinderPlugin.getInstance());
                }
            }
        }.runTaskAsynchronously(PathFinderPlugin.getInstance());

        BukkitTask timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!PathFinderPlugin.getInstance().isEnabled()) {
                    searchTask.cancel();
                    return;
                }

                if (!searchTask.isCancelled()) {
                    searchTask.cancel();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!PathFinderPlugin.getInstance().isEnabled()) {
                                return;
                            }

                            synchronized (callbackCalled) {
                                if (callbackCalled[0]) {
                                    return;
                                }
                                callbackCalled[0] = true;
                            }

                            TaskManager.removeBeaconSearchTask(playerId);

                            if (player.isOnline()) {
                                player.sendMessage(
                                        String.valueOf((Object) ChatColor.RED)
                                                + LanguageManager.getInstance()
                                                        .getString(player, "messages.beacon-search-timeout", taskId));
                                callback.accept(null);
                            }
                        }
                    }.runTask(PathFinderPlugin.getInstance());
                }
            }
        }.runTaskLaterAsynchronously(PathFinderPlugin.getInstance(), 20 * 20);

        TaskManager.addBeaconSearchTask(playerId, searchTask, timeoutTask);
    }

    public static void searchBeaconAtPosition(World world, int x, int z, Location playerLocation,
                                int minY, int maxY, BeaconResultHandler resultHandler) {
        int playerY = playerLocation.getBlockY();

        checkForBeacon(world, x, playerY, z, playerLocation, resultHandler);

        for (int yOffset = 1; yOffset <= Math.max(playerY - minY, maxY - playerY); yOffset++) {
            int upperY = playerY + yOffset;
            if (upperY <= maxY) {
                checkForBeacon(world, x, upperY, z, playerLocation, resultHandler);
            }

            int lowerY = playerY - yOffset;
            if (lowerY >= minY) {
                checkForBeacon(world, x, lowerY, z, playerLocation, resultHandler);
            }

            if (upperY > maxY && lowerY < minY) {
                break;
            }
        }
    }

    public static Location findWaterLanding(Location playerLoc, Location targetLoc) {
        int startY = playerLoc.getBlockY();
        int endY = targetLoc.getBlockY();
        int x = targetLoc.getBlockX();
        int z = targetLoc.getBlockZ();
        Location safeLanding = findSafeLandingNearWater(targetLoc);
        if (safeLanding != null) {
            return safeLanding;
        }
        for (int y = startY; y > endY && y > 0; --y) {
            Location aboveLoc;
            Location checkLoc = new Location(playerLoc.getWorld(), (double) x, (double) y, (double) z);
            if (!checkLoc.getBlock().getType().name().contains((CharSequence) "WATER")
                    || (aboveLoc = checkLoc.clone().add(0.0, 1.0, 0.0)).getBlock().getType().name()
                            .contains((CharSequence) "WATER")
                    || !aboveLoc.getBlock().isPassable())
                continue;
            boolean hasSpace = true;
            for (int checkY = y + 1; checkY < y + 3; ++checkY) {
                Location spaceCheckLoc = new Location(playerLoc.getWorld(), (double) x, (double) checkY, (double) z);
                if (spaceCheckLoc.getBlock().isPassable())
                    continue;
                hasSpace = false;
                break;
            }
            if (!hasSpace)
                continue;
            int depth = 1;
            Location belowLoc = checkLoc.clone();
            while (belowLoc.getBlockY() > 0) {
                belowLoc.subtract(0.0, 1.0, 0.0);
                if (!belowLoc.getBlock().getType().name().contains((CharSequence) "WATER"))
                    break;
                ++depth;
            }
            if (depth < 3)
                continue;
            return aboveLoc;
        }
        int radius = 10;
        for (int r = 1; r <= radius; ++r) {
            for (int dx = -r; dx <= r; ++dx) {
                for (int dz = -r; dz <= r; ++dz) {
                    if (Math.abs((int) dx) != r && Math.abs((int) dz) != r)
                        continue;
                    Location nearbyTarget = new Location(playerLoc.getWorld(), (double) (x + dx), (double) endY,
                            (double) (z + dz));
                    Location nearbySafeLanding = findSafeLandingNearWater(nearbyTarget);
                    if (nearbySafeLanding != null) {
                        return nearbySafeLanding;
                    }
                    Location checkLoc = new Location(playerLoc.getWorld(), (double) (x + dx), (double) endY,
                            (double) (z + dz));
                    for (int y = endY; y < startY; ++y) {
                        Location aboveLoc;
                        checkLoc.setY((double) y);
                        if (!checkLoc.getBlock().getType().name().contains((CharSequence) "WATER")
                                || (aboveLoc = checkLoc.clone().add(0.0, 1.0, 0.0)).getBlock().getType().name()
                                        .contains((CharSequence) "WATER")
                                || !aboveLoc.getBlock().isPassable())
                            continue;
                        int depth = 1;
                        Location belowLoc = checkLoc.clone();
                        while (belowLoc.getBlockY() > 0) {
                            belowLoc.subtract(0.0, 1.0, 0.0);
                            if (!belowLoc.getBlock().getType().name().contains((CharSequence) "WATER"))
                                break;
                            ++depth;
                        }
                        if (depth < 3)
                            continue;
                        return aboveLoc;
                    }
                }
            }
        }
        return null;
    }

    public static Location findSafeLandingNearWater(Location waterLoc) {
        int z;
        int y;
        int x;
        if (waterLoc == null) {
            return null;
        }
        World world = waterLoc.getWorld();
        Location aboveWater = new Location(world, (double) (x = waterLoc.getBlockX()),
                (double) ((y = waterLoc.getBlockY()) + 1), (double) (z = waterLoc.getBlockZ()));
        if (isSafeLanding(aboveWater)) {
            return aboveWater;
        }

        int maxRadius = 5;
        int checkedLocations = 0;
        int maxLocationsToCheck = 100;

        for (int r = 1; r <= maxRadius && checkedLocations < maxLocationsToCheck; ++r) {
            for (int dx = -r; dx <= r && checkedLocations < maxLocationsToCheck; ++dx) {
                for (int dz = -r; dz <= r && checkedLocations < maxLocationsToCheck; ++dz) {
                    if (Math.abs((int) dx) != r && Math.abs((int) dz) != r)
                        continue;
                    for (int dy = -2; dy <= 2 && checkedLocations < maxLocationsToCheck; ++dy) {
                        checkedLocations++;
                        Location checkLoc = new Location(world, (double) (x + dx), (double) (y + dy),
                                (double) (z + dz));
                        if (!isSafeLanding(checkLoc)
                                || checkLoc.getBlock().getType().name().contains((CharSequence) "WATER"))
                            continue;
                        return checkLoc;
                    }
                }
            }
        }
        return null;
    }

    public static boolean isSafeLanding(Location loc) {
        if (loc == null) {
            return false;
        }
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        boolean feetPassable = feet.isPassable() || feet.getType().name().contains((CharSequence) "DOOR")
                || feet.getType().name().contains((CharSequence) "TRAPDOOR")
                || feet.getType().name().contains((CharSequence) "LADDER")
                || feet.getType().name().contains((CharSequence) "SCAFFOLDING");
        boolean headPassable = head.isPassable() || head.getType().name().contains((CharSequence) "DOOR")
                || head.getType().name().contains((CharSequence) "TRAPDOOR")
                || head.getType().name().contains((CharSequence) "LADDER")
                || head.getType().name().contains((CharSequence) "SCAFFOLDING");
        boolean groundSolid = ground.getType().isSolid() || ground.getType().name().contains((CharSequence) "LADDER")
                || ground.getType().name().contains((CharSequence) "SCAFFOLDING");
        boolean isDangerous = feet.getType().name().contains((CharSequence) "LAVA")
                || feet.getType().name().contains((CharSequence) "FIRE")
                || head.getType().name().contains((CharSequence) "LAVA")
                || head.getType().name().contains((CharSequence) "FIRE")
                || ground.getType().name().contains((CharSequence) "LAVA")
                || ground.getType().name().contains((CharSequence) "FIRE");
        return feetPassable && headPassable && groundSolid && !isDangerous;
    }

    public static void generateJumpParabola(Player player, Location jumpStart, Location jumpEnd) {
        double horizontalDistance = Math
                .sqrt(Math.pow(jumpEnd.getX() - jumpStart.getX(), 2) + Math.pow(jumpEnd.getZ() - jumpStart.getZ(), 2));
        double verticalDistance = jumpEnd.getY() - jumpStart.getY();
        int steps = Math.max((int) Math.ceil(horizontalDistance / (PathfinderConfig.PARTICLE_SPACING * 0.2d)), 15);

        double maxHeight = 0.5;

        Particle particleType = Particle.DUST;
        Color particleColor = Color.AQUA;
        float particleSize = PathfinderConfig.PARTICLE_SIZE;

        for (int step = 0; step <= steps; ++step) {
            double t = (double) step / (double) steps;

            double x = jumpStart.getX() + (jumpEnd.getX() - jumpStart.getX()) * t;
            double z = jumpStart.getZ() + (jumpEnd.getZ() - jumpStart.getZ()) * t;

            // y = startY + verticalDistance*t + maxHeight*4*t*(1-t)
            double y = jumpStart.getY() + verticalDistance * t + maxHeight * 4.0d * t * (1.0d - t);

            Location intermediateLoc = new Location(jumpStart.getWorld(), x, y, z);
            Location particleLoc = ParticleGen.adjustParticleLocationForWater(intermediateLoc);

            float size = (step == 0 || step == steps) ? particleSize * 1.5f : particleSize * 1.2f;
            player.spawnParticle(particleType, particleLoc, 1, 0.0, 0.0, 0.0, 0.0,
                    (Object) new Particle.DustOptions(particleColor, size));
        }
    }

    public static void checkForBeacon(World world, int x, int y, int z, Location playerLocation,
                               BeaconResultHandler resultHandler) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == Material.BEACON) {
            Location beaconLoc = block.getLocation().clone();
            Location beaconCenter = beaconLoc.clone().add(0.5, 0.5, 0.5);
            double distanceSquared = playerLocation.distanceSquared(beaconCenter);

            Block blockBelow = world.getBlockAt(x, y - 1, z);

            resultHandler.handleResult(beaconLoc, distanceSquared, blockBelow);
        }
    }

    @SuppressWarnings("deprecation")
    public static void updateActionBarWithNewTarget(Player player, Location newTarget, String newTargetName) {
        Location playerLoc = player.getLocation().clone();
        double distance = playerLoc.distance(newTarget);
        double horizontalDistance = Math.sqrt(Math.pow(playerLoc.getX() - newTarget.getX(), 2.0)
                + Math.pow(playerLoc.getZ() - newTarget.getZ(), 2.0));
        double verticalDistance = newTarget.getY() - playerLoc.getY();
        Vector direction = newTarget.toVector().subtract(playerLoc.toVector());
        float playerYaw = playerLoc.getYaw();
        double angle = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        double relativeAngle = (angle - playerYaw + 360.0) % 360.0;
        String directionString = getDirectionString(player, relativeAngle);
        String verticalDirection = verticalDistance > 0.0
                ? LanguageManager.getInstance().getString(player, "messages.up")
                : LanguageManager.getInstance().getString(player, "messages.down");
        if (PlayerTracker.getInstance().isActionBarSuppressed(player.getUniqueId())) {
            return;
        }
        String message = LanguageManager.getInstance().getString(player, "messages.action-bar",
                newTargetName, String.format("%.1f", distance), directionString, (int) relativeAngle + "°",
                String.format("%.1f", horizontalDistance), String.format("%.1f", Math.abs(verticalDistance)),
                verticalDirection);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
    }

    public static String getDirectionString(Player player, double angle) {
        if ((angle = (angle % 360.0 + 360.0) % 360.0) >= 337.5 || angle < 22.5) {
            return LanguageManager.getInstance().getString(player, "messages.front");
        }
        if (angle < 67.5) {
            return LanguageManager.getInstance().getString(player, "messages.front-right");
        }
        if (angle < 112.5) {
            return LanguageManager.getInstance().getString(player, "messages.right");
        }
        if (angle < 157.5) {
            return LanguageManager.getInstance().getString(player, "messages.right-rear");
        }
        if (angle < 202.5) {
            return LanguageManager.getInstance().getString(player, "messages.back");
        }
        if (angle < 247.5) {
            return LanguageManager.getInstance().getString(player, "messages.left-rear");
        }
        if (angle < 292.5) {
            return LanguageManager.getInstance().getString(player, "messages.left");
        }
        if (angle < 337.5) {
            return LanguageManager.getInstance().getString(player, "messages.left-front");
        }
        return "";
    }

    public interface BeaconResultHandler {
        void handleResult(Location location, double distanceSquared, Block blockBelow);
    }

    private static class BeaconSearchResult {
        Location nearestBeacon = null;
        double minDistance = Double.MAX_VALUE;
    }
}
