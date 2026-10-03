package com.plotsquared.lite.storage;

import com.plotsquared.lite.plot.Plot;
import com.plotsquared.lite.plot.PlotId;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 直接讀寫 PlotSquared 的資料庫（SQLite 的 storage.db 或 MySQL），資料表結構完全相同，
 * 因此可以隨時在 PlotSquared 與 PlotSquaredLite 之間切換。
 * <p>
 * 啟動時一次把地皮載入記憶體，之後所有寫入都丟到單一背景執行緒依序執行，不會卡主執行緒。
 */
public final class Database {

    private final Logger logger;
    private final boolean mySQL;
    private final String prefix;
    private final Connection connection;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PlotSquaredLite-Database");
        thread.setDaemon(true);
        return thread;
    });

    private Database(Logger logger, boolean mySQL, String prefix, Connection connection) {
        this.logger = logger;
        this.mySQL = mySQL;
        this.prefix = prefix;
        this.connection = connection;
    }

    /**
     * 依照 PlotSquared 的 config/storage.yml 開啟資料庫。
     *
     * @param dataFolder PlotSquared 的資料夾（通常是 plugins/PlotSquared）
     */
    public static Database open(File dataFolder, Logger logger) throws SQLException {
        File storageFile = new File(dataFolder, "config/storage.yml");
        YamlConfiguration storage = storageFile.exists()
                ? YamlConfiguration.loadConfiguration(storageFile)
                : new YamlConfiguration();
        String prefix = storage.getString("prefix", "");
        ConfigurationSection mysql = storage.getConfigurationSection("mysql");
        Connection connection;
        boolean useMySQL = mysql != null && mysql.getBoolean("use", false);
        if (useMySQL) {
            StringBuilder url = new StringBuilder("jdbc:mysql://")
                    .append(mysql.getString("host", "localhost")).append(':')
                    .append(mysql.getString("port", "3306")).append('/')
                    .append(mysql.getString("database", "plot_db"));
            List<String> properties = mysql.getStringList("properties");
            if (properties.isEmpty()) {
                properties = List.of("useSSL=false");
            }
            url.append('?').append(String.join("&", properties));
            connection = DriverManager.getConnection(
                    url.toString(),
                    mysql.getString("user", "root"),
                    mysql.getString("password", "")
            );
            logger.info("使用 MySQL 資料庫");
        } else {
            String name = storage.getString("sqlite.db", "storage");
            File file = new File(dataFolder, name + ".db");
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                throw new SQLException("無法建立資料夾 " + file.getParentFile());
            }
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("伺服器缺少 SQLite 驅動", e);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            logger.info("使用 SQLite 資料庫：" + file.getPath());
        }
        Database database = new Database(logger, useMySQL, prefix, connection);
        database.createTables();
        return database;
    }

    private String table(String name) {
        return "`" + prefix + name + "`";
    }

    /**
     * 建立 PlotSquared 用到的地皮資料表（已存在則略過），DDL 與 PlotSquared 的 SQLManager 一致。
     */
    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (mySQL) {
                String engine = " ENGINE=InnoDB DEFAULT CHARSET=utf8";
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot") + " ("
                        + "`id` INT(11) NOT NULL AUTO_INCREMENT,`plot_id_x` INT(11) NOT NULL,"
                        + "`plot_id_z` INT(11) NOT NULL,`owner` VARCHAR(40) NOT NULL,`world` VARCHAR(45) NOT NULL,"
                        + "`timestamp` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY (`id`))"
                        + engine + " AUTO_INCREMENT=0");
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_denied")
                        + " (`plot_plot_id` INT(11) NOT NULL,`user_uuid` VARCHAR(40) NOT NULL)" + engine);
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_helpers")
                        + " (`plot_plot_id` INT(11) NOT NULL,`user_uuid` VARCHAR(40) NOT NULL)" + engine);
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_trusted")
                        + " (`plot_plot_id` INT(11) NOT NULL,`user_uuid` VARCHAR(40) NOT NULL)" + engine);
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_settings") + " ("
                        + "`plot_plot_id` INT(11) NOT NULL,`biome` VARCHAR(45) DEFAULT 'FOREST',`rain` INT(1) DEFAULT 0,"
                        + "`custom_time` TINYINT(1) DEFAULT '0',`time` INT(11) DEFAULT '8000',"
                        + "`deny_entry` TINYINT(1) DEFAULT '0',`alias` VARCHAR(50) DEFAULT NULL,"
                        + "`merged` INT(11) DEFAULT NULL,`position` VARCHAR(50) NOT NULL DEFAULT 'DEFAULT',"
                        + "PRIMARY KEY (`plot_plot_id`))" + engine);
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_rating")
                        + " (`plot_plot_id` INT(11) NOT NULL,`rating` INT(2) NOT NULL,`player` VARCHAR(40) NOT NULL)"
                        + engine);
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_flags") + "("
                        + "`id` INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,`plot_id` INT(11) NOT NULL,"
                        + "`flag` VARCHAR(64),`value` VARCHAR(512),"
                        + "FOREIGN KEY (plot_id) REFERENCES " + table("plot") + " (id) ON DELETE CASCADE,"
                        + "UNIQUE (plot_id, flag))" + engine);
            } else {
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot") + " ("
                        + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,`plot_id_x` INT(11) NOT NULL,"
                        + "`plot_id_z` INT(11) NOT NULL,`owner` VARCHAR(45) NOT NULL,`world` VARCHAR(45) NOT NULL,"
                        + "`timestamp` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_denied")
                        + " (`plot_plot_id` INT(11) NOT NULL,`user_uuid` VARCHAR(40) NOT NULL)");
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_helpers")
                        + " (`plot_plot_id` INT(11) NOT NULL,`user_uuid` VARCHAR(40) NOT NULL)");
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_trusted")
                        + " (`plot_plot_id` INT(11) NOT NULL,`user_uuid` VARCHAR(40) NOT NULL)");
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_settings") + " ("
                        + "`plot_plot_id` INT(11) NOT NULL,`biome` VARCHAR(45) DEFAULT 'FOREST',`rain` INT(1) DEFAULT 0,"
                        + "`custom_time` TINYINT(1) DEFAULT '0',`time` INT(11) DEFAULT '8000',"
                        + "`deny_entry` TINYINT(1) DEFAULT '0',`alias` VARCHAR(50) DEFAULT NULL,"
                        + "`merged` INT(11) DEFAULT NULL,`position` VARCHAR(50) NOT NULL DEFAULT 'DEFAULT',"
                        + "PRIMARY KEY (`plot_plot_id`))");
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_rating")
                        + " (`plot_plot_id` INT(11) NOT NULL,`rating` INT(2) NOT NULL,`player` VARCHAR(40) NOT NULL)");
                statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("plot_flags") + "("
                        + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,`plot_id` INTEGER NOT NULL,"
                        + "`flag` VARCHAR(64),`value` VARCHAR(512),"
                        + "FOREIGN KEY (plot_id) REFERENCES " + table("plot") + " (id) ON DELETE CASCADE,"
                        + "UNIQUE (plot_id, flag))");
            }
            statement.executeBatch();
        }
    }

    /**
     * 載入全部地皮，回傳 世界名稱 → (地皮座標 → 地皮)。
     * 與 PlotSquared 相同：非 UUID 格式的擁有者會轉成離線模式 UUID。
     */
    public Map<String, Map<PlotId, Plot>> loadPlots() throws SQLException {
        Map<String, Map<PlotId, Plot>> result = new HashMap<>();
        Map<Integer, Plot> byId = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT `id`, `plot_id_x`, `plot_id_z`, `owner`, `world`, `timestamp` FROM " + table("plot"))) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    PlotId plotId = new PlotId(rs.getInt("plot_id_x"), rs.getInt("plot_id_z"));
                    String world = rs.getString("world");
                    UUID owner = parseOwner(rs.getString("owner"));
                    Plot plot = new Plot(world, plotId, owner, readTimestamp(rs, id));
                    plot.dbId(id);
                    Plot previous = result.computeIfAbsent(world, k -> new HashMap<>()).put(plotId, plot);
                    if (previous != null) {
                        logger.warning("地皮 " + world + ";" + plotId + " 在資料庫中重複（#" + previous.dbId()
                                + " 與 #" + id + "），以後者為準");
                    }
                    byId.put(id, plot);
                }
            }
            loadUsers(statement, "plot_helpers", byId, Plot::trusted);
            loadUsers(statement, "plot_trusted", byId, Plot::members);
            loadUsers(statement, "plot_denied", byId, Plot::denied);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT `plot_plot_id`, `alias`, `merged`, `position` FROM " + table("plot_settings"))) {
                while (rs.next()) {
                    Plot plot = byId.get(rs.getInt("plot_plot_id"));
                    if (plot == null) {
                        continue;
                    }
                    plot.alias(rs.getString("alias"));
                    plot.mergedFromHash(rs.getInt("merged"));
                    plot.position(rs.getString("position"));
                }
            }
        }
        return result;
    }

    private void loadUsers(
            Statement statement,
            String tableName,
            Map<Integer, Plot> byId,
            java.util.function.Function<Plot, java.util.Set<UUID>> target
    ) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT `user_uuid`, `plot_plot_id` FROM " + table(tableName))) {
            while (rs.next()) {
                Plot plot = byId.get(rs.getInt("plot_plot_id"));
                if (plot == null) {
                    continue;
                }
                try {
                    target.apply(plot).add(UUID.fromString(rs.getString("user_uuid")));
                } catch (IllegalArgumentException e) {
                    logger.warning("略過 " + tableName + " 中無效的 UUID：" + rs.getString("user_uuid"));
                }
            }
        }
    }

    private static UUID parseOwner(String owner) {
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + owner).getBytes(StandardCharsets.UTF_8));
        }
    }

    private long readTimestamp(ResultSet rs, int id) {
        try {
            Timestamp timestamp = rs.getTimestamp("timestamp");
            if (timestamp != null) {
                return timestamp.getTime();
            }
        } catch (SQLException ignored) {
            // SQLite 可能存成文字，往下用字串解析
        }
        try {
            String text = rs.getString("timestamp");
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text).getTime();
        } catch (SQLException | ParseException | NullPointerException e) {
            return System.currentTimeMillis() + id;
        }
    }

    // ---------------------------------------------------------------- 寫入（全部非同步、依序執行）

    private void submit(String description, SqlTask task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "資料庫操作失敗：" + description, e);
            }
        });
    }

    public void createPlot(Plot plot, Runnable whenDone) {
        final int x = plot.id().x();
        final int z = plot.id().z();
        final String owner = plot.owner().toString();
        final String world = plot.area();
        final long timestamp = plot.timestamp();
        submit("建立地皮 " + plot, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + table("plot")
                            + "(`plot_id_x`, `plot_id_z`, `owner`, `world`, `timestamp`) VALUES(?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, x);
                statement.setInt(2, z);
                statement.setString(3, owner);
                statement.setString(4, world);
                statement.setTimestamp(5, new Timestamp(timestamp));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        plot.dbId(keys.getInt(1));
                    }
                } catch (SQLException ignored) {
                    // 新版 sqlite-jdbc 可能不支援 getGeneratedKeys，下面改用查詢取得 id
                }
            }
            if (plot.dbId() < 0) {
                plot.dbId(lookupId(world, x, z));
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + table("plot_settings") + "(`plot_plot_id`) VALUES(?)")) {
                statement.setInt(1, plot.dbId());
                statement.executeUpdate();
            }
            if (whenDone != null) {
                whenDone.run();
            }
        });
    }

    private int lookupId(String world, int x, int z) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT `id` FROM " + table("plot")
                        + " WHERE `world` = ? AND `plot_id_x` = ? AND `plot_id_z` = ? ORDER BY `id` DESC")) {
            statement.setString(1, world);
            statement.setInt(2, x);
            statement.setInt(3, z);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("找不到剛建立的地皮 " + world + ";" + x + ";" + z);
    }

    /**
     * 刪除地皮與所有關聯資料，順序與 PlotSquared 的 SQLManager#delete 相同。
     */
    public void deletePlot(Plot plot) {
        submit("刪除地皮 " + plot, () -> {
            int id = plot.dbId();
            if (id < 0) {
                return;
            }
            for (String[] entry : new String[][]{
                    {"plot_settings", "plot_plot_id"},
                    {"plot_denied", "plot_plot_id"},
                    {"plot_helpers", "plot_plot_id"},
                    {"plot_trusted", "plot_plot_id"},
                    {"plot_rating", "plot_plot_id"},
                    {"plot_flags", "plot_id"},
                    {"plot", "id"}
            }) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table(entry[0]) + " WHERE `" + entry[1] + "` = ?")) {
                    statement.setInt(1, id);
                    statement.executeUpdate();
                }
            }
            plot.dbId(-1);
        });
    }

    public void setOwner(Plot plot, UUID owner) {
        final String value = owner.toString();
        submit("變更擁有者 " + plot, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + table("plot") + " SET `owner` = ? WHERE `id` = ?")) {
                statement.setString(1, value);
                statement.setInt(2, plot.dbId());
                statement.executeUpdate();
            }
        });
    }

    public void setPosition(Plot plot, String position) {
        final String value = position == null ? "" : position;
        submit("設定家園位置 " + plot, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + table("plot_settings") + " SET `position` = ? WHERE `plot_plot_id` = ?")) {
                statement.setString(1, value);
                statement.setInt(2, plot.dbId());
                statement.executeUpdate();
            }
        });
    }

    public enum UserTable {
        /**
         * PlotSquared 的 trusted（隨時可建築）。
         */
        TRUSTED("plot_helpers"),
        /**
         * PlotSquared 的 member（擁有者在線才可建築）。
         */
        MEMBER("plot_trusted"),
        DENIED("plot_denied");

        private final String table;

        UserTable(String table) {
            this.table = table;
        }
    }

    public void addUser(Plot plot, UserTable userTable, UUID uuid) {
        final String value = uuid.toString();
        submit("新增 " + userTable + " " + plot, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + table(userTable.table) + " (`plot_plot_id`, `user_uuid`) VALUES(?, ?)")) {
                statement.setInt(1, plot.dbId());
                statement.setString(2, value);
                statement.executeUpdate();
            }
        });
    }

    public void removeUser(Plot plot, UserTable userTable, UUID uuid) {
        final String value = uuid.toString();
        submit("移除 " + userTable + " " + plot, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table(userTable.table) + " WHERE `plot_plot_id` = ? AND `user_uuid` = ?")) {
                statement.setInt(1, plot.dbId());
                statement.setString(2, value);
                statement.executeUpdate();
            }
        });
    }

    /**
     * 等待所有排隊中的寫入完成後關閉連線。
     */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("資料庫寫入在 30 秒內未完成，部分變更可能遺失");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "關閉資料庫失敗", e);
        }
    }

    @FunctionalInterface
    private interface SqlTask {

        void run() throws SQLException;

    }

}
