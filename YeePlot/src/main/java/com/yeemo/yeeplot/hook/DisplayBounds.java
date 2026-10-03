package com.yeemo.yeeplot.hook;

import com.yeemo.yeeplot.listener.PlotPermissions;
import com.yeemo.yeeplot.plot.PlotManager;
import com.yeemo.yeeplot.world.PlotWorld;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 展示實體（方塊、物品、文字展示）的實際外觀範圍。
 * <p>
 * 展示實體的位置點可以在地皮內，但透過變換矩陣（位移、旋轉、縮放）可以畫到很遠的地方，
 * 所以要用變換後的外形來判斷有沒有超出可建築的範圍。計算方式與 Minecraft 的 DisplayRenderer 相同：
 * 先套用變換矩陣，再套用實體本身的旋轉（FIXED）；會跟著鏡頭轉的展示實體則取所有可能方向的最大範圍。
 */
public final class DisplayBounds {

    /**
     * 文字展示每個像素等於多少格（Minecraft 文字展示的縮放比例）。
     */
    private static final float TEXT_PIXEL = 0.025f;
    /**
     * 一次檢查最多幾欄，避免超大展示實體拖慢伺服器；超過直接視為不允許。
     */
    private static final int MAX_COLUMNS = 65536;

    private final PlotManager manager;
    private final PlotPermissions permissions;
    private float itemSize = 1.0f;

    public DisplayBounds(PlotManager manager, PlotPermissions permissions) {
        this.manager = manager;
        this.permissions = permissions;
    }

    /**
     * 物品展示的外形大小（格）。一般物品是 1 格；資源包的自訂模型最大可以到 3 格。
     */
    public void setItemSize(float itemSize) {
        this.itemSize = Math.max(0.1f, itemSize);
    }

    /**
     * 玩家能不能擁有這個展示實體目前的外觀：外觀範圍內的每一欄都必須是玩家可以建築的地方。
     */
    public boolean allowed(Player player, Display display) {
        Location location = display.getLocation();
        if (location.getWorld() == null) {
            return true;
        }
        PlotWorld plotWorld = manager.world(location.getWorld().getName());
        if (plotWorld == null) {
            return true;
        }
        BoundingBox box = bounds(display);
        int minX = floor(box.getMinX());
        int minZ = floor(box.getMinZ());
        int maxX = Math.max(minX, ceilExclusive(box.getMaxX()));
        int maxZ = Math.max(minZ, ceilExclusive(box.getMaxZ()));
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_COLUMNS) {
            return false;
        }
        if (!permissions.inBuildHeight(player, plotWorld, floor(box.getMinY()))
                || !permissions.inBuildHeight(player, plotWorld, Math.max(floor(box.getMinY()), ceilExclusive(box.getMaxY())))) {
            return false;
        }
        Location probe = location.clone();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                probe.setX(x);
                probe.setZ(z);
                if (!permissions.checkColumn(player, probe, PlotPermissions.Action.BUILD)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 展示實體在世界中的外觀範圍。
     */
    public BoundingBox bounds(Display display) {
        float[][] corners = shape(display);
        Transformation transformation = display.getTransformation();
        Matrix4f matrix = new Matrix4f()
                .translation(transformation.getTranslation())
                .rotate(transformation.getLeftRotation())
                .scale(transformation.getScale())
                .rotate(transformation.getRightRotation());
        Location location = display.getLocation();
        Vector3f[] points = new Vector3f[8];
        int i = 0;
        for (float x : corners[0]) {
            for (float y : corners[1]) {
                for (float z : corners[2]) {
                    points[i++] = matrix.transformPosition(new Vector3f(x, y, z));
                }
            }
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        switch (display.getBillboard()) {
            case FIXED -> {
                // 與 DisplayRenderer 相同：rotationYXZ(-yaw, pitch, 0)
                Quaternionf rotation = new Quaternionf().rotationYXZ(
                        (float) Math.toRadians(-location.getYaw()), (float) Math.toRadians(location.getPitch()), 0f);
                for (Vector3f point : points) {
                    rotation.transform(point);
                    minX = Math.min(minX, point.x);
                    minY = Math.min(minY, point.y);
                    minZ = Math.min(minZ, point.z);
                    maxX = Math.max(maxX, point.x);
                    maxY = Math.max(maxY, point.y);
                    maxZ = Math.max(maxZ, point.z);
                }
            }
            case VERTICAL -> {
                // 只繞垂直軸跟著鏡頭轉：水平方向取最大半徑，高度照算
                float radius = 0;
                for (Vector3f point : points) {
                    radius = Math.max(radius, (float) Math.sqrt(point.x * point.x + point.z * point.z));
                    minY = Math.min(minY, point.y);
                    maxY = Math.max(maxY, point.y);
                }
                minX = minZ = -radius;
                maxX = maxZ = radius;
            }
            default -> {
                // HORIZONTAL、CENTER 會往任意方向轉，取最大半徑的球
                float radius = 0;
                for (Vector3f point : points) {
                    radius = Math.max(radius, point.length());
                }
                minX = minY = minZ = -radius;
                maxX = maxY = maxZ = radius;
            }
        }
        return new BoundingBox(
                location.getX() + minX, location.getY() + minY, location.getZ() + minZ,
                location.getX() + maxX, location.getY() + maxY, location.getZ() + maxZ
        );
    }

    /**
     * 展示實體在變換之前的外形：{x 範圍, y 範圍, z 範圍}。
     */
    private float[][] shape(Display display) {
        if (display instanceof BlockDisplay) {
            // 方塊展示從位置點往正方向畫一個方塊
            return new float[][]{{0, 1}, {0, 1}, {0, 1}};
        }
        if (display instanceof TextDisplay text) {
            String plain = PlainTextComponentSerializer.plainText().serialize(text.text());
            int lineWidth = Math.max(1, text.getLineWidth());
            int longest = 0;
            int lines = 0;
            for (String line : plain.split("\n", -1)) {
                // 以每個字 6 像素估算（中文字較寬，取 9 像素），超過行寬會換行
                int width = 0;
                for (int c = 0; c < line.length(); c++) {
                    width += line.charAt(c) > 0x2E80 ? 9 : 6;
                }
                lines += Math.max(1, (int) Math.ceil(width / (double) lineWidth));
                longest = Math.max(longest, Math.min(width, lineWidth));
            }
            float halfWidth = (longest + 2) * TEXT_PIXEL / 2f;
            float height = (lines * 10 + 2) * TEXT_PIXEL;
            return new float[][]{{-halfWidth, halfWidth}, {0, height}, {-0.01f, 0.01f}};
        }
        if (display instanceof ItemDisplay) {
            float half = itemSize / 2f;
            return new float[][]{{-half, half}, {-half, half}, {-half, half}};
        }
        return new float[][]{{-0.5f, 0.5f}, {-0.5f, 0.5f}, {-0.5f, 0.5f}};
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /**
     * 範圍上限所在的方塊座標（剛好落在方塊邊界上時不算進下一格）。
     */
    private static int ceilExclusive(double value) {
        return (int) Math.ceil(value) - 1;
    }

}
