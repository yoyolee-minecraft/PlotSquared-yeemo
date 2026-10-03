package com.plotsquared.lite.plot;

import com.plotsquared.lite.world.PlotWorld;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * 把一批欄位重新鋪成生成器的樣子，工作量分散到多個 tick，避免卡服。
 * 每一欄都從世界最低點處理到最高點，不受 worlds.yml 的生成高度限制，確保不留殘餘方塊。
 */
public final class RegionRegenerator {

    /**
     * 一欄要重鋪的內容。
     *
     * @param claimedWall 圍牆頂端是否使用已認領的方塊
     */
    public record Column(int x, int z, PlotWorld.CellType type, boolean claimedWall) {

        public Column(int x, int z, PlotWorld.CellType type) {
            this(x, z, type, false);
        }

    }

    private RegionRegenerator() {
    }

    /**
     * @param entityAreas  要清除實體的範圍
     * @param removeEntity 範圍內哪些實體要移除（玩家永遠不會被移除）
     */
    public static void run(
            Plugin plugin,
            World world,
            PlotWorld plotWorld,
            List<Column> columns,
            List<BoundingBox> entityAreas,
            Predicate<Entity> removeEntity,
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
                if (!(entity instanceof org.bukkit.entity.Player) && removeEntity.test(entity)) {
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
                        BlockData target = plotWorld.blockAt(column.type(), y, random, column.claimedWall());
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
