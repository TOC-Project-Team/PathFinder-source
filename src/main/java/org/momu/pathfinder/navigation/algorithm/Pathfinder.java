package org.momu.pathfinder.navigation.algorithm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.momu.pathfinder.bootstrap.PathFinderPlugin;
import org.momu.pathfinder.config.PathfinderConfig;

import java.util.*;

public class Pathfinder {

    public static void loadConfig(PathFinderPlugin plugin) {
        PathfinderConfig.loadConfig(plugin);
    }

    public static void clearBannerCache() {
        BANNER_CACHE.clear();
        PathFinderPlugin plugin = PathFinderPlugin.getInstance();
    }

    public static boolean isPathCachingEnabled() {
        return PathfinderConfig.ENABLE_PATH_CACHING;
    }

    public static int getMaxSearchRadius() {
        return PathfinderConfig.MAX_SEARCH_RADIUS;
    }

    private static final Map<String, Boolean> STRING_CONTAINS_CACHE = new HashMap<>();
    private static final Map<String, String> LOCATION_KEY_CACHE = new HashMap<>(1000);
    private static final Map<Location, Boolean> SAFE_LOCATION_CACHE = new LinkedHashMap<Location, Boolean>(500, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Location, Boolean> eldest) {
            return size() > 500;
        }
    };

    private static final Set<Material> OBSTACLES = new HashSet<>();

    static {
        OBSTACLES.add(Material.CACTUS);
        OBSTACLES.add(Material.COBWEB);
        OBSTACLES.add(Material.SWEET_BERRY_BUSH);
        OBSTACLES.add(Material.VINE);
        OBSTACLES.add(Material.POWDER_SNOW);
        OBSTACLES.add(Material.POINTED_DRIPSTONE);
    }

    public static final int MOVE_HORIZONTAL = 0;
    public static final int MOVE_UP = 1;
    public static final int MOVE_DOWN = 2;
    public static final int MOVE_JUMP = 3;
    public static final int MOVE_FALL = 4;
    public static final int MOVE_BLOCK_JUMP = 5;

    public static boolean isPlayerInAir(Player player) {
        if (player == null) return false;
        Location playerLoc = player.getLocation();
        double verticalVelocity = player.getVelocity().getY();
        if (verticalVelocity > 0.05) return false;
        if (isBlockSupportive(playerLoc.getBlock().getRelative(0, -1, 0))) return false;
        for (int i = 2; i <= 10; i++) {
            Block blockBelow = playerLoc.getBlock().getRelative(0, -i, 0);
            if (isBlockSupportive(blockBelow)) {
                if (verticalVelocity >= -5.5) return false;
            }
        }
        return true;
    }
    private static boolean isBlockSupportive(Block block) {
        return block.getType().isSolid() ||
               block.getType().name().contains("WATER") ||
               isLadder(block) ||
               isScaffolding(block) ||
               isDoorPassable(block);
    }

    private static boolean shouldBreakBlock(Block block, Player player) {
        if (block == null)
            return false;
        Material type = block.getType();

        if (type.isAir()) {
            return false;
        }

        if (block.isPassable()) {
            return false;
        }

        if (!type.isSolid()) {
            return false;
        }

        if (isDoor(block) || isBanner(block) || isTrapdoor(block) || isScaffolding(block) || isFenceGate(block)) {
            return false;
        }

        if (isLowBlockButNotStair(block)) {
            return false;
        }

        return isBreakable(block.getLocation(), player);
    }

    public static List<Node> findPath(Location start, Location end, Player player) {
        SAFE_LOCATION_CACHE.clear();
        LOCATION_KEY_CACHE.clear();

        Location endLoc = end.getBlock().getLocation();
        PriorityQueue<Node> openSet = new PriorityQueue<>((n1, n2) -> {
            int fCompare = Double.compare(n1.f, n2.f);
            if (fCompare != 0)
                return fCompare;

            int gCompare = Double.compare(n2.g, n1.g);
            if (gCompare != 0)
                return gCompare;

            int xCompare = Integer.compare(n1.location.getBlockX(), n2.location.getBlockX());
            if (xCompare != 0)
                return xCompare;

            int zCompare = Integer.compare(n1.location.getBlockZ(), n2.location.getBlockZ());
            if (zCompare != 0)
                return zCompare;

            return Integer.compare(n1.location.getBlockY(), n2.location.getBlockY());
        });
        Map<String, Node> allNodes = new HashMap<>(1000);
        Set<String> closedSet = new HashSet<>(1000);

        Location startLoc = start.getBlock().getLocation();

        double startHeuristic = heuristic(startLoc, endLoc);

        Node startNode = new Node(startLoc, null, 0, startHeuristic, false);
        openSet.add(startNode);
        allNodes.put(locationKey(startLoc), startNode);

        int iterations = 0;
        double targetDistance = 1.0;
        double maxSearchRadiusSq = PathfinderConfig.MAX_SEARCH_RADIUS * PathfinderConfig.MAX_SEARCH_RADIUS;
        double earlyExitDistanceSq = 1.0;

        while (!openSet.isEmpty() && iterations < PathfinderConfig.MAX_ITERATIONS) {
            iterations++;
            Node current = openSet.poll();
            String currentKey = locationKey(current.location);

            if (closedSet.contains(currentKey)) {
                continue;
            }
            closedSet.add(currentKey);

            double distanceToEnd = current.location.distanceSquared(endLoc);
            if (distanceToEnd < targetDistance * targetDistance) {
                return reconstructPath(current);
            }

            if (distanceToEnd < earlyExitDistanceSq) {
                return reconstructPath(current);
            }

            if (current.location.distanceSquared(start) > maxSearchRadiusSq) {
                continue;
            }

            int[] dx = { 0, 1, -1, 0, 1, -1, 1, -1, 0 };
            int[] dz = { 1, 0, 0, -1, 1, 1, -1, -1, 0 };

            for (int i = 0; i < dx.length; i++) {
                if (dx[i] != 0 && dz[i] != 0) {
                    Location side1 = current.location.clone().add(dx[i], 0, 0);
                    Location side2 = current.location.clone().add(0, 0, dz[i]);
                    Location diagonal = current.location.clone().add(dx[i], 0, dz[i]);
                    Location head1 = side1.clone().add(0, 1, 0);
                    Location head2 = side2.clone().add(0, 1, 0);
                    Location diagHead = diagonal.clone().add(0, 1, 0);

                    if (!isSafe(side1) || !isSafe(side2) || !isSafe(diagonal) ||
                            (!head1.getBlock().isPassable() && !isDoor(head1.getBlock()) && !isBanner(head1.getBlock()))
                            ||
                            (!head2.getBlock().isPassable() && !isDoor(head2.getBlock()) && !isBanner(head2.getBlock()))
                            ||
                            (!diagHead.getBlock().isPassable() && !isDoor(diagHead.getBlock())
                                    && !isBanner(diagHead.getBlock()))) {
                        continue;
                    }
                }
                int minYOffset = -1;
                if (dx[i] == 0 && dz[i] == 0) {
                    minYOffset = -PathfinderConfig.MAX_SAFE_FALL_HEIGHT;
                } else if (Math.abs(dx[i]) <= 1 && Math.abs(dz[i]) <= 1) {
                    minYOffset = -2;
                }

                if (Math.abs(current.location.getY() - endLoc.getY()) > 10) {
                    minYOffset = Math.max(minYOffset, -5);
                }

                Location tempLoc = current.location.clone();

                for (int yOffset = minYOffset; yOffset <= 1; yOffset++) {
                    tempLoc.setX(current.location.getX() + dx[i]);
                    tempLoc.setY(current.location.getY() + yOffset);
                    tempLoc.setZ(current.location.getZ() + dz[i]);
                    Location nextLocation = tempLoc.clone();
                    String nextKey = locationKey(nextLocation);

                    if (closedSet.contains(nextKey)) {
                        continue;
                    }

                    boolean toBreak = false;
                    int moveType;

                    if (yOffset > 0) {
                        if (dx[i] != 0 || dz[i] != 0) {
                            moveType = MOVE_JUMP;
                        } else {
                            moveType = MOVE_UP;
                        }
                    } else if (yOffset < 0) {
                        moveType = MOVE_DOWN;
                    } else {
                        moveType = MOVE_HORIZONTAL;
                    }

                    // Height space checks based on move type
                    Block nextHead = nextLocation.getBlock().getRelative(0, 1, 0);
                    Block nextCeiling = nextLocation.getBlock().getRelative(0, 2, 0);
                    Block nextAbove = nextLocation.getBlock().getRelative(0, 3, 0);

                    int currentHeight = current.location.getBlockY();
                    int nextHeight = nextLocation.getBlockY();

                    if (moveType == MOVE_HORIZONTAL && currentHeight == nextHeight) {
                        Block targetFeet = nextLocation.getBlock();
                        boolean canPassThroughLowBlock = isLowBlockButNotStair(targetFeet) && (nextHead.isPassable()
                                || isDoor(nextHead) || isBanner(nextHead) || isTrapdoorPassable(nextHead))
                                && !OBSTACLES.contains(nextHead.getType());

                        if (canPassThroughLowBlock) {
                        } else {
                            if (!nextHead.isPassable() && !isDoor(nextHead) && !isBanner(nextHead)
                                    && !isTrapdoorPassable(nextHead)) {
                                if (shouldBreakBlock(nextHead, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                        }
                    }

                    if (moveType == MOVE_DOWN) {
                        boolean currentIsScaffolding = isScaffolding(current.location.getBlock());
                        boolean nextIsScaffolding = isScaffolding(nextLocation.getBlock());

                        if (!(currentIsScaffolding && nextIsScaffolding)) {
                            if (!nextHead.isPassable() && nextHead.getType().isSolid() &&
                                    !isDoor(nextHead) && !isBanner(nextHead) && !isIronTrapdoor(nextHead)
                                    && !isScaffolding(nextHead)) {
                                if (shouldBreakBlock(nextHead, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                            if (!nextCeiling.isPassable() && nextCeiling.getType().isSolid() &&
                                    !isDoor(nextCeiling) && !isBanner(nextCeiling) && !isIronTrapdoor(nextCeiling)
                                    && !isScaffolding(nextCeiling)) {
                                if (shouldBreakBlock(nextCeiling, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                        }
                        if (!nextAbove.isPassable() && nextAbove.getType().isSolid() &&
                                !isDoor(nextAbove) && !isBanner(nextAbove) && !isIronTrapdoor(nextAbove)) {
                            if (shouldBreakBlock(nextAbove, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        Block targetBlock = nextLocation.getBlock();
                        if (!isLowBlockButNotStair(targetBlock) && shouldBreakBlock(targetBlock, player)) {
                            toBreak = true;
                        }

                        int fallHeight = current.location.getBlockY() - nextLocation.getBlockY();

                        boolean targetInWater = isInWater(nextLocation);
                        if (fallHeight > PathfinderConfig.MAX_SAFE_FALL_HEIGHT && targetInWater) {
                            continue;
                        }

                        if (fallHeight > PathfinderConfig.MAX_SAFE_FALL_HEIGHT) {
                            if (Math.abs(dx[i]) <= 1 && Math.abs(dz[i]) <= 1) {
                                int blocksToBreak = fallHeight - PathfinderConfig.MAX_SAFE_FALL_HEIGHT;
                                boolean canBreak = true;
                                for (int j = 1; j <= blocksToBreak; j++) {
                                    Block blockBelow = current.location.getBlock().getRelative(0, -j, 0);
                                    if (!isBreakable(blockBelow.getLocation(), player)) {
                                        canBreak = false;
                                        break;
                                    }
                                }
                                if (canBreak) {
                                    toBreak = true;
                                    moveType = MOVE_FALL;
                                } else {
                                    continue;
                                }
                            } else {
                                continue;
                            }
                        } else if (fallHeight > 0) {
                            moveType = MOVE_FALL;
                        }
                    }

                    if (moveType == MOVE_UP) {
                        boolean currentIsLadder = isLadder(current.location.getBlock());
                        boolean nextIsLadder = isLadder(nextLocation.getBlock());
                        boolean currentIsScaffolding = isScaffolding(current.location.getBlock());
                        boolean nextIsScaffolding = isScaffolding(nextLocation.getBlock());
                        boolean currentInWater = containsWithCache(current.location.getBlock().getType().name(), "WATER");
                        boolean nextInWater = containsWithCache(nextLocation.getBlock().getType().name(), "WATER");
                        if (!( (currentIsLadder && nextIsLadder) || (currentIsScaffolding && nextIsScaffolding) || (currentInWater && nextInWater) )) {
                            continue;
                        }

                        if (!(currentIsScaffolding && nextIsScaffolding)) {
                            if (!nextHead.isPassable() && nextHead.getType().isSolid() &&
                                    !isDoor(nextHead) && !isBanner(nextHead) && !isIronTrapdoor(nextHead)
                                    && !isScaffolding(nextHead)) {
                                if (shouldBreakBlock(nextHead, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                            if (!nextCeiling.isPassable() && nextCeiling.getType().isSolid() &&
                                    !isDoor(nextCeiling) && !isBanner(nextCeiling) && !isIronTrapdoor(nextCeiling)
                                    && !isScaffolding(nextCeiling)) {
                                if (shouldBreakBlock(nextCeiling, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                        }

                        Block currentFeet = current.location.getBlock();
                        if (!currentFeet.isPassable() && currentFeet.getType().isSolid() &&
                                !isDoor(currentFeet) && !isBanner(currentFeet) && !isIronTrapdoor(currentFeet)) {
                            if (shouldBreakBlock(currentFeet, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }
                    }

                    if (moveType == MOVE_JUMP) {
                        Block targetBlock = nextLocation.getBlock();

                        Block targetHead = targetBlock.getRelative(0, 1, 0);
                        Block targetBelow = targetBlock.getRelative(0, -1, 0);
                        if (isBanner(targetBlock) || isBanner(targetHead) || isBanner(targetBelow)) {
                            continue;
                        }

                        Block currentFeetBlock = current.location.getBlock();
                        Block currentGroundBlock = current.location.clone().add(0, -1, 0).getBlock();

                        boolean onScaffolding = isScaffolding(currentGroundBlock) || isScaffolding(currentFeetBlock);
                        boolean standingOnLowBlock = (isLowBlockButNotStair(currentFeetBlock)
                                || isLowBlockButNotStair(currentGroundBlock)) && !onScaffolding;

                        if (standingOnLowBlock) {
                            int heightDiff = nextLocation.getBlockY() - current.location.getBlockY();
                            if (heightDiff > 0) {
                                continue;
                            }

                            Block targetAbove = nextLocation.clone().add(0, 1, 0).getBlock();
                            if (!targetAbove.isPassable() && targetAbove.getType().isSolid() && !isDoor(targetAbove)
                                    && !isBanner(targetAbove)) {
                                continue;
                            }
                        }

                        if (isFenceGate(targetBlock)) {
                            continue;
                        }

                        if (isBanner(targetBlock)) {
                            continue;
                        }

                        Block belowTarget = nextLocation.clone().add(0, -1, 0).getBlock();
                        if (isFenceGate(belowTarget)) {
                            continue;
                        }

                        if (isBanner(belowTarget)) {
                            continue;
                        }

                        if (isFence(targetBlock)) {
                            Block aboveFence = targetBlock.getRelative(0, 1, 0);
                            if (!isCarpet(aboveFence)) {
                                continue;
                            }
                        }

                        if (isFence(belowTarget)) {
                            Block aboveFence = belowTarget.getRelative(0, 1, 0);
                            if (!isCarpet(aboveFence)) {
                                continue;
                            }
                        }

                        if (!nextHead.isPassable() && nextHead.getType().isSolid() && !isDoor(nextHead)
                                && !isBanner(nextHead) && !isIronTrapdoor(nextHead) && !isScaffolding(nextHead)
                                && !isFenceGate(nextHead) && !isLowBlock(nextHead)) {
                            if (shouldBreakBlock(nextHead, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        if (nextHead.isPassable() || isDoorPassable(nextHead) || isBannerPassable(nextHead) ||
                                isTrapdoorPassable(nextHead) || nextHead.getType().name().contains("WATER")) {
                            if (!nextCeiling.isPassable() && nextCeiling.getType().isSolid() &&
                                    !isDoor(nextCeiling) && !isBanner(nextCeiling) && !isIronTrapdoor(nextCeiling)) {
                                if (shouldBreakBlock(nextCeiling, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                        } else {
                            if (!nextCeiling.isPassable() && nextCeiling.getType().isSolid() && !isDoor(nextCeiling)
                                    && !isBanner(nextCeiling) && !isTrapdoorPassable(nextCeiling)) {
                                if (shouldBreakBlock(nextCeiling, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                        }

                        Block currentHead = current.location.getBlock().getRelative(0, 1, 0);
                        Block currentCeiling = current.location.getBlock().getRelative(0, 2, 0);
                        Block currentAbove = current.location.getBlock().getRelative(0, 3, 0);

                        if (!currentHead.isPassable() && currentHead.getType().isSolid() &&
                                !isDoor(currentHead) && !isBanner(currentHead) && !isIronTrapdoor(currentHead)) {
                            if (shouldBreakBlock(currentHead, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        if (!currentCeiling.isPassable() && currentCeiling.getType().isSolid() &&
                                !isDoor(currentCeiling) && !isBanner(currentCeiling)
                                && !isIronTrapdoor(currentCeiling)) {
                            if (shouldBreakBlock(currentCeiling, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        if (!currentAbove.isPassable() && currentAbove.getType().isSolid() &&
                                !isDoor(currentAbove) && !isBanner(currentAbove) && !isIronTrapdoor(currentAbove)) {
                            if (shouldBreakBlock(currentAbove, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                    }

                    if (moveType == MOVE_FALL) {
                        Block targetBlock = nextLocation.getBlock();
                        if (!isLowBlockButNotStair(targetBlock) && shouldBreakBlock(targetBlock, player)) {
                            toBreak = true;
                        }
                    }

                    boolean diagonal = (dx[i] != 0 && dz[i] != 0);
                    double moveCost = diagonal ? PathfinderConfig.DIAGONAL_COST : PathfinderConfig.STRAIGHT_COST;

                    if (current.parent != null) {
                        int currentDirX = dx[i];
                        int currentDirZ = dz[i];

                        int prevDirX = current.dirX;
                        int prevDirZ = current.dirZ;

                        if (prevDirX != 0 || prevDirZ != 0) {
                            if (currentDirX != prevDirX || currentDirZ != prevDirZ) {
                                double dotProduct = currentDirX * prevDirX + currentDirZ * prevDirZ;
                                double prevMagnitude = Math.sqrt(prevDirX * prevDirX + prevDirZ * prevDirZ);
                                double currentMagnitude = Math
                                        .sqrt(currentDirX * currentDirX + currentDirZ * currentDirZ);
                                double cosAngle = dotProduct / (prevMagnitude * currentMagnitude);

                                if (Math.abs(cosAngle) < 0.01) {
                                    moveCost += PathfinderConfig.RIGHT_ANGLE_TURN_COST;
                                }
                                else if (Math.abs(cosAngle - 0.7071) < 0.1 || Math.abs(cosAngle + 0.7071) < 0.1) {
                                    moveCost += PathfinderConfig.DIAGONAL_TURN_COST;
                                }
                            }
                        }
                    }

                    if (yOffset <= 0) {
                        Location headPath = current.location.clone().add(dx[i], 1, dz[i]);
                        Block headPathBlock = headPath.getBlock();
                        Block belowHeadPath = headPath.clone().add(0, -1, 0).getBlock();

                        boolean isKelpOnWater = containsWithCache(headPathBlock.getType().name(), "KELP") &&
                                containsWithCache(belowHeadPath.getType().name(), "WATER");

                        if (isKelpOnWater) {
                        } else if (!headPathBlock.isPassable() && headPathBlock.getType().isSolid()
                                && !isDoor(headPathBlock) && !isBanner(headPathBlock)) {
                            if (shouldBreakBlock(headPathBlock, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }
                    }

                    if (!isSafe(nextLocation)) {
                        Block nextBlock = nextLocation.getBlock();
                        Block groundBlock = nextLocation.clone().add(0, -1, 0).getBlock();

                        boolean isKelpOnWater = containsWithCache(nextBlock.getType().name(), "KELP") &&
                                containsWithCache(groundBlock.getType().name(), "WATER");

                        if (isKelpOnWater) {
                        } else if (!isDoor(nextBlock) && !isBanner(nextBlock) && !isLowBlockButNotStair(nextBlock)
                                && shouldBreakBlock(nextBlock, player)) {
                            toBreak = true;
                        } else {
                            continue;
                        }
                    }

                    if (toBreak) {
                        moveCost += PathfinderConfig.BREAK_BLOCK_COST;
                    }

                    boolean nextInsideWater = isInsideWater(nextLocation);
                    boolean nextOnWaterSurface = isOnWaterSurface(nextLocation);
                    
                    if (nextInsideWater) {
                        moveCost += PathfinderConfig.WATER_COST * 5.0;
                    }

                    if (nextOnWaterSurface) {
                        moveCost += PathfinderConfig.WATER_COST;
                    }

                    if (isDoorPassable(nextLocation.getBlock()) || isBannerPassable(nextLocation.getBlock())
                            || isFenceGate(nextLocation.getBlock())) {
                        moveCost += PathfinderConfig.DOOR_COST;
                    }

                    Block currentFeet = nextLocation.getBlock();
                    Block belowFeet = nextLocation.clone().add(0, -1, 0).getBlock();

                    if (isFence(currentFeet) || isFence(belowFeet)) {
                        Block fenceBlock = isFence(currentFeet) ? currentFeet : belowFeet;
                        Block carpetBlock = fenceBlock.getRelative(0, 1, 0);
                        Block aboveCarpet = carpetBlock.getRelative(0, 1, 0);

                        if (!isCarpet(carpetBlock)) {
                            continue;
                        }

                        if (!aboveCarpet.isPassable()) {
                            continue;
                        }
                    }

                    if (isTrapdoor(nextLocation.getBlock())) {
                        moveCost += PathfinderConfig.TRAPDOOR_COST;
                    }
                    if (isTrapdoor(nextLocation.getBlock().getRelative(0, 1, 0))) {
                        moveCost += PathfinderConfig.TRAPDOOR_COST;
                    }

                    if (isScaffolding(nextLocation.getBlock())) {
                        moveCost += PathfinderConfig.SCAFFOLDING_COST;
                    }

                    if (moveType == MOVE_JUMP) {
                        moveCost += PathfinderConfig.JUMP_COST;
                    }

                    if (moveType == MOVE_BLOCK_JUMP) {
                        moveCost += PathfinderConfig.BLOCK_JUMP_COST;
                    }

                    if (moveType == MOVE_UP) {
                        moveCost += PathfinderConfig.VERTICAL_COST;
                    } else if (moveType == MOVE_DOWN || moveType == MOVE_FALL) {
                        moveCost += PathfinderConfig.FALL_COST;

                        int fallHeight = current.location.getBlockY() - nextLocation.getBlockY();
                        if (fallHeight > 1) {
                            moveCost += (fallHeight - 1) * 0.1;
                        }
                    }

                    double nextG = current.g + moveCost;
                    double nextH = heuristic(nextLocation, endLoc);

                    Node existingNode = allNodes.get(nextKey);
                    if (existingNode != null) {
                        if (nextG < existingNode.g) {
                            existingNode.parent = current;
                            existingNode.g = nextG;
                            existingNode.f = nextG + existingNode.h;
                            existingNode.toBreak = toBreak;
                            existingNode.moveType = moveType;
                            existingNode.dirX = nextLocation.getBlockX() - current.location.getBlockX();
                            existingNode.dirZ = nextLocation.getBlockZ() - current.location.getBlockZ();
                            openSet.remove(existingNode);
                            openSet.add(existingNode);
                        }
                    } else {
                        Node neighbor = new Node(nextLocation, current, nextG, nextH, toBreak, moveType);
                        openSet.add(neighbor);
                        allNodes.put(nextKey, neighbor);
                    }
                }
            }

            int[] jumpDx = { 0, 1, -1, 0 };
            int[] jumpDz = { 1, 0, 0, -1 };

            for (int dir = 0; dir < jumpDx.length; dir++) {
                int jumpDirX = jumpDx[dir];
                int jumpDirZ = jumpDz[dir];

                Block currentFeetBlock = current.location.getBlock();
                Block currentGroundBlock = current.location.clone().add(0, -1, 0).getBlock();
                boolean onScaffoldingForBlockJump = isScaffolding(currentGroundBlock) || isScaffolding(currentFeetBlock);
                if ((isLowBlockButNotStair(currentFeetBlock) || isLowBlockButNotStair(currentGroundBlock)) && !onScaffoldingForBlockJump) {
                    continue;
                }

                for (int distance = 2; distance <= PathfinderConfig.MAX_BLOCK_JUMP_DISTANCE; distance++) {
                    Location jumpTarget = current.location.clone().add(jumpDirX * distance, 0, jumpDirZ * distance);
                    String jumpKey = locationKey(jumpTarget);

                    if (closedSet.contains(jumpKey)) {
                        continue;
                    }

                    boolean canJump = true;

                    for (int y = 1; y <= 3; y++) {
                        Block checkBlock = current.location.getBlock().getRelative(0, y, 0);
                        if (!checkBlock.isPassable() && checkBlock.getType().isSolid() && !isDoor(checkBlock)
                                && !isBanner(checkBlock)) {
                            canJump = false;
                            break;
                        }
                    }

                    if (!canJump)
                        continue;

                    for (int y = 1; y <= 3; y++) {
                        Block checkBlock = jumpTarget.getBlock().getRelative(0, y, 0);
                        if (!checkBlock.isPassable() && checkBlock.getType().isSolid() && !isDoor(checkBlock)
                                && !isBanner(checkBlock)) {
                            canJump = false;
                            break;
                        }
                    }

                    if (!canJump)
                        continue;

                        boolean hasObstacle = false;
                        for (int step = 1; step < distance; step++) {
                            Location pathPoint = current.location.clone().add(jumpDirX * step, 0, jumpDirZ * step);
                            Block pathBlock = pathPoint.getBlock();
                            Block belowPathBlock = pathPoint.clone().add(0, -1, 0).getBlock();

                            boolean jumpableFence = isJumpableFence(pathBlock);
                            if (isBanner(pathBlock) || isBanner(belowPathBlock)) {
                                hasObstacle = true;
                                canJump = false;
                                break;
                            }
                            if (!pathBlock.isPassable() && pathBlock.getType().isSolid() && !jumpableFence
                                    && !isDoor(pathBlock)) {
                                hasObstacle = true;
                                canJump = false;
                                break;
                            }

                        if ((isFence(pathBlock) && !isJumpableFence(pathBlock)) || isFenceGate(pathBlock)) {
                            hasObstacle = true;
                            canJump = false;
                            break;
                        }

                        if (isFenceGate(belowPathBlock)) {
                            hasObstacle = true;
                            canJump = false;
                            break;
                        }

                        String blockName = pathBlock.getType().name();
                        if (containsWithCache(blockName, "WATER")) {
                            hasObstacle = true;
                            canJump = false;
                            break;
                        }

                        if (OBSTACLES.contains(pathBlock.getType()) ||
                                containsWithCache(blockName, "LAVA") ||
                                containsWithCache(blockName, "FIRE")) {
                            hasObstacle = true;
                            canJump = false;
                            break;
                        }

                        for (int y = 1; y <= 3; y++) {
                            Block checkBlock = pathPoint.getBlock().getRelative(0, y, 0);
                            if (isBanner(checkBlock)) {
                                hasObstacle = true;
                                canJump = false;
                                break;
                            }
                            if (!checkBlock.isPassable() && checkBlock.getType().isSolid() && !isDoor(checkBlock)) {
                                hasObstacle = true;
                                canJump = false;
                                break;
                            }

                            String checkBlockName = checkBlock.getType().name();
                            if (OBSTACLES.contains(checkBlock.getType()) ||
                                    containsWithCache(checkBlockName, "LAVA") ||
                                    containsWithCache(checkBlockName, "FIRE")) {
                                hasObstacle = true;
                                canJump = false;
                                break;
                            }
                        }
                        if (!canJump)
                            break;
                    }

                    int heightDiff = jumpTarget.getBlockY() - current.location.getBlockY();

                    boolean canReachByNormalMove = true;
                    if (distance == 2) {
                        Location midPoint = current.location.clone().add(jumpDirX, heightDiff, jumpDirZ);
                        if (!isSafe(midPoint) || heightDiff > 1) {
                            canReachByNormalMove = false;
                        }
                    }

                    if (!hasObstacle && distance == 2 && heightDiff <= 0 && canReachByNormalMove) {
                        canJump = false;
                    }

                    if (!canJump)
                        continue;

                    if (!isSafe(jumpTarget)) {
                        continue;
                    }

                    Block jumpTargetBlock = jumpTarget.getBlock();
                    Block belowJumpTarget = jumpTarget.clone().add(0, -1, 0).getBlock();
                    if (isFenceGate(jumpTargetBlock) || isFenceGate(belowJumpTarget)) {
                        continue;
                    }

                    if (isBanner(jumpTargetBlock) || isBanner(belowJumpTarget)) {
                        continue;
                    }

                    double jumpCost = PathfinderConfig.BLOCK_JUMP_COST * distance * distance;
                    double nextG = current.g + jumpCost;
                    double nextH = heuristic(jumpTarget, endLoc);

                    Node existingNode = allNodes.get(jumpKey);
                    if (existingNode != null) {
                        if (nextG < existingNode.g) {
                            existingNode.parent = current;
                            existingNode.g = nextG;
                            existingNode.f = nextG + existingNode.h;
                            existingNode.toBreak = false;
                            existingNode.moveType = MOVE_BLOCK_JUMP;
                            existingNode.dirX = jumpDirX * distance;
                            existingNode.dirZ = jumpDirZ * distance;
                            openSet.remove(existingNode);
                            openSet.add(existingNode);
                        }
                    } else {
                        Node jumpNode = new Node(jumpTarget, current, nextG, nextH, false, MOVE_BLOCK_JUMP);
                        jumpNode.dirX = jumpDirX * distance;
                        jumpNode.dirZ = jumpDirZ * distance;
                        openSet.add(jumpNode);
                        allNodes.put(jumpKey, jumpNode);
                    }
                }
            }
        }

        Node closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Node node : allNodes.values()) {
            double distance = node.location.distance(endLoc);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = node;
            }
        }

        return closest != null ? reconstructPath(closest) : null;
    }

    private static String locationKey(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        String key = x + "," + y + "," + z;

        String cachedKey = LOCATION_KEY_CACHE.get(key);
        if (cachedKey != null) {
            return cachedKey;
        }

        LOCATION_KEY_CACHE.put(key, key);
        return key;
    }

    private static List<Node> reconstructPath(Node node) {
        int pathLength = 0;
        Node temp = node;
        while (temp != null) {
            pathLength++;
            temp = temp.parent;
        }

        List<Node> path = new ArrayList<>(pathLength * 2);
        while (node != null) {
            path.add(node);
            node = node.parent;
        }
        Collections.reverse(path);

        List<Node> expandedPath = new ArrayList<>();

        if (!path.isEmpty()) {
            Node firstNode = path.get(0);
            if (path.size() > 1) {
                Node nextNode = path.get(1);
                int dx = nextNode.location.getBlockX() - firstNode.location.getBlockX();
                int dz = nextNode.location.getBlockZ() - firstNode.location.getBlockZ();
                int distance = Math.max(Math.abs(dx), Math.abs(dz));

                if (nextNode.moveType == MOVE_BLOCK_JUMP || distance > 1) {
                    firstNode.moveType = MOVE_BLOCK_JUMP;
                    firstNode.dirX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
                    firstNode.dirZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);
                }
            }
            expandedPath.add(firstNode);
        }

        for (int i = 0; i < path.size() - 1; i++) {
            Node currentNode = path.get(i);
            Node nextNode = path.get(i + 1);

            int dx = nextNode.location.getBlockX() - currentNode.location.getBlockX();
            int dz = nextNode.location.getBlockZ() - currentNode.location.getBlockZ();
            int dy = nextNode.location.getBlockY() - currentNode.location.getBlockY();
            int distance = Math.max(Math.abs(dx), Math.abs(dz));

            if (distance > 1 || nextNode.moveType == MOVE_BLOCK_JUMP) {
                currentNode.moveType = MOVE_BLOCK_JUMP;
                nextNode.moveType = MOVE_BLOCK_JUMP;

                int stepX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
                int stepZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);

                currentNode.dirX = stepX;
                currentNode.dirZ = stepZ;

                nextNode.dirX = stepX;
                nextNode.dirZ = stepZ;

                if (distance > 1) {
                    for (int step = 1; step < distance; step++) {
                        int midX = currentNode.location.getBlockX() + stepX * step;
                        int midY = currentNode.location.getBlockY();
                        int midZ = currentNode.location.getBlockZ() + stepZ * step;

                        Location midLoc = new Location(
                                currentNode.location.getWorld(),
                                midX,
                                midY,
                                midZ);

                        Node midNode = new Node(midLoc, currentNode, 0, 0, false, MOVE_BLOCK_JUMP);
                        midNode.dirX = stepX;
                        midNode.dirZ = stepZ;
                        expandedPath.add(midNode);
                    }
                }
            } else if (nextNode.moveType == MOVE_JUMP || dy > 0) {
                currentNode.moveType = MOVE_JUMP;
                nextNode.moveType = MOVE_JUMP;

                currentNode.dirX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
                currentNode.dirZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);
                nextNode.dirX = currentNode.dirX;
                nextNode.dirZ = currentNode.dirZ;
            }

            expandedPath.add(nextNode);
        }

        return expandedPath;
    }

    private static double heuristic(Location a, Location b) {
        int dx = Math.abs(a.getBlockX() - b.getBlockX());
        int dy = Math.abs(a.getBlockY() - b.getBlockY());
        int dz = Math.abs(a.getBlockZ() - b.getBlockZ());

        double baseHeuristic = dx + dz + dy * 1.5;

        boolean insideWater = isInsideWater(a);
        boolean targetInsideWater = isInsideWater(b);
        boolean onWaterSurface = isOnWaterSurface(a);

        if (insideWater) {
            baseHeuristic += PathfinderConfig.WATER_COST * 2.0;
        }

        if (targetInsideWater) {
            baseHeuristic += PathfinderConfig.WATER_COST * 2.0;
        }

        if (onWaterSurface) {
            baseHeuristic += PathfinderConfig.WATER_COST;
        }

        double perturbation = (a.getBlockX() * 0.001 + a.getBlockZ() * 0.0001) % 0.01;

        return baseHeuristic + perturbation;
    }

    private static boolean isInWater(Location location) {
        Block block = location.getBlock();
        String blockName = block.getType().name();

        return containsWithCache(blockName, "WATER");
    }

    private static boolean isOnWaterSurface(Location location) {
        Block currentBlock = location.getBlock();
        Block belowBlock = location.clone().add(0, -1, 0).getBlock();
        Block aboveBlock = location.clone().add(0, 1, 0).getBlock();

        String currentName = currentBlock.getType().name();
        String belowName = belowBlock.getType().name();
        String aboveName = aboveBlock.getType().name();

        boolean currentIsPassable = currentBlock.isPassable() || !containsWithCache(currentName, "WATER");
        boolean belowIsWater = containsWithCache(belowName, "WATER");
        boolean onWaterSurface = currentIsPassable && belowIsWater;

        boolean aboveIsWater = containsWithCache(aboveName, "WATER");
        boolean underWaterSurface = currentIsPassable && aboveIsWater && belowIsWater;

        boolean currentIsWater = containsWithCache(currentName, "WATER");
        boolean aboveIsPassable = aboveBlock.isPassable() && !containsWithCache(aboveName, "WATER");
        boolean shallowWaterSurface = currentIsWater && aboveIsPassable;

        return onWaterSurface || underWaterSurface || shallowWaterSurface;
    }

    private static boolean isInsideWater(Location location) {
        if (containsWithCache(location.getBlock().getType().name(), "WATER")) {
            Location aboveLocation = location.clone().add(0, 1, 0);
            if (containsWithCache(aboveLocation.getBlock().getType().name(), "WATER")) {
                return true;
            }

            Location belowLocation = location.clone().add(0, -1, 0);
            if (containsWithCache(belowLocation.getBlock().getType().name(), "WATER")) {
                Location deeperLocation = location.clone().add(0, -2, 0);
                if (containsWithCache(deeperLocation.getBlock().getType().name(), "WATER")) {
                    return true;
                }
            }
        }

        return false;
    }

    private static final Set<String> UNBREAKABLE_BLOCKS = new HashSet<>(Arrays.asList(
            "BEDROCK", "PORTAL", "SPAWNER", "BARRIER", "END_PORTAL", "END_GATEWAY"));

    private static boolean isBreakable(Location loc, Player player) {
        Block block = loc.getBlock();
        Material type = block.getType();

        if (type.isAir() || block.isPassable()) {
            return false;
        }

        if (!type.isSolid() || type.getHardness() >= 50) {
            return false;
        }

        if (isDoor(block) || isBanner(block) || isTrapdoor(block)) {
            return false;
        }

        if (isLowBlockButNotStair(block)) {
            Block above = block.getRelative(0, 1, 0);
            if (above.isPassable() && !OBSTACLES.contains(above.getType())) {
                return false;
            }
        }

        String typeName = type.name();
        if (containsWithCache(typeName, "KELP")) {
            Block below = block.getRelative(0, -1, 0);
            if (containsWithCache(below.getType().name(), "WATER")) {
                return false;
            }
        }

        if (isCarpet(block)) {
            Block below = block.getRelative(0, -1, 0);
            Block above = block.getRelative(0, 1, 0);

            if (isFence(below) && above.isPassable()) {
                return false;
            }
        }

        if (isFence(block)) {
            Block above = block.getRelative(0, 1, 0);
            Block aboveAbove = block.getRelative(0, 2, 0);

            if (isCarpet(above) && aboveAbove.isPassable()) {
                return false;
            }
        }

        String blockType = type.name();
        for (String unbreakable : UNBREAKABLE_BLOCKS) {
            if (blockType.contains(unbreakable)) {
                return false;
            }
        }

        boolean nearLava = false;
        int[][] directions = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, -1, 0 } };

        for (int[] dir : directions) {
            Block neighbor = block.getRelative(dir[0], dir[1], dir[2]);
            String neighborType = neighbor.getType().name();

            if (neighborType.contains("LAVA")) {
                nearLava = true;
                block.setMetadata("nearLava", new FixedMetadataValue(PathFinderPlugin.getInstance(), true));
                break;
            }
        }

        if (nearLava) {
            return false;
        }

        return true;
    }

    private static final Map<Material, Boolean> DOOR_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> TRAPDOOR_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> BANNER_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> LADDER_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> SCAFFOLDING_CACHE = new HashMap<>();

    public static boolean isDoor(Block block) {
        Material type = block.getType();
        Boolean result = DOOR_CACHE.get(type);
        if (result != null) {
            return result;
        }
        
        String materialName = type.name();
        String keyString = type.getKey().toString();

        result = (type == Material.OAK_DOOR || type == Material.SPRUCE_DOOR ||
                type == Material.BIRCH_DOOR || type == Material.JUNGLE_DOOR ||
                type == Material.ACACIA_DOOR || type == Material.DARK_OAK_DOOR ||
                type == Material.IRON_DOOR || type == Material.MANGROVE_DOOR ||
                type == Material.BAMBOO_DOOR || type == Material.CRIMSON_DOOR ||
                type == Material.WARPED_DOOR || type == Material.COPPER_DOOR ||
                type == Material.CHERRY_DOOR) ||
                containsWithCache(materialName, "DOOR") ||
                containsWithCache(keyString, "_door");

        DOOR_CACHE.put(type, result);
        return result;
    }

    public static boolean isDoorPassable(Block block) {
        if (!isDoor(block)) {
            return false;
        }

        return true;
    }

    public static boolean isBanner(Block block) {
        Material type = block.getType();
        Boolean result = BANNER_CACHE.get(type);
        if (result != null) {
            return result;
        }

        String materialName = type.name();
        String keyString = type.getKey().toString();

        result = (type == Material.WHITE_BANNER || type == Material.ORANGE_BANNER ||
                type == Material.MAGENTA_BANNER || type == Material.LIGHT_BLUE_BANNER ||
                type == Material.YELLOW_BANNER || type == Material.LIME_BANNER ||
                type == Material.PINK_BANNER || type == Material.GRAY_BANNER ||
                type == Material.LIGHT_GRAY_BANNER || type == Material.CYAN_BANNER ||
                type == Material.PURPLE_BANNER || type == Material.BLUE_BANNER ||
                type == Material.BROWN_BANNER || type == Material.GREEN_BANNER ||
                type == Material.RED_BANNER || type == Material.BLACK_BANNER ||
                type == Material.WHITE_WALL_BANNER || type == Material.ORANGE_WALL_BANNER ||
                type == Material.MAGENTA_WALL_BANNER || type == Material.LIGHT_BLUE_WALL_BANNER ||
                type == Material.YELLOW_WALL_BANNER || type == Material.LIME_WALL_BANNER ||
                type == Material.PINK_WALL_BANNER || type == Material.GRAY_WALL_BANNER ||
                type == Material.LIGHT_GRAY_WALL_BANNER || type == Material.CYAN_WALL_BANNER ||
                type == Material.PURPLE_WALL_BANNER || type == Material.BLUE_WALL_BANNER ||
                type == Material.BROWN_WALL_BANNER || type == Material.GREEN_WALL_BANNER ||
                type == Material.RED_WALL_BANNER || type == Material.BLACK_WALL_BANNER) ||
                containsWithCache(materialName, "BANNER") ||
                containsWithCache(keyString, "_banner") ||
                containsWithCache(keyString, "banners") ||
                containsWithCache(keyString, ":banner") ||
                keyString.equals("minecraft:banners");

        if (!result && (containsWithCache(materialName.toLowerCase(), "banner") ||
                containsWithCache(keyString.toLowerCase(), "banner"))) {
            PathFinderPlugin plugin = PathFinderPlugin.getInstance();
            if (plugin != null) {
                plugin.getLogger().info("未识别的旗帜方块: Material=" + materialName + ", Key=" + keyString);
            }
        }

        BANNER_CACHE.put(type, result);
        return result;
    }

    public static boolean isBannerPassable(Block block) {
        if (!isBanner(block)) {
            return false;
        }

        return true;
    }

    public static boolean isTrapdoor(Block block) {
        Material type = block.getType();
        Boolean result = TRAPDOOR_CACHE.get(type);
        if (result != null) {
            return result;
        }

        String materialName = type.name();
        String keyString = type.getKey().toString();

        result = (type == Material.OAK_TRAPDOOR || type == Material.SPRUCE_TRAPDOOR ||
                type == Material.BIRCH_TRAPDOOR || type == Material.JUNGLE_TRAPDOOR ||
                type == Material.ACACIA_TRAPDOOR || type == Material.DARK_OAK_TRAPDOOR ||
                type == Material.IRON_TRAPDOOR || type == Material.MANGROVE_TRAPDOOR ||
                type == Material.BAMBOO_TRAPDOOR || type == Material.CRIMSON_TRAPDOOR ||
                type == Material.WARPED_TRAPDOOR || type == Material.COPPER_TRAPDOOR ||
                type == Material.CHERRY_TRAPDOOR) ||
                containsWithCache(materialName, "TRAPDOOR") ||
                containsWithCache(keyString, "trapdoor");

        TRAPDOOR_CACHE.put(type, result);
        return result;
    }

    public static boolean isIronTrapdoor(Block block) {
        Material type = block.getType();
        String materialName = type.name();
        String keyString = type.getKey().toString();

        return type == Material.IRON_TRAPDOOR ||
                containsWithCache(materialName, "IRON_TRAPDOOR") ||
                containsWithCache(keyString, "iron_trapdoor");
    }

    public static boolean isTrapdoorPassable(Block block) {
        return isTrapdoor(block) && !isIronTrapdoor(block);
    }

    public static boolean isLadder(Block block) {
        Material type = block.getType();
        Boolean result = LADDER_CACHE.get(type);
        if (result != null) {
            return result;
        }
        result = containsWithCache(type.name(), "LADDER");
        LADDER_CACHE.put(type, result);
        return result;
    }
    
    private static boolean isJumpableFence(Block fenceBlock) {
        if (!isFence(fenceBlock)) {
            return false;
        }
        Block carpetBlock = fenceBlock.getRelative(0, 1, 0);
        if (!isCarpet(carpetBlock)) {
            return false;
        }
        Block aboveCarpet = carpetBlock.getRelative(0, 1, 0);
        return aboveCarpet.isPassable();
    }

    public static boolean isScaffolding(Block block) {
        Material type = block.getType();
        Boolean result = SCAFFOLDING_CACHE.get(type);
        if (result != null) {
            return result;
        }
        result = containsWithCache(type.name(), "SCAFFOLDING");
        SCAFFOLDING_CACHE.put(type, result);
        return result;
    }

    public static boolean isNearLava(Block block) {
        if (block.hasMetadata("nearLava")) {
            for (MetadataValue value : block.getMetadata("nearLava")) {
                if (value.asBoolean()) {
                    return true;
                }
            }
        }

        int[][] directions = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, -1, 0 } };

        for (int[] dir : directions) {
            Block neighbor = block.getRelative(dir[0], dir[1], dir[2]);
            if (neighbor.getType().name().contains("LAVA") || neighbor.getType().name().contains("FIRE")) {
                block.setMetadata("nearLava", new FixedMetadataValue(PathFinderPlugin.getInstance(), true));
                return true;
            }
        }

        return false;
    }

    private static final Map<Material, Boolean> PASSABLE_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> SOLID_CACHE = new HashMap<>();

    private static boolean containsWithCache(String str, String substr) {
        String key = str + "_" + substr;
        Boolean result = STRING_CONTAINS_CACHE.get(key);
        if (result != null) {
            return result;
        }
        result = str.contains(substr);
        STRING_CONTAINS_CACHE.put(key, result);
        return result;
    }

    private static boolean isSafe(Location loc) {
        Boolean cachedResult = SAFE_LOCATION_CACHE.get(loc);
        if (cachedResult != null) {
            return cachedResult;
        }

        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);

        Material feetType = feet.getType();
        Material headType = head.getType();
        Material groundType = ground.getType();

        String feetName = feetType.name();
        String headName = headType.name();
        String groundName = groundType.name();

        if (OBSTACLES.contains(feetType) || OBSTACLES.contains(headType) ||
                containsWithCache(feetName, "LAVA") || containsWithCache(feetName, "FIRE") ||
                containsWithCache(headName, "LAVA") || containsWithCache(headName, "FIRE") ||
                containsWithCache(groundName, "LAVA") || containsWithCache(groundName, "FIRE")) {
            SAFE_LOCATION_CACHE.put(loc.clone(), false);
            return false;
        }

        if (containsWithCache(groundName, "WATER") && containsWithCache(feetName, "KELP")) {
            feetType = Material.AIR;
            feetName = "AIR";
        }

        if (isInsideWater(loc)) {
            if (!containsWithCache(headName, "WATER") && !head.isPassable()) {
                SAFE_LOCATION_CACHE.put(loc.clone(), false);
                return false;
            }
        }

        boolean feetPassable = (feet.isPassable() && !OBSTACLES.contains(feetType)) || isDoorPassable(feet)
                || isBannerPassable(feet) || (isTrapdoor(feet) && !isIronTrapdoor(feet)) || isLadder(feet)
                || isScaffolding(feet) || isFenceGate(feet) ||
                containsWithCache(feetName, "WATER") ||
                (containsWithCache(groundName, "WATER") && containsWithCache(feetName, "KELP")) ||
                (isLowBlockButNotStair(feet) && head.isPassable() && !OBSTACLES.contains(headType));
        boolean headPassable = (head.isPassable() && !OBSTACLES.contains(headType)) || isDoorPassable(head)
                || isBannerPassable(head) || (isTrapdoor(head) && !isIronTrapdoor(head)) || isLadder(head)
                || isScaffolding(head) || isFenceGate(head);

        boolean groundSolid = groundType.isSolid() || containsWithCache(groundName, "WATER") ||
                isLadder(ground) || isScaffolding(ground);

        if (!headPassable && (isDoorPassable(head) || isBannerPassable(head)
                || (isTrapdoor(head) && !isIronTrapdoor(head)) || isFenceGate(head))) {
            headPassable = true;
        }

        boolean nearLava = false;
        int[][] directions = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, -1, 0 } };

        for (int[] dir : directions) {
            Block neighbor = feet.getRelative(dir[0], dir[1], dir[2]);
            String neighborType = neighbor.getType().name();

            if (containsWithCache(neighborType, "LAVA") || containsWithCache(neighborType, "FIRE")) {
                nearLava = true;
                break;
            }
        }

        if (nearLava) {
            loc.getBlock().setMetadata("nearLava",
                    new org.bukkit.metadata.FixedMetadataValue(PathFinderPlugin.getInstance(), true));
            SAFE_LOCATION_CACHE.put(loc.clone(), false);
            return false;
        }

        boolean result = feetPassable && headPassable && groundSolid;
        SAFE_LOCATION_CACHE.put(loc.clone(), result);
        return result;
    }

    public static class Node {
        public Location location;
        public Node parent;
        public double g;
        public double h;
        public double f;
        public boolean toBreak;
        public int moveType;
        public int dirX;
        public int dirZ;
        public boolean isFenceGate;
        public boolean isDoor;
        public boolean isBanner;

        Node(Location location, Node parent, double g, double h, boolean toBreak) {
            this.location = location;
            this.parent = parent;
            this.g = g;
            this.h = h;
            this.f = g + h;
            this.toBreak = toBreak;
            this.moveType = MOVE_HORIZONTAL;
            this.dirX = 0;
            this.dirZ = 0;
            this.isFenceGate = isFenceGate(location.getBlock()) ||
                    isFenceGate(location.clone().add(0, -1, 0).getBlock()) ||
                    isFenceGate(location.clone().add(0, 1, 0).getBlock());
            this.isDoor = isDoor(location.getBlock()) ||
                    isDoor(location.clone().add(0, -1, 0).getBlock()) ||
                    isDoor(location.clone().add(0, 1, 0).getBlock());
            this.isBanner = isBanner(location.getBlock()) ||
                    isBanner(location.clone().add(0, -1, 0).getBlock()) ||
                    isBanner(location.clone().add(0, 1, 0).getBlock());

            if (parent != null) {
                this.dirX = location.getBlockX() - parent.location.getBlockX();
                this.dirZ = location.getBlockZ() - parent.location.getBlockZ();

                int yDiff = location.getBlockY() - parent.location.getBlockY();
                if (yDiff > 0) {
                    this.moveType = MOVE_UP;
                } else if (yDiff < 0) {
                    this.moveType = MOVE_DOWN;
                }
            }
        }

        Node(Location location, Node parent, double g, double h, boolean toBreak, int moveType) {
            this.location = location;
            this.parent = parent;
            this.g = g;
            this.h = h;
            this.f = g + h;
            this.toBreak = toBreak;
            this.moveType = moveType;
            this.dirX = 0;
            this.dirZ = 0;
            this.isFenceGate = isFenceGate(location.getBlock()) ||
                    isFenceGate(location.clone().add(0, -1, 0).getBlock()) ||
                    isFenceGate(location.clone().add(0, 1, 0).getBlock());

            this.isDoor = isDoor(location.getBlock()) ||
                    isDoor(location.clone().add(0, -1, 0).getBlock()) ||
                    isDoor(location.clone().add(0, 1, 0).getBlock());

            this.isBanner = isBanner(location.getBlock()) ||
                    isBanner(location.clone().add(0, -1, 0).getBlock()) ||
                    isBanner(location.clone().add(0, 1, 0).getBlock());

            if (parent != null) {
                this.dirX = location.getBlockX() - parent.location.getBlockX();
                this.dirZ = location.getBlockZ() - parent.location.getBlockZ();
            }
        }
    }

    public static boolean isFence(Block block) {
        String name = block.getType().name();
        return containsWithCache(name, "FENCE") && !containsWithCache(name, "GATE");
    }

    public static boolean isFenceGate(Block block) {
        return containsWithCache(block.getType().name(), "FENCE_GATE");
    }

    public static boolean isCarpet(Block block) {
        return containsWithCache(block.getType().name(), "CARPET");
    }

    public static boolean isSlab(Block block) {
        if (!containsWithCache(block.getType().name(), "SLAB")) {
            return false;
        }

        try {
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Slab) {
                org.bukkit.block.data.type.Slab slabData = (org.bukkit.block.data.type.Slab) block.getBlockData();
                return slabData.getType() != org.bukkit.block.data.type.Slab.Type.DOUBLE;
            }
        } catch (Exception e) {
        }
        return true;
    }

    public static boolean isLantern(Block block) {
        String name = block.getType().name();
        return containsWithCache(name, "LANTERN");
    }

    public static boolean isCake(Block block) {
        return containsWithCache(block.getType().name(), "CAKE");
    }

    public static boolean isCandle(Block block) {
        return containsWithCache(block.getType().name(), "CANDLE");
    }

    public static boolean isCakeWithCandle(Block block) {
        String name = block.getType().name();
        return containsWithCache(name, "CANDLE_CAKE");
    }

    public static boolean isSlabStair(Block slabBlock) {
        if (!isSlab(slabBlock)) {
            return false;
        }

        Location loc = slabBlock.getLocation();

        int[][] directions = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

        for (int[] dir : directions) {
            if (isStairPatternInDirection(loc, dir[0], dir[1])) {
                return true;
            }
        }

        return false;
    }

    private static boolean isStairPatternInDirection(Location startLoc, int dirX, int dirZ) {
        int stairCount = 0;
        boolean hasHeightChange = false;

        for (int step = 1; step <= 3; step++) {
            Location checkLoc = startLoc.clone().add(dirX * step, 0, dirZ * step);
            Block checkBlock = checkLoc.getBlock();

            if (isSlab(checkBlock)) {
                stairCount++;
                continue;
            }

            Block upperBlock = checkLoc.clone().add(0, 1, 0).getBlock();
            if (isSlab(upperBlock)) {
                stairCount++;
                hasHeightChange = true;
                continue;
            }

            break;
        }

        for (int step = 1; step <= 3; step++) {
            Location checkLoc = startLoc.clone().add(-dirX * step, 0, -dirZ * step);
            Block checkBlock = checkLoc.getBlock();

            if (isSlab(checkBlock)) {
                stairCount++;
                continue;
            }

            Block lowerBlock = checkLoc.clone().add(0, -1, 0).getBlock();
            if (isSlab(lowerBlock)) {
                stairCount++;
                hasHeightChange = true;
                continue;
            }

            break;
        }

        return stairCount >= 2 && hasHeightChange;
    }

    public static boolean isLowBlockButNotStair(Block block) {
        if (!isLowBlock(block)) {
            return false;
        }

        if (isSlab(block) && isSlabStair(block)) {
            return false;
        }

        return true;
    }

    public static boolean isLowBlock(Block block) {
        return isSlab(block) || isLantern(block) || (isCake(block) && !isCakeWithCandle(block)) || isCandle(block);
    }

    public static boolean isCompletelyPassable(Block block) {
        return block.isPassable() || isDoor(block) || isBanner(block) || isFenceGate(block);
    }

    public static boolean isLocationPassableForPlayer(Location location) {
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();
        boolean feetPassable = isCompletelyPassable(feet);
        boolean headPassable = isCompletelyPassable(head);

        return feetPassable && headPassable;
    }
}
