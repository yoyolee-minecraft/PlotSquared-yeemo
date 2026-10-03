package com.yeemo.yeeplot.listener;

import com.yeemo.yeeplot.plot.Plot;
import com.yeemo.yeeplot.plot.PlotId;
import com.yeemo.yeeplot.plot.PlotManager;
import com.yeemo.yeeplot.world.PlotWorld;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 判斷玩家能否在某個位置建築、破壞或互動。
 * 權限節點沿用 PlotSquared，讓現有權限組設定不用修改。
 */
public final class PlotPermissions {

    public enum Action {
        BUILD("plots.admin.build"),
        DESTROY("plots.admin.destroy"),
        INTERACT("plots.admin.interact");

        private final String node;

        Action(String node) {
            this.node = node;
        }
    }

    public enum Result {
        ALLOW, DENY, HEIGHT
    }

    private final PlotManager manager;

    public PlotPermissions(PlotManager manager) {
        this.manager = manager;
    }

    public Result check(Player player, Location location, Action action) {
        if (location.getWorld() == null) {
            return Result.ALLOW;
        }
        PlotWorld world = manager.world(location.getWorld().getName());
        if (world == null) {
            return Result.ALLOW;
        }
        if (!checkColumn(player, location, action)) {
            return Result.DENY;
        }
        if (action != Action.INTERACT && !inBuildHeight(player, world, location.getBlockY())) {
            return Result.HEIGHT;
        }
        return Result.ALLOW;
    }

    /**
     * 只判斷平面位置（道路、未認領、別人的地皮），不檢查高度。Axiom 逐區段檢查時使用。
     */
    public boolean checkColumn(Player player, Location location, Action action) {
        PlotId id = manager.getPlotId(location);
        if (id == null) {
            return player.hasPermission(action.node + ".road");
        }
        Plot plot = manager.getPlotAbs(location.getWorld().getName(), id);
        if (plot == null) {
            return player.hasPermission(action.node + ".unowned");
        }
        return plot.isAdded(player.getUniqueId(), manager.isOwnerOnline(plot))
                || player.hasPermission(action.node + ".other");
    }

    public boolean inBuildHeight(Player player, PlotWorld world, int y) {
        return y >= world.minBuildHeight && y < world.maxBuildHeight
                || player.hasPermission("plots.admin.build.heightlimit");
    }

    public boolean canEnter(Player player, Plot plot) {
        if (plot == null) {
            return true;
        }
        if (!plot.isDenied(player.getUniqueId(), manager.isOwnerOnline(plot))) {
            return true;
        }
        return player.hasPermission("plots.admin.entry.denied");
    }

}
