package org.momu.tOCplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class Pathfinder {

    // 从配置文件加载的参数
    private static int MAX_SEARCH_RADIUS = 3000; // 搜索半径
    public static double PARTICLE_SPACING = 0.5; // 粒子间距
    public static int MAX_PARTICLE_DISTANCE = 30; // 显示粒子的最大距离
    public static float PARTICLE_SIZE = 1.0f; // 粒子大小
    public static int PATH_REFRESH_TICKS = 15; // 路径刷新频率（单位：tick，20tick=1秒）
    private static double DIAGONAL_COST = 4.0; // 对角线移动的代价
    private static double STRAIGHT_COST = 2.0; // 直线移动的代价
    private static double RIGHT_ANGLE_TURN_COST = 1.5; // 直角转弯的额外代价
    private static double DIAGONAL_TURN_COST = 0.5; // 斜角转弯的额外代价
    private static double BREAK_BLOCK_COST = 100.0; // 破坏方块的代价
    private static double WATER_COST = 10.0; // 水面行走的代价
    private static double DOOR_COST = 0.0; // 经过门的代价
    private static double TRAPDOOR_COST = 0.0; // 经过活板门的代价
    private static double SCAFFOLDING_COST = 0.0; // 经过脚手架的代价
    private static double JUMP_COST = 0.0; // 跳跃的代价
    private static double VERTICAL_COST = 1.0; // 垂直移动的代价
    private static double FALL_COST = 1.0; // 下落移动的代价
    private static double BLOCK_JUMP_COST = 1.0; // 跨方块跳跃的代价
    private static int MAX_BLOCK_JUMP_DISTANCE = 3; // 最大跨方块跳跃距离
    private static int MAX_SAFE_FALL_HEIGHT = 3; // 最大安全下落高度
    private static int MAX_ITERATIONS = 10000; // 最大迭代次数，增加以支持复杂水路寻路和大范围寻路
    private static boolean ENABLE_PATH_CACHING = false; // 是否启用路径缓存/平滑机制

    /**
     * 从配置文件加载寻路参数
     * 
     * @param plugin 插件实例
     */
    public static void loadConfig(TOCpluginNative plugin) {
        File pathfinderFile = new File(plugin.getDataFolder(), "pathfinder.yml");
        if (!pathfinderFile.exists()) {
            plugin.saveResource("pathfinder.yml", false);
        }

        // 加载当前配置文件
        FileConfiguration config = YamlConfiguration.loadConfiguration(pathfinderFile);

        // 加载默认配置文件，用于检查是否有新的配置项
        InputStream defaultConfigStream = plugin.getResource("pathfinder.yml");
        if (defaultConfigStream != null) {
            try {
                // 加载默认配置
                YamlConfiguration defaultConfig = YamlConfiguration
                        .loadConfiguration(new InputStreamReader(defaultConfigStream));
                boolean configUpdated = false;

                // 遍历默认配置中的所有键
                for (String key : defaultConfig.getKeys(true)) {
                    if (!config.contains(key)) {
                        // 如果当前配置中没有这个键，就添加它
                        config.set(key, defaultConfig.get(key));
                        configUpdated = true;
                        plugin.getLogger()
                                .info(LanguageManager.getInstance().getString("messages.added-new-config-item", key));
                    }
                }

                // 如果配置有更新，保存更新后的配置
                if (configUpdated) {
                    config.save(pathfinderFile);
                    plugin.getLogger()
                            .info(LanguageManager.getInstance().getString("messages.pathfinder-config-updated"));
                }
            } catch (Exception e) {
                plugin.getLogger().warning(LanguageManager.getInstance()
                        .getString("messages.pathfinder-config-update-error", e.getMessage()));
            } finally {
                try {
                    defaultConfigStream.close();
                } catch (IOException e) {
                    plugin.getLogger().warning(LanguageManager.getInstance()
                            .getString("messages.pathfinder-config-close-error", e.getMessage()));
                }
            }
        }

        // 加载所有参数
        MAX_SEARCH_RADIUS = config.getInt("max_search_radius", 3000);
        PARTICLE_SPACING = config.getDouble("particle_spacing", 0.5);
        MAX_PARTICLE_DISTANCE = config.getInt("max_particle_distance", 30);
        PARTICLE_SIZE = (float) config.getDouble("particle_size", 1.0);
        PATH_REFRESH_TICKS = config.getInt("path_refresh_ticks", 15);
        DIAGONAL_COST = config.getDouble("diagonal_cost", 3.0);
        STRAIGHT_COST = config.getDouble("straight_cost", 1.0);
        RIGHT_ANGLE_TURN_COST = config.getDouble("right_angle_turn_cost", 0.5);
        DIAGONAL_TURN_COST = config.getDouble("diagonal_turn_cost", 0.3);
        BREAK_BLOCK_COST = config.getDouble("break_block_cost", 100.0);
        WATER_COST = config.getDouble("water_cost", 10.0);
        DOOR_COST = config.getDouble("door_cost", 0.0);
        TRAPDOOR_COST = config.getDouble("trapdoor_cost", 0.0);
        SCAFFOLDING_COST = config.getDouble("scaffolding_cost", 0.0);
        JUMP_COST = config.getDouble("jump_cost", 0.0);
        VERTICAL_COST = config.getDouble("vertical_cost", 1.0);
        FALL_COST = config.getDouble("fall_cost", 1.0);
        BLOCK_JUMP_COST = config.getDouble("block_jump_cost", 1.0);
        MAX_BLOCK_JUMP_DISTANCE = config.getInt("max_block_jump_distance", 3);
        MAX_SAFE_FALL_HEIGHT = config.getInt("max_safe_fall_height", 3);
        MAX_ITERATIONS = config.getInt("max_iterations", 10000);
        ENABLE_PATH_CACHING = config.getBoolean("enable_path_caching", true);

        // 清理所有缓存，确保配置更改立即生效
        STRING_CONTAINS_CACHE.clear();
        LOCATION_KEY_CACHE.clear();
        SAFE_LOCATION_CACHE.clear();
        DOOR_CACHE.clear();
        TRAPDOOR_CACHE.clear();
        BANNER_CACHE.clear(); // 清理旗帜缓存，确保新的检测逻辑生效
        LADDER_CACHE.clear();
        SCAFFOLDING_CACHE.clear();
        PASSABLE_CACHE.clear();
        SOLID_CACHE.clear();

        plugin.getLogger()
                .info(LanguageManager.getInstance().getString("messages.pathfinder-config-loaded-and-cached"));
    }

    public static void clearBannerCache() {
        BANNER_CACHE.clear();
        TOCpluginNative plugin = TOCpluginNative.getInstance();
    }

    /**
     * 获取路径缓存是否启用
     * 
     * @return 是否启用路径缓存/平滑机制
     */
    public static boolean isPathCachingEnabled() {
        return ENABLE_PATH_CACHING;
    }

    /**
     * 获取最大搜索半径
     * 
     * @return 最大搜索半径
     */
    public static int getMaxSearchRadius() {
        return MAX_SEARCH_RADIUS;
    }

    // 全局缓存，减少重复计算和对象创建
    private static final Map<String, Boolean> STRING_CONTAINS_CACHE = new HashMap<>();
    private static final Map<String, String> LOCATION_KEY_CACHE = new HashMap<>(1000);
    private static final Map<Location, Boolean> SAFE_LOCATION_CACHE = new LinkedHashMap<Location, Boolean>(500, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Location, Boolean> eldest) {
            return size() > 500; // 限制缓存大小，防止内存泄漏
        }
    };

    private static final Set<Material> OBSTACLES = new HashSet<>();

    static {
        OBSTACLES.add(Material.CACTUS); // 仙人掌
        OBSTACLES.add(Material.COBWEB); // 蜘蛛网
        OBSTACLES.add(Material.SWEET_BERRY_BUSH); // 甜浆果丛
        OBSTACLES.add(Material.VINE); // 藤蔓
        OBSTACLES.add(Material.POWDER_SNOW); // 细雪方块
        OBSTACLES.add(Material.POINTED_DRIPSTONE); // 滴水石锥
    }

    // 移动方向类型
    public static final int MOVE_HORIZONTAL = 0;
    public static final int MOVE_UP = 1;
    public static final int MOVE_DOWN = 2;
    public static final int MOVE_JUMP = 3;
    public static final int MOVE_FALL = 4;
    public static final int MOVE_BLOCK_JUMP = 5; // 跨方块跳跃

    /**
     * 检测玩家是否处于悬空状态
     * 
     * @param player 要检测的玩家
     * @return 如果玩家悬空返回true，否则返回false
     */
    public static boolean isPlayerInAir(Player player) {
        if (player == null)
            return false;

        // 获取玩家脚下的方块
        Location playerLoc = player.getLocation();

        // 检查玩家是否在跳跃
        // 玩家跳跃时垂直速度为正，但我们需要考虑到玩家可能正在下落
        // 使用更精确的判断：只有当玩家脚下没有方块且垂直速度为0或负值时才认为悬空
        double verticalVelocity = player.getVelocity().getY();

        // 如果玩家正在上升（跳跃中），不认为是悬空
        if (verticalVelocity > 0.05) {
            return false;
        }

        // 检查玩家脚下一格是否有实体方块或水
        Block blockBelow = playerLoc.getBlock().getRelative(0, -1, 0);
        if (blockBelow.getType().isSolid() ||
                blockBelow.getType().name().contains("WATER") ||
                isLadder(blockBelow) ||
                isScaffolding(blockBelow) ||
                isDoorPassable(blockBelow)) {
            // 旗帜不提供支撑，被视为空气方块
            return false;
        }

        // 如果玩家脚下没有方块且不是在跳跃，检查更远的距离
        // 只有当脚下3格内都没有方块时才认为真正悬空
        for (int i = 2; i <= 10; i++) {
            blockBelow = playerLoc.getBlock().getRelative(0, -i, 0);
            if (blockBelow.getType().isSolid() ||
                    blockBelow.getType().name().contains("WATER") ||
                    isLadder(blockBelow) ||
                    isScaffolding(blockBelow) ||
                    isDoorPassable(blockBelow)) {
                    // 旗帜不提供支撑，被视为空气方块
                // 如果在2-3格范围内有方块，且玩家不是在跳跃，则认为是在下落但不是真正悬空
                if (verticalVelocity >= -5.5) { // 轻微下落不算悬空
                    return false;
                }
            }
        }

        // 如果脚下3格内都没有方块，或者正在快速下落，则认为悬空
        return true;
    }

    // 安全地设置破坏标记，确保只有固体且可破坏的方块才被标记
    private static boolean shouldBreakBlock(Block block, Player player) {
        if (block == null)
            return false;
        Material type = block.getType();

        // 空气方块永远不需要破坏
        if (type.isAir()) {
            return false;
        }

        // 可穿越方块永远不需要破坏
        if (block.isPassable()) {
            return false;
        }

        if (!type.isSolid()) {
            return false;
        }

        // 门、旗帜、活板门、脚手架等特殊方块不需要破坏
        if (isDoor(block) || isBanner(block) || isTrapdoor(block) || isScaffolding(block) || isFenceGate(block)) {
            return false;
        }

        // 低矮方块通常不需要破坏，但楼梯半砖除外
        if (isLowBlockButNotStair(block)) {
            return false;
        }

        // 最终检查是否可破坏
        return isBreakable(block.getLocation(), player);
    }

    public static List<Node> findPath(Location start, Location end, Player player) {
        // 清理缓存以确保路径计算的一致性
        SAFE_LOCATION_CACHE.clear();
        LOCATION_KEY_CACHE.clear();

        // 将目标位置也对齐到方块网格
        Location endLoc = end.getBlock().getLocation();
        // 使用稳定的节点比较器，避免相同f值时的随机排序
        PriorityQueue<Node> openSet = new PriorityQueue<>((n1, n2) -> {
            int fCompare = Double.compare(n1.f, n2.f);
            if (fCompare != 0)
                return fCompare;

            // f值相同时，优先选择g值更大的节点（更接近目标）
            int gCompare = Double.compare(n2.g, n1.g);
            if (gCompare != 0)
                return gCompare;

            // 如果g值也相同，使用位置坐标确保稳定排序
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

        // 使用玩家脚下的位置作为起始点
        Location startLoc = start.getBlock().getLocation();

        // 计算起始节点到目标的启发式距离
        double startHeuristic = heuristic(startLoc, endLoc);

        Node startNode = new Node(startLoc, null, 0, startHeuristic, false);
        openSet.add(startNode);
        allNodes.put(locationKey(startLoc), startNode);

        int iterations = 0;
        double targetDistance = 1.0; // 目标距离阈值，更精确的判断
        // 预先计算搜索半径的平方，避免开方操作
        double maxSearchRadiusSq = MAX_SEARCH_RADIUS * MAX_SEARCH_RADIUS;
        double earlyExitDistanceSq = 1.0; // 提前退出距离阈值

        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            Node current = openSet.poll();
            String currentKey = locationKey(current.location);

            if (closedSet.contains(currentKey)) {
                continue;
            }
            closedSet.add(currentKey);

            // 检查是否到达目标（对于玩家导航使用更精确的判断）
            double distanceToEnd = current.location.distanceSquared(endLoc);
            if (distanceToEnd < targetDistance * targetDistance) {
                return reconstructPath(current);
            }

            // 提前退出：如果已经很接近目标，减少不必要的搜索
            if (distanceToEnd < earlyExitDistanceSq) {
                return reconstructPath(current);
            }

            // 检查搜索半径，使用平方距离避免开方操作
            if (current.location.distanceSquared(start) > maxSearchRadiusSq) {
                continue;
            }

            // 探索邻居节点
            int[] dx = { 0, 1, -1, 0, 1, -1, 1, -1, 0 };
            int[] dz = { 1, 0, 0, -1, 1, 1, -1, -1, 0 };

            for (int i = 0; i < dx.length; i++) {
                // 如果是对角线移动，确保两条相邻直线方向都可通过，避免角落剪切
                if (dx[i] != 0 && dz[i] != 0) {
                    // 重用临时位置对象，减少对象创建
                    Location side1 = current.location.clone().add(dx[i], 0, 0);
                    Location side2 = current.location.clone().add(0, 0, dz[i]);
                    Location diagonal = current.location.clone().add(dx[i], 0, dz[i]);
                    Location head1 = side1.clone().add(0, 1, 0);
                    Location head2 = side2.clone().add(0, 1, 0);
                    Location diagHead = diagonal.clone().add(0, 1, 0);

                    // 检查玩家脚部和头部位置是否都能通过（门和旗帜完全视为可通过方块）
                    if (!isSafe(side1) || !isSafe(side2) || !isSafe(diagonal) ||
                            (!head1.getBlock().isPassable() && !isDoor(head1.getBlock()) && !isBanner(head1.getBlock()))
                            ||
                            (!head2.getBlock().isPassable() && !isDoor(head2.getBlock()) && !isBanner(head2.getBlock()))
                            ||
                            (!diagHead.getBlock().isPassable() && !isDoor(diagHead.getBlock())
                                    && !isBanner(diagHead.getBlock()))) {
                        continue; // 任一位置被阻挡，则不允许对角线移动
                    }
                }
                // 允许从高处直接下落，但增加代价
                int minYOffset = -1;
                // 严格限制柱子下落的触发条件：只有纯垂直下落才允许更大的下落高度
                if (dx[i] == 0 && dz[i] == 0) {
                    minYOffset = -MAX_SAFE_FALL_HEIGHT;
                } else if (Math.abs(dx[i]) <= 1 && Math.abs(dz[i]) <= 1) {
                    // 对于小范围移动，只允许2格下落
                    minYOffset = -2;
                }

                // 限制垂直搜索范围，减少计算量
                if (Math.abs(current.location.getY() - endLoc.getY()) > 10) {
                    minYOffset = Math.max(minYOffset, -5); // 限制下落高度
                }

                // 复用临时位置对象，减少对象创建
                Location tempLoc = current.location.clone();

                for (int yOffset = minYOffset; yOffset <= 1; yOffset++) {
                    // 重用位置对象，而不是每次创建新的
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

                    // 判断移动类型
                    if (yOffset > 0) {
                        // 向上移动，如果有水平位移，则为跳跃
                        if (dx[i] != 0 || dz[i] != 0) {
                            // 门完全视为空气方块，不需要特殊处理
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

                    // 获取当前和目标位置的高度
                    int currentHeight = current.location.getBlockY();
                    int nextHeight = nextLocation.getBlockY();

                    // 水平移动：如果目标高度与当前高度相同，需要检查空间
                    if (moveType == MOVE_HORIZONTAL && currentHeight == nextHeight) {
                        // 检查目标位置是否是低矮方块，如果是且上方可通过，则允许潜行通过
                        Block targetFeet = nextLocation.getBlock();
                        boolean canPassThroughLowBlock = isLowBlockButNotStair(targetFeet) && (nextHead.isPassable()
                                || isDoor(nextHead) || isBanner(nextHead) || isTrapdoorPassable(nextHead))
                                && !OBSTACLES.contains(nextHead.getType());

                        if (canPassThroughLowBlock) {
                            // 低矮方块可以潜行通过，不需要额外检查（楼梯半砖和有蜡烛的蛋糕已排除）
                        } else {
                            // 检查头部空间 - 在两格高通道中，只有头部被真正阻挡时才需要破坏
                            // 门、旗帜和非铁活板门完全视为可通过方块
                            if (!nextHead.isPassable() && !isDoor(nextHead) && !isBanner(nextHead)
                                    && !isTrapdoorPassable(nextHead)) {
                                if (shouldBreakBlock(nextHead, player)) {
                                    toBreak = true;
                                } else {
                                    continue; // 如果不能破坏且不可通过，则跳过这个路径
                                }
                            }
                        }
                    }

                    // 下落移动：如果目标高度低于当前高度，需要三格空间
                    if (moveType == MOVE_DOWN) {
                        // 检查是否是脚手架下落，如果是则允许特殊处理
                        boolean currentIsScaffolding = isScaffolding(current.location.getBlock());
                        boolean nextIsScaffolding = isScaffolding(nextLocation.getBlock());

                        // 如果不是从脚手架到脚手架的垂直移动，则需要检查空间
                        if (!(currentIsScaffolding && nextIsScaffolding)) {
                            // 检查头部（第二格）
                            if (!nextHead.isPassable() && nextHead.getType().isSolid() &&
                                    !isDoor(nextHead) && !isBanner(nextHead) && !isIronTrapdoor(nextHead)
                                    && !isScaffolding(nextHead)) {
                                if (shouldBreakBlock(nextHead, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                            // 检查头顶（第三格）
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
                        // 检查第四格空间（从三格高柱子下落时需要）
                        if (!nextAbove.isPassable() && nextAbove.getType().isSolid() &&
                                !isDoor(nextAbove) && !isBanner(nextAbove) && !isIronTrapdoor(nextAbove)) {
                            if (shouldBreakBlock(nextAbove, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        // 检查目标方块（玩家脚下的方块）是否需要破坏
                        Block targetBlock = nextLocation.getBlock();
                        if (!isLowBlockButNotStair(targetBlock) && shouldBreakBlock(targetBlock, player)) {
                            toBreak = true;
                        }

                        // 检查下落高度，如果超过安全下落高度，则尝试破坏方块降低高度
                        int fallHeight = current.location.getBlockY() - nextLocation.getBlockY();

                        // 检查目标位置是否在水中或水面上
                        boolean targetInWater = isInWater(nextLocation);
                        // 禁止直接从高处落入水中
                        if (fallHeight > MAX_SAFE_FALL_HEIGHT && targetInWater) {
                            continue; // 不允许从高处直接落入水中
                        }

                        if (fallHeight > MAX_SAFE_FALL_HEIGHT) {
                            // 如果是直接从高处下落或小范围移动下落，检查是否可以通过破坏下方块来创建安全路径
                            if (Math.abs(dx[i]) <= 1 && Math.abs(dz[i]) <= 1) { // 直线或小范围下降
                                int blocksToBreak = fallHeight - MAX_SAFE_FALL_HEIGHT;
                                boolean canBreak = true;
                                for (int j = 1; j <= blocksToBreak; j++) {
                                    Block blockBelow = current.location.getBlock().getRelative(0, -j, 0);
                                    if (!isBreakable(blockBelow.getLocation(), player)) {
                                        canBreak = false;
                                        break;
                                    }
                                }
                                if (canBreak) {
                                    toBreak = true; // 这里是特殊情况，批量破坏方块创建安全下落路径
                                    // 设置为MOVE_FALL类型，使用更低的代价
                                    moveType = MOVE_FALL;
                                } else {
                                    continue;
                                }
                            } else { // 如果是大范围跳跃下落，则不允许
                                continue;
                            }
                        } else if (fallHeight > 0) {
                            // 对于安全范围内的下落，设置为MOVE_FALL类型，使用更低的代价
                            moveType = MOVE_FALL;
                        }
                    }

                    // 向上移动：目标高度高于当前高度，需要两格空间
                    if (moveType == MOVE_UP) {
                        // 仅允许在梯子、脚手架或水中进行垂直上升
                        boolean currentIsLadder = isLadder(current.location.getBlock());
                        boolean nextIsLadder = isLadder(nextLocation.getBlock());
                        boolean currentIsScaffolding = isScaffolding(current.location.getBlock());
                        boolean nextIsScaffolding = isScaffolding(nextLocation.getBlock());
                        boolean currentInWater = containsWithCache(current.location.getBlock().getType().name(), "WATER");
                        boolean nextInWater = containsWithCache(nextLocation.getBlock().getType().name(), "WATER");
                        if (!( (currentIsLadder && nextIsLadder) || (currentIsScaffolding && nextIsScaffolding) || (currentInWater && nextInWater) )) {
                            continue; // 禁止通过其他方式垂直上升（例如旗帜堆叠）
                        }

                        // 如果不是从脚手架到脚手架的垂直移动，则需要检查空间
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

                        // 检查脚下方块是否需要破坏（垂直向上挖掘）
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

                    // 跳跃移动：需要检查起点三格空间和目标两格空间
                    if (moveType == MOVE_JUMP) {
                        // 门完全视为空气方块，不需要特殊处理
                        Block targetBlock = nextLocation.getBlock();

                        // 禁止在旗帜上方或相邻位置产生跳跃
                        Block targetHead = targetBlock.getRelative(0, 1, 0);
                        Block targetBelow = targetBlock.getRelative(0, -1, 0);
                        if (isBanner(targetBlock) || isBanner(targetHead) || isBanner(targetBelow)) {
                            continue;
                        }

                        // 检查当前位置是否为低矮方块，如果是则限制跳跃能力
                        Block currentFeetBlock = current.location.getBlock();
                        Block currentGroundBlock = current.location.clone().add(0, -1, 0).getBlock();

                        // 如果当前站立在低矮方块上或低矮方块内，检查跳跃高度限制
                        // 但是楼梯半砖除外，因为楼梯应该允许正常跳跃
                        boolean onScaffolding = isScaffolding(currentGroundBlock) || isScaffolding(currentFeetBlock);
                        boolean standingOnLowBlock = (isLowBlockButNotStair(currentFeetBlock)
                                || isLowBlockButNotStair(currentGroundBlock)) && !onScaffolding;

                        if (standingOnLowBlock) {
                            // 从低矮方块只能跳跃到相同高度或更低的位置，不能向上跳跃
                            int heightDiff = nextLocation.getBlockY() - current.location.getBlockY();
                            if (heightDiff > 0) {
                                continue; // 不能从低矮方块向上跳跃
                            }

                            // 检查目标位置上方是否有方块阻挡（因为从低矮方块起跳高度有限，门和旗帜视为可通过）
                            Block targetAbove = nextLocation.clone().add(0, 1, 0).getBlock();
                            if (!targetAbove.isPassable() && targetAbove.getType().isSolid() && !isDoor(targetAbove)
                                    && !isBanner(targetAbove)) {
                                continue; // 目标位置上方有阻挡，无法跳跃
                            }
                        }

                        // 检查是否跳跃栅栏门 - 玩家不能跳到栅栏门上方
                        if (isFenceGate(targetBlock)) {
                            continue; // 不能跳跃栅栏门
                        }

                        // 检查是否跳跃到旗帜方块上 - 玩家不能跳到旗帜上方
                        if (isBanner(targetBlock)) {
                            continue; // 不能跳跃到旗帜上方
                        }

                        // 检查目标位置下方是否是栅栏门或旗帜
                        Block belowTarget = nextLocation.clone().add(0, -1, 0).getBlock();
                        if (isFenceGate(belowTarget)) {
                            continue; // 不能跳跃到栅栏门上方
                        }

                        // 检查目标位置下方是否是旗帜
                        if (isBanner(belowTarget)) {
                            continue; // 不能跳跃到旗帜上方
                        }

                        // 新增：检查是否跳跃栅栏
                        if (isFence(targetBlock)) {
                            Block aboveFence = targetBlock.getRelative(0, 1, 0);
                            if (!isCarpet(aboveFence)) {
                                continue; // 不能跳跃没有地毯的栅栏
                            }
                        }

                        // 检查目标位置下方是否是栅栏
                        if (isFence(belowTarget)) {
                            Block aboveFence = belowTarget.getRelative(0, 1, 0);
                            if (!isCarpet(aboveFence)) {
                                continue; // 不能跳跃到没有地毯的栅栏上方
                            }
                        }

                        // 检查目标位置的两格空间
                        if (!nextHead.isPassable() && nextHead.getType().isSolid() && !isDoor(nextHead)
                                && !isBanner(nextHead) && !isIronTrapdoor(nextHead) && !isScaffolding(nextHead)
                                && !isFenceGate(nextHead) && !isLowBlock(nextHead)) {
                            if (shouldBreakBlock(nextHead, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        // 如果头部可通过，则只需要检查头顶
                        if (nextHead.isPassable() || isDoorPassable(nextHead) || isBannerPassable(nextHead) ||
                                isTrapdoorPassable(nextHead) || nextHead.getType().name().contains("WATER")) {
                            // 头部空间足够，只检查头顶
                            if (!nextCeiling.isPassable() && nextCeiling.getType().isSolid() &&
                                    !isDoor(nextCeiling) && !isBanner(nextCeiling) && !isIronTrapdoor(nextCeiling)) {
                                if (shouldBreakBlock(nextCeiling, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                        } else {
                            // 头部不可通过，检查头顶（门、旗帜和非铁活板门视为可通过）
                            if (!nextCeiling.isPassable() && nextCeiling.getType().isSolid() && !isDoor(nextCeiling)
                                    && !isBanner(nextCeiling) && !isTrapdoorPassable(nextCeiling)) {
                                if (shouldBreakBlock(nextCeiling, player)) {
                                    toBreak = true;
                                } else {
                                    continue;
                                }
                            }
                        }

                        // 检查起点位置的三格空间
                        Block currentHead = current.location.getBlock().getRelative(0, 1, 0);
                        Block currentCeiling = current.location.getBlock().getRelative(0, 2, 0);
                        Block currentAbove = current.location.getBlock().getRelative(0, 3, 0);

                        // 检查第一格空间（头部）
                        if (!currentHead.isPassable() && currentHead.getType().isSolid() &&
                                !isDoor(currentHead) && !isBanner(currentHead) && !isIronTrapdoor(currentHead)) {
                            if (shouldBreakBlock(currentHead, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        // 检查第二格空间（头顶）
                        if (!currentCeiling.isPassable() && currentCeiling.getType().isSolid() &&
                                !isDoor(currentCeiling) && !isBanner(currentCeiling)
                                && !isIronTrapdoor(currentCeiling)) {
                            if (shouldBreakBlock(currentCeiling, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                        // 检查第三格空间（跳跃所需空间）
                        if (!currentAbove.isPassable() && currentAbove.getType().isSolid() &&
                                !isDoor(currentAbove) && !isBanner(currentAbove) && !isIronTrapdoor(currentAbove)) {
                            if (shouldBreakBlock(currentAbove, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }

                    }

                    // 对于下落（MOVE_FALL）场景，如果目标方块是固体且可破坏，确保正确标记需要破坏
                    if (moveType == MOVE_FALL) {
                        Block targetBlock = nextLocation.getBlock();
                        if (!isLowBlockButNotStair(targetBlock) && shouldBreakBlock(targetBlock, player)) {
                            toBreak = true;
                        }
                    }

                    boolean diagonal = (dx[i] != 0 && dz[i] != 0);
                    double moveCost = diagonal ? DIAGONAL_COST : STRAIGHT_COST; // 使用定义的常量

                    // 计算转弯代价
                    if (current.parent != null) {
                        // 当前移动方向
                        int currentDirX = dx[i];
                        int currentDirZ = dz[i];

                        // 上一次移动方向
                        int prevDirX = current.dirX;
                        int prevDirZ = current.dirZ;

                        // 如果方向发生变化，添加转弯代价
                        if (prevDirX != 0 || prevDirZ != 0) { // 确保不是起始节点
                            if (currentDirX != prevDirX || currentDirZ != prevDirZ) {
                                // 计算方向变化的角度
                                double dotProduct = currentDirX * prevDirX + currentDirZ * prevDirZ;
                                double prevMagnitude = Math.sqrt(prevDirX * prevDirX + prevDirZ * prevDirZ);
                                double currentMagnitude = Math
                                        .sqrt(currentDirX * currentDirX + currentDirZ * currentDirZ);
                                double cosAngle = dotProduct / (prevMagnitude * currentMagnitude);

                                // 直角转弯 (cos(90°) = 0)
                                if (Math.abs(cosAngle) < 0.01) {
                                    moveCost += RIGHT_ANGLE_TURN_COST;
                                }
                                // 斜角转弯 (cos(45°) ≈ 0.7071, cos(135°) ≈ -0.7071)
                                else if (Math.abs(cosAngle - 0.7071) < 0.1 || Math.abs(cosAngle + 0.7071) < 0.1) {
                                    moveCost += DIAGONAL_TURN_COST;
                                }
                            }
                        }
                    }

                    // 额外检查：对于下落或水平移动，确保玩家头部前方路径无阻挡
                    if (yOffset <= 0) {
                        Location headPath = current.location.clone().add(dx[i], 1, dz[i]);
                        Block headPathBlock = headPath.getBlock();
                        Block belowHeadPath = headPath.clone().add(0, -1, 0).getBlock();

                        // 检查是否是水面上的海带方块，这些视为可通过
                        boolean isKelpOnWater = containsWithCache(headPathBlock.getType().name(), "KELP") &&
                                containsWithCache(belowHeadPath.getType().name(), "WATER");

                        if (isKelpOnWater) {
                            // 水面上的海带视为可通过，不需要破坏
                        } else if (!headPathBlock.isPassable() && headPathBlock.getType().isSolid()
                                && !isDoor(headPathBlock) && !isBanner(headPathBlock)) {
                            if (shouldBreakBlock(headPathBlock, player)) {
                                toBreak = true;
                            } else {
                                continue;
                            }
                        }
                    }

                    // 仅当位置安全或可破坏时加入队列
                    if (!isSafe(nextLocation)) {
                        Block nextBlock = nextLocation.getBlock();
                        Block groundBlock = nextLocation.clone().add(0, -1, 0).getBlock();

                        // 检查是否是水面上的海带方块，这些视为可通过
                        boolean isKelpOnWater = containsWithCache(nextBlock.getType().name(), "KELP") &&
                                containsWithCache(groundBlock.getType().name(), "WATER");

                        if (isKelpOnWater) {
                            // 水面上的海带视为可通过，不需要破坏
                        } else if (!isDoor(nextBlock) && !isBanner(nextBlock) && !isLowBlockButNotStair(nextBlock)
                                && shouldBreakBlock(nextBlock, player)) {
                            // 只有当脚下方块为实体方块且可破坏时才标记破坏（排除门、旗帜、低矮方块但保留楼梯半砖）
                            toBreak = true;
                        } else {
                            continue;
                        }
                    }

                    // 计算移动代价
                    // 添加破坏方块的代价
                    if (toBreak) {
                        moveCost += BREAK_BLOCK_COST;
                    }

                    // 检查水面行走
                    boolean nextInsideWater = isInsideWater(nextLocation);
                    boolean nextOnWaterSurface = isOnWaterSurface(nextLocation);
                    
                    if (nextInsideWater) {
                        moveCost += WATER_COST * 5.0;
                    }

                    // 水面行走增加额外代价
                    if (nextOnWaterSurface) {
                        moveCost += WATER_COST; // 增加水面行走的代价
                    }

                    // 添加经过门和旗帜的代价
                    if (isDoorPassable(nextLocation.getBlock()) || isBannerPassable(nextLocation.getBlock())
                            || isFenceGate(nextLocation.getBlock())) {
                        moveCost += DOOR_COST;
                    }

                    // 检查是否需要跳跃栅栏，只有在栅栏上有地毯且地毯上方没有方块时才允许通过
                    Block currentFeet = nextLocation.getBlock();
                    Block belowFeet = nextLocation.clone().add(0, -1, 0).getBlock();

                    // 如果当前位置或下方位置是栅栏，需要特殊处理
                    if (isFence(currentFeet) || isFence(belowFeet)) {
                        Block fenceBlock = isFence(currentFeet) ? currentFeet : belowFeet;
                        Block carpetBlock = fenceBlock.getRelative(0, 1, 0);
                        Block aboveCarpet = carpetBlock.getRelative(0, 1, 0);

                        // 检查栅栏上是否有地毯
                        if (!isCarpet(carpetBlock)) {
                            // 栅栏上没有地毯，不允许通过
                            continue;
                        }

                        // 检查地毯上方是否有方块阻挡
                        if (!aboveCarpet.isPassable()) {
                            // 地毯上方有方块阻挡，不允许通过
                            continue;
                        }
                    }

                    // 添加经过活板门的代价（脚部和头部位置）
                    if (isTrapdoor(nextLocation.getBlock())) {
                        moveCost += TRAPDOOR_COST;
                    }
                    if (isTrapdoor(nextLocation.getBlock().getRelative(0, 1, 0))) {
                        moveCost += TRAPDOOR_COST;
                    }

                    // 添加经过脚手架的代价
                    if (isScaffolding(nextLocation.getBlock())) {
                        moveCost += SCAFFOLDING_COST;
                    }

                    // 添加跳跃的代价
                    if (moveType == MOVE_JUMP) {
                        moveCost += JUMP_COST;
                    }

                    // 添加跨方块跳跃的代价
                    if (moveType == MOVE_BLOCK_JUMP) {
                        moveCost += BLOCK_JUMP_COST;
                    }

                    // 添加垂直移动的代价
                    if (moveType == MOVE_UP) {
                        moveCost += VERTICAL_COST;
                    } else if (moveType == MOVE_DOWN || moveType == MOVE_FALL) {
                        // 下落的代价更低，鼓励算法选择下落路径
                        moveCost += FALL_COST;

                        // 根据下落高度增加少量代价，但仍然比绕路更优
                        int fallHeight = current.location.getBlockY() - nextLocation.getBlockY();
                        if (fallHeight > 1) {
                            // 轻微增加代价，但保持总体低于绕路
                            moveCost += (fallHeight - 1) * 0.1;
                        }
                    }

                    double nextG = current.g + moveCost;
                    double nextH = heuristic(nextLocation, endLoc); // 启发式函数

                    // 检查是否已经有节点，如果有则更新，否则创建新节点
                    Node existingNode = allNodes.get(nextKey);
                    if (existingNode != null) {
                        // 如果找到了更好的路径，更新节点
                        if (nextG < existingNode.g) {
                            existingNode.parent = current;
                            existingNode.g = nextG;
                            existingNode.f = nextG + existingNode.h;
                            existingNode.toBreak = toBreak;
                            existingNode.moveType = moveType;
                            // 更新方向属性
                            existingNode.dirX = nextLocation.getBlockX() - current.location.getBlockX();
                            existingNode.dirZ = nextLocation.getBlockZ() - current.location.getBlockZ();
                            // 重新加入队列以更新优先级
                            openSet.remove(existingNode);
                            openSet.add(existingNode);
                        }
                    } else {
                        // 创建新节点
                        Node neighbor = new Node(nextLocation, current, nextG, nextH, toBreak, moveType);
                        openSet.add(neighbor);
                        allNodes.put(nextKey, neighbor);
                    }
                }
            }

            // 添加跨方块跳跃的逻辑
            // 检查四个方向的跨方块跳跃（不允许斜着跳）
            int[] jumpDx = { 0, 1, -1, 0 };
            int[] jumpDz = { 1, 0, 0, -1 };

            for (int dir = 0; dir < jumpDx.length; dir++) {
                int jumpDirX = jumpDx[dir];
                int jumpDirZ = jumpDz[dir];

                // 检查当前位置是否为低矮方块，如果是则不允许跨方块跳跃
                // 但是楼梯半砖除外，因为楼梯应该允许跨方块跳跃
                Block currentFeetBlock = current.location.getBlock();
                Block currentGroundBlock = current.location.clone().add(0, -1, 0).getBlock();
                boolean onScaffoldingForBlockJump = isScaffolding(currentFeetBlock) || isScaffolding(currentGroundBlock);
                if ((isLowBlockButNotStair(currentFeetBlock) || isLowBlockButNotStair(currentGroundBlock)) && !onScaffoldingForBlockJump) {
                    continue; // 不能从低矮方块进行跨方块跳跃（楼梯半砖或脚手架除外）
                }

                // 尝试不同距离的跨方块跳跃，只有距离大于等于2才考虑跨方块跳跃
                for (int distance = 2; distance <= MAX_BLOCK_JUMP_DISTANCE; distance++) {
                    Location jumpTarget = current.location.clone().add(jumpDirX * distance, 0, jumpDirZ * distance);
                    String jumpKey = locationKey(jumpTarget);

                    if (closedSet.contains(jumpKey)) {
                        continue;
                    }

                    // 检查跳跃条件：起点和终点以及路径上方都要有三格空气
                    boolean canJump = true;

                    // 检查起点上方三格空间（门和旗帜视为可通过）
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

                    // 检查终点上方三格空间（门和旗帜视为可通过）
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

                                            // 检查跳跃路径中间是否有障碍物（包括栅栏、水方块等）
                        boolean hasObstacle = false; // 标记路径中是否有障碍物
                        for (int step = 1; step < distance; step++) {
                            Location pathPoint = current.location.clone().add(jumpDirX * step, 0, jumpDirZ * step);
                            Block pathBlock = pathPoint.getBlock();
                            Block belowPathBlock = pathPoint.clone().add(0, -1, 0).getBlock();

                            // 检查路径中间的方块是否为障碍物（门视为可通过，旗帜视为障碍）
                            boolean jumpableFence = isJumpableFence(pathBlock);
                            if (isBanner(pathBlock) || isBanner(belowPathBlock)) {
                                hasObstacle = true;
                                canJump = false;
                                break;
                            }
                            if (!pathBlock.isPassable() && pathBlock.getType().isSolid() && !jumpableFence
                                    && !isDoor(pathBlock)) {
                                hasObstacle = true; // 有障碍物
                                canJump = false;
                                break;
                            }

                        // 特别检查栅栏和栅栏门（可跳跃的栅栏+地毯除外）
                        if ((isFence(pathBlock) && !isJumpableFence(pathBlock)) || isFenceGate(pathBlock)) {
                            hasObstacle = true; // 有障碍物
                            canJump = false;
                            break;
                        }

                        // 检查路径下方是否有栅栏门（不能跳过栅栏门上方）
                        if (isFenceGate(belowPathBlock)) {
                            hasObstacle = true; // 有障碍物
                            canJump = false;
                            break;
                        }

                        // 检查水方块：不允许跨越任何水体
                        String blockName = pathBlock.getType().name();
                        if (containsWithCache(blockName, "WATER")) {
                            hasObstacle = true; // 将任何水体都视为障碍物
                            canJump = false;
                            break;
                        }

                        // 检查危险方块（火焰、岩浆等）
                        if (OBSTACLES.contains(pathBlock.getType()) ||
                                containsWithCache(blockName, "LAVA") ||
                                containsWithCache(blockName, "FIRE")) {
                            hasObstacle = true; // 有危险方块
                            canJump = false;
                            break;
                        }

                        // 检查路径上方三格空间（门视为可通过，旗帜视为障碍）
                        for (int y = 1; y <= 3; y++) {
                            Block checkBlock = pathPoint.getBlock().getRelative(0, y, 0);
                            if (isBanner(checkBlock)) {
                                hasObstacle = true;
                                canJump = false;
                                break;
                            }
                            if (!checkBlock.isPassable() && checkBlock.getType().isSolid() && !isDoor(checkBlock)) {
                                hasObstacle = true; // 有障碍物
                                canJump = false;
                                break;
                            }

                            // 检查上方空间是否有危险方块
                            String checkBlockName = checkBlock.getType().name();
                            if (OBSTACLES.contains(checkBlock.getType()) ||
                                    containsWithCache(checkBlockName, "LAVA") ||
                                    containsWithCache(checkBlockName, "FIRE")) {
                                hasObstacle = true; // 有危险方块
                                canJump = false;
                                break;
                            }
                        }
                        if (!canJump)
                            break;
                    }

                    // 检查是否真的需要跳跃：如果目标位置比当前位置高，或者路径中有障碍物，则需要跳跃
                    int heightDiff = jumpTarget.getBlockY() - current.location.getBlockY();

                    // 检查是否可以通过普通移动到达（不需要跨方块跳跃）
                    boolean canReachByNormalMove = true;
                    if (distance == 2) {
                        // 检查中间位置是否可以通过普通移动到达
                        Location midPoint = current.location.clone().add(jumpDirX, heightDiff, jumpDirZ);
                        if (!isSafe(midPoint) || heightDiff > 1) {
                            canReachByNormalMove = false;
                        }
                    }

                    if (!hasObstacle && distance == 2 && heightDiff <= 0 && canReachByNormalMove) {
                        // 只有在没有障碍物、距离为2、不需要向上跳跃且可以通过普通移动到达时，才不使用跨方块跳跃
                        canJump = false;
                    }

                    if (!canJump)
                        continue;

                    // 检查目标位置是否安全
                    if (!isSafe(jumpTarget)) {
                        continue;
                    }

                    // 检查跨方块跳跃目标是否是栅栏门、旗帜或它们上方
                    Block jumpTargetBlock = jumpTarget.getBlock();
                    Block belowJumpTarget = jumpTarget.clone().add(0, -1, 0).getBlock();
                    if (isFenceGate(jumpTargetBlock) || isFenceGate(belowJumpTarget)) {
                        continue; // 不能跳跃到栅栏门上
                    }

                    // 检查跨方块跳跃目标是否是旗帜或旗帜上方
                    if (isBanner(jumpTargetBlock) || isBanner(belowJumpTarget)) {
                        continue; // 不能跳跃到旗帜上
                    }

                    // 计算跨方块跳跃的代价
                    double jumpCost = BLOCK_JUMP_COST * distance * distance; // 使用距离的平方，使长距离跳跃代价更高
                    double nextG = current.g + jumpCost;
                    double nextH = heuristic(jumpTarget, endLoc);

                    // 检查是否已经有节点，如果有则更新，否则创建新节点
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

        // 如果没有找到完整路径，返回最接近目标的路径
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

    // 位置键生成，使用缓存减少字符串创建
    private static String locationKey(Location loc) {
        // 直接生成位置键，避免使用hashCode作为中间键
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // 使用简单的字符串拼接，避免StringBuilder开销
        String key = x + "," + y + "," + z;

        // 检查缓存
        String cachedKey = LOCATION_KEY_CACHE.get(key);
        if (cachedKey != null) {
            return cachedKey;
        }

        // 缓存结果
        LOCATION_KEY_CACHE.put(key, key);
        return key;
    }

    // 路径重建，使用预分配容量的ArrayList
    private static List<Node> reconstructPath(Node node) {
        // 首先计算路径长度
        int pathLength = 0;
        Node temp = node;
        while (temp != null) {
            pathLength++;
            temp = temp.parent;
        }

        // 使用预分配容量的ArrayList
        List<Node> path = new ArrayList<>(pathLength * 2); // 预留更多空间给中间节点
        while (node != null) {
            path.add(node);
            node = node.parent;
        }
        Collections.reverse(path);

        // 为跨方块跳跃添加中间节点
        List<Node> expandedPath = new ArrayList<>();

        // 处理起点节点
        if (!path.isEmpty()) {
            Node firstNode = path.get(0);
            // 确保跨方块跳跃的起点也被标记为跨方块跳跃
            if (path.size() > 1) {
                Node nextNode = path.get(1);
                // 检查是否是跨方块跳跃
                int dx = nextNode.location.getBlockX() - firstNode.location.getBlockX();
                int dz = nextNode.location.getBlockZ() - firstNode.location.getBlockZ();
                int distance = Math.max(Math.abs(dx), Math.abs(dz));

                if (nextNode.moveType == MOVE_BLOCK_JUMP || distance > 1) {
                    // 如果下一个节点是跨方块跳跃或者水平距离大于1，就将起点也标记为跨方块跳跃
                    firstNode.moveType = MOVE_BLOCK_JUMP;
                    // 设置方向与下一个节点一致
                    firstNode.dirX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
                    firstNode.dirZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);
                }
            }
            expandedPath.add(firstNode);
        }

        // 处理中间节点
        for (int i = 0; i < path.size() - 1; i++) {
            Node currentNode = path.get(i);
            Node nextNode = path.get(i + 1);

            // 计算跳跃距离
            int dx = nextNode.location.getBlockX() - currentNode.location.getBlockX();
            int dz = nextNode.location.getBlockZ() - currentNode.location.getBlockZ();
            int dy = nextNode.location.getBlockY() - currentNode.location.getBlockY();
            int distance = Math.max(Math.abs(dx), Math.abs(dz));

            // 检查是否是跨方块跳跃
            if (distance > 1 || nextNode.moveType == MOVE_BLOCK_JUMP) {
                // 确保当前节点和下一个节点都被标记为跨方块跳跃
                currentNode.moveType = MOVE_BLOCK_JUMP;
                nextNode.moveType = MOVE_BLOCK_JUMP;

                // 设置方向
                int stepX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
                int stepZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);

                // 更新当前节点的方向
                currentNode.dirX = stepX;
                currentNode.dirZ = stepZ;

                // 更新下一个节点的方向
                nextNode.dirX = stepX;
                nextNode.dirZ = stepZ;

                // 如果距离大于1，添加中间节点
                if (distance > 1) {
                    // 添加中间节点，并标记为跨方块跳跃类型
                    for (int step = 1; step < distance; step++) {
                        // 计算中间节点的位置，使用整数坐标
                        int midX = currentNode.location.getBlockX() + stepX * step;
                        int midY = currentNode.location.getBlockY(); // 保持相同的Y坐标
                        int midZ = currentNode.location.getBlockZ() + stepZ * step;

                        // 创建精确的位置，确保粒子显示在方块中心
                        Location midLoc = new Location(
                                currentNode.location.getWorld(),
                                midX,
                                midY,
                                midZ);

                        // 创建中间节点并设置为跨方块跳跃类型
                        Node midNode = new Node(midLoc, currentNode, 0, 0, false, MOVE_BLOCK_JUMP);
                        // 设置方向与目标节点一致
                        midNode.dirX = stepX;
                        midNode.dirZ = stepZ;
                        expandedPath.add(midNode);
                    }
                }
            } else if (nextNode.moveType == MOVE_JUMP || dy > 0) {
                // 处理普通跳跃：确保连续跳跃不会直接连接，而是每段都独立处理
                currentNode.moveType = MOVE_JUMP;
                nextNode.moveType = MOVE_JUMP;

                // 设置跳跃方向
                currentNode.dirX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
                currentNode.dirZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);
                nextNode.dirX = currentNode.dirX;
                nextNode.dirZ = currentNode.dirZ;
            }

            // 添加下一个节点
            expandedPath.add(nextNode);
        }

        return expandedPath;
    }

    // 启发式函数，使用简化的曼哈顿距离计算
    private static double heuristic(Location a, Location b) {
        // 使用简化的曼哈顿距离计算，避免浮点精度问题
        int dx = Math.abs(a.getBlockX() - b.getBlockX());
        int dy = Math.abs(a.getBlockY() - b.getBlockY());
        int dz = Math.abs(a.getBlockZ() - b.getBlockZ());

        // 简化的启发式计算，减少浮点运算
        double baseHeuristic = dx + dz + dy * 1.5;

        // 检查水体内部和水面
        boolean insideWater = isInsideWater(a);
        boolean targetInsideWater = isInsideWater(b);
        boolean onWaterSurface = isOnWaterSurface(a);

        // 修复水路寻路问题：减少水体内部的启发式惩罚，避免当水路是唯一路径时无法找到路径
        if (insideWater) {
            // 降低水体内部的启发式惩罚，使用较小的代价增量而不是极高代价
            baseHeuristic += WATER_COST * 2.0; // 使用适中的代价估计，而不是极高的BREAK_BLOCK_COST * 10.0
        }

        // 如果目标在水体内部，也使用适中的代价增量
        if (targetInsideWater) {
            baseHeuristic += WATER_COST * 2.0; // 使用适中的代价估计
        }

        // 如果起点在水面上，增加水面行走的代价估计
        if (onWaterSurface) {
            baseHeuristic += WATER_COST; // 增加水面行走的代价估计，使用完整的代价值
        }

        // 添加微小的确定性扰动，基于位置坐标，避免相同代价路径的随机选择
        double perturbation = (a.getBlockX() * 0.001 + a.getBlockZ() * 0.0001) % 0.01;

        return baseHeuristic + perturbation;
    }

    // 检查位置是否在水中（包括水面和水体内部）
    private static boolean isInWater(Location location) {
        Block block = location.getBlock();
        String blockName = block.getType().name();

        // 检查当前位置是否是水方块
        return containsWithCache(blockName, "WATER");
    }

    // 检查位置是否在水面上（玩家站在水面上方的水中，脚下是水）
    private static boolean isOnWaterSurface(Location location) {
        Block currentBlock = location.getBlock();
        Block belowBlock = location.clone().add(0, -1, 0).getBlock();
        Block aboveBlock = location.clone().add(0, 1, 0).getBlock();

        String currentName = currentBlock.getType().name();
        String belowName = belowBlock.getType().name();
        String aboveName = aboveBlock.getType().name();

        // 情况1：当前位置是空气（或可通过方块），脚下是水，表示站在水面上
        boolean currentIsPassable = currentBlock.isPassable() || !containsWithCache(currentName, "WATER");
        boolean belowIsWater = containsWithCache(belowName, "WATER");
        boolean onWaterSurface = currentIsPassable && belowIsWater;

        // 情况2：当前位置是水面下一格（当前是空气，上方是水，脚下也是水）
        boolean aboveIsWater = containsWithCache(aboveName, "WATER");
        boolean underWaterSurface = currentIsPassable && aboveIsWater && belowIsWater;

        // 情况3：当前位置本身是水，且上方是空气/可通过方块，表示一格深水面
        boolean currentIsWater = containsWithCache(currentName, "WATER");
        boolean aboveIsPassable = aboveBlock.isPassable() && !containsWithCache(aboveName, "WATER");
        boolean shallowWaterSurface = currentIsWater && aboveIsPassable;

        return onWaterSurface || underWaterSurface || shallowWaterSurface;
    }

    // 判断位置是否在水体内部（不包括水面）
    private static boolean isInsideWater(Location location) {
        // 检查当前方块是否是水
        if (containsWithCache(location.getBlock().getType().name(), "WATER")) {
            // 检查上方是否也是水，如果是则表示在水体内部而不是水面
            Location aboveLocation = location.clone().add(0, 1, 0);
            if (containsWithCache(aboveLocation.getBlock().getType().name(), "WATER")) {
                return true; // 在水体内部
            }

            // 检查下方是否也是水，如果是则可能在水体内部
            Location belowLocation = location.clone().add(0, -1, 0);
            if (containsWithCache(belowLocation.getBlock().getType().name(), "WATER")) {
                // 再检查下方两格是否也是水，如果是则更可能在水体内部
                Location deeperLocation = location.clone().add(0, -2, 0);
                if (containsWithCache(deeperLocation.getBlock().getType().name(), "WATER")) {
                    return true; // 在水体内部
                }
            }
        }

        return false; // 不在水体内部
    }

    // 缓存不可破坏的方块类型，避免重复字符串比较
    private static final Set<String> UNBREAKABLE_BLOCKS = new HashSet<>(Arrays.asList(
            "BEDROCK", "PORTAL", "SPAWNER", "BARRIER", "END_PORTAL", "END_GATEWAY"));

    private static boolean isBreakable(Location loc, Player player) {
        Block block = loc.getBlock();
        Material type = block.getType();

        // 首先检查：空气方块和可穿越方块永远不需要破坏
        if (type.isAir() || block.isPassable()) {
            return false;
        }

        // 快速检查：非固体或高硬度方块
        if (!type.isSolid() || type.getHardness() >= 50) {
            return false;
        }

        // 检查是否是门、旗帜或活板门，这些不应该被破坏
        if (isDoor(block) || isBanner(block) || isTrapdoor(block)) {
            return false; // 门、旗帜和活板门不应该被破坏
        }

        // 检查是否是低矮方块（半砖、灯笼、普通蛋糕、蜡烛），这些可以潜行通过，不需要破坏
        // 但是楼梯半砖除外，楼梯半砖需要正常处理
        if (isLowBlockButNotStair(block)) {
            Block above = block.getRelative(0, 1, 0);
            if (above.isPassable() && !OBSTACLES.contains(above.getType())) {
                return false; // 低矮方块上方可通过时不需要破坏（楼梯半砖已排除）
            }
        }

        // 检查是否是水面上的海带方块，这些不应该被破坏
        String typeName = type.name();
        if (containsWithCache(typeName, "KELP")) {
            Block below = block.getRelative(0, -1, 0);
            if (containsWithCache(below.getType().name(), "WATER")) {
                return false; // 水面上的海带不应被破坏
            }
        }

        // 检查是否是栅栏上的地毯，且地毯上方是可穿越方块
        if (isCarpet(block)) {
            Block below = block.getRelative(0, -1, 0);
            Block above = block.getRelative(0, 1, 0);

            // 如果地毯下方是栅栏，且地毯上方是可穿越方块，则不应该被破坏
            if (isFence(below) && above.isPassable()) {
                return false; // 栅栏上的地毯不应被破坏
            }
        }

        // 检查是否是栅栏，且上方有地毯，地毯上方是可穿越方块
        if (isFence(block)) {
            Block above = block.getRelative(0, 1, 0);
            Block aboveAbove = block.getRelative(0, 2, 0);

            // 如果栅栏上方是地毯，且地毯上方是可穿越方块，则栅栏不应该被破坏
            if (isCarpet(above) && aboveAbove.isPassable()) {
                return false; // 有地毯的栅栏不应被破坏
            }
        }

        // 使用缓存集合检查不可破坏方块
        String blockType = type.name();
        for (String unbreakable : UNBREAKABLE_BLOCKS) {
            if (blockType.contains(unbreakable)) {
                return false;
            }
        }

        // 岩浆检查
        boolean nearLava = false;
        int[][] directions = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, -1, 0 } };

        for (int[] dir : directions) {
            Block neighbor = block.getRelative(dir[0], dir[1], dir[2]);
            String neighborType = neighbor.getType().name();

            if (neighborType.contains("LAVA")) {
                nearLava = true;
                block.setMetadata("nearLava", new FixedMetadataValue(TOCpluginNative.getInstance(), true));
                break;
            }
        }

        // 如果方块靠近岩浆，不标记为需要破坏，但允许通过
        if (nearLava) {
            return false; // 不破坏靠近岩浆的方块，但在isSafe中允许通过
        }

        return true;
    }

    // 缓存方块类型检查结果
    private static final Map<Material, Boolean> DOOR_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> TRAPDOOR_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> BANNER_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> LADDER_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> SCAFFOLDING_CACHE = new HashMap<>();

    // 检查方块是否是门
    public static boolean isDoor(Block block) {
        Material type = block.getType();
        // 避免使用computeIfAbsent，改用get和put，防止并发修改异常
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

    // 检查门是否可以通过（门完全视为空气方块，无任何阻挡）
    public static boolean isDoorPassable(Block block) {
        if (!isDoor(block)) {
            return false;
        }

        // 门完全视为空气方块，无条件可通过
        return true;
    }

    // 检查方块是否是旗帜
    public static boolean isBanner(Block block) {
        Material type = block.getType();
        // 避免使用computeIfAbsent，改用get和put，防止并发修改异常
        Boolean result = BANNER_CACHE.get(type);
        if (result != null) {
            return result;
        }

        // 检查所有旗帜类型：立式旗帜和墙上旗帜
        String materialName = type.name();
        String keyString = type.getKey().toString();

        // 明确检查所有旗帜材质（立式旗帜和墙上旗帜）
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

        // 调试输出
        if (!result && (containsWithCache(materialName.toLowerCase(), "banner") ||
                containsWithCache(keyString.toLowerCase(), "banner"))) {
            TOCpluginNative plugin = TOCpluginNative.getInstance();
            if (plugin != null) {
                plugin.getLogger().info("未识别的旗帜方块: Material=" + materialName + ", Key=" + keyString);
            }
        }

        BANNER_CACHE.put(type, result);
        return result;
    }

    // 检查旗帜是否可以通过（旗帜完全视为空气方块，无任何阻挡）
    public static boolean isBannerPassable(Block block) {
        if (!isBanner(block)) {
            return false;
        }

        // 旗帜完全视为空气方块，无条件可通过
        // 这包括所有16种颜色的立式旗帜和墙上旗帜
        return true;
    }

    // 检查方块是否是活板门
    public static boolean isTrapdoor(Block block) {
        Material type = block.getType();
        // 避免使用computeIfAbsent，改用get和put，防止并发修改异常
        Boolean result = TRAPDOOR_CACHE.get(type);
        if (result != null) {
            return result;
        }

        // 同时检查Material枚举和NamespacedKey，确保兼容性
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

    // 检查方块是否是铁活板门（只有铁活板门被视为障碍物）
    public static boolean isIronTrapdoor(Block block) {
        Material type = block.getType();
        String materialName = type.name();
        String keyString = type.getKey().toString();

        return type == Material.IRON_TRAPDOOR ||
                containsWithCache(materialName, "IRON_TRAPDOOR") ||
                containsWithCache(keyString, "iron_trapdoor");
    }

    // 检查活板门是否可以视为空气（非铁活板门完全可通过）
    public static boolean isTrapdoorPassable(Block block) {
        return isTrapdoor(block) && !isIronTrapdoor(block);
    }

    // 检查方块是否是梯子
    public static boolean isLadder(Block block) {
        Material type = block.getType();
        // 避免使用computeIfAbsent，改用get和put，防止并发修改异常
        Boolean result = LADDER_CACHE.get(type);
        if (result != null) {
            return result;
        }
        result = containsWithCache(type.name(), "LADDER");
        LADDER_CACHE.put(type, result);
        return result;
    }
    
    // 判断栅栏+地毯+空隙结构是否可跳跃
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

    // 检查方块是否是脚手架
    public static boolean isScaffolding(Block block) {
        Material type = block.getType();
        // 避免使用computeIfAbsent，改用get和put，防止并发修改异常
        Boolean result = SCAFFOLDING_CACHE.get(type);
        if (result != null) {
            return result;
        }
        result = containsWithCache(type.name(), "SCAFFOLDING");
        SCAFFOLDING_CACHE.put(type, result);
        return result;
    }

    // 检查方块是否靠近岩浆
    public static boolean isNearLava(Block block) {
        // 首先检查元数据
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
                // 设置元数据以便后续使用
                block.setMetadata("nearLava", new FixedMetadataValue(TOCpluginNative.getInstance(), true));
                return true;
            }
        }

        return false;
    }

    // 缓存常用的方块类型检查结果
    private static final Map<Material, Boolean> PASSABLE_CACHE = new HashMap<>();
    private static final Map<Material, Boolean> SOLID_CACHE = new HashMap<>();

    // 检查字符串是否包含特定子串，使用全局缓存提高性能
    private static boolean containsWithCache(String str, String substr) {
        String key = str + "_" + substr;
        // 避免使用computeIfAbsent，改用get和put，防止并发修改异常
        Boolean result = STRING_CONTAINS_CACHE.get(key);
        if (result != null) {
            return result;
        }
        result = str.contains(substr);
        STRING_CONTAINS_CACHE.put(key, result);
        return result;
    }

    private static boolean isSafe(Location loc) {
        // 使用缓存检查是否已经计算过该位置的安全性
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

        // 快速检查危险方块
        String feetName = feetType.name();
        String headName = headType.name();
        String groundName = groundType.name();

        // 检查是否有危险方块
        if (OBSTACLES.contains(feetType) || OBSTACLES.contains(headType) ||
                containsWithCache(feetName, "LAVA") || containsWithCache(feetName, "FIRE") ||
                containsWithCache(headName, "LAVA") || containsWithCache(headName, "FIRE") ||
                containsWithCache(groundName, "LAVA") || containsWithCache(groundName, "FIRE")) {
            SAFE_LOCATION_CACHE.put(loc.clone(), false);
            return false;
        }

        // 水面寻路时无视海带方块
        if (containsWithCache(groundName, "WATER") && containsWithCache(feetName, "KELP")) {
            // 如果脚下是水且当前位置是海带，则视为可通过
            feetType = Material.AIR; // 将海带视为空气
            feetName = "AIR";
        }

        // 水体内部现在被视为安全，允许水中导航
        // 但仍需检查是否有足够的游泳空间
        if (isInsideWater(loc)) {
            // 检查头部是否也在水中，确保有足够的游泳空间
            if (!containsWithCache(headName, "WATER") && !head.isPassable()) {
                SAFE_LOCATION_CACHE.put(loc.clone(), false);
                return false;
            }
        }

        // 水面现在被当作普通地面处理，不再有特殊判断

        // 检查脚部和头部是否可通过
        boolean feetPassable = (feet.isPassable() && !OBSTACLES.contains(feetType)) || isDoorPassable(feet)
                || isBannerPassable(feet) || (isTrapdoor(feet) && !isIronTrapdoor(feet)) || isLadder(feet)
                || isScaffolding(feet) || isFenceGate(feet) ||
                containsWithCache(feetName, "WATER") ||
                (containsWithCache(groundName, "WATER") && containsWithCache(feetName, "KELP")) || // 水面上的海带视为可通过
                (isLowBlockButNotStair(feet) && head.isPassable() && !OBSTACLES.contains(headType)); // 低矮方块可以潜行通过，只要上方可通过（楼梯半砖已排除）
        boolean headPassable = (head.isPassable() && !OBSTACLES.contains(headType)) || isDoorPassable(head)
                || isBannerPassable(head) || (isTrapdoor(head) && !isIronTrapdoor(head)) || isLadder(head)
                || isScaffolding(head) || isFenceGate(head);

        // 检查地面是否可以 standalone（固体方块或水面或梯子或脚手架）
        boolean groundSolid = groundType.isSolid() || containsWithCache(groundName, "WATER") ||
                isLadder(ground) || isScaffolding(ground);

        // 2x2条形通道检查：如果头部不可通过但是是门、旗帜或普通活板门，则认为可通过
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

        // 将nearLava信息存储在Block的元数据中
        if (nearLava) {
            loc.getBlock().setMetadata("nearLava",
                    new org.bukkit.metadata.FixedMetadataValue(TOCpluginNative.getInstance(), true));
            SAFE_LOCATION_CACHE.put(loc.clone(), false);
            return false;
        }

        // 水面现在被当作普通地面处理，不再有特殊判断
        // 如果脚下是水，也被视为可站立的地面

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
        public int moveType; // 移动类型：水平、向上、向下、跳跃
        public int dirX; // 移动方向X分量
        public int dirZ; // 移动方向Z分量
        public boolean isFenceGate; // 是否为栅栏门节点
        public boolean isDoor; // 是否为门节点
        public boolean isBanner; // 是否为旗帜节点

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
                // 计算移动方向
                this.dirX = location.getBlockX() - parent.location.getBlockX();
                this.dirZ = location.getBlockZ() - parent.location.getBlockZ();

                // 确定移动类型
                int yDiff = location.getBlockY() - parent.location.getBlockY();
                if (yDiff > 0) {
                    // 门完全视为空气方块，不需要特殊处理
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
            // 检查当前位置及其周围是否有栅栏门
            this.isFenceGate = isFenceGate(location.getBlock()) ||
                    isFenceGate(location.clone().add(0, -1, 0).getBlock()) ||
                    isFenceGate(location.clone().add(0, 1, 0).getBlock());

            // 检查当前位置及其周围是否有门
            this.isDoor = isDoor(location.getBlock()) ||
                    isDoor(location.clone().add(0, -1, 0).getBlock()) ||
                    isDoor(location.clone().add(0, 1, 0).getBlock());

            // 检查当前位置及其周围是否有旗帜
            this.isBanner = isBanner(location.getBlock()) ||
                    isBanner(location.clone().add(0, -1, 0).getBlock()) ||
                    isBanner(location.clone().add(0, 1, 0).getBlock());

            if (parent != null) {
                // 计算移动方向
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

    /**
     * 检查半砖是否是楼梯的一部分
     * 楼梯模式：连续的半砖呈阶梯状排列（高度递增或递减）
     */
    public static boolean isSlabStair(Block slabBlock) {
        if (!isSlab(slabBlock)) {
            return false;
        }

        Location loc = slabBlock.getLocation();

        // 检查水平四个方向，看是否有形成楼梯的模式
        int[][] directions = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

        for (int[] dir : directions) {
            // 检查这个方向是否形成楼梯
            if (isStairPatternInDirection(loc, dir[0], dir[1])) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查指定方向是否形成楼梯模式
     */
    private static boolean isStairPatternInDirection(Location startLoc, int dirX, int dirZ) {
        int stairCount = 0;
        boolean hasHeightChange = false;

        // 向前检查3个方块
        for (int step = 1; step <= 3; step++) {
            Location checkLoc = startLoc.clone().add(dirX * step, 0, dirZ * step);
            Block checkBlock = checkLoc.getBlock();

            // 检查当前位置的半砖
            if (isSlab(checkBlock)) {
                stairCount++;
                continue;
            }

            // 检查上一格是否有半砖（向上楼梯）
            Block upperBlock = checkLoc.clone().add(0, 1, 0).getBlock();
            if (isSlab(upperBlock)) {
                stairCount++;
                hasHeightChange = true;
                continue;
            }

            // 如果没有找到连续的半砖，跳出循环
            break;
        }

        // 向后检查3个方块
        for (int step = 1; step <= 3; step++) {
            Location checkLoc = startLoc.clone().add(-dirX * step, 0, -dirZ * step);
            Block checkBlock = checkLoc.getBlock();

            // 检查当前位置的半砖
            if (isSlab(checkBlock)) {
                stairCount++;
                continue;
            }

            // 检查下一格是否有半砖（向下楼梯）
            Block lowerBlock = checkLoc.clone().add(0, -1, 0).getBlock();
            if (isSlab(lowerBlock)) {
                stairCount++;
                hasHeightChange = true;
                continue;
            }

            // 如果没有找到连续的半砖，跳出循环
            break;
        }

        // 如果连续半砖数量>= 2 且有高度变化，则认为是楼梯
        return stairCount >= 2 && hasHeightChange;
    }

    /**
     * 改进的低矮方块检测，排除楼梯半砖
     */
    public static boolean isLowBlockButNotStair(Block block) {
        if (!isLowBlock(block)) {
            return false;
        }

        // 如果是半砖且构成楼梯，则不允许潜行通过
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