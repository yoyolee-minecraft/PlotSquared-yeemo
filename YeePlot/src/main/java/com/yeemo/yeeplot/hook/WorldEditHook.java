package com.yeemo.yeeplot.hook;

import com.yeemo.yeeplot.Messages;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.eventbus.EventHandler;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 一般版 WorldEdit 的地皮限制（沒有安裝 FAWE 時使用），做法與 PlotSquared 的 WESubscriber 相同：
 * 在每個 EditSession 外面包一層只允許寫入可編輯範圍的 extent。
 * <p>
 * 只覆寫 BlockVector3 版本的方法；一般版 WorldEdit 只會走這些方法。FAWE 另有 {@link FaweHook}。
 */
public final class WorldEditHook {

    /**
     * 允許在地皮世界用 WorldEdit 建立展示實體（例如 //paste -e）。
     */
    public static final String DISPLAYS = "plots.worldedit.displays";

    private final EditAccess access;
    private final Messages messages;
    /**
     * false 時（使用 FAWE）不做範圍遮罩，範圍交給 {@link FaweHook}，這裡只負責擋掉展示實體。
     */
    private final boolean restrictArea;

    public WorldEditHook(EditAccess access, Messages messages, boolean restrictArea) {
        this.access = access;
        this.messages = messages;
        this.restrictArea = restrictArea;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
    }

    public void unregister() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    @Subscribe(priority = EventHandler.Priority.VERY_EARLY)
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }
        World world = event.getWorld();
        if (world == null || !access.isPlotWorld(world.getName())) {
            return;
        }
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) {
            // 後台與指令方塊不受限制，與 PlotSquared 相同
            return;
        }
        Player player = Bukkit.getPlayer(actor.getUniqueId());
        // 展示實體與範圍限制分開判斷：plots.worldedit.displays 預設沒有人有（包含 OP），需要時再手動給
        boolean filterDisplays = player == null || !player.hasPermission(DISPLAYS);
        boolean bypassArea = !restrictArea || player != null && player.hasPermission(EditAccess.BYPASS);
        if (bypassArea) {
            if (filterDisplays) {
                event.setExtent(new MaskedExtent(event.getExtent(), null, true));
            }
            return;
        }
        EditAccess.EditMask mask = player == null ? null : access.maskFor(player, player.hasPermission(EditAccess.MEMBER));
        if (mask == null || !mask.world().equals(world.getName())) {
            event.setExtent(new NullExtent());
            if (player != null) {
                messages.send(player, "worldedit-denied");
            }
            return;
        }
        event.setExtent(new MaskedExtent(event.getExtent(), mask, filterDisplays));
    }

    /**
     * 只允許在可編輯範圍內讀寫；範圍外讀到的一律是空氣，避免複製別人的建築。
     * 另外一律不建立展示實體：展示實體可以透過變換矩陣畫到範圍外，貼上（//paste -e）時直接略過。
     * mask 為 null 時（FAWE）只擋展示實體，不限制範圍。
     * 刻意使用 getBlockX() 等舊方法：WorldEdit 7.2 沒有新的 x()，舊方法在 7.2 與 7.3 都存在。
     */
    @SuppressWarnings("removal")
    private static final class MaskedExtent extends AbstractDelegateExtent {

        private static final BlockState AIR = BlockTypes.AIR.getDefaultState();
        private static final BaseBlock AIR_BASE = AIR.toBaseBlock();

        private final EditAccess.EditMask mask;
        private final boolean filterDisplays;

        private MaskedExtent(Extent extent, EditAccess.EditMask mask, boolean filterDisplays) {
            super(extent);
            this.mask = mask;
            this.filterDisplays = filterDisplays;
        }

        private boolean allowed(int x, int y, int z) {
            return mask == null || mask.contains(x, y, z);
        }

        private boolean blocked(BaseEntity entity) {
            return filterDisplays && isDisplay(entity);
        }

        private static boolean isDisplay(BaseEntity entity) {
            String id = entity.getType().getId();
            return id.endsWith("block_display") || id.endsWith("item_display") || id.endsWith("text_display");
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws WorldEditException {
            return allowed(location.getBlockX(), location.getBlockY(), location.getBlockZ())
                    && super.setBlock(location, block);
        }

        @Override
        public BlockState getBlock(BlockVector3 location) {
            return allowed(location.getBlockX(), location.getBlockY(), location.getBlockZ())
                    ? super.getBlock(location) : AIR;
        }

        @Override
        public BaseBlock getFullBlock(BlockVector3 location) {
            return allowed(location.getBlockX(), location.getBlockY(), location.getBlockZ())
                    ? super.getFullBlock(location) : AIR_BASE;
        }

        @Override
        public boolean setBiome(BlockVector3 position, BiomeType biome) {
            return allowed(position.getBlockX(), position.getBlockY(), position.getBlockZ())
                    && super.setBiome(position, biome);
        }

        @Override
        public Entity createEntity(Location location, BaseEntity entity) {
            if (!blocked(entity) && allowed(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                return super.createEntity(location, entity);
            }
            return null;
        }

        /**
         * FAWE 額外的多載（一般版 WorldEdit 沒有這個方法，所以不加 @Override）。
         * FAWE 貼上實體時可能走這個版本，要一起擋。
         */
        public Entity createEntity(Location location, BaseEntity entity, java.util.UUID uuid) {
            if (!blocked(entity) && allowed(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                return getExtent().createEntity(location, entity, uuid);
            }
            return null;
        }

    }

}
