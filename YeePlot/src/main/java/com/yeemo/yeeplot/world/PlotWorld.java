package com.yeemo.yeeplot.world;

import com.yeemo.yeeplot.plot.PlotId;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;

/**
 * 一個地皮世界的設定與座標換算。
 * 讀取 PlotSquared worlds.yml 中 {@code worlds.<世界名稱>} 區段，數學運算與
 * PlotSquared 的 SquarePlotManager / HybridGen 完全一致，確保既有地皮的位置不變。
 */
public final class PlotWorld {

    public enum CellType {
        PLOT, WALL, ROAD
    }

    /**
     * PlotSquared 的地皮區域類型（worlds.yml 的 generator.type）。
     */
    public enum AreaType {
        /**
         * 整個世界都是地皮世界，地形由地皮生成器產生。
         */
        NORMAL,
        /**
         * 整個世界都是地皮區域，但地形由世界原本的生成器產生（例如原版地形），只是在上面劃出地皮格線。
         */
        AUGMENTED,
        /**
         * 只在世界中劃出部分區域當作地皮（YeePlot 不支援）。
         */
        PARTIAL;

        /**
         * 解析 generator.type；PlotSquared 舊版用數字 0、1、2。無法辨識時回傳 null。
         */
        public static AreaType parse(String value) {
            if (value == null) {
                return NORMAL;
            }
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "", "0", "NORMAL" -> NORMAL;
                case "1", "AUGMENTED" -> AUGMENTED;
                case "2", "PARTIAL" -> PARTIAL;
                default -> null;
            };
        }
    }

    private final String name;

    public final int plotWidth;
    public final int roadWidth;
    public final int roadOffsetX;
    public final int roadOffsetZ;
    public final int size;
    /**
     * 相對座標小於這個值是道路，等於這個值是圍牆（與 HybridPlotWorld.PATH_WIDTH_LOWER 相同）。
     */
    public final int pathLower;
    /**
     * 相對座標大於這個值是道路，等於這個值是圍牆（與 HybridPlotWorld.PATH_WIDTH_UPPER 相同）。
     */
    public final int pathUpper;

    public final int plotHeight;
    public final int roadHeight;
    public final int wallHeight;
    public final boolean plotBedrock;
    public final boolean placeTopBlock;

    public final int minGenHeight;
    public final int maxGenHeight;
    /**
     * 可建築的最低高度（包含）。
     */
    public final int minBuildHeight;
    /**
     * 可建築的最高高度（不包含）。
     */
    public final int maxBuildHeight;

    public final BlockPattern mainBlock;
    public final BlockPattern topBlock;
    public final BlockPattern wallBlock;
    public final BlockPattern claimedWallBlock;
    public final BlockPattern wallFilling;
    public final BlockPattern roadBlock;
    public final Biome biome;

    /**
     * 地皮區域類型；AUGMENTED 世界的地形不屬於 YeePlot，任何操作都不會修改地形。
     */
    public final AreaType areaType;
    /**
     * AUGMENTED 世界的 generator.terrain（NONE、ORE、ROAD、ALL），一般世界是 null。
     */
    public final String terrain;

    public final String homeDefault;
    public final String homeNonMembers;

    private final BlockData bedrock = Material.BEDROCK.createBlockData();

    public PlotWorld(String name, ConfigurationSection config, Logger logger) {
        this.name = name;
        this.plotWidth = config.getInt("plot.size", 42);
        this.roadWidth = config.getInt("road.width", 7);
        this.roadOffsetX = config.getInt("road.offset.x", 0);
        this.roadOffsetZ = config.getInt("road.offset.z", 0);
        this.size = plotWidth + roadWidth;
        if ((roadWidth & 1) == 0) {
            this.pathLower = roadWidth / 2 - 1;
        } else {
            this.pathLower = roadWidth / 2;
        }
        if (roadWidth == 0) {
            this.pathUpper = size + 1;
        } else {
            this.pathUpper = pathLower + plotWidth + 1;
        }

        this.minGenHeight = config.getInt("world.min_gen_height", -64);
        this.maxGenHeight = config.getInt("world.max_gen_height", 319);
        this.minBuildHeight = config.getInt("world.min_height", -63);
        this.maxBuildHeight = config.getInt("world.max_height", 320);

        this.plotBedrock = config.getBoolean("plot.bedrock", true);
        this.placeTopBlock = config.getBoolean("wall.place_top_block", true);
        this.plotHeight = Math.min(maxGenHeight, config.getInt("plot.height", 62));
        this.roadHeight = Math.min(maxGenHeight, config.getInt("road.height", 62));
        this.wallHeight = Math.min(maxGenHeight - (placeTopBlock ? 1 : 0), config.getInt("wall.height", 62));

        this.mainBlock = BlockPattern.parse(config.getString("plot.filling"), "stone", logger);
        this.topBlock = BlockPattern.parse(config.getString("plot.floor"), "grass_block", logger);
        this.wallBlock = BlockPattern.parse(config.getString("wall.block"), "stone_slab", logger);
        this.claimedWallBlock = BlockPattern.parse(config.getString("wall.block_claimed"), "sandstone_slab", logger);
        this.wallFilling = BlockPattern.parse(config.getString("wall.filling"), "stone", logger);
        this.roadBlock = BlockPattern.parse(config.getString("road.block"), "quartz_block", logger);
        this.biome = parseBiome(config.getString("plot.biome", "FOREST"), logger);

        AreaType type = AreaType.parse(config.getString("generator.type", "NORMAL"));
        this.areaType = type == null ? AreaType.NORMAL : type;
        this.terrain = areaType == AreaType.AUGMENTED
                ? config.getString("generator.terrain", "ALL").toUpperCase(Locale.ROOT) : null;

        this.homeDefault = config.getString("home.default", "side");
        this.homeNonMembers = config.getString("home.nonmembers", homeDefault);
    }

    /**
     * 新建地皮世界時寫入 worlds.yml 的預設值，格式與 PlotSquared 相同。
     */
    public static void writeDefaults(ConfigurationSection config) {
        setIfAbsent(config, "plot.size", 42);
        setIfAbsent(config, "plot.height", 62);
        setIfAbsent(config, "plot.filling", "stone");
        setIfAbsent(config, "plot.floor", "grass_block");
        setIfAbsent(config, "plot.bedrock", true);
        setIfAbsent(config, "plot.biome", "minecraft:forest");
        setIfAbsent(config, "road.width", 7);
        setIfAbsent(config, "road.height", 62);
        setIfAbsent(config, "road.block", "quartz_block");
        setIfAbsent(config, "road.offset.x", 0);
        setIfAbsent(config, "road.offset.z", 0);
        setIfAbsent(config, "wall.block", "stone_slab");
        setIfAbsent(config, "wall.block_claimed", "sandstone_slab");
        setIfAbsent(config, "wall.filling", "stone");
        setIfAbsent(config, "wall.height", 62);
        setIfAbsent(config, "wall.place_top_block", true);
        setIfAbsent(config, "world.min_gen_height", -64);
        setIfAbsent(config, "world.max_gen_height", 319);
        setIfAbsent(config, "world.min_height", -63);
        setIfAbsent(config, "world.max_height", 320);
        setIfAbsent(config, "home.default", "side");
        setIfAbsent(config, "home.nonmembers", "side");
    }

    private static void setIfAbsent(ConfigurationSection config, String path, Object value) {
        if (!config.contains(path)) {
            config.set(path, value);
        }
    }

    private static Biome parseBiome(String input, Logger logger) {
        String key = input.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        Biome biome = Registry.BIOME.get(NamespacedKey.minecraft(key));
        if (biome == null) {
            logger.warning("未知的生態域 '" + input + "'，改用 forest");
            biome = Registry.BIOME.get(NamespacedKey.minecraft("forest"));
        }
        return biome;
    }

    public String name() {
        return name;
    }

    /**
     * YeePlot 是否負責這個世界的地形（生成、清除、刪除還原、合併鋪地板、圍牆頂端）。
     * AUGMENTED 世界的地形來自世界原本的生成器，YeePlot 一律不修改。
     */
    public boolean managesTerrain() {
        return areaType == AreaType.NORMAL;
    }

    private int relative(int coordinate, int offset) {
        return Math.floorMod(coordinate - offset, size);
    }

    private boolean isRoad(int relative) {
        return roadWidth != 0 && (relative < pathLower || relative > pathUpper);
    }

    private boolean isWall(int relative) {
        return roadWidth != 0 && (relative == pathLower || relative == pathUpper);
    }

    /**
     * 依照 HybridGen 的規則判斷某一欄是地皮、圍牆還是道路。
     */
    public CellType cellType(int x, int z) {
        return cellType(x, z, false, false);
    }

    /**
     * 判斷格子類型，可以忽略某個方向。合併地皮時，被合併方向上的道路與圍牆會變成地皮，
     * 只剩另一個方向決定這一格是道路還是圍牆（例如合併缺口兩端，外圍圍牆會接起來）。
     */
    public CellType cellType(int x, int z, boolean ignoreX, boolean ignoreZ) {
        int rx = relative(x, roadOffsetX);
        int rz = relative(z, roadOffsetZ);
        boolean roadX = !ignoreX && isRoad(rx);
        boolean roadZ = !ignoreZ && isRoad(rz);
        if (roadX || roadZ) {
            return CellType.ROAD;
        }
        if (!ignoreX && isWall(rx) || !ignoreZ && isWall(rz)) {
            return CellType.WALL;
        }
        return CellType.PLOT;
    }

    /**
     * 不考慮合併的地皮座標（與 SquarePlotManager#getPlotIdAbs 相同），站在道路或圍牆上回傳 null。
     */
    public PlotId plotIdAbs(int x, int z) {
        int[] raw = rawPlotId(x, z);
        return raw[2] == 0 ? new PlotId(raw[0], raw[1]) : null;
    }

    /**
     * 回傳 {idX, idZ, hash}，hash 表示這一格位於地皮哪一側的道路上（0 代表地皮內部）。
     * hash 的位元與 PlotSquared 的 SquarePlotManager#getPlotId 相同：8 北、4 東、2 南、1 西。
     */
    public int[] rawPlotId(int x, int z) {
        x -= roadOffsetX;
        z -= roadOffsetZ;
        int lower;
        int end;
        if (roadWidth == 0) {
            lower = -1;
            end = plotWidth;
        } else {
            lower = pathLower;
            end = pathLower + plotWidth;
        }
        int dx = Math.floorDiv(x, size) + 1;
        int rx = Math.floorMod(x, size);
        int dz = Math.floorDiv(z, size) + 1;
        int rz = Math.floorMod(z, size);
        int hash = ((rz <= lower) ? 8 : 0) + ((rx > end) ? 4 : 0) + ((rz > end) ? 2 : 0) + ((rx <= lower) ? 1 : 0);
        return new int[]{dx, dz, hash};
    }

    public int bottomX(PlotId id) {
        return roadOffsetX + id.x() * size - plotWidth - roadWidth / 2;
    }

    public int bottomZ(PlotId id) {
        return roadOffsetZ + id.z() * size - plotWidth - roadWidth / 2;
    }

    public int topX(PlotId id) {
        return roadOffsetX + id.x() * size - roadWidth / 2 - 1;
    }

    public int topZ(PlotId id) {
        return roadOffsetZ + id.z() * size - roadWidth / 2 - 1;
    }

    /**
     * 某一欄在高度 y 應該生成的方塊，空氣回傳 null。地形生成與清除地皮共用此邏輯。
     */
    public BlockData blockAt(CellType type, int y, Random random) {
        return blockAt(type, y, random, false);
    }

    /**
     * @param claimedWall 圍牆頂端是否使用「已認領」的方塊（wall.block_claimed）
     */
    public BlockData blockAt(CellType type, int y, Random random, boolean claimedWall) {
        if (claimedWall && type == CellType.WALL && placeTopBlock && y == wallHeight + 1) {
            return claimedWallBlock.pick(random);
        }
        if (y == minGenHeight && plotBedrock) {
            return bedrock;
        }
        if (y < minGenHeight) {
            return null;
        }
        switch (type) {
            case ROAD:
                return y <= roadHeight ? roadBlock.pick(random) : null;
            case WALL:
                if (y <= wallHeight) {
                    return wallFilling.pick(random);
                }
                if (placeTopBlock && y == wallHeight + 1) {
                    return wallBlock.pick(random);
                }
                return null;
            default:
                if (y < plotHeight) {
                    return mainBlock.pick(random);
                }
                if (y == plotHeight) {
                    return topBlock.pick(random);
                }
                return null;
        }
    }

}
