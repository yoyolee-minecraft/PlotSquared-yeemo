package com.plotsquared.lite.listener;

import com.plotsquared.lite.Messages;
import com.plotsquared.lite.plot.Plot;
import com.plotsquared.lite.plot.PlotId;
import com.plotsquared.lite.plot.PlotManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Wither;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 地皮保護：建築、破壞、互動、液體與活塞跨界、爆炸、禁止進入。
 */
public final class ProtectionListener implements Listener {

    private final PlotManager manager;
    private final PlotPermissions permissions;
    private final Messages messages;
    private final boolean disablePvp;

    public ProtectionListener(PlotManager manager, PlotPermissions permissions, Messages messages, boolean disablePvp) {
        this.manager = manager;
        this.permissions = permissions;
        this.messages = messages;
        this.disablePvp = disablePvp;
    }

    private boolean deny(Player player, Location location, PlotPermissions.Action action) {
        PlotPermissions.Result result = permissions.check(player, location, action);
        if (result == PlotPermissions.Result.ALLOW) {
            return false;
        }
        messages.send(player, result == PlotPermissions.Result.HEIGHT ? "build-height" : "build-denied");
        return true;
    }

    private boolean inPlotWorld(Location location) {
        return location.getWorld() != null && manager.isPlotWorld(location.getWorld().getName());
    }

    /**
     * 區域代號：已認領地皮回傳基準地皮，未認領地皮回傳座標，道路回傳 null。
     * 兩個位置代號相同（且不是 null）才允許方塊、液體在之間移動。
     */
    private Object regionKey(Location location) {
        PlotId id = manager.getPlotId(location);
        if (id == null) {
            return null;
        }
        Plot plot = manager.getPlotAbs(location.getWorld().getName(), id);
        return plot != null ? manager.getBasePlot(plot) : id;
    }

    private boolean sameRegion(Location a, Location b) {
        Object key = regionKey(a);
        return key != null && key.equals(regionKey(b));
    }

    // ---------------------------------------------------------------- 玩家建築與互動

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation(), PlotPermissions.Action.DESTROY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation(), PlotPermissions.Action.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (deny(event.getPlayer(), target.getLocation(), PlotPermissions.Action.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlockClicked().getLocation(), PlotPermissions.Action.DESTROY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !inPlotWorld(block.getLocation())) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getAction() == Action.PHYSICAL) {
            // 踩壞耕地、壓力板、絆線、海龜蛋
            if (permissions.check(player, block.getLocation(), PlotPermissions.Action.INTERACT)
                    != PlotPermissions.Result.ALLOW) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (block.getType().isInteractable() && event.useInteractedBlock() != Event.Result.DENY) {
            if (deny(player, block.getLocation(), PlotPermissions.Action.INTERACT)) {
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                return;
            }
        }
        ItemStack item = event.getItem();
        if (item != null && modifiesWorld(item.getType())) {
            Location target = block.getRelative(event.getBlockFace()).getLocation();
            if (deny(player, block.getLocation(), PlotPermissions.Action.BUILD)
                    || permissions.check(player, target, PlotPermissions.Action.BUILD) != PlotPermissions.Result.ALLOW) {
                event.setUseItemInHand(Event.Result.DENY);
                event.setUseInteractedBlock(Event.Result.DENY);
            }
        }
    }

    /**
     * 右鍵方塊時會改變世界、但不會觸發 BlockPlaceEvent 的物品。
     */
    private static boolean modifiesWorld(Material type) {
        String name = type.name();
        return name.endsWith("_HOE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_SPAWN_EGG") || name.endsWith("_BOAT") || name.endsWith("_RAFT")
                || name.endsWith("MINECART") || name.endsWith("_DYE")
                || type == Material.BONE_MEAL || type == Material.ARMOR_STAND || type == Material.END_CRYSTAL
                || type == Material.FLINT_AND_STEEL || type == Material.FIRE_CHARGE || type == Material.HONEYCOMB
                || type == Material.INK_SAC || type == Material.GLOW_INK_SAC || type == Material.SHEARS
                || type == Material.BRUSH;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof Player) {
            return;
        }
        if (inPlotWorld(entity.getLocation())
                && deny(event.getPlayer(), entity.getLocation(), PlotPermissions.Action.INTERACT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked().getLocation(), PlotPermissions.Action.INTERACT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!inPlotWorld(victim.getLocation())) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            attacker = player;
        }
        if (attacker == null) {
            return;
        }
        if (victim instanceof Player) {
            if (disablePvp && !attacker.hasPermission("plots.admin.pvp")) {
                event.setCancelled(true);
            }
            return;
        }
        if (deny(attacker, victim.getLocation(), PlotPermissions.Action.DESTROY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        if (remover instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            remover = shooter;
        }
        if (remover instanceof Player player) {
            if (deny(player, event.getEntity().getLocation(), PlotPermissions.Action.DESTROY)) {
                event.setCancelled(true);
            }
        } else if (inPlotWorld(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && deny(player, event.getEntity().getLocation(), PlotPermissions.Action.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getAttacker() instanceof Player player
                && deny(player, event.getVehicle().getLocation(), PlotPermissions.Action.DESTROY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Location location = event.getBlock().getLocation();
        if (!inPlotWorld(location)) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null) {
            if (deny(player, location, PlotPermissions.Action.BUILD)) {
                event.setCancelled(true);
            }
            return;
        }
        Block source = event.getIgnitingBlock();
        if (source != null && !sameRegion(source.getLocation(), location)) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- 環境（不讓東西跨越地皮邊界）

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Location from = event.getBlock().getLocation();
        if (inPlotWorld(from) && !sameRegion(from, event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Location to = event.getBlock().getLocation();
        if (inPlotWorld(to) && !sameRegion(event.getSource().getLocation(), to)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection().getModX(),
                event.getDirection().getModY(), event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonCrosses(event.getBlock(), event.getBlocks(), event.getDirection().getModX(),
                event.getDirection().getModY(), event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    private boolean pistonCrosses(Block piston, List<Block> blocks, int dx, int dy, int dz) {
        Location origin = piston.getLocation();
        if (!inPlotWorld(origin)) {
            return false;
        }
        Object key = regionKey(origin);
        if (key == null) {
            return true;
        }
        Location head = origin.clone().add(dx, dy, dz);
        if (!key.equals(regionKey(head))) {
            return true;
        }
        for (Block block : blocks) {
            Location location = block.getLocation();
            if (!key.equals(regionKey(location)) || !key.equals(regionKey(location.clone().add(dx, dy, dz)))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.getLocation(), event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.getBlock().getLocation(), event.blockList());
    }

    private void filterExplosion(Location origin, List<Block> blocks) {
        if (!inPlotWorld(origin)) {
            return;
        }
        Object key = regionKey(origin);
        if (key == null) {
            blocks.clear();
            return;
        }
        blocks.removeIf(block -> !key.equals(regionKey(block.getLocation())));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (!inPlotWorld(event.getBlock().getLocation())) {
            return;
        }
        if (entity instanceof Player player) {
            if (deny(player, event.getBlock().getLocation(), PlotPermissions.Action.BUILD)) {
                event.setCancelled(true);
            }
        } else if (entity instanceof Enderman || entity instanceof Wither || entity instanceof Ravager
                || entity instanceof Silverfish) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Location origin = event.getLocation();
        if (!inPlotWorld(origin)) {
            return;
        }
        Object key = regionKey(origin);
        List<BlockState> blocks = event.getBlocks();
        if (key == null) {
            blocks.clear();
            return;
        }
        blocks.removeIf(state -> !key.equals(regionKey(state.getLocation())));
    }

    // ---------------------------------------------------------------- 禁止進入

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !inPlotWorld(to)) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        Plot plot = manager.getPlotAt(to);
        if (plot == null || permissions.canEnter(event.getPlayer(), plot)) {
            return;
        }
        Plot fromPlot = from.getWorld() == to.getWorld() ? manager.getPlotAt(from) : null;
        if (fromPlot != null && manager.getBasePlot(fromPlot) == manager.getBasePlot(plot)) {
            // 已經在裡面（例如剛被加入禁止名單），送回世界出生點
            event.setTo(to.getWorld().getSpawnLocation());
        } else {
            event.setCancelled(true);
        }
        messages.send(event.getPlayer(), "cannot-enter");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || !inPlotWorld(to)) {
            return;
        }
        Plot plot = manager.getPlotAt(to);
        if (plot != null && !permissions.canEnter(event.getPlayer(), plot)) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "cannot-enter");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location location = player.getLocation();
        if (!inPlotWorld(location)) {
            return;
        }
        Plot plot = manager.getPlotAt(location);
        if (plot != null && !permissions.canEnter(player, plot)) {
            player.teleport(location.getWorld().getSpawnLocation());
        }
    }

}
