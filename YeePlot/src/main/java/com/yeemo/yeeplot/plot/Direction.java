package com.yeemo.yeeplot.plot;

/**
 * 與 PlotSquared 相同的方向索引：0 北 (-z)、1 東 (+x)、2 南 (+z)、3 西 (-x)。
 * 資料庫中 merged 欄位的位元順序依賴這個索引，不可更動。
 */
public enum Direction {
    NORTH(0, 0, -1),
    EAST(1, 1, 0),
    SOUTH(2, 0, 1),
    WEST(3, -1, 0);

    private final int index;
    private final int dx;
    private final int dz;

    Direction(int index, int dx, int dz) {
        this.index = index;
        this.dx = dx;
        this.dz = dz;
    }

    public int index() {
        return index;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public Direction opposite() {
        return values()[(index + 2) % 4];
    }

}
