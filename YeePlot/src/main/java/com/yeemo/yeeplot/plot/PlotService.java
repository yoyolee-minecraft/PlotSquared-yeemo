package com.yeemo.yeeplot.plot;

import com.yeemo.yeeplot.storage.Database;
import com.yeemo.yeeplot.world.PlotWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * 地皮的高階操作：認領、刪除、清除、名單管理、家園位置。
 * 對合併群組的操作會套用到群組內每一塊地皮，與 PlotSquared 的行為一致。
 */
public final class PlotService {

    private final Plugin plugin;
    private final PlotManager manager;
    private final Database database;
    private final RegenJobs jobs;
    /**
     * 正在刪除（地形還原中）的地皮，期間不允許重新認領。
     */
    private final Set<String> busy = new HashSet<>();
    private int blocksPerTick = 40000;

    public PlotService(Plugin plugin, PlotManager manager, Database database, RegenJobs jobs) {
        this.plugin = plugin;
        this.manager = manager;
        this.database = database;
        this.jobs = jobs;
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
        startJob(jobs.add(plot.area(), RegenJobs.Mode.CLEAR, manager.getGroupRects(plot)), whenDone);
    }

    /**
     * 刪除地皮：先從記憶體與資料庫移除，再把整個群組範圍（包含合併掉的道路）恢復成生成器的樣子。
     */
    public void delete(Plot plot, Runnable whenDone) {
        Set<Plot> group = manager.getConnected(plot);
        List<int[]> rects = manager.getGroupRects(plot);
        setWallTop(group, false);
        for (Plot member : group) {
            manager.remove(member);
            database.deletePlot(member);
        }
        // 往外多一圈：連同外圍圍牆與合併缺口兩端（原版合併時會把外圍圍牆接起來，刪除後要恢復成道路）
        List<int[]> expanded = new ArrayList<>();
        for (int[] rect : rects) {
            expanded.add(new int[]{rect[0] - 1, rect[1] - 1, rect[2] + 1, rect[3] + 1});
        }
        startJob(jobs.add(plot.area(), RegenJobs.Mode.DELETE, expanded), whenDone);
    }

    /**
     * 修復道路：把範圍內不屬於任何地皮的道路與圍牆恢復原樣，並移除上面的裝飾實體。
     * 用來處理原版 PlotSquared 刪除合併地皮後留在道路上的方塊。
     */
    public void fixRoads(String world, int minX, int minZ, int maxX, int maxZ, Runnable whenDone) {
        startJob(jobs.add(world, RegenJobs.Mode.FIX_ROADS, List.of(new int[]{minX, minZ, maxX, maxZ})), whenDone);
    }

    /**
     * 啟動時接著做上次沒完成的工作。
     */
    public void resumeJobs() {
        for (RegenJobs.Job job : jobs.pending()) {
            plugin.getLogger().info("接續上次未完成的地形還原：" + job.world() + " " + job.mode());
            startJob(job, null);
        }
    }

    private void startJob(RegenJobs.Job job, Runnable whenDone) {
        World world = Bukkit.getWorld(job.world());
        PlotWorld plotWorld = manager.world(job.world());
        if (world == null || plotWorld == null) {
            plugin.getLogger().warning("世界 " + job.world() + " 不存在，放棄地形還原工作");
            jobs.complete(job);
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        // 刪除期間不允許重新認領範圍內的地皮
        List<String> keys = new ArrayList<>();
        if (job.mode() == RegenJobs.Mode.DELETE) {
            for (PlotId id : plotIdsIn(plotWorld, job.rects())) {
                String key = job.world() + ";" + id;
                if (busy.add(key)) {
                    keys.add(key);
                }
            }
        }
        List<RegionRegenerator.Column> columns = collectColumns(world, plotWorld, job);
        List<BoundingBox> boxes = new ArrayList<>();
        for (int[] rect : job.rects()) {
            boxes.add(new BoundingBox(rect[0], world.getMinHeight(), rect[1],
                    rect[2] + 1, world.getMaxHeight(), rect[3] + 1));
        }
        Predicate<Entity> removeEntity = job.mode() == RegenJobs.Mode.FIX_ROADS
                ? entity -> isDecoration(entity) && manager.getPlotId(entity.getLocation()) == null
                : entity -> true;
        RegionRegenerator.run(plugin, world, plotWorld, columns, boxes, removeEntity, blocksPerTick, () -> {
            keys.forEach(busy::remove);
            jobs.complete(job);
            if (whenDone != null) {
                whenDone.run();
            }
        });
    }

    /**
     * 修復道路時只移除不會自己移動的裝飾實體，避免誤殺道路上的生物或礦車。
     */
    private static boolean isDecoration(Entity entity) {
        return entity instanceof Hanging || entity instanceof ArmorStand || entity instanceof Display;
    }

    private static Set<PlotId> plotIdsIn(PlotWorld plotWorld, List<int[]> rects) {
        Set<PlotId> ids = new HashSet<>();
        for (int[] rect : rects) {
            for (int x = rect[0]; x <= rect[2]; x++) {
                for (int z = rect[1]; z <= rect[3]; z++) {
                    PlotId id = plotWorld.plotIdAbs(x, z);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private List<RegionRegenerator.Column> collectColumns(World world, PlotWorld plotWorld, RegenJobs.Job job) {
        List<RegionRegenerator.Column> columns = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Location probe = new Location(world, 0, 0, 0);
        for (int[] rect : job.rects()) {
            for (int x = rect[0]; x <= rect[2]; x++) {
                for (int z = rect[1]; z <= rect[3]; z++) {
                    if (!seen.add(((long) x << 32) ^ (z & 0xffffffffL))) {
                        continue; // 長方形之間可能重疊
                    }
                    if (job.mode() == RegenJobs.Mode.CLEAR) {
                        columns.add(new RegionRegenerator.Column(x, z, PlotWorld.CellType.PLOT));
                        continue;
                    }
                    probe.setX(x);
                    probe.setZ(z);
                    if (job.mode() == RegenJobs.Mode.DELETE) {
                        // 地皮已從記憶體移除；範圍內若有其他仍被認領的格子（例如重啟後接續時），一律不動
                        if (manager.getPlotAt(probe) != null) {
                            continue;
                        }
                        if (plotWorld.plotIdAbs(x, z) != null) {
                            columns.add(new RegionRegenerator.Column(x, z, PlotWorld.CellType.PLOT));
                            continue;
                        }
                    } else if (manager.getPlotId(probe) != null) {
                        continue; // 修復道路：地皮內部（不論是否認領）與合併道路都不動
                    }
                    PlotWorld.CellType type = roadCellType(plotWorld, x, z);
                    boolean claimedWall = type == PlotWorld.CellType.WALL && besideClaimedPlot(world, x, z);
                    columns.add(new RegionRegenerator.Column(x, z, type, claimedWall));
                }
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

    /**
     * 不屬於任何地皮的格子應該是道路還是圍牆，考慮相鄰地皮的合併狀態。
     * <p>
     * 原版合併地皮時，合併缺口兩端的外圍圍牆會接起來；L 形群組的內角則保留道路、但邊緣是圍牆。
     * 分別用「忽略被合併的方向」來看：只有一個方向合併就用那個觀點；兩個方向都合併（但斜角沒合併）時，
     * 任一觀點是圍牆就是圍牆，否則是道路。
     */
    private PlotWorld.CellType roadCellType(PlotWorld plotWorld, int x, int z) {
        int[] raw = plotWorld.rawPlotId(x, z);
        int hash = raw[2];
        Plot plot = hash == 0 ? null : manager.getPlotAbs(plotWorld.name(), new PlotId(raw[0], raw[1]));
        if (plot == null) {
            return plotWorld.cellType(x, z);
        }
        boolean mergedX = (hash & 4) != 0 && plot.isMerged(Direction.EAST) || (hash & 1) != 0 && plot.isMerged(Direction.WEST);
        boolean mergedZ = (hash & 8) != 0 && plot.isMerged(Direction.NORTH) || (hash & 2) != 0 && plot.isMerged(Direction.SOUTH);
        if (mergedX && mergedZ) {
            PlotWorld.CellType eastWest = plotWorld.cellType(x, z, true, false);
            PlotWorld.CellType northSouth = plotWorld.cellType(x, z, false, true);
            return eastWest == PlotWorld.CellType.WALL || northSouth == PlotWorld.CellType.WALL
                    ? PlotWorld.CellType.WALL : PlotWorld.CellType.ROAD;
        }
        return plotWorld.cellType(x, z, mergedX, mergedZ);
    }

    /**
     * 圍牆旁邊（含斜角）是否有已認領的地皮（含合併道路），用來決定圍牆頂端要放哪種方塊。
     */
    private boolean besideClaimedPlot(World world, int x, int z) {
        Location probe = new Location(world, 0, 0, 0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                probe.setX(x + dx);
                probe.setZ(z + dz);
                if (manager.getPlotAt(probe) != null) {
                    return true;
                }
            }
        }
        return false;
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

    // ---------------------------------------------------------------- 時間與天氣

    /**
     * 設定整個合併群組的時間（null 代表移除），存成 PlotSquared 的 time flag。
     */
    public void setTime(Plot plot, Long time) {
        for (Plot member : manager.getConnected(plot)) {
            member.time(time);
            database.setFlag(member, "time", time == null ? null : String.valueOf(time));
        }
    }

    /**
     * 設定整個合併群組的天氣（clear、rain，null 代表移除），存成 PlotSquared 的 weather flag。
     */
    public void setWeather(Plot plot, String weather) {
        for (Plot member : manager.getConnected(plot)) {
            member.weather(weather);
            database.setFlag(member, "weather", weather == null ? null : weather.toUpperCase(Locale.ROOT));
        }
    }

    // ---------------------------------------------------------------- 別名

    public enum AliasCheck {
        OK, EMPTY, TOO_LONG, NUMBER, INVALID_CHARACTER, TAKEN
    }

    /**
     * 檢查別名格式與是否重複，規則與 PlotSquared 相同：一個單字、少於 50 字、不能是純數字、
     * 同一個世界內不能重複（不分大小寫）。另外禁止色碼字元，避免在訊息中產生格式。
     * 是否與玩家名稱相同由呼叫端檢查。
     */
    public AliasCheck checkAlias(Plot plot, String alias) {
        if (alias == null || alias.isBlank()) {
            return AliasCheck.EMPTY;
        }
        if (alias.length() >= 50) {
            return AliasCheck.TOO_LONG;
        }
        if (alias.matches("-?\\d+")) {
            return AliasCheck.NUMBER;
        }
        if (alias.contains(" ") || alias.contains("&") || alias.contains("\u00a7")) {
            return AliasCheck.INVALID_CHARACTER;
        }
        Plot existing = manager.findByAlias(alias, plot.area());
        if (existing != null && !manager.getConnected(plot).contains(existing)) {
            return AliasCheck.TAKEN;
        }
        return AliasCheck.OK;
    }

    /**
     * 設定整個合併群組的別名（null 代表移除）。
     */
    public void setAlias(Plot plot, String alias) {
        for (Plot member : manager.getConnected(plot)) {
            member.alias(alias);
            database.setAlias(member, alias);
        }
    }

    // ---------------------------------------------------------------- 合併

    public enum MergeCheck {
        OK, NO_TARGET, DIFFERENT_OWNER, NOT_RECTANGLE, TOO_LARGE
    }

    /**
     * 合併的目標：從 plot 所在群組往 direction 走，第一塊不在群組裡的地皮。
     */
    public Plot mergeTarget(Plot plot, Direction direction) {
        Set<PlotId> group = new HashSet<>();
        for (Plot member : manager.getConnected(plot)) {
            group.add(member.id());
        }
        PlotId id = plot.id();
        while (group.contains(id)) {
            id = id.relative(direction);
        }
        return manager.getPlotAbs(plot.area(), id);
    }

    /**
     * 檢查能不能合併：目標必須是同一個擁有者的地皮，合併後必須是完整的長方形，總塊數不超過上限。
     */
    public MergeCheck checkMerge(Plot plot, Plot target, int maxPlots) {
        if (target == null) {
            return MergeCheck.NO_TARGET;
        }
        if (plot.owner() == null || !plot.owner().equals(target.owner())) {
            return MergeCheck.DIFFERENT_OWNER;
        }
        Set<PlotId> ids = mergedIds(plot, target);
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlotId id : ids) {
            minX = Math.min(minX, id.x());
            minZ = Math.min(minZ, id.z());
            maxX = Math.max(maxX, id.x());
            maxZ = Math.max(maxZ, id.z());
        }
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) != ids.size()) {
            return MergeCheck.NOT_RECTANGLE;
        }
        if (ids.size() > maxPlots) {
            return MergeCheck.TOO_LARGE;
        }
        return MergeCheck.OK;
    }

    private Set<PlotId> mergedIds(Plot plot, Plot target) {
        Set<PlotId> ids = new HashSet<>();
        for (Plot member : manager.getConnected(plot)) {
            ids.add(member.id());
        }
        for (Plot member : manager.getConnected(target)) {
            ids.add(member.id());
        }
        return ids;
    }

    /**
     * 合併兩個群組（呼叫前必須先通過 checkMerge）。
     * <ul>
     *     <li>長方形內所有相鄰的地皮互相合併，寫入資料庫</li>
     *     <li>名單合併：信任者、成員、禁止名單取聯集；時間與天氣沿用 plot 所在群組的設定</li>
     *     <li>地形：只把這次新併入的道路與圍牆鋪成地皮地板，外圍圍牆接起來；已經合併過的道路不動</li>
     * </ul>
     */
    public void merge(Plot plot, Plot target, Runnable whenDone) {
        World world = Bukkit.getWorld(plot.area());
        PlotWorld plotWorld = manager.world(plot.area());
        Set<PlotId> ids = mergedIds(plot, target);
        List<Plot> plots = new ArrayList<>();
        for (PlotId id : ids) {
            plots.add(manager.getPlotAbs(plot.area(), id));
        }
        Long time = plot.time();
        String weather = plot.weather();
        // 別名沿用執行指令那一邊；那一邊沒有的話用另一邊的
        String alias = plot.alias() != null ? plot.alias() : target.alias();

        // 合併前就屬於這些地皮的格子（地皮內部與已合併的道路），這些格子不會被重鋪
        int[] bounds = idBounds(plotWorld, ids);
        Set<Long> before = coveredColumns(world, ids, bounds);

        // 合併狀態
        for (Plot member : plots) {
            for (Direction direction : Direction.values()) {
                if (ids.contains(member.id().relative(direction))) {
                    member.merged()[direction.index()] = true;
                }
            }
            database.setMerged(member);
        }

        // 名單取聯集（優先順序與 addUser 相同：信任者 > 成員 > 禁止）
        Set<UUID> trusted = new HashSet<>();
        Set<UUID> members = new HashSet<>();
        Set<UUID> denied = new HashSet<>();
        for (Plot member : plots) {
            trusted.addAll(member.trusted());
            members.addAll(member.members());
            denied.addAll(member.denied());
        }
        members.removeAll(trusted);
        denied.removeAll(trusted);
        denied.removeAll(members);
        for (Plot member : plots) {
            syncUsers(member, Database.UserTable.TRUSTED, trusted);
            syncUsers(member, Database.UserTable.MEMBER, members);
            syncUsers(member, Database.UserTable.DENIED, denied);
        }
        setTime(plot, time);
        setWeather(plot, weather);
        setAlias(plot, alias);

        List<RegionRegenerator.Column> columns = mergeColumns(plotWorld, bounds, before);
        RegionRegenerator.run(plugin, world, plotWorld, columns, List.of(), entity -> false, blocksPerTick, whenDone);
    }

    /**
     * 一組地皮座標涵蓋的方塊範圍 {minX, minZ, maxX, maxZ}（地皮內部加上中間的道路）。
     */
    static int[] idBounds(PlotWorld plotWorld, Set<PlotId> ids) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlotId id : ids) {
            minX = Math.min(minX, plotWorld.bottomX(id));
            minZ = Math.min(minZ, plotWorld.bottomZ(id));
            maxX = Math.max(maxX, plotWorld.topX(id));
            maxZ = Math.max(maxZ, plotWorld.topZ(id));
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    /**
     * 範圍內目前屬於這些地皮的格子。
     */
    Set<Long> coveredColumns(World world, Set<PlotId> ids, int[] bounds) {
        Set<Long> covered = new HashSet<>();
        Location probe = new Location(world, 0, 0, 0);
        for (int x = bounds[0]; x <= bounds[2]; x++) {
            for (int z = bounds[1]; z <= bounds[3]; z++) {
                probe.setX(x);
                probe.setZ(z);
                PlotId id = manager.getPlotId(probe);
                if (id != null && ids.contains(id)) {
                    covered.add(columnKey(x, z));
                }
            }
        }
        return covered;
    }

    /**
     * 合併後要重鋪的格子（合併狀態更新後呼叫）：
     * 範圍內原本不屬於地皮的格子鋪成地皮地板；外圍一圈依合併狀態應該是圍牆的格子，鋪成已認領的圍牆（合併缺口兩端接起來）。
     */
    List<RegionRegenerator.Column> mergeColumns(PlotWorld plotWorld, int[] bounds, Set<Long> before) {
        List<RegionRegenerator.Column> columns = new ArrayList<>();
        for (int x = bounds[0] - 1; x <= bounds[2] + 1; x++) {
            for (int z = bounds[1] - 1; z <= bounds[3] + 1; z++) {
                boolean ring = x < bounds[0] || x > bounds[2] || z < bounds[1] || z > bounds[3];
                if (!ring) {
                    if (!before.contains(columnKey(x, z))) {
                        columns.add(new RegionRegenerator.Column(x, z, PlotWorld.CellType.PLOT));
                    }
                } else if (roadCellType(plotWorld, x, z) == PlotWorld.CellType.WALL) {
                    columns.add(new RegionRegenerator.Column(x, z, PlotWorld.CellType.WALL, true));
                }
            }
        }
        return columns;
    }

    private void syncUsers(Plot plot, Database.UserTable table, Set<UUID> wanted) {
        Set<UUID> current = set(plot, table);
        for (UUID uuid : new ArrayList<>(current)) {
            if (!wanted.contains(uuid)) {
                current.remove(uuid);
                database.removeUser(plot, table, uuid);
            }
        }
        for (UUID uuid : wanted) {
            if (current.add(uuid)) {
                database.addUser(plot, table, uuid);
            }
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
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
