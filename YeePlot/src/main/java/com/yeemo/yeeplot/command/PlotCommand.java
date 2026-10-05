package com.yeemo.yeeplot.command;

import com.yeemo.yeeplot.Messages;
import com.yeemo.yeeplot.YeePlot;
import com.yeemo.yeeplot.plot.Direction;
import com.yeemo.yeeplot.plot.Plot;
import com.yeemo.yeeplot.plot.PlotId;
import com.yeemo.yeeplot.plot.PlotManager;
import com.yeemo.yeeplot.plot.PlotService;
import com.yeemo.yeeplot.storage.Database;
import com.yeemo.yeeplot.world.PlotWorld;
import net.kyori.adventure.text.Component;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * /plot 指令。子指令名稱與權限節點沿用 PlotSquared。
 */
public final class PlotCommand implements TabExecutor {

    private static final long CONFIRM_TIMEOUT = 20_000L;
    private static final List<String> SUBCOMMANDS = List.of(
            "help", "claim", "auto", "home", "visit", "tp", "info", "list", "trust", "add", "remove",
            "deny", "undeny", "sethome", "alias", "time", "weather", "flag", "merge", "clear", "delete", "setowner", "fixroads", "reload", "confirm"
    );
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("c", "claim"), Map.entry("a", "auto"), Map.entry("h", "home"),
            Map.entry("v", "visit"), Map.entry("i", "info"), Map.entry("t", "trust"),
            Map.entry("r", "remove"), Map.entry("untrust", "remove"), Map.entry("d", "deny"),
            Map.entry("ud", "undeny"), Map.entry("dispose", "delete"), Map.entry("unclaim", "delete"),
            Map.entry("del", "delete"), Map.entry("l", "list"), Map.entry("teleport", "tp"),
            Map.entry("seth", "sethome"), Map.entry("?", "help"), Map.entry("m", "merge"), Map.entry("f", "flag")
    );

    private final YeePlot plugin;
    private final Map<UUID, PendingConfirm> pending = new HashMap<>();
    /**
     * 地皮擁有者的名稱，給 Tab 補全用。名稱很少改變，查過一次就留著，避免每次按 Tab 都去讀玩家資料。
     */
    private final Map<UUID, String> ownerNames = new ConcurrentHashMap<>();

    private record PendingConfirm(Runnable action, long expires) {
    }

    public PlotCommand(YeePlot plugin) {
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
        if (command.getName().equalsIgnoreCase("plotlist")) {
            // /plotlist [玩家] 等同 /plot list [玩家]
            String[] withSub = new String[args.length + 1];
            withSub[0] = "list";
            System.arraycopy(args, 0, withSub, 1, args.length);
            args = withSub;
        }
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
            case "alias" -> alias(player, rest);
            case "time" -> time(player, rest);
            case "weather" -> weather(player, rest);
            case "flag" -> flag(player, rest);
            case "merge" -> merge(player, rest, label);
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

    /**
     * 與 ownedPlotHere 相同，但有指定「世界;x;z」時改用那塊地皮（/plot info 的 [X] 按鈕會帶上，
     * 避免玩家走到別塊地皮後才點擊，結果改到別塊地皮）。
     */
    private Plot ownedPlot(Player player, String adminPermission, String reference) {
        if (reference == null) {
            return ownedPlotHere(player, adminPermission);
        }
        int last = reference.lastIndexOf(';');
        int middle = last > 0 ? reference.lastIndexOf(';', last - 1) : -1;
        PlotId id = middle > 0 ? PlotId.parse(reference.substring(middle + 1)) : null;
        if (id == null) {
            messages().send(player, "invalid-id");
            return null;
        }
        Plot plot = manager().getPlotAbs(reference.substring(0, middle), id);
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
                // 與 PlotSquared 相同：找不到玩家時，當成地皮別名
                Plot aliased = manager().findByAlias(targetName, null);
                if (aliased == null) {
                    messages().send(player, "player-not-found", "player", targetName);
                    return;
                }
                if (!checkPermission(player, aliased.isOwner(player.getUniqueId()) ? "plots.home" : "plots.visit.other")) {
                    return;
                }
                teleport(player, aliased);
                return;
            }
        } else if (visit) {
            messages().send(player, "player-not-found", "player", args.length > 0 ? args[0] : "?");
            return;
        }
        String aliasArg = null;
        if (args.length > next) {
            if (isInteger(args[next])) {
                index = Integer.parseInt(args[next]);
            } else {
                // /plot visit <玩家> <別名>：第二個參數不是數字時，在這個玩家的地皮裡找別名
                aliasArg = args[next];
            }
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
        if (aliasArg != null) {
            for (Plot plot : plots) {
                if (plot.alias() != null && plot.alias().equalsIgnoreCase(aliasArg)) {
                    teleport(player, plot);
                    return;
                }
            }
            messages().send(player, "alias-not-found", "player", targetName, "alias", aliasArg);
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
        boolean owner = plot.isOwner(player.getUniqueId());
        // 擁有者（或有管理權限）才會在名字後面看到 [X]，點擊後執行 remove／undeny
        boolean canRemove = player.hasPermission("plots.remove")
                && (owner || player.hasPermission("plots.admin.command.remove"));
        boolean canUndeny = player.hasPermission("plots.undeny")
                && (owner || player.hasPermission("plots.admin.command.undeny"));
        String reference = plot.area() + ";" + plot.id();
        Map<String, Component> lists = new LinkedHashMap<>();
        lists.put("trusted", userList(plot.trusted(), canRemove ? "remove" : null, reference, "info-remove-hover"));
        lists.put("members", userList(plot.members(), canRemove ? "remove" : null, reference, "info-remove-hover"));
        lists.put("denied", userList(plot.denied(), canUndeny ? "undeny" : null, reference, "info-undeny-hover"));
        String ownerName = name(plot.owner());
        messages().sendRich(player, "info", lists,
                "world", plot.area(),
                "id", plot.id().toString(),
                "alias", plot.alias() == null || plot.alias().isEmpty() ? "-" : plot.alias(),
                "owner", ownerName,
                "merged", merged.isEmpty() ? "-" : String.join(", ", merged),
                "time", plot.time() == null ? "-" : String.valueOf(plot.time()),
                "weather", plot.weather() == null ? "-" : messages().format("weather-" + plot.weather()),
                "displays", plugin.displayLimit().perPlot() > 0
                        ? plugin.displayLimit().count(plot) + "/" + plugin.displayLimit().limit(plot)
                        : String.valueOf(plugin.displayLimit().count(plot))
        );
        if (player.hasPermission(owner ? "plots.list" : "plots.list.player")) {
            // 指定第 1 頁，避免純數字的玩家名稱被當成頁數
            messages().sendClickable(player, "info-other-plots", "info-other-plots-hover",
                    "/plot list " + ownerName + " 1", "owner", ownerName);
        }
    }

    /**
     * /plot info 的名單：每個名字後面可以加上 [X]，點擊執行「/plot command 名字 世界;x;z」。
     */
    private Component userList(Collection<UUID> uuids, String command, String reference, String hoverKey) {
        if (uuids.isEmpty()) {
            return messages().component("info-user", "player", "-");
        }
        List<Component> parts = new ArrayList<>();
        for (UUID uuid : uuids) {
            if (!parts.isEmpty()) {
                parts.add(messages().component("info-user-separator"));
            }
            String name = name(uuid);
            parts.add(messages().component("info-user", "player", name));
            if (command != null) {
                parts.add(messages().clickable("info-remove", hoverKey,
                        "/plot " + command + " " + name + " " + reference, "player", name));
            }
        }
        return Component.text().append(parts).build();
    }

    /**
     * /plot list [玩家] [頁數]，或 /plot list [頁數]。只有一個純數字參數時當成自己的頁數。
     */
    private void list(Player player, String[] args) {
        UUID target = player.getUniqueId();
        String targetName = player.getName();
        int page = 1;
        if (args.length == 1 && isInteger(args[0])) {
            page = Integer.parseInt(args[0]);
        } else if (args.length > 0) {
            target = resolve(args[0]);
            targetName = args[0];
            if (target == null) {
                if (checkPermission(player, "plots.list.player")) {
                    messages().send(player, "player-not-found", "player", targetName);
                }
                return;
            }
            if (args.length > 1) {
                page = isInteger(args[1]) ? Integer.parseInt(args[1]) : 0;
            }
        }
        boolean self = target.equals(player.getUniqueId());
        if (self) {
            targetName = player.getName();
        }
        if (!checkPermission(player, self ? "plots.list" : "plots.list.player")) {
            return;
        }
        List<Plot> plots = manager().getOwnedBasePlots(target, null);
        if (plots.isEmpty()) {
            messages().send(player, "no-plots", "player", targetName);
            return;
        }
        int pageSize = plugin.listPageSize();
        int pages = (plots.size() + pageSize - 1) / pageSize;
        if (page < 1 || page > pages) {
            messages().send(player, "list-page-invalid", "max", String.valueOf(pages));
            return;
        }
        messages().send(player, "list-header", "player", targetName, "amount", String.valueOf(plots.size()),
                "page", String.valueOf(page), "pages", String.valueOf(pages));
        int from = (page - 1) * pageSize;
        for (int i = from; i < Math.min(from + pageSize, plots.size()); i++) {
            Plot plot = plots.get(i);
            int index = i + 1;
            // 點擊後執行 home／visit，所以照樣會檢查傳送權限與禁止進入
            String click = self ? "/plot home " + index : "/plot visit " + targetName + " " + index;
            // 有別名就優先顯示別名，沒有的話顯示座標；滑鼠移上去仍會顯示座標
            String coordinates = plot.area() + ";" + plot.id();
            messages().sendClickable(player, "list-entry", "list-hover", click,
                    "index", String.valueOf(index),
                    "name", plot.alias() != null ? plot.alias() : coordinates,
                    "world", plot.area(),
                    "id", plot.id().toString(),
                    "alias", plot.alias() == null ? "" : plot.alias());
        }
        if (pages > 1) {
            String current = String.valueOf(page);
            String total = String.valueOf(pages);
            List<Component> footer = new ArrayList<>();
            if (page > 1) {
                footer.add(messages().clickable("list-previous", "list-previous-hover",
                        "/plot list " + targetName + " " + (page - 1),
                        "page", current, "pages", total, "target", String.valueOf(page - 1)));
                footer.add(Component.text(" "));
            }
            footer.add(messages().component("list-page", "page", current, "pages", total));
            if (page < pages) {
                footer.add(Component.text(" "));
                footer.add(messages().clickable("list-next", "list-next-hover",
                        "/plot list " + targetName + " " + (page + 1),
                        "page", current, "pages", total, "target", String.valueOf(page + 1)));
            }
            messages().sendLine(player, footer.toArray(new Component[0]));
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
        Plot plot = ownedPlot(player, "plots.admin.command.remove", args.length > 1 ? args[1] : null);
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
        Plot plot = ownedPlot(player, "plots.admin.command.undeny", args.length > 1 ? args[1] : null);
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
        if (!service().managesTerrain(plot.area())) {
            messages().send(player, "terrain-not-managed");
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
        if (!service().managesTerrain(plot.area())) {
            messages().send(player, "delete-keeps-terrain");
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

    // ---------------------------------------------------------------- 別名

    /**
     * /plot alias set &lt;名稱&gt;、/plot alias remove（與 PlotSquared 相同）。
     */
    private void alias(Player player, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("set") && !args[0].equalsIgnoreCase("remove")) {
            messages().send(player, "alias-usage");
            return;
        }
        boolean set = args[0].equalsIgnoreCase("set");
        if (!checkPermission(player, set ? "plots.alias.set" : "plots.alias.remove")) {
            return;
        }
        Plot plot = ownedPlotHere(player, set ? "plots.admin.alias.set" : "plots.admin.alias.remove");
        if (plot == null) {
            return;
        }
        if (!set) {
            service().setAlias(plot, null);
            messages().send(player, "alias-removed");
            return;
        }
        if (args.length != 2) {
            messages().send(player, "alias-usage");
            return;
        }
        String alias = args[1];
        switch (service().checkAlias(plot, alias)) {
            case EMPTY -> {
                messages().send(player, "alias-usage");
                return;
            }
            case TOO_LONG -> {
                messages().send(player, "alias-too-long");
                return;
            }
            case NUMBER, INVALID_CHARACTER -> {
                messages().send(player, "alias-invalid");
                return;
            }
            case TAKEN -> {
                messages().send(player, "alias-taken", "alias", alias);
                return;
            }
            default -> {
            }
        }
        // 不能跟玩家名稱相同，否則 /plot visit 會分不清楚
        if (Bukkit.getPlayerExact(alias) != null || Bukkit.getOfflinePlayerIfCached(alias) != null) {
            messages().send(player, "alias-taken", "alias", alias);
            return;
        }
        service().setAlias(plot, alias);
        messages().send(player, "alias-set", "alias", alias);
    }

    // ---------------------------------------------------------------- 時間與天氣

    private boolean hasFlagPermission(Player player, String flag) {
        return player.hasPermission("plots.set.flag." + flag) || player.hasPermission("plots.set.flag." + flag + ".*");
    }

    /**
     * /plot time &lt;0~24000|day|noon|night|midnight|reset&gt;
     */
    private void time(Player player, String[] args) {
        if (!hasFlagPermission(player, "time")) {
            messages().send(player, "no-permission", "permission", "plots.set.flag.time");
            return;
        }
        if (args.length < 1) {
            messages().send(player, "time-usage");
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.set.flag.other");
        if (plot == null) {
            return;
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        Long time = switch (input) {
            case "reset", "remove", "off" -> null;
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            default -> {
                try {
                    long value = Long.parseLong(input);
                    yield value >= 0 && value <= 24000 ? value : -1L;
                } catch (NumberFormatException e) {
                    yield -1L;
                }
            }
        };
        if (time != null && time < 0) {
            messages().send(player, "time-usage");
            return;
        }
        service().setTime(plot, time);
        plugin.effects().refreshAll();
        if (time == null) {
            messages().send(player, "time-reset");
        } else {
            messages().send(player, "time-set", "time", String.valueOf(time));
        }
    }

    /**
     * /plot weather &lt;clear|rain|reset&gt;
     */
    private void weather(Player player, String[] args) {
        if (!hasFlagPermission(player, "weather")) {
            messages().send(player, "no-permission", "permission", "plots.set.flag.weather");
            return;
        }
        if (args.length < 1) {
            messages().send(player, "weather-usage");
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.set.flag.other");
        if (plot == null) {
            return;
        }
        String weather = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "clear", "sun", "sunny" -> "clear";
            case "rain", "storm", "downfall" -> "rain";
            case "reset", "remove", "off" -> null;
            default -> "?";
        };
        if ("?".equals(weather)) {
            messages().send(player, "weather-usage");
            return;
        }
        service().setWeather(plot, weather);
        plugin.effects().refreshAll();
        if (weather == null) {
            messages().send(player, "weather-reset");
        } else {
            messages().send(player, "weather-set", "weather", messages().format("weather-" + weather));
        }
    }

    /**
     * 相容 PlotSquared 的寫法：/plot flag set time 6000、/plot flag remove weather。
     * 只支援 time 與 weather。
     */
    private void flag(Player player, String[] args) {
        if (args.length >= 2 && (args[1].equalsIgnoreCase("time") || args[1].equalsIgnoreCase("weather"))) {
            String[] value;
            if (args[0].equalsIgnoreCase("set") && args.length >= 3) {
                value = new String[]{args[2]};
            } else if (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete")) {
                value = new String[]{"reset"};
            } else {
                messages().send(player, "flag-usage");
                return;
            }
            if (args[1].equalsIgnoreCase("time")) {
                time(player, value);
            } else {
                weather(player, value);
            }
            return;
        }
        messages().send(player, "flag-usage");
    }

    // ---------------------------------------------------------------- 合併

    private int allowedMergeSize(Player player) {
        if (player.hasPermission("plots.admin") || player.hasPermission("plots.merge.*")) {
            return Integer.MAX_VALUE;
        }
        for (int i = 64; i > 1; i--) {
            if (player.hasPermission("plots.merge." + i)) {
                return i;
            }
        }
        return plugin.mergeDefaultMax();
    }

    /**
     * /plot merge [north|east|south|west]：與相鄰的自己的地皮合併，預設是面向的方向。
     */
    private void merge(Player player, String[] args, String label) {
        if (!checkPermission(player, "plots.merge")) {
            return;
        }
        Plot plot = ownedPlotHere(player, "plots.admin.command.merge");
        if (plot == null) {
            return;
        }
        Direction direction;
        if (args.length > 0) {
            direction = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "north", "n", "北" -> Direction.NORTH;
                case "east", "e", "東" -> Direction.EAST;
                case "south", "s", "南" -> Direction.SOUTH;
                case "west", "w", "西" -> Direction.WEST;
                default -> null;
            };
            if (direction == null) {
                messages().send(player, "merge-usage");
                return;
            }
        } else {
            // yaw 0 南、90 西、180 北、270 東
            int quadrant = Math.floorMod(Math.round(player.getLocation().getYaw() / 90f), 4);
            direction = switch (quadrant) {
                case 0 -> Direction.SOUTH;
                case 1 -> Direction.WEST;
                case 2 -> Direction.NORTH;
                default -> Direction.EAST;
            };
        }
        Plot target = service().mergeTarget(plot, direction);
        int max = allowedMergeSize(player);
        String directionName = messages().format("direction-" + direction.name().toLowerCase(Locale.ROOT));
        switch (service().checkMerge(plot, target, max)) {
            case NO_TARGET -> {
                messages().send(player, "merge-no-target", "direction", directionName);
                return;
            }
            case DIFFERENT_OWNER -> {
                messages().send(player, "merge-different-owner", "direction", directionName);
                return;
            }
            case NOT_RECTANGLE -> {
                messages().send(player, "merge-not-rectangle");
                return;
            }
            case TOO_LARGE -> {
                messages().send(player, "merge-too-large", "max", String.valueOf(max));
                return;
            }
            default -> {
            }
        }
        messages().send(player, "merge-warning", "direction", directionName, "target", target.id().toString());
        requireConfirm(player, label, () -> {
            // 確認期間地皮可能已被刪除或改變，重新檢查一次
            if (manager().getPlotAbs(plot.area(), plot.id()) != plot
                    || manager().getPlotAbs(target.area(), target.id()) != target
                    || service().checkMerge(plot, target, max) != PlotService.MergeCheck.OK) {
                messages().send(player, "merge-changed");
                return;
            }
            messages().send(player, "merging");
            service().merge(plot, target, () -> messages().send(player, "merged"));
        });
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
        if (!service().managesTerrain(world)) {
            messages().send(player, "terrain-not-managed");
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
        if (command.getName().equalsIgnoreCase("plotlist")) {
            String[] withSub = new String[args.length + 1];
            withSub[0] = "list";
            System.arraycopy(args, 0, withSub, 1, args.length);
            args = withSub;
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        String sub = ALIASES.getOrDefault(args[0].toLowerCase(Locale.ROOT), args[0].toLowerCase(Locale.ROOT));
        if (args.length == 2) {
            switch (sub) {
                case "trust", "add", "deny", "setowner", "list" -> {
                    return filter(playerNames(), args[1]);
                }
                case "remove" -> {
                    return filter(listedNames(sender, true), args[1]);
                }
                case "undeny" -> {
                    return filter(listedNames(sender, false), args[1]);
                }
                case "visit", "home" -> {
                    Set<String> options = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    options.addAll(playerNames());
                    for (Plot plot : manager().allPlots()) {
                        if (plot.alias() != null && !plot.alias().isEmpty()) {
                            options.add(plot.alias());
                        }
                    }
                    return filter(new ArrayList<>(options), args[1]);
                }
                case "sethome" -> {
                    return List.of("reset");
                }
                case "alias" -> {
                    return filter(List.of("set", "remove"), args[1]);
                }
                case "time" -> {
                    return filter(List.of("day", "noon", "night", "midnight", "reset"), args[1]);
                }
                case "weather" -> {
                    return filter(List.of("clear", "rain", "reset"), args[1]);
                }
                case "merge" -> {
                    return filter(List.of("north", "east", "south", "west"), args[1]);
                }
                case "flag" -> {
                    return filter(List.of("set", "remove"), args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3) {
            switch (sub) {
                case "flag" -> {
                    return filter(List.of("time", "weather"), args[2]);
                }
                case "visit", "home", "list" -> {
                    // 第二個參數是玩家時，補上那位玩家的地皮別名與編號（list 補頁數）
                    UUID target = isInteger(args[1]) ? null : resolve(args[1]);
                    if (target == null) {
                        return List.of();
                    }
                    List<Plot> plots = manager().getOwnedBasePlots(target, null);
                    List<String> options = new ArrayList<>();
                    int count = sub.equals("list")
                            ? (plots.size() + plugin.listPageSize() - 1) / plugin.listPageSize()
                            : plots.size();
                    for (int i = 1; i <= count; i++) {
                        options.add(String.valueOf(i));
                    }
                    if (!sub.equals("list")) {
                        for (Plot plot : plots) {
                            if (plot.alias() != null && !plot.alias().isEmpty()) {
                                options.add(plot.alias());
                            }
                        }
                    }
                    return filter(options, args[2]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    /**
     * 在線玩家加上所有地皮擁有者的名稱。
     */
    private List<String> playerNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        for (Plot plot : manager().allPlots()) {
            UUID owner = plot.owner();
            if (owner == null || owner.equals(Plot.EVERYONE)) {
                continue;
            }
            // 查不到名稱時存空字串，下次就不用再查
            String name = ownerNames.computeIfAbsent(owner, uuid -> {
                String found = Bukkit.getOfflinePlayer(uuid).getName();
                return found == null ? "" : found;
            });
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * 腳下地皮名單裡的玩家：remove 用信任者、成員、禁止名單，undeny 只用禁止名單。
     * 不在地皮上時改用在線玩家。
     */
    private List<String> listedNames(CommandSender sender, boolean all) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        Plot plot = manager().getPlotAt(player.getLocation());
        if (plot == null) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        }
        Set<UUID> uuids = new LinkedHashSet<>(plot.denied());
        if (all) {
            uuids.addAll(plot.trusted());
            uuids.addAll(plot.members());
        }
        return uuids.stream().map(PlotCommand::name).collect(Collectors.toList());
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }

}
