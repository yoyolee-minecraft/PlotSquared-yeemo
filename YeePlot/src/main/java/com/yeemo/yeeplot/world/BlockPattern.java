package com.yeemo.yeeplot.world;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * 解析 worlds.yml 裡的方塊設定，支援 PlotSquared 會寫出的幾種格式：
 * <ul>
 *     <li>單一方塊：{@code grass_block}、{@code minecraft:stone_slab[type=top]}</li>
 *     <li>舊版機率格式：{@code stone:50,dirt:50}</li>
 *     <li>WorldEdit 機率格式：{@code 50%stone,50%dirt}</li>
 * </ul>
 * 其他 WorldEdit 進階語法（例如 ##tag）不支援，會退回預設方塊。
 */
public final class BlockPattern {

    private final String input;
    private final BlockData[] blocks;
    private final int[] cumulative;
    private final int total;

    private BlockPattern(String input, List<BlockData> blocks, List<Integer> weights) {
        this.input = input;
        this.blocks = blocks.toArray(new BlockData[0]);
        this.cumulative = new int[weights.size()];
        int sum = 0;
        for (int i = 0; i < weights.size(); i++) {
            sum += weights.get(i);
            cumulative[i] = sum;
        }
        this.total = sum;
    }

    public static BlockPattern parse(String input, String fallback, Logger logger) {
        if (input != null && !input.isBlank()) {
            try {
                return parseStrict(input.trim());
            } catch (IllegalArgumentException e) {
                logger.warning("無法解析方塊設定 '" + input + "'，改用 " + fallback + "（" + e.getMessage() + "）");
            }
        }
        return parseStrict(fallback);
    }

    private static BlockPattern parseStrict(String input) {
        List<BlockData> blocks = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        // 以逗號切割，但忽略方括號內的逗號（方塊狀態）
        for (String entry : input.split(",(?![^\\[]*])")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int weight = 1;
            String block = entry;
            int percent = entry.indexOf('%');
            if (percent > 0) {
                weight = Integer.parseInt(entry.substring(0, percent).trim());
                block = entry.substring(percent + 1).trim();
            } else {
                int bracket = entry.indexOf('[');
                int colon = entry.lastIndexOf(':');
                if (colon > 0 && (bracket < 0 || colon > entry.lastIndexOf(']'))) {
                    String tail = entry.substring(colon + 1);
                    if (tail.chars().allMatch(Character::isDigit) && !tail.isEmpty()) {
                        weight = Integer.parseInt(tail);
                        block = entry.substring(0, colon);
                    }
                }
            }
            if (weight <= 0) {
                continue;
            }
            blocks.add(Bukkit.createBlockData(block.toLowerCase(java.util.Locale.ROOT)));
            weights.add(weight);
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("沒有任何方塊");
        }
        return new BlockPattern(input, blocks, weights);
    }

    public BlockData pick(Random random) {
        if (blocks.length == 1) {
            return blocks[0];
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return blocks[i];
            }
        }
        return blocks[blocks.length - 1];
    }

    @Override
    public String toString() {
        return input;
    }

}
