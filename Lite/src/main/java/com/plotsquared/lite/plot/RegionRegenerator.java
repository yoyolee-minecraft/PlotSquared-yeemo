package com.plotsquared.lite.plot;

import com.plotsquared.lite.world.PlotWorld;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 把一批欄位重新鋪成生成器的樣子，工作量分散到多個 tick，避免卡服。
 */
public final class RegionRegenerator {

    /**
     * 一欄要重鋪的內容。
     */
    public record Column(int x, int z, PlotWorld.CellType type) {
    }

    private RegionRegenerator() {
    }

    public static void run(
            Plugin plugin,
            World world,
            PlotWorld plotWorld,
            List<Column> columns,
            List<BoundingBox> entityAreas,
            int blocksPerTick,
            Runnable whenDone
    ) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int height = maxY - minY + 1;
        int columnsPerTick = Math.max(1, blocksPerTick / Math.max(1, height));
        BlockData air = Material.AIR.createBlockData();

        for (BoundingBox box : entityAreas) {
            for (Entity entity : world.getNearbyEntities(box)) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }
        }

        List<Column> queue = new ArrayList<>(columns);
        new BukkitRunnable() {
            private int index = 0;

            @Override
            public void run() {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int end = Math.min(queue.size(), index + columnsPerTick);
                for (; index < end; index++) {
                    Column column = queue.get(index);
                    for (int y = minY; y <= maxY; y++) {
                        Block block = world.getBlockAt(column.x(), y, column.z());
                        BlockData target = plotWorld.blockAt(column.type(), y, random);
                        if (target == null) {
                            if (!block.getType().isAir()) {
                                block.setBlockData(air, false);
                            }
                        } else {
                            block.setBlockData(target, false);
                        }
                    }
                }
                if (index >= queue.size()) {
                    cancel();
                    if (whenDone != null) {
                        whenDone.run();
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

}
