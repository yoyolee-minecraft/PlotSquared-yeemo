package com.yeemo.yeeplot.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 經典地皮世界生成器，輸出與 PlotSquared 未使用道路模板 (road schematic) 時相同的地形。
 * 世界設定透過 Supplier 取得，讓 /plot reload 之後新生成的區塊會套用新設定。
 */
public final class PlotGenerator extends ChunkGenerator {

    private final Supplier<PlotWorld> world;

    public PlotGenerator(Supplier<PlotWorld> world) {
        this.world = world;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        PlotWorld plotWorld = world.get();
        if (plotWorld == null) {
            return;
        }
        int minY = Math.max(data.getMinHeight(), plotWorld.minGenHeight);
        int maxY = Math.min(data.getMaxHeight() - 1, plotWorld.maxGenHeight);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                PlotWorld.CellType type = plotWorld.cellType(baseX + x, baseZ + z);
                int top = switch (type) {
                    case ROAD -> plotWorld.roadHeight;
                    case WALL -> plotWorld.wallHeight + 1;
                    case PLOT -> plotWorld.plotHeight;
                };
                top = Math.min(top, maxY);
                for (int y = minY; y <= top; y++) {
                    BlockData block = plotWorld.blockAt(type, y, random);
                    if (block != null) {
                        data.setBlock(x, y, z, block);
                    }
                }
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        PlotWorld plotWorld = world.get();
        if (plotWorld == null) {
            return null;
        }
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
                PlotWorld current = world.get();
                return current != null ? current.biome : plotWorld.biome;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo worldInfo) {
                return Collections.singletonList(plotWorld.biome);
            }
        };
    }

    @Override
    public Location getFixedSpawnLocation(World bukkitWorld, Random random) {
        PlotWorld plotWorld = world.get();
        int y = plotWorld != null ? plotWorld.roadHeight + 1 : 64;
        return new Location(bukkitWorld, 0.5, y, 0.5);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

}
