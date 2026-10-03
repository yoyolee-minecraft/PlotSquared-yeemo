package com.yeemo.yeeplot.hook;

import com.fastasyncworldedit.core.FaweAPI;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.regions.FaweMask;
import com.fastasyncworldedit.core.regions.FaweMaskManager;
import com.fastasyncworldedit.core.regions.RegionWrapper;
import com.fastasyncworldedit.core.util.WEManager;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionIntersection;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FAWE 的地皮限制。FAWE 的大範圍操作會繞過一般的 extent，所以要改成向 FAWE 註冊區域遮罩管理器，
 * 做法與 FAWE 內建的 PlotSquared 整合相同。
 * <p>
 * key 使用「PlotSquared」，玩家需要的權限是 fawe.plotsquared（FAWE 預設給所有人），與原本相同。
 */
public final class FaweHook extends FaweMaskManager {

    private final EditAccess access;

    public FaweHook(EditAccess access) {
        super("PlotSquared");
        this.access = access;
    }

    public void register() {
        FaweAPI.addMaskManager(this);
    }

    /**
     * FAWE 會移除不在 config.yml「extent.allowed-plugins」白名單裡的第三方 extent，
     * 所以要先把 YeePlot 的 extent 加進白名單（只改記憶體中的設定，不寫入 FAWE 的設定檔）。
     */
    public static void allowExtent(String classPrefix) {
        List<String> allowed = new ArrayList<>(Settings.settings().EXTENT.ALLOWED_PLUGINS);
        for (String entry : allowed) {
            if (classPrefix.toLowerCase(java.util.Locale.ROOT).contains(entry.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
        }
        allowed.add(classPrefix);
        Settings.settings().EXTENT.ALLOWED_PLUGINS = allowed;
    }

    public void unregister() {
        WEManager.weManager().getManagers().remove(this);
    }

    @Override
    public FaweMask getMask(com.sk89q.worldedit.entity.Player wePlayer, MaskType type, boolean isWhitelist) {
        if (!isWhitelist) {
            return null;
        }
        org.bukkit.entity.Player player = Bukkit.getPlayer(wePlayer.getUniqueId());
        if (player == null || !access.isPlotWorld(player.getWorld().getName())) {
            return null;
        }
        if (player.hasPermission(EditAccess.BYPASS)) {
            return new FaweMask(RegionWrapper.GLOBAL());
        }
        boolean allowMember = type == MaskType.MEMBER || player.hasPermission(EditAccess.MEMBER);
        EditAccess.EditMask mask = access.maskFor(player, allowMember);
        if (mask == null) {
            return null;
        }
        return new PlotMask(toRegion(mask), mask, allowMember);
    }

    private static Region toRegion(EditAccess.EditMask mask) {
        List<Region> regions = new ArrayList<>();
        for (int[] rect : mask.rects()) {
            regions.add(new CuboidRegion(
                    BlockVector3.at(rect[0], mask.minY(), rect[1]),
                    BlockVector3.at(rect[2], mask.maxY(), rect[3])
            ));
        }
        return regions.size() == 1 ? regions.get(0) : new RegionIntersection(regions);
    }

    /**
     * FAWE 會快取遮罩，每次使用前呼叫 isValid；地皮被刪除、名單變更或玩家換到別塊地皮時就讓快取失效。
     */
    private final class PlotMask extends FaweMask {

        private final EditAccess.EditMask snapshot;
        private final boolean allowMember;

        private PlotMask(Region region, EditAccess.EditMask snapshot, boolean allowMember) {
            super(region);
            this.snapshot = snapshot;
            this.allowMember = allowMember;
        }

        @Override
        public boolean isValid(com.sk89q.worldedit.entity.Player wePlayer, MaskType type, boolean notify) {
            org.bukkit.entity.Player player = Bukkit.getPlayer(wePlayer.getUniqueId());
            if (player == null) {
                return false;
            }
            EditAccess.EditMask current = access.maskFor(player, allowMember);
            return current != null && current.world().equals(snapshot.world()) && sameRects(current, snapshot);
        }

        private boolean sameRects(EditAccess.EditMask a, EditAccess.EditMask b) {
            if (a.rects().size() != b.rects().size()) {
                return false;
            }
            for (int i = 0; i < a.rects().size(); i++) {
                if (!Arrays.equals(a.rects().get(i), b.rects().get(i))) {
                    return false;
                }
            }
            return true;
        }

    }

}
