package com.plotsquared.lite.listener;

import com.plotsquared.lite.plot.Plot;
import com.plotsquared.lite.plot.PlotId;
import com.plotsquared.lite.plot.PlotManager;
import com.plotsquared.lite.world.PlotWorld;
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
        PlotId id = manager.getPlotId(location);
        if (id == null) {
            return player.hasPermission(action.node + ".road") ? Result.ALLOW : Result.DENY;
        }
        Plot plot = manager.getPlotAbs(world.name(), id);
        if (plot == null) {
            return player.hasPermission(action.node + ".unowned") ? Result.ALLOW : Result.DENY;
        }
        boolean added = plot.isAdded(player.getUniqueId(), manager.isOwnerOnline(plot));
        if (!added && !player.hasPermission(action.node + ".other")) {
            return Result.DENY;
        }
        if (action != Action.INTERACT) {
            int y = location.getBlockY();
            if ((y < world.minBuildHeight || y >= world.maxBuildHeight)
                    && !player.hasPermission("plots.admin.build.heightlimit")) {
                return Result.HEIGHT;
            }
        }
        return Result.ALLOW;
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
