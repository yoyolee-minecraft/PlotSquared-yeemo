package com.plotsquared.lite.plot;

import com.plotsquared.lite.world.PlotWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 記憶體中的地皮索引。只在主執行緒修改；生成器、WorldEdit、Axiom 可能在其他執行緒讀取，
 * 所以使用執行緒安全的集合（讀到的可能是稍舊的資料，但不會損壞）。
 */
public final class PlotManager {

    /**
     * 不可變的快照，整份替換；生成器會在非主執行緒讀取這份資料。
     */
    private volatile Map<String, PlotWorld> worlds = Map.of();
    private final Map<String, Map<PlotId, Plot>> plots = new ConcurrentHashMap<>();

    public void setWorlds(Map<String, PlotWorld> newWorlds) {
        worlds = Map.copyOf(newWorlds);
    }

    public void setPlots(Map<String, Map<PlotId, Plot>> loaded) {
        plots.clear();
        loaded.forEach((world, map) -> plots.put(world, new ConcurrentHashMap<>(map)));
    }

    public PlotWorld world(String name) {
        return worlds.get(name);
    }

    public Collection<PlotWorld> worlds() {
        return Collections.unmodifiableCollection(worlds.values());
    }

    public boolean isPlotWorld(String name) {
        return worlds.containsKey(name);
    }

    /**
     * 不考慮合併，直接取得某個座標的已認領地皮。
     */
    public Plot getPlotAbs(String world, PlotId id) {
        Map<PlotId, Plot> map = plots.get(world);
        return map == null ? null : map.get(id);
    }

    public void add(Plot plot) {
        plots.computeIfAbsent(plot.area(), k -> new ConcurrentHashMap<>()).put(plot.id(), plot);
    }

    public void remove(Plot plot) {
        Map<PlotId, Plot> map = plots.get(plot.area());
        if (map != null) {
            map.remove(plot.id(), plot);
        }
    }

    /**
     * 位置所在的地皮座標（考慮合併後被吃掉的道路），不在地皮內回傳 null。
     * 邏輯與 PlotSquared 的 SquarePlotManager#getPlotId 相同。
     */
    public PlotId getPlotId(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        String worldName = location.getWorld().getName();
        PlotWorld world = worlds.get(worldName);
        if (world == null) {
            return null;
        }
        int[] raw = world.rawPlotId(location.getBlockX(), location.getBlockZ());
        PlotId id = new PlotId(raw[0], raw[1]);
        int hash = raw[2];
        if (hash == 0) {
            return id;
        }
        Plot plot = getPlotAbs(worldName, id);
        if (plot == null) {
            return null;
        }
        boolean merged = switch (hash) {
            case 8 -> plot.isMerged(Direction.NORTH);
            case 4 -> plot.isMerged(Direction.EAST);
            case 2 -> plot.isMerged(Direction.SOUTH);
            case 1 -> plot.isMerged(Direction.WEST);
            case 12 -> isMergedDiagonal(plot, Direction.NORTH, Direction.EAST);
            case 6 -> isMergedDiagonal(plot, Direction.EAST, Direction.SOUTH);
            case 3 -> isMergedDiagonal(plot, Direction.SOUTH, Direction.WEST);
            case 9 -> isMergedDiagonal(plot, Direction.WEST, Direction.NORTH);
            default -> false;
        };
        return merged ? id : null;
    }

    /**
     * 對角方向的交叉路口只有在四塊地皮全部互相合併時才屬於地皮。
     */
    private boolean isMergedDiagonal(Plot plot, Direction a, Direction b) {
        if (!plot.isMerged(a) || !plot.isMerged(b)) {
            return false;
        }
        Plot plotA = getPlotAbs(plot.area(), plot.id().relative(a));
        Plot plotB = getPlotAbs(plot.area(), plot.id().relative(b));
        return plotA != null && plotB != null && plotA.isMerged(b) && plotB.isMerged(a);
    }

    /**
     * 位置所在的已認領地皮（不是基準地皮，而是那一格實際所屬的地皮）。
     */
    public Plot getPlotAt(Location location) {
        PlotId id = getPlotId(location);
        if (id == null) {
            return null;
        }
        return getPlotAbs(location.getWorld().getName(), id);
    }

    /**
     * 與 plot 合併在一起的所有地皮（包含自己）。
     */
    public Set<Plot> getConnected(Plot plot) {
        if (!plot.isMerged()) {
            return Collections.singleton(plot);
        }
        Set<Plot> result = new LinkedHashSet<>();
        Deque<Plot> queue = new ArrayDeque<>();
        queue.add(plot);
        while (!queue.isEmpty()) {
            Plot current = queue.poll();
            if (!result.add(current)) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                if (current.isMerged(direction)) {
                    Plot other = getPlotAbs(current.area(), current.id().relative(direction));
                    if (other != null && other.isMerged(direction.opposite()) && !result.contains(other)) {
                        queue.add(other);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 合併群組實際佔用的平面範圍（包含被合併掉的道路），以多個長方形 {minX, minZ, maxX, maxZ} 表示。
     * 群組可能是 L 形，所以每塊地皮各自往東、往南延伸，十字路口只在四塊都合併時才加入。
     */
    public List<int[]> getGroupRects(Plot plot) {
        PlotWorld world = worlds.get(plot.area());
        List<int[]> rects = new ArrayList<>();
        if (world == null) {
            return rects;
        }
        for (Plot member : getConnected(plot)) {
            PlotId id = member.id();
            int minX = world.bottomX(id);
            int minZ = world.bottomZ(id);
            int maxX = world.topX(id);
            int maxZ = world.topZ(id);
            int eastX = member.isMerged(Direction.EAST) ? world.bottomX(id.relative(Direction.EAST)) - 1 : maxX;
            int southZ = member.isMerged(Direction.SOUTH) ? world.bottomZ(id.relative(Direction.SOUTH)) - 1 : maxZ;
            rects.add(new int[]{minX, minZ, eastX, maxZ});
            if (southZ != maxZ) {
                rects.add(new int[]{minX, minZ, maxX, southZ});
            }
            if (eastX != maxX && southZ != maxZ && isMergedDiagonal(member, Direction.EAST, Direction.SOUTH)) {
                rects.add(new int[]{maxX + 1, maxZ + 1, eastX, southZ});
            }
        }
        // 固定順序，讓同一個群組不論從哪塊地皮查詢都得到相同結果
        rects.sort(Comparator.<int[]>comparingInt(r -> r[0]).thenComparingInt(r -> r[1])
                .thenComparingInt(r -> r[2]).thenComparingInt(r -> r[3]));
        return rects;
    }

    /**
     * 合併群組中的基準地皮：z 最小，其次 x 最小（與 PlotSquared 相同）。
     */
    public Plot getBasePlot(Plot plot) {
        Plot base = plot;
        for (Plot other : getConnected(plot)) {
            if (other.id().z() < base.id().z() || other.id().z() == base.id().z() && other.id().x() < base.id().x()) {
                base = other;
            }
        }
        return base;
    }

    /**
     * 玩家擁有的基準地皮（合併群組只算一次），依世界與座標排序，供 /plot home 編號使用。
     */
    public List<Plot> getOwnedBasePlots(UUID owner, String worldOrNull) {
        List<Plot> result = new ArrayList<>();
        for (Map.Entry<String, Map<PlotId, Plot>> entry : plots.entrySet()) {
            if (worldOrNull != null && !worldOrNull.equals(entry.getKey())) {
                continue;
            }
            if (!worlds.containsKey(entry.getKey())) {
                continue;
            }
            for (Plot plot : entry.getValue().values()) {
                if (plot.isOwner(owner) && getBasePlot(plot) == plot) {
                    result.add(plot);
                }
            }
        }
        result.sort(Comparator.comparing(Plot::area)
                .thenComparingLong(Plot::timestamp)
                .thenComparingInt(p -> p.id().x())
                .thenComparingInt(p -> p.id().z()));
        return result;
    }

    /**
     * 玩家擁有的地皮數量，與 PlotSquared 相同：合併的每一塊都分別計算，只算已載入的地皮世界。
     *
     * @param worldOrNull null 代表所有世界
     */
    public int countOwned(UUID owner, String worldOrNull) {
        int count = 0;
        for (Map.Entry<String, Map<PlotId, Plot>> entry : plots.entrySet()) {
            if (worldOrNull != null && !worldOrNull.equals(entry.getKey())) {
                continue;
            }
            if (!worlds.containsKey(entry.getKey())) {
                continue;
            }
            for (Plot plot : entry.getValue().values()) {
                if (plot.isOwner(owner)) {
                    count++;
                }
            }
        }
        return count;
    }

    public int countPlots(String world) {
        Map<PlotId, Plot> map = plots.get(world);
        return map == null ? 0 : map.size();
    }

    /**
     * 從中心往外螺旋尋找下一塊空地（與 PlotSquared 的 /plot auto 行為類似）。
     */
    public PlotId findFreePlot(String world, Predicate<PlotId> filter) {
        Map<PlotId, Plot> map = plots.getOrDefault(world, Collections.emptyMap());
        for (int radius = 0; radius < 10_000; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    PlotId id = new PlotId(dx, dz);
                    if (!map.containsKey(id) && filter.test(id)) {
                        return id;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 擁有者是否在線，用來判斷 member 是否可以建築。
     */
    public boolean isOwnerOnline(Plot plot) {
        return plot.owner() != null && Bukkit.getPlayer(plot.owner()) != null;
    }

}
