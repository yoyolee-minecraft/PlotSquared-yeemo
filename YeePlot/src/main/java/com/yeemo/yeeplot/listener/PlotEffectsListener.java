package com.yeemo.yeeplot.listener;

import com.yeemo.yeeplot.plot.Plot;
import com.yeemo.yeeplot.plot.PlotManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地皮的時間與天氣（PlotSquared 的 time、weather flag）。
 * 只改變玩家自己看到的時間與天氣（setPlayerTime／setPlayerWeather），不影響世界本身與其他玩家。
 */
public final class PlotEffectsListener implements Listener {

    /**
     * 玩家目前套用的效果，用來判斷是否需要更新，避免每一步都重設。
     */
    private record Applied(Long time, String weather) {
    }

    private static final Applied NONE = new Applied(null, null);

    private final Plugin plugin;
    private final PlotManager manager;
    private final Map<UUID, Applied> applied = new ConcurrentHashMap<>();

    public PlotEffectsListener(Plugin plugin, PlotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * 依玩家目前所在位置，套用或恢復時間與天氣。
     */
    public void update(Player player, Location location) {
        Plot plot = location.getWorld() != null && manager.isPlotWorld(location.getWorld().getName())
                ? manager.getPlotAt(location) : null;
        Applied target = plot == null ? NONE : new Applied(plot.time(), plot.weather());
        Applied current = applied.getOrDefault(player.getUniqueId(), NONE);
        if (target.equals(current)) {
            return;
        }
        if (!Objects.equals(target.time(), current.time())) {
            if (target.time() == null) {
                player.resetPlayerTime();
            } else {
                // relative = false：時間固定不動，與 PlotSquared 相同
                player.setPlayerTime(target.time(), false);
            }
        }
        if (!Objects.equals(target.weather(), current.weather())) {
            if (target.weather() == null) {
                player.resetPlayerWeather();
            } else {
                player.setPlayerWeather("rain".equals(target.weather()) ? WeatherType.DOWNFALL : WeatherType.CLEAR);
            }
        }
        if (target.equals(NONE)) {
            applied.remove(player.getUniqueId());
        } else {
            applied.put(player.getUniqueId(), target);
        }
    }

    /**
     * 地皮設定改變後，立即更新所有在線玩家（只會真的改到站在那塊地皮上的人）。
     */
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player, player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        update(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            update(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 等玩家完全進入世界後再套用
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                update(player, player.getLocation());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        applied.remove(event.getPlayer().getUniqueId());
    }

}
