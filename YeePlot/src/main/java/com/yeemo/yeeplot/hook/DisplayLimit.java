package com.yeemo.yeeplot.hook;

import com.yeemo.yeeplot.plot.Plot;
import com.yeemo.yeeplot.plot.PlotManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 每塊地皮的展示實體（方塊、物品、文字展示）數量上限，避免大量展示實體造成卡頓。
 * 上限以「每塊地皮」計算，合併群組依塊數累加。只計算已載入區塊中的實體。
 */
public final class DisplayLimit {

    public static final String BYPASS = "plots.admin.displaylimit";

    private final PlotManager manager;
    private volatile int perPlot;

    public DisplayLimit(PlotManager manager, int perPlot) {
        this.manager = manager;
        this.perPlot = perPlot;
    }

    public void setPerPlot(int perPlot) {
        this.perPlot = perPlot;
    }

    /**
     * @return 0 或負數代表不限制
     */
    public int perPlot() {
        return perPlot;
    }

    public int limit(Plot plot) {
        return perPlot * manager.getConnected(plot).size();
    }

    /**
     * 群組範圍內（包含合併道路、整個世界高度）的展示實體數量。
     */
    public int count(Plot plot) {
        World world = Bukkit.getWorld(plot.area());
        if (world == null) {
            return 0;
        }
        List<int[]> rects = manager.getGroupRects(plot);
        Set<UUID> seen = new HashSet<>();
        for (int[] rect : rects) {
            BoundingBox box = new BoundingBox(rect[0], world.getMinHeight(), rect[1],
                    rect[2] + 1, world.getMaxHeight(), rect[3] + 1);
            for (Entity entity : world.getNearbyEntities(box)) {
                if (entity instanceof Display) {
                    seen.add(entity.getUniqueId());
                }
            }
        }
        return seen.size();
    }

    /**
     * 新的展示實體（已經加入世界）是否讓所在地皮超過上限。
     */
    public boolean exceeds(Display display) {
        if (perPlot <= 0) {
            return false;
        }
        Plot plot = manager.getPlotAt(display.getLocation());
        return plot != null && count(plot) > limit(plot);
    }

}
