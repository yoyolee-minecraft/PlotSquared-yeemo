package com.plotsquared.lite.plot;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一塊已被認領的地皮。欄位對應 PlotSquared 的資料表：
 * <ul>
 *     <li>trusted：plot_helpers 表，隨時可以建築</li>
 *     <li>members：plot_trusted 表，擁有者在線時才可以建築</li>
 *     <li>denied：plot_denied 表，禁止進入</li>
 * </ul>
 * 表名與意義錯位是 PlotSquared 的歷史包袱，這裡照原樣保留以確保資料互通。
 * 所有欄位只在主執行緒修改。
 */
public final class Plot {

    /**
     * PlotSquared 用來表示「所有人」的特殊 UUID。
     */
    public static final UUID EVERYONE = UUID.fromString("1-1-3-3-7");

    private final String area;
    private final PlotId id;
    private final Set<UUID> trusted = new HashSet<>();
    private final Set<UUID> members = new HashSet<>();
    private final Set<UUID> denied = new HashSet<>();
    private final boolean[] merged = new boolean[4];
    private volatile int dbId = -1;
    private UUID owner;
    private long timestamp;
    private String alias;
    private String position;

    public Plot(String area, PlotId id, UUID owner, long timestamp) {
        this.area = area;
        this.id = id;
        this.owner = owner;
        this.timestamp = timestamp;
    }

    public String area() {
        return area;
    }

    public PlotId id() {
        return id;
    }

    public int dbId() {
        return dbId;
    }

    public void dbId(int dbId) {
        this.dbId = dbId;
    }

    public UUID owner() {
        return owner;
    }

    public void owner(UUID owner) {
        this.owner = owner;
    }

    public long timestamp() {
        return timestamp;
    }

    public Set<UUID> trusted() {
        return trusted;
    }

    public Set<UUID> members() {
        return members;
    }

    public Set<UUID> denied() {
        return denied;
    }

    public boolean[] merged() {
        return merged;
    }

    public boolean isMerged(Direction direction) {
        return merged[direction.index()];
    }

    public boolean isMerged() {
        return merged[0] || merged[1] || merged[2] || merged[3];
    }

    public String alias() {
        return alias;
    }

    public void alias(String alias) {
        this.alias = alias;
    }

    public String position() {
        return position;
    }

    public void position(String position) {
        this.position = position;
    }

    public boolean isOwner(UUID uuid) {
        return owner != null && owner.equals(uuid);
    }

    /**
     * 與 PlotSquared 的 Plot#isAdded 相同的規則。
     *
     * @param uuid        玩家
     * @param ownerOnline 擁有者是否在線（members 只有在擁有者在線時才算數）
     */
    public boolean isAdded(UUID uuid, boolean ownerOnline) {
        if (owner == null || denied.contains(uuid)) {
            return false;
        }
        if (isOwner(uuid)) {
            return true;
        }
        if (members.contains(uuid)) {
            return ownerOnline;
        }
        if (trusted.contains(uuid) || trusted.contains(EVERYONE)) {
            return true;
        }
        if (members.contains(EVERYONE)) {
            return ownerOnline;
        }
        return false;
    }

    public boolean isDenied(UUID uuid, boolean ownerOnline) {
        if (isAdded(uuid, ownerOnline)) {
            return false;
        }
        return denied.contains(uuid) || denied.contains(EVERYONE);
    }

    /**
     * PlotSquared 把 merged 陣列編碼成 4 個位元，merged[0] 是最高位。
     */
    public int mergedHash() {
        return ((merged[0] ? 1 : 0) << 3) + ((merged[1] ? 1 : 0) << 2) + ((merged[2] ? 1 : 0) << 1) + (merged[3] ? 1 : 0);
    }

    public void mergedFromHash(int hash) {
        for (int i = 0; i < 4; i++) {
            merged[3 - i] = (hash & 1 << i) != 0;
        }
    }

    @Override
    public String toString() {
        return area + ";" + id;
    }

}
