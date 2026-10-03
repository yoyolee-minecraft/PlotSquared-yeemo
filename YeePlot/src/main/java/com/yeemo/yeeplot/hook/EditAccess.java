package com.yeemo.yeeplot.hook;

import com.yeemo.yeeplot.plot.Plot;
import com.yeemo.yeeplot.plot.PlotManager;
import com.yeemo.yeeplot.world.PlotWorld;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldEdit 與 FAWE 共用的規則，與 PlotSquared 的 WEManager#getMask 相同：
 * <ul>
 *     <li>只能編輯「目前所在」的地皮（合併群組整個算進去）；站在道路上時沿用上一次所在的地皮</li>
 *     <li>擁有者與信任者可以編輯；成員需要 plots.worldedit.member 權限，且擁有者要在線</li>
 *     <li>高度限制在 worlds.yml 的可建築範圍內</li>
 * </ul>
 * 可能在非主執行緒呼叫。
 */
public final class EditAccess {

    public static final String BYPASS = "plots.worldedit.bypass";
    public static final String MEMBER = "plots.worldedit.member";

    private final PlotManager manager;
    private final Map<UUID, Plot> lastPlot = new ConcurrentHashMap<>();

    public EditAccess(PlotManager manager) {
        this.manager = manager;
    }

    public boolean isPlotWorld(String world) {
        return manager.isPlotWorld(world);
    }

    public boolean canEdit(Player player, Plot plot, boolean allowMember) {
        UUID uuid = player.getUniqueId();
        if (plot.owner() == null || plot.denied().contains(uuid)) {
            return false;
        }
        if (plot.isOwner(uuid) || plot.trusted().contains(uuid) || plot.trusted().contains(Plot.EVERYONE)) {
            return true;
        }
        return allowMember && plot.isAdded(uuid, manager.isOwnerOnline(plot));
    }

    /**
     * 取得玩家目前可以編輯的範圍；回傳 null 代表不能編輯任何地方。
     */
    public EditMask maskFor(Player player, boolean allowMember) {
        Location location = player.getLocation();
        String world = location.getWorld().getName();
        PlotWorld plotWorld = manager.world(world);
        if (plotWorld == null) {
            return null;
        }
        Plot plot = manager.getPlotAt(location);
        if (plot == null) {
            Plot last = lastPlot.get(player.getUniqueId());
            // 站在道路上時沿用上一塊地皮，但前提是那塊地皮還存在、而且在同一個世界
            if (last != null && last.area().equals(world) && manager.getPlotAbs(world, last.id()) == last) {
                plot = last;
            }
        }
        if (plot == null || !canEdit(player, plot, allowMember)) {
            return null;
        }
        lastPlot.put(player.getUniqueId(), plot);
        return new EditMask(world, manager.getGroupRects(plot), plotWorld.minBuildHeight, plotWorld.maxBuildHeight - 1);
    }

    public void forget(UUID uuid) {
        lastPlot.remove(uuid);
    }

    /**
     * 不可變的編輯範圍快照，可以安全地在 WorldEdit / FAWE 的工作執行緒使用。
     */
    public record EditMask(String world, List<int[]> rects, int minY, int maxY) {

        public boolean containsColumn(int x, int z) {
            for (int[] rect : rects) {
                if (x >= rect[0] && x <= rect[2] && z >= rect[1] && z <= rect[3]) {
                    return true;
                }
            }
            return false;
        }

        public boolean contains(int x, int y, int z) {
            return y >= minY && y <= maxY && containsColumn(x, z);
        }

    }

}
