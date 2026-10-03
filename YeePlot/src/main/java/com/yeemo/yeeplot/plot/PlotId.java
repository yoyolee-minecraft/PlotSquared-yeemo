package com.yeemo.yeeplot.plot;

/**
 * 地皮座標。x 對應資料庫的 plot_id_x，z 對應 plot_id_z。
 */
public record PlotId(int x, int z) {

    public static PlotId parse(String input) {
        if (input == null) {
            return null;
        }
        String[] split = input.split("[;,]");
        if (split.length != 2) {
            return null;
        }
        try {
            return new PlotId(Integer.parseInt(split[0].trim()), Integer.parseInt(split[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public PlotId relative(Direction direction) {
        return new PlotId(x + direction.dx(), z + direction.dz());
    }

    @Override
    public String toString() {
        return x + ";" + z;
    }

}
