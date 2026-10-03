package com.plotsquared.lite.plot;

import com.plotsquared.lite.storage.Database;
import com.plotsquared.lite.world.PlotWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 地皮的高階操作：認領、刪除、清除、名單管理、家園位置。
 * 對合併群組的操作會套用到群組內每一塊地皮，與 PlotSquared 的行為一致。
 */
public final class PlotService {

    private final Plugin plugin;
    private final PlotManager manager;
    private final Database database;
    /**
     * 正在刪除（地形還原中）的地皮，期間不允許重新認領。
     */
    private final Set<String> busy = new HashSet<>();
    private int blocksPerTick = 40000;

    public PlotService(Plugin plugin, PlotManager manager, Database database) {
        this.plugin = plugin;
        this.manager = manager;
        this.database = database;
    }

    public void setBlocksPerTick(int blocksPerTick) {
        this.blocksPerTick = Math.max(1000, blocksPerTick);
    }

    public PlotManager manager() {
        return manager;
    }

    // ---------------------------------------------------------------- 認領與刪除

    public boolean isBusy(String world, PlotId id) {
        return busy.contains(world + ";" + id);
    }

    /**
     * 下一塊可以認領的空地（跳過刪除中的地皮）。
     */
    public PlotId findFreePlot(String world) {
        return manager.findFreePlot(world, candidate -> !isBusy(world, candidate));
    }

    public Plot claim(String world, PlotId id, UUID owner) {
        Plot plot = new Plot(world, id, owner, System.currentTimeMillis());
        manager.add(plot);
        database.createPlot(plot, null);
        setWallTop(Set.of(plot), true);
        return plot;
    }

    /**
     * 清除地皮的方塊但保留擁有權。合併群組會一起清除，被合併掉的道路會鋪成地皮地板。
     */
    public void clear(Plot plot, Runnable whenDone) {
        Set<Plot> group = manager.getConnected(plot);
        World world = Bukkit.getWorld(plot.area());
        PlotWorld plotWorld = manager.world(plot.area());
        if (world == null || plotWorld == null) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        RegionRegenerator.run(plugin, world, plotWorld, collectColumns(group, false),
                entityBoxes(group), blocksPerTick, whenDone);
    }

    /**
     * 刪除地皮：先從記憶體與資料庫移除，再把地形（包含合併掉的道路）恢復成生成器的樣子。
     */
    public void delete(Plot plot, Runnable whenDone) {
        Set<Plot> group = manager.getConnected(plot);
        List<RegionRegenerator.Column> columns = collectColumns(group, true);
        List<BoundingBox> boxes = entityBoxes(group);
        setWallTop(group, false);
        List<String> keys = new ArrayList<>();
        for (Plot member : group) {
            manager.remove(member);
            database.deletePlot(member);
            String key = member.area() + ";" + member.id();
            busy.add(key);
            keys.add(key);
        }
        Runnable finish = () -> {
            keys.forEach(busy::remove);
            if (whenDone != null) {
                whenDone.run();
            }
        };
        World world = Bukkit.getWorld(plot.area());
        PlotWorld plotWorld = manager.world(plot.area());
        if (world == null || plotWorld == null) {
            finish.run();
            return;
        }
        RegionRegenerator.run(plugin, world, plotWorld, columns, boxes, blocksPerTick, finish);
    }

    /**
     * 找出群組佔用的所有欄位（包含合併後被吃掉的道路）。
     *
     * @param restoreRoads true 時道路與圍牆照生成器原樣重鋪，false 時一律鋪成地皮地板
     */
    private List<RegionRegenerator.Column> collectColumns(Set<Plot> group, boolean restoreRoads) {
        Plot any = group.iterator().next();
        PlotWorld plotWorld = manager.world(any.area());
        World world = Bukkit.getWorld(any.area());
        List<RegionRegenerator.Column> columns = new ArrayList<>();
        if (plotWorld == null || world == null) {
            return columns;
        }
        Set<PlotId> ids = new HashSet<>();
        for (Plot plot : group) {
            ids.add(plot.id());
        }
        int[] bounds = bounds(plotWorld, group);
        Location probe = new Location(world, 0, 0, 0);
        for (int x = bounds[0]; x <= bounds[2]; x++) {
            for (int z = bounds[1]; z <= bounds[3]; z++) {
                probe.setX(x);
                probe.setZ(z);
                PlotId id = manager.getPlotId(probe);
                if (id == null || !ids.contains(id)) {
                    continue;
                }
                PlotWorld.CellType type = restoreRoads ? plotWorld.cellType(x, z) : PlotWorld.CellType.PLOT;
                columns.add(new RegionRegenerator.Column(x, z, type));
            }
        }
        return columns;
    }

    /**
     * 群組外框：{minX, minZ, maxX, maxZ}，只涵蓋地皮本身與夾在中間的道路。
     */
    private static int[] bounds(PlotWorld plotWorld, Set<Plot> group) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Plot plot : group) {
            minX = Math.min(minX, plotWorld.bottomX(plot.id()));
            minZ = Math.min(minZ, plotWorld.bottomZ(plot.id()));
            maxX = Math.max(maxX, plotWorld.topX(plot.id()));
            maxZ = Math.max(maxZ, plotWorld.topZ(plot.id()));
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    private List<BoundingBox> entityBoxes(Set<Plot> group) {
        Plot any = group.iterator().next();
        PlotWorld plotWorld = manager.world(any.area());
        List<BoundingBox> boxes = new ArrayList<>();
        if (plotWorld == null) {
            return boxes;
        }
        for (Plot plot : group) {
            boxes.add(new BoundingBox(
                    plotWorld.bottomX(plot.id()), plotWorld.minGenHeight, plotWorld.bottomZ(plot.id()),
                    plotWorld.topX(plot.id()) + 1, plotWorld.maxBuildHeight, plotWorld.topZ(plot.id()) + 1
            ));
        }
        return boxes;
    }

    /**
     * 把群組外圍圍牆頂端換成「已認領」或「未認領」的方塊（wall.block_claimed / wall.block）。
     */
    private void setWallTop(Set<Plot> group, boolean claimed) {
        Plot any = group.iterator().next();
        PlotWorld plotWorld = manager.world(any.area());
        World world = Bukkit.getWorld(any.area());
        if (plotWorld == null || world == null || !plotWorld.placeTopBlock || plotWorld.roadWidth == 0) {
            return;
        }
        Set<PlotId> ids = new HashSet<>();
        for (Plot plot : group) {
            ids.add(plot.id());
        }
        int y = plotWorld.wallHeight + 1;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location probe = new Location(world, 0, 0, 0);
        for (Plot plot : group) {
            int minX = plotWorld.bottomX(plot.id()) - 1;
            int minZ = plotWorld.bottomZ(plot.id()) - 1;
            int maxX = plotWorld.topX(plot.id()) + 1;
            int maxZ = plotWorld.topZ(plot.id()) + 1;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x != minX && x != maxX && z != minZ && z != maxZ) {
                        continue;
                    }
                    if (plotWorld.cellType(x, z) != PlotWorld.CellType.WALL) {
                        continue;
                    }
                    probe.setX(x);
                    probe.setZ(z);
                    PlotId id = manager.getPlotId(probe);
                    if (id != null && ids.contains(id)) {
                        continue; // 合併群組內部的圍牆
                    }
                    // 與 PlotSquared 相同，直接覆蓋圍牆頂端
                    world.getBlockAt(x, y, z).setBlockData(
                            (claimed ? plotWorld.claimedWallBlock : plotWorld.wallBlock).pick(random), false);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 名單

    /**
     * 將玩家加入名單，會套用到整個合併群組。加入 trusted/member 時會從另外兩個名單移除，與 PlotSquared 相同。
     */
    public void addUser(Plot plot, Database.UserTable table, UUID uuid) {
        for (Plot member : manager.getConnected(plot)) {
            removeFrom(member, Database.UserTable.TRUSTED, uuid, table);
            removeFrom(member, Database.UserTable.MEMBER, uuid, table);
            removeFrom(member, Database.UserTable.DENIED, uuid, table);
            if (set(member, table).add(uuid)) {
                database.addUser(member, table, uuid);
            }
        }
    }

    private void removeFrom(Plot plot, Database.UserTable table, UUID uuid, Database.UserTable except) {
        if (table != except && set(plot, table).remove(uuid)) {
            database.removeUser(plot, table, uuid);
        }
    }

    public boolean removeUser(Plot plot, Database.UserTable table, UUID uuid) {
        boolean removed = false;
        for (Plot member : manager.getConnected(plot)) {
            if (set(member, table).remove(uuid)) {
                database.removeUser(member, table, uuid);
                removed = true;
            }
        }
        return removed;
    }

    public static Set<UUID> set(Plot plot, Database.UserTable table) {
        return switch (table) {
            case TRUSTED -> plot.trusted();
            case MEMBER -> plot.members();
            case DENIED -> plot.denied();
        };
    }

    public void setOwner(Plot plot, UUID owner) {
        for (Plot member : manager.getConnected(plot)) {
            member.owner(owner);
            database.setOwner(member, owner);
        }
    }

    // ---------------------------------------------------------------- 家園

    public void setHome(Plot plot, Location location) {
        Plot base = manager.getBasePlot(plot);
        PlotWorld plotWorld = manager.world(base.area());
        String position;
        if (location == null) {
            position = "";
        } else {
            position = (location.getBlockX() - plotWorld.bottomX(base.id())) + ","
                    + location.getBlockY() + ","
                    + (location.getBlockZ() - plotWorld.bottomZ(base.id())) + ","
                    + location.getYaw() + "," + location.getPitch();
        }
        base.position(position);
        database.setPosition(base, position);
    }

    /**
     * 計算地皮的傳送位置，規則與 PlotSquared 的 Plot#getHome 相同。
     *
     * @param member 是否為擁有者或已加入名單的玩家（決定使用 home.default 或 home.nonmembers）
     */
    public Location getHome(Plot plot, boolean member) {
        Plot base = manager.getBasePlot(plot);
        PlotWorld plotWorld = manager.world(base.area());
        World world = Bukkit.getWorld(base.area());
        if (plotWorld == null || world == null) {
            return null;
        }
        String position = base.position();
        if (position != null) {
            String[] parts = position.split(",");
            if (parts.length == 3 || parts.length == 5) {
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    float yaw = parts.length == 5 ? Float.parseFloat(parts[3].trim()) : 0;
                    float pitch = parts.length == 5 ? Float.parseFloat(parts[4].trim()) : 0;
                    if (x != 0 || z != 0) {
                        Location location = new Location(world,
                                plotWorld.bottomX(base.id()) + x + 0.5, y, plotWorld.bottomZ(base.id()) + z + 0.5,
                                yaw, pitch);
                        if (!location.getBlock().getType().isAir()) {
                            int highest = world.getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
                            location.setY(Math.max(highest + 1, plotWorld.minGenHeight));
                        }
                        return location;
                    }
                } catch (NumberFormatException ignored) {
                    // 格式錯誤就使用預設位置
                }
            }
        }
        return getDefaultHome(base, plotWorld, world, member ? plotWorld.homeDefault : plotWorld.homeNonMembers);
    }

    private Location getDefaultHome(Plot base, PlotWorld plotWorld, World world, String mode) {
        int[] bounds = bounds(plotWorld, manager.getConnected(base));
        String lower = mode == null ? "side" : mode.toLowerCase(Locale.ROOT);
        int x;
        int z;
        Float fixedY = null;
        float yaw = 0;
        float pitch = 0;
        switch (lower) {
            case "center", "centre", "middle" -> {
                x = (bounds[2] >> 1) - (bounds[0] >> 1) + bounds[0];
                z = (bounds[3] >> 1) - (bounds[1] >> 1) + bounds[1];
            }
            case "side" -> {
                x = (bounds[2] >> 1) - (bounds[0] >> 1) + bounds[0];
                z = bounds[1] - 1;
            }
            default -> {
                String[] parts = lower.split(",");
                try {
                    x = plotWorld.bottomX(base.id()) + Integer.parseInt(parts[0].trim());
                    fixedY = (float) Integer.parseInt(parts[1].trim());
                    z = plotWorld.bottomZ(base.id()) + Integer.parseInt(parts[2].trim());
                    if (parts.length == 5) {
                        yaw = Float.parseFloat(parts[3].trim());
                        pitch = Float.parseFloat(parts[4].trim());
                    }
                } catch (RuntimeException e) {
                    x = (bounds[2] >> 1) - (bounds[0] >> 1) + bounds[0];
                    z = bounds[1] - 1;
                }
            }
        }
        // yaw 0 面向南方，站在地皮北側時剛好面向地皮
        double y = fixedY != null ? fixedY : world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5, yaw, pitch);
    }

}
