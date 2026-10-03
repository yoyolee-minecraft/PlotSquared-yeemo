package com.plotsquared.lite.command;

import com.plotsquared.lite.Messages;
import com.plotsquared.lite.PlotSquaredLite;
import com.plotsquared.lite.plot.Direction;
import com.plotsquared.lite.plot.Plot;
import com.plotsquared.lite.plot.PlotId;
import com.plotsquared.lite.plot.PlotManager;
import com.plotsquared.lite.plot.PlotService;
import com.plotsquared.lite.storage.Database;
import com.plotsquared.lite.world.PlotWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /plot 指令。子指令名稱與權限節點沿用 PlotSquared。
 */
public final class PlotCommand implements TabExecutor {

    private static final long CONFIRM_TIMEOUT = 20_000L;
    private static final List<String> SUBCOMMANDS = List.of(
            "help", "claim", "auto", "home", "visit", "tp", "info", "list", "trust", "add", "remove",
            "deny", "undeny", "sethome", "clear", "delete", "setowner", "fixroads", "reload", "confirm"
    );
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("c", "claim"), Map.entry("a", "auto"), Map.entry("h", "home"),
            Map.entry("v", "visit"), Map.entry("i", "info"), Map.entry("t", "trust"),
            Map.entry("r", "remove"), Map.entry("untrust", "remove"), Map.entry("d", "deny"),
            Map.entry("ud", "undeny"), Map.entry("dispose", "delete"), Map.entry("unclaim", "delete"),
            Map.entry("del", "delete"), Map.entry("l", "list"), Map.entry("teleport", "tp"),
            Map.entry("seth", "sethome"), Map.entry("?", "help")
    );

    private final PlotSquaredLite plugin;
    private final Map<UUID, PendingConfirm> pending = new HashMap<>();

    private record PendingConfirm(Runnable action, long expires) {
    }

    public PlotCommand(PlotSquaredLite plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return plugin.messages();
    }

    private PlotManager manager() {
        return plugin.plotManager();
    }

    private PlotService service() {
        return plugin.plotService();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            messages().send(sender, "help", "label", label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        sub = ALIASES.getOrDefault(sub, sub);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (sub.equals("reload")) {
            if (checkPermission(sender, "plots.admin.command.reload")) {
                plugin.reload();
                messages().send(sender, "reloaded");
            }
            return true;
        }
        if (sub.equals("help")) {
            messages().send(sender, "help", "label", label);
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return true;
        }
        switch (sub) {
            case "claim" -> claim(player);
            case "auto" -> auto(player);
            case "home" -> home(player, rest, false);
            case "visit" -> home(player, rest, true);
            case "tp" -> teleportToId(player, rest);
            case "info" -> info(player);
            case "list" -> list(player, rest);
            case "trust" -> addUser(player, rest, Database.UserTable.TRUSTED, "plots.trust", "trusted");
            case "add" -> addUser(player, rest, Database.UserTable.MEMBER, "plots.add", "member-added");
            case "deny" -> addUser(player, rest, Database.UserTable.DENIED, "plots.deny", "denied");
            case "remove" -> remove(player, rest);
            case "undeny" -> undeny(player, rest);
            case "sethome" -> setHome(player, rest);
            case "clear" -> clear(player, label);
            case "delete" -> delete(player, label);
            case "setowner" -> setOwner(player, rest);
            case "fixroads" -> fixRoads(player, rest, label);
            case "confirm" -> confirm(player);
            default -> messages().send(sender, "help", "label", label);
        }
        return true;
    }

    // ---------------------------------------------------------------- 共用工具

    private boolean checkPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages().send(sender, "no-permission", "permission", permission);
        return false;
    }

    /**
     * 取得玩家腳下的已認領地皮，並檢查擁有者或管理員權限。失敗時已送出訊息並回傳 null。
     */
    private Plot ownedPlotHere(Player player, String adminPermission) {
        Location location = player.getLocation();
        if (!manager().isPlotWorld(location.getWorld().getName())) {
            messages().send(player, "not-in-plot-world");
            return null;
        }
        PlotId id = manager().getPlotId(location);
        if (id == null) {
            messages().send(player, "not-in-plot");
            return null;
        }
        Plot plot = manager().getPlotAbs(location.getWorld().getName(), id);
        if (plot == null) {
            messages().send(player, "plot-unowned");
            return null;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission(adminPermission)) {
            messages().send(player, "not-owner");
            return null;
        }
        return plot;
    }

    private int allowedPlots(Player player) {
        if (player.hasPermission("plots.admin") || player.hasPermission("plots.*") || player.hasPermission("plots.plot.*")) {
            return Integer.MAX_VALUE;
        }
        for (int i = plugin.maxPlotsPermission(); i > 0; i--) {
            if (player.hasPermission("plots.plot." + i)) {
                return i;
            }
        }
        return 0;
    }

    private boolean checkLimit(Player player, String world) {
        int allowed = allowedPlots(player);
        int owned = manager().countOwned(player.getUniqueId(), plugin.globalLimit() ? null : world);
        if (owned >= allowed) {
            messages().send(player, "limit-reached", "amount", String.valueOf(allowed));
            return false;
        }
        return true;
    }

    /**
     * 以名稱解析玩家 UUID。「*」代表所有人，與 PlotSquared 相同。
     */
    private UUID resolve(String name) {
        if (name.equals("*")) {
            return Plot.EVERYONE;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached.getUniqueId();
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String name(UUID uuid) {
        if (uuid == null) {
            return "-";
        }
        if (uuid.equals(Plot.EVERYONE)) {
            return "*";
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString();
    }

    private static String names(Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return "-";
        }
        return uuids.stream().map(PlotCommand::name).collect(Collectors.joining(", "));
    }

    private void teleport(Player player, Plot plot) {
        boolean member = plot.isAdded(player.getUniqueId(), manager().isOwnerOnline(plot));
        Location home = service().getHome(plot, member);
        if (home != null) {
            messages().send(player, "teleporting");
            player.teleport(home);
        }
    }

    private void requireConfirm(Player player, String label, Runnable action) {
        pending.put(player.getUniqueId(), new PendingConfirm(action, System.currentTimeMillis() + CONFIRM_TIMEOUT));
        messages().send(player, "confirm", "label", label);
    }

    // ---------------------------------------------------------------- 子指令

    private void claim(Player player) {
        if (!checkPermission(player, "plots.claim")) {
            return;
        }
        Location location = player.getLocation();
        String world = location.getWorld().getName();
        if (!manager().isPlotWorld(world)) {
            messages().send(player, "not-in-plot-world");
            return;
        }
        PlotId id = manager().world(world).plotIdAbs(location.getBlockX(), location.getBlockZ());
        if (id == null) {
            messages().send(player, "not-in-plot");
            return;
        }
        if (manager().getPlotAbs(world, id) != null || service().isBusy(world, id)) {
            messages().send(player, "plot-claimed");
            return;
        }
        if (!checkLimit(player, world)) {
            return;
        }
        Plot plot = service().claim(world, id, player.getUniqueId());
        messages().send(player, "claimed", "id", plot.id().toString());
        if (plugin.teleportOnClaim()) {
            teleport(player, plot);
        }
    }

    private void auto(Player player) {
        if (!checkPermission(player, "plots.auto")) {
            return;
        }
        String world = player.getWorld().getName();
        if (!manager().isPlotWorld(world)) {
            if (manager().worlds().size() == 1) {
                world = manager().worlds().iterator().next().name();
            } else {
                messages().send(player, "not-in-plot-world");
                return;
            }
        }
        if (Bukkit.getWorld(world) == null) {
            messages().send(player, "not-in-plot-world");
            return;
        }
        if (!checkLimit(player, world)) {
            return;
        }
        PlotId id = service().findFreePlot(world);
        if (id == null) {
            messages().send(player, "no-free-plot");
            return;
        }
        Plot plot = service().claim(world, id, player.getUniqueId());
        messages().send(player, "claimed", "id", plot.id().toString());
        teleport(player, plot);
    }

    /**
     * /plot home [編號|玩家] [編號] 與 /plot visit &lt;玩家&gt; [編號]。
     */
    private void home(Player player, String[] args, boolean visit) {
        UUID target = player.getUniqueId();
        String targetName = player.getName();
        int index = 1;
        int next = 0;
        if (args.length > next && !isInteger(args[next])) {
            target = resolve(args[next]);
            targetName = args[next];
            next++;
            if (target == null) {
                messages().send(player, "player-not-found", "player", targetName);
                return;
            }
        } else if (visit) {
            messages().send(player, "player-not-found", "player", args.length > 0 ? args[0] : "?");
            return;
        }
        if (args.length > next && isInteger(args[next])) {
            index = Integer.parseInt(args[next]);
        }
        boolean self = target.equals(player.getUniqueId());
        if (!checkPermission(player, self ? "plots.home" : "plots.visit.other")) {
            return;
        }
        List<Plot> plots = manager().getOwnedBasePlots(target, null);
        if (plots.isEmpty()) {
            messages().send(player, "no-plots", "player", targetName);
            return;
        }
        if (index < 1 || index > plots.size()) {
            messages().send(player, "home-index-invalid", "max", String.valueOf(plots.size()));
            return;
        }
        teleport(player, plots.get(index - 1));
    }

    private void teleportToId(Player player, String[] args) {
        if (!checkPermission(player, "plots.tp")) {
            return;
        }
        if (args.length < 1) {
            messages().send(player, "invalid-id");
            return;
        }
        PlotId id = PlotId.parse(args[0]);
        if (id == null) {
            messages().send(player, "invalid-id");
            return;
        }
        String world = args.length > 1 ? args[1] : player.getWorld().getName();
        PlotWorld plotWorld = manager().world(world);
        World bukkitWorld = Bukkit.getWorld(world);
        if (plotWorld == null || bukkitWorld == null) {
            messages().send(player, "not-in-plot-world");
            return;
        }
        Plot plot = manager().getPlotAbs(world, id);
        if (plot != null) {
            teleport(player, plot);
            return;
        }
        int x = (plotWorld.bottomX(id) + plotWorld.topX(id)) / 2;
        int z = plotWorld.bottomZ(id) - 1;
        messages().send(player, "teleporting");
        player.teleport(new Location(bukkitWorld, x + 0.5, bukkitWorld.getHighestBlockYAt(x, z) + 1, z + 0.5));
    }

    private void info(Player player) {
        if (!checkPermission(player, "plots.info")) {
            return;
        }
        Location location = player.getLocation();
        if (!manager().isPlotWorld(location.getWorld().getName())) {
            messages().send(player, "not-in-plot-world");
            return;
        }
        PlotId id = manager().getPlotId(location);
        if (id == null) {
            messages().send(player, "not-in-plot");
            return;
        }
        Plot plot = manager().getPlotAbs(location.getWorld().getName(), id);
        if (plot == null) {
            messages().send(player, "plot-unowned");
            return;
        }
        List<String> merged = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            if (plot.isMerged(direction)) {
                merged.add(direction.name().toLowerCase(Locale.ROOT));
            }
        }
        messages().send(player, "info",
                "world", plot.area(),
                "id", plot.id().toString(),
                "alias", plot.alias() == null || plot.alias().isEmpty() ? "-" : plot.alias(),
                "owner", name(plot.owner()),
                "trusted", names(plot.trusted()),
                "members", names(plot.members()),
                "denied", names(plot.denied()),
                "merged", merged.isEmpty() ? "-" : String.join(", ", merged)
        );
    }

    private void list(Player player, String[] args) {
        UUID target = player.getUniqueId();
        String targetName = player.getName();
        if (args.length > 0) {
            if (!checkPermission(player, "plots.list.player")) {
                return;
            }
            target = resolve(args[0]);
            targetName = args[0];
            if (target == null) {
                messages().send(player, "player-not-found", "player", targetName);
                return;
            }
        } else if (!checkPermission(player, "plots.list")) {
            return;
        }
        List<Plot> plots = manager().getOwnedBasePlots(target, null);
        if (plots.isEmpty()) {
            messages().send(player, "no-plots", "player", targetName);
            return;
        }
        messages().send(player, "list-header", "player", targetName, "amount", String.valueOf(plots.size()));
        for (int i = 0; i < plots.size(); i++) {
            Plot plot = plots.get(i);
            messages().send(player, "list-entry",
                    "index", String.valueOf(i + 1),
                    "world", plot.area(),
                    "id", plot.id().toString(),
                    "alias", plot.alias() == null ? "" : plot.alias());
        }
    }

    private void addUser(Player player, String[] args, Database.UserTable table, String permission, String message) {
        if (!checkPermission(player, permission)) {
            return;
        }
        if (args.length < 1) {
            messages().send(player, "player-not-found", "player", "?");
            return;
        }
        String adminPermission = table == Database.UserTable.DENIED ? "plots.admin.command.deny" : "plots.admin.command.trust";
        Plot plot = ownedPlotHere(player, adminPermission);
        if (plot == null) {
            return;
        }
        UUID uuid = resolve(args[0]);
        if (uuid == null) {
            messages().send(player, "player-not-found", "player", args[0]);
            return;
        }
        if (uuid.equals(Plot.EVERYONE) && !player.hasPermission(permission + ".everyone")) {
            messages().send(player, "no-permission", "permission", permission + ".everyone");
            return;
        }
        if (plot.isOwner(uuid)) {
            messages().send(player, "cannot-add-owner");
            return;
        }
        if (PlotService.set(plot, table).contains(uuid)) {
            messages().send(player, "already-added", "player", args[0]);
            return;
        }
        service().addUser(plot, table, uuid);
        messages().send(player, message, "player", args[0]);
        if (table == Database.UserTable.DENIED) {
            kickDenied(plot, uuid);
        }
    }

    /**
     * 被禁止的玩家若正在地皮內，送回世界出生點。
     */
    private void kickDenied(Plot plot, UUID uuid) {
        Set<Plot> group = manager().getConnected(plot);
        Collection<? extends Player> targets = uuid.equals(Plot.EVERYONE)
                ? Bukkit.getOnlinePlayers()
                : java.util.Optional.ofNullable(Bukkit.getPlayer(uuid)).map(List::of).orElse(List.of());
        for (Player target : targets) {
            Plot at = manager().getPlotAt(target.getLocation());
            if (at != null && group.contains(at) && !plugin.permissions().canEnter(target, at)) {
                target.teleport(target.getWorld().getSpawnLocation());
                messages().send(target, "you-were-denied");
            }
        }
    }

    private void remove(Player player, String[] args) {
        if (!checkPermission(player, "plots.remove")) {
            return;
        }
        if (args.length < 1) {
            messages().send(player, "player-not-found", "player", "?");
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.admin.command.remove");
        if (plot == null) {
            return;
        }
        UUID uuid = resolve(args[0]);
        if (uuid == null) {
            messages().send(player, "player-not-found", "player", args[0]);
            return;
        }
        boolean removed = service().removeUser(plot, Database.UserTable.TRUSTED, uuid);
        removed |= service().removeUser(plot, Database.UserTable.MEMBER, uuid);
        // 與 PlotSquared 相同，remove 也會解除禁止
        removed |= service().removeUser(plot, Database.UserTable.DENIED, uuid);
        messages().send(player, removed ? "removed" : "not-added", "player", args[0]);
    }

    private void undeny(Player player, String[] args) {
        if (!checkPermission(player, "plots.undeny")) {
            return;
        }
        if (args.length < 1) {
            messages().send(player, "player-not-found", "player", "?");
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.admin.command.undeny");
        if (plot == null) {
            return;
        }
        UUID uuid = resolve(args[0]);
        if (uuid == null) {
            messages().send(player, "player-not-found", "player", args[0]);
            return;
        }
        boolean removed = service().removeUser(plot, Database.UserTable.DENIED, uuid);
        messages().send(player, removed ? "undenied" : "not-added", "player", args[0]);
    }

    private void setHome(Player player, String[] args) {
        if (!checkPermission(player, "plots.set.home")) {
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.admin.command.sethome");
        if (plot == null) {
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            service().setHome(plot, null);
            messages().send(player, "home-reset");
        } else {
            service().setHome(plot, player.getLocation());
            messages().send(player, "home-set");
        }
    }

    private void clear(Player player, String label) {
        if (!checkPermission(player, "plots.clear")) {
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.admin.command.clear");
        if (plot == null) {
            return;
        }
        requireConfirm(player, label, () -> {
            if (manager().getPlotAbs(plot.area(), plot.id()) != plot) {
                messages().send(player, "plot-unowned");
                return;
            }
            messages().send(player, "clearing");
            service().clear(plot, () -> messages().send(player, "cleared"));
        });
    }

    private void delete(Player player, String label) {
        if (!checkPermission(player, "plots.delete")) {
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.admin.command.delete");
        if (plot == null) {
            return;
        }
        requireConfirm(player, label, () -> {
            if (manager().getPlotAbs(plot.area(), plot.id()) != plot) {
                messages().send(player, "plot-unowned");
                return;
            }
            messages().send(player, "deleting");
            service().delete(plot, () -> messages().send(player, "deleted"));
        });
    }

    private void setOwner(Player player, String[] args) {
        if (!checkPermission(player, "plots.admin.command.setowner")) {
            return;
        }
        if (args.length < 1) {
            messages().send(player, "player-not-found", "player", "?");
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.admin.command.setowner");
        if (plot == null) {
            return;
        }
        UUID uuid = resolve(args[0]);
        if (uuid == null || uuid.equals(Plot.EVERYONE)) {
            messages().send(player, "player-not-found", "player", args[0]);
            return;
        }
        service().setOwner(plot, uuid);
        messages().send(player, "setowner", "player", args[0]);
    }

    /**
     * /plot fixroads [半徑]：把玩家周圍不屬於任何地皮的道路與圍牆恢復原樣。
     */
    private void fixRoads(Player player, String[] args, String label) {
        if (!checkPermission(player, "plots.admin.command.fixroads")) {
            return;
        }
        String world = player.getWorld().getName();
        if (!manager().isPlotWorld(world)) {
            messages().send(player, "not-in-plot-world");
            return;
        }
        int max = plugin.fixRoadsMaxRadius();
        int radius = Math.min(32, max);
        if (args.length > 0) {
            if (!isInteger(args[0]) || Integer.parseInt(args[0]) < 1 || Integer.parseInt(args[0]) > max) {
                messages().send(player, "fixroads-radius", "max", String.valueOf(max));
                return;
            }
            radius = Integer.parseInt(args[0]);
        }
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        int finalRadius = radius;
        messages().send(player, "fixroads-warning", "radius", String.valueOf(radius));
        requireConfirm(player, label, () -> {
            messages().send(player, "fixroads-start");
            service().fixRoads(world, x - finalRadius, z - finalRadius, x + finalRadius, z + finalRadius,
                    () -> messages().send(player, "fixroads-done"));
        });
    }

    private void confirm(Player player) {
        PendingConfirm confirm = pending.remove(player.getUniqueId());
        if (confirm == null || confirm.expires() < System.currentTimeMillis()) {
            messages().send(player, "nothing-to-confirm");
            return;
        }
        confirm.action().run();
    }

    private static boolean isInteger(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Tab 補全

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = ALIASES.getOrDefault(args[0].toLowerCase(Locale.ROOT), args[0].toLowerCase(Locale.ROOT));
            switch (sub) {
                case "trust", "add", "deny", "remove", "undeny", "visit", "home", "list", "setowner" -> {
                    String prefix = args[1].toLowerCase(Locale.ROOT);
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .collect(Collectors.toList());
                }
                case "sethome" -> {
                    return List.of("reset");
                }
                default -> {
                    return List.of();
                }
            }
        }
        return List.of();
    }

}
