package com.yeemo.yeeplot.hook;

import com.yeemo.yeeplot.Messages;
import com.yeemo.yeeplot.listener.PlotPermissions;
import com.yeemo.yeeplot.plot.Plot;
import com.yeemo.yeeplot.plot.PlotManager;
import com.yeemo.yeeplot.world.PlotWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * AxiomPaper 的地皮限制。
 * <p>
 * AxiomPaper 的 Axiom 編輯會直接寫入世界、不觸發 Bukkit 方塊事件；它內建的 PlotSquared 整合只認原版 PlotSquared。
 * 新版 AxiomPaper 提供 {@code Integration.registerCustomIntegration}，可以逐方塊與逐區段（16x16x16）檢查，
 * 這裡用反射接上，所以編譯時不需要 AxiomPaper，也能相容不同版本。
 * <p>
 * 舊版 AxiomPaper 沒有這個介面時，改用 AxiomModifyWorldEvent：地皮世界中直接禁止 Axiom 修改，
 * 只有 axiomadmin.bypass_region_checks 可以使用，寧可擋多也不留漏洞。
 */
public final class AxiomHook implements Listener {

    private static final String PACKAGE = "com.moulberry.axiom.";
    public static final String BYPASS = "axiomadmin.bypass_region_checks";

    private final Plugin plugin;
    private final PlotManager manager;
    private final PlotPermissions permissions;
    private final Messages messages;
    private final Logger logger;
    private final DisplayBounds displayBounds;
    private final DisplayLimit displayLimit;
    private final Map<UUID, DisplaySnapshot> snapshots = new ConcurrentHashMap<>();
    private boolean fineGrained;

    public AxiomHook(Plugin plugin, PlotManager manager, PlotPermissions permissions, Messages messages,
                     DisplayBounds displayBounds, DisplayLimit displayLimit) {
        this.plugin = plugin;
        this.manager = manager;
        this.permissions = permissions;
        this.messages = messages;
        this.logger = plugin.getLogger();
        this.displayBounds = displayBounds;
        this.displayLimit = displayLimit;
    }

    /**
     * 找出 AxiomPaper 插件（依主類別判斷，不依賴插件名稱）。
     */
    public static Plugin findAxiom(Plugin[] plugins) {
        for (Plugin candidate : plugins) {
            if (candidate.isEnabled() && candidate.getClass().getName().startsWith(PACKAGE)) {
                return candidate;
            }
        }
        return null;
    }

    public void register(Plugin axiom) {
        ClassLoader loader = axiom.getClass().getClassLoader();
        try {
            registerIntegration(loader);
            fineGrained = true;
            logger.info("已接上 AxiomPaper：玩家只能用 Axiom 編輯自己有權限的地皮");
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("這個版本的 AxiomPaper 沒有整合介面（" + e + "），改為在地皮世界禁止 Axiom 修改。"
                    + "更新 AxiomPaper 後可以讓玩家在自己的地皮使用 Axiom");
        }
        registerEvents(loader);
    }

    // ---------------------------------------------------------------- 逐方塊／逐區段檢查

    private void registerIntegration(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> integration = Class.forName(PACKAGE + "integration.Integration", true, loader);
        Class<?> customType = Class.forName(PACKAGE + "integration.Integration$CustomIntegration", true, loader);
        Class<?> checkerType = Class.forName(PACKAGE + "integration.SectionPermissionChecker", true, loader);
        Class<?> boxType = Class.forName(PACKAGE + "integration.Box", true, loader);

        Object allAllowed = checkerType.getField("ALL_ALLOWED").get(null);
        Object noneAllowed = checkerType.getField("NONE_ALLOWED").get(null);
        Method fromAllowedBoxes = checkerType.getMethod("fromAllowedBoxes", List.class);
        Constructor<?> boxConstructor = boxType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        Method register = integration.getMethod("registerCustomIntegration", Plugin.class, customType);
        SectionFactory factory = new SectionFactory(allAllowed, noneAllowed, fromAllowedBoxes, boxConstructor);

        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "canBreakBlock" -> canEdit((Player) args[0], ((Block) args[1]).getLocation(), PlotPermissions.Action.DESTROY);
            case "canPlaceBlock" -> canEdit((Player) args[0], (Location) args[1], PlotPermissions.Action.BUILD);
            case "checkSection" -> checkSection(factory, (Player) args[0], (World) args[1],
                    (Integer) args[2], (Integer) args[3], (Integer) args[4]);
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "YeePlot Axiom integration";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        Object custom = Proxy.newProxyInstance(loader, new Class<?>[]{customType}, handler);
        register.invoke(null, plugin, custom);
    }

    private boolean canEdit(Player player, Location location, PlotPermissions.Action action) {
        return permissions.check(player, location, action) == PlotPermissions.Result.ALLOW;
    }

    /**
     * 逐欄判斷這個 16x16x16 區段中哪些位置可以編輯，再合併成長方體交給 AxiomPaper。
     * 方塊座標相對於區段最小角（0~15），與 AxiomPaper 的 PlotSquared 整合相同。
     */
    private Object checkSection(SectionFactory factory, Player player, World world, int cx, int cy, int cz)
            throws ReflectiveOperationException {
        PlotWorld plotWorld = manager.world(world.getName());
        if (plotWorld == null) {
            return factory.allAllowed;
        }
        int baseX = cx << 4;
        int baseY = cy << 4;
        int baseZ = cz << 4;
        int minY = 0;
        int maxY = 15;
        if (!player.hasPermission("plots.admin.build.heightlimit")) {
            minY = Math.max(0, plotWorld.minBuildHeight - baseY);
            maxY = Math.min(15, plotWorld.maxBuildHeight - 1 - baseY);
            if (minY > maxY) {
                return factory.noneAllowed;
            }
        }
        Location probe = new Location(world, 0, baseY, 0);
        List<int[]> runs = new ArrayList<>();
        int allowedColumns = 0;
        for (int z = 0; z < 16; z++) {
            int runStart = -1;
            for (int x = 0; x <= 16; x++) {
                boolean allowed = false;
                if (x < 16) {
                    probe.setX(baseX + x);
                    probe.setZ(baseZ + z);
                    allowed = permissions.checkColumn(player, probe, PlotPermissions.Action.BUILD)
                            && permissions.checkColumn(player, probe, PlotPermissions.Action.DESTROY);
                }
                if (allowed) {
                    allowedColumns++;
                    if (runStart < 0) {
                        runStart = x;
                    }
                } else if (runStart >= 0) {
                    runs.add(new int[]{runStart, z, x - 1});
                    runStart = -1;
                }
            }
        }
        if (allowedColumns == 0) {
            return factory.noneAllowed;
        }
        if (allowedColumns == 256 && minY == 0 && maxY == 15) {
            return factory.allAllowed;
        }
        // 把相鄰列中範圍相同的段落合併，減少長方體數量
        List<Object> boxes = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            int[] run = runs.get(i);
            if (run == null) {
                continue;
            }
            int endZ = run[1];
            for (int j = i + 1; j < runs.size(); j++) {
                int[] next = runs.get(j);
                if (next != null && next[1] == endZ + 1 && next[0] == run[0] && next[2] == run[2]) {
                    endZ = next[1];
                    runs.set(j, null);
                } else if (next != null && next[1] > endZ + 1) {
                    break;
                }
            }
            boxes.add(factory.box(run[0], minY, run[1], run[2], maxY, endZ));
        }
        return factory.fromAllowedBoxes.invoke(null, boxes);
    }

    private record SectionFactory(Object allAllowed, Object noneAllowed, Method fromAllowedBoxes, Constructor<?> boxConstructor) {

        Object box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws ReflectiveOperationException {
            return boxConstructor.newInstance(minX, minY, minZ, maxX, maxY, maxZ);
        }

    }

    // ---------------------------------------------------------------- 事件（實體操作與舊版退路）

    private void registerEvents(ClassLoader loader) {
        listen(loader, "event.AxiomModifyWorldEvent", event -> {
            if (fineGrained) {
                return;
            }
            Player player = (Player) invoke(event, "getPlayer");
            World world = (World) invoke(event, "getWorld");
            if (player != null && world != null && manager.isPlotWorld(world.getName()) && !player.hasPermission(BYPASS)) {
                ((Cancellable) event).setCancelled(true);
                messages.send(player, "axiom-denied");
            }
        });
        listen(loader, "event.AxiomSpawnEntityEvent", event -> {
            checkEntity(event, PlotPermissions.Action.BUILD);
            checkDisplaySpawn(event);
        });
        listen(loader, "event.AxiomManipulateEntityEvent", event -> {
            checkEntity(event, PlotPermissions.Action.BUILD);
            snapshotDisplay(event);
        });
        listen(loader, "event.AxiomRemoveEntityEvent", event -> checkEntity(event, PlotPermissions.Action.DESTROY));
        listen(loader, "event.AxiomAfterManipulateEntityEvent", this::checkDisplayAfterManipulate, false);
    }

    // ---------------------------------------------------------------- 展示實體的外觀範圍

    /**
     * 調整前的展示實體狀態，調整後若超出範圍就還原成這個樣子。
     */
    private record DisplaySnapshot(Location location, Transformation transformation, Component text, int lineWidth) {
    }

    /**
     * 生成時：事件在實體加入世界後觸發，看得到完整的變換矩陣，超出範圍直接取消（AxiomPaper 會移除實體）。
     */
    private void checkDisplaySpawn(Event event) {
        if (((Cancellable) event).isCancelled()) {
            return;
        }
        Player player = (Player) invoke(event, "getPlayer");
        Entity entity = (Entity) invoke(event, "getEntity");
        if (player == null || !(entity instanceof Display display) || player.hasPermission(BYPASS)) {
            return;
        }
        if (!displayBounds.allowed(player, display)) {
            ((Cancellable) event).setCancelled(true);
            messages.send(player, "axiom-display-outside");
            return;
        }
        // 事件在實體加入世界之後觸發，所以計數已經包含這一個
        if (!player.hasPermission(DisplayLimit.BYPASS) && displayLimit.exceeds(display)) {
            ((Cancellable) event).setCancelled(true);
            Plot plot = manager.getPlotAt(display.getLocation());
            messages.send(player, "display-limit", "limit", String.valueOf(plot == null ? 0 : displayLimit.limit(plot)));
        }
    }

    /**
     * 調整前：AxiomPaper 還沒套用新的位置與變換矩陣，先記下目前的狀態。
     */
    private void snapshotDisplay(Event event) {
        if (((Cancellable) event).isCancelled()) {
            return;
        }
        Player player = (Player) invoke(event, "getPlayer");
        Entity entity = (Entity) invoke(event, "getEntity");
        if (player == null || !(entity instanceof Display display) || player.hasPermission(BYPASS)) {
            return;
        }
        Component text = display instanceof TextDisplay textDisplay ? textDisplay.text() : null;
        int lineWidth = display instanceof TextDisplay textDisplay ? textDisplay.getLineWidth() : 0;
        snapshots.put(display.getUniqueId(),
                new DisplaySnapshot(display.getLocation(), display.getTransformation(), text, lineWidth));
    }

    /**
     * 調整後：這個事件不能取消，所以超出範圍時把位置、變換矩陣與文字還原。
     */
    private void checkDisplayAfterManipulate(Event event) {
        Entity entity = (Entity) invoke(event, "getEntity");
        if (!(entity instanceof Display display)) {
            return;
        }
        DisplaySnapshot snapshot = snapshots.remove(display.getUniqueId());
        Player player = (Player) invoke(event, "getPlayer");
        if (snapshot == null || player == null || displayBounds.allowed(player, display)) {
            return;
        }
        display.setTransformation(snapshot.transformation());
        if (display instanceof TextDisplay textDisplay && snapshot.text() != null) {
            textDisplay.text(snapshot.text());
            textDisplay.setLineWidth(snapshot.lineWidth());
        }
        if (!display.isInsideVehicle()) {
            display.teleport(snapshot.location());
        }
        messages.send(player, "axiom-display-outside");
    }

    private void checkEntity(Event event, PlotPermissions.Action action) {
        Player player = (Player) invoke(event, "getPlayer");
        Entity entity = (Entity) invoke(event, "getEntity");
        if (player == null || entity == null || player.hasPermission(BYPASS)) {
            return;
        }
        if (!canEdit(player, entity.getLocation(), action)) {
            ((Cancellable) event).setCancelled(true);
        }
    }

    private interface EventHandlerFunction {

        void handle(Event event);

    }

    private void listen(ClassLoader loader, String className, EventHandlerFunction handler) {
        listen(loader, className, handler, true);
    }

    @SuppressWarnings("unchecked")
    private void listen(ClassLoader loader, String className, EventHandlerFunction handler, boolean cancellable) {
        try {
            Class<?> type = Class.forName(PACKAGE + className, true, loader);
            if (!Event.class.isAssignableFrom(type) || cancellable && !Cancellable.class.isAssignableFrom(type)) {
                return;
            }
            plugin.getServer().getPluginManager().registerEvent(
                    (Class<? extends Event>) type, this, EventPriority.LOW,
                    (listener, event) -> {
                        if (type.isInstance(event)) {
                            handler.handle(event);
                        }
                    },
                    plugin, true
            );
        } catch (ClassNotFoundException | LinkageError e) {
            logger.fine("AxiomPaper 沒有 " + className + "，略過");
        }
    }

    private static Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

}
