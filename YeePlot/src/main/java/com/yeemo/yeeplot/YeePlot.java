package com.yeemo.yeeplot;

import com.yeemo.yeeplot.command.PlotCommand;
import com.yeemo.yeeplot.hook.AxiomHook;
import com.yeemo.yeeplot.hook.DisplayBounds;
import com.yeemo.yeeplot.hook.DisplayLimit;
import com.yeemo.yeeplot.hook.EditAccess;
import com.yeemo.yeeplot.hook.FaweHook;
import com.yeemo.yeeplot.hook.WorldEditHook;
import com.yeemo.yeeplot.listener.PlotEffectsListener;
import com.yeemo.yeeplot.listener.PlotPermissions;
import com.yeemo.yeeplot.listener.ProtectionListener;
import com.yeemo.yeeplot.plot.PlotManager;
import com.yeemo.yeeplot.plot.PlotService;
import com.yeemo.yeeplot.plot.RegenJobs;
import com.yeemo.yeeplot.storage.Database;
import com.yeemo.yeeplot.world.PlotGenerator;
import com.yeemo.yeeplot.world.PlotWorld;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * YeePlot：PlotSquared 的輕量版，只保留地皮世界生成、認領、權限控制與名單管理，
 * 並直接沿用 PlotSquared 的 worlds.yml、storage.yml 與資料庫。
 */
public final class YeePlot extends JavaPlugin {

    private final PlotManager plotManager = new PlotManager();
    private File sourceFolder;
    private File worldsFile;
    private YamlConfiguration worldsConfig;
    private Messages messages;
    private Database database;
    private PlotService plotService;
    private PlotPermissions permissions;
    private boolean globalLimit;
    private int maxPlotsPermission;
    private boolean teleportOnClaim;
    private Runnable unregisterHooks;
    private DisplayLimit displayLimit;
    private PlotEffectsListener effects;

    @Override
    public void onLoad() {
        migrateFromOldName();
        saveDefaultConfig();
        loadSettings();
        // 生成器可能在 onEnable 之前就被要求，所以在 onLoad 先讀取世界設定
        loadWorlds();
    }

    @Override
    public void onEnable() {
        try {
            database = Database.open(sourceFolder, getLogger());
            plotManager.setPlots(database.loadPlots());
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "無法開啟資料庫，插件停用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        plotService = new PlotService(this, plotManager, database,
                new RegenJobs(new File(getDataFolder(), "pending-regen.yml"), getLogger()));
        plotService.setBlocksPerTick(getConfig().getInt("clear.blocks-per-tick", 40000));
        permissions = new PlotPermissions(plotManager);

        getServer().getPluginManager().registerEvents(
                new ProtectionListener(plotManager, permissions, messages, getConfig().getBoolean("disable-pvp", true)),
                this
        );
        displayLimit = new DisplayLimit(plotManager, getConfig().getInt("displays.per-plot", 100));
        effects = new PlotEffectsListener(this, plotManager);
        getServer().getPluginManager().registerEvents(effects, this);
        PlotCommand command = new PlotCommand(this);
        for (String name : new String[]{"plots", "plotlist"}) {
            PluginCommand pluginCommand = getCommand(name);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(command);
                pluginCommand.setTabCompleter(command);
            }
        }

        int total = 0;
        for (PlotWorld world : plotManager.worlds()) {
            total += plotManager.countPlots(world.name());
        }
        getLogger().info("已載入 " + plotManager.worlds().size() + " 個地皮世界、" + total + " 塊地皮");

        // 等伺服器載入完預設世界、其他插件都啟用後，再補載地皮世界並接上 WorldEdit / Axiom
        Bukkit.getScheduler().runTask(this, () -> {
            loadMissingWorlds();
            registerHooks();
            plotService.resumeJobs();
        });
    }

    @Override
    public void onDisable() {
        if (unregisterHooks != null) {
            unregisterHooks.run();
        }
        if (database != null) {
            database.close();
        }
    }

    // ---------------------------------------------------------------- WorldEdit / FAWE / Axiom

    private void registerHooks() {
        EditAccess editAccess = new EditAccess(plotManager);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                editAccess.forget(event.getPlayer().getUniqueId());
            }
        }, this);

        Plugin fawe = getServer().getPluginManager().getPlugin("FastAsyncWorldEdit");
        Plugin worldEdit = getServer().getPluginManager().getPlugin("WorldEdit");
        try {
            if (fawe != null && fawe.isEnabled()) {
                FaweHook hook = new FaweHook(editAccess);
                hook.register();
                // FAWE 的範圍由遮罩管理器處理；另外掛 EditSession 監聽，貼上時略過展示實體
                WorldEditHook displayFilter = new WorldEditHook(editAccess, messages, false);
                displayFilter.register();
                unregisterHooks = () -> {
                    hook.unregister();
                    displayFilter.unregister();
                };
                getLogger().info("已接上 FastAsyncWorldEdit：玩家只能編輯自己所在且有權限的地皮");
            } else if (worldEdit != null && worldEdit.isEnabled()) {
                WorldEditHook hook = new WorldEditHook(editAccess, messages, true);
                hook.register();
                unregisterHooks = hook::unregister;
                getLogger().info("已接上 WorldEdit：玩家只能編輯自己所在且有權限的地皮");
            }
        } catch (LinkageError e) {
            getLogger().log(Level.SEVERE, "無法接上 WorldEdit，地皮世界中的 WorldEdit 編輯不受保護！", e);
        }

        Plugin axiom = AxiomHook.findAxiom(getServer().getPluginManager().getPlugins());
        if (axiom != null) {
            DisplayBounds displayBounds = new DisplayBounds(plotManager, permissions);
            displayBounds.setItemSize((float) getConfig().getDouble("axiom.item-display-size", 1.0));
            new AxiomHook(this, plotManager, permissions, messages, displayBounds, displayLimit).register(axiom);
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (plotManager.world(worldName) == null) {
            // 新的地皮世界：以 PlotSquared 的格式寫入預設設定
            ConfigurationSection section = worldsConfig.getConfigurationSection("worlds." + worldName);
            if (section == null) {
                section = worldsConfig.createSection("worlds." + worldName);
            }
            PlotWorld.writeDefaults(section);
            saveWorlds();
            loadWorlds();
        }
        return new PlotGenerator(() -> plotManager.world(worldName));
    }

    // ---------------------------------------------------------------- 設定

    /**
     * 1.0.0 版的插件名稱是 PlotSquaredLite，資料夾在 plugins/PlotSquaredLite。
     * 第一次以 YeePlot 啟動時，把舊的 config.yml 與未完成的地形還原工作搬過來；
     * 舊設定若還是預設的「[地皮]」前綴，順便換成新的前綴。
     */
    private void migrateFromOldName() {
        File folder = getDataFolder();
        File old = new File(folder.getParentFile(), "PlotSquaredLite");
        if (folder.exists() || !old.isDirectory()) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(folder.toPath());
            for (String name : new String[]{"config.yml", "pending-regen.yml"}) {
                File source = new File(old, name);
                if (source.isFile()) {
                    java.nio.file.Files.copy(source.toPath(), new File(folder, name).toPath());
                }
            }
            File config = new File(folder, "config.yml");
            if (config.isFile()) {
                String text = java.nio.file.Files.readString(config.toPath());
                text = text.replace("prefix: \"&8[&6地皮&8] &7\"", "prefix: \"&8[&6Yeemo小幫手&8] &7\"");
                java.nio.file.Files.writeString(config.toPath(), text);
            }
            getLogger().info("已從 plugins/PlotSquaredLite 搬移設定，確認無誤後可以刪除舊資料夾");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "搬移 plugins/PlotSquaredLite 的設定失敗", e);
        }
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        File serverRoot = getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
        String folder = config.getString("data-folder", "plugins/PlotSquared");
        File resolved = new File(folder);
        sourceFolder = resolved.isAbsolute() ? resolved : new File(serverRoot, folder);
        if (!sourceFolder.exists() && !sourceFolder.mkdirs()) {
            getLogger().severe("無法建立資料夾 " + sourceFolder);
        }
        if (messages == null) {
            messages = new Messages(config.getConfigurationSection("messages"));
        } else {
            messages.update(config.getConfigurationSection("messages"));
        }
        globalLimit = config.getBoolean("limits.global", false);
        maxPlotsPermission = config.getInt("limits.max-plots", 127);
        teleportOnClaim = config.getBoolean("teleport-on-claim", true);
    }

    private void loadWorlds() {
        worldsFile = new File(sourceFolder, "config/worlds.yml");
        worldsConfig = YamlConfiguration.loadConfiguration(worldsFile);
        Map<String, PlotWorld> worlds = new HashMap<>();
        ConfigurationSection section = worldsConfig.getConfigurationSection("worlds");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection worldSection = section.getConfigurationSection(name);
                if (worldSection == null) {
                    continue;
                }
                String type = worldSection.getString("generator.type", "0");
                if (!"0".equals(type) && !"NORMAL".equalsIgnoreCase(type)) {
                    getLogger().warning("世界 " + name + " 使用 PlotSquared 的部分地皮區域 (generator.type=" + type
                            + ")，YeePlot 不支援，已略過");
                    continue;
                }
                worlds.put(name, new PlotWorld(name, worldSection, getLogger()));
                File roadSchematic = new File(sourceFolder, "schematics/GEN_ROAD_SCHEMATIC/" + name);
                if (roadSchematic.isDirectory()) {
                    getLogger().warning("世界 " + name + " 有道路模板 (road schematic)，YeePlot 不會套用到新生成的區塊");
                }
            }
        }
        plotManager.setWorlds(worlds);
    }

    private void saveWorlds() {
        try {
            File parent = worldsFile.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IOException("無法建立資料夾 " + parent);
            }
            worldsConfig.save(worldsFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "無法儲存 worlds.yml", e);
        }
    }

    private void loadMissingWorlds() {
        for (PlotWorld plotWorld : plotManager.worlds()) {
            String name = plotWorld.name();
            World world = Bukkit.getWorld(name);
            if (world == null) {
                getLogger().info("載入地皮世界 " + name);
                world = new WorldCreator(name)
                        .generator(new PlotGenerator(() -> plotManager.world(name)))
                        .createWorld();
            }
            if (world != null && !(world.getGenerator() instanceof PlotGenerator)) {
                getLogger().warning("世界 " + name + " 不是由 YeePlot 生成器載入，新區塊不會生成地皮地形。"
                        + "請把 bukkit.yml 或 Multiverse 中的 generator 從 PlotSquared 改成 YeePlot");
            }
        }
    }

    /**
     * /plot reload：重新讀取 config.yml 與 worlds.yml（資料庫內容不受影響）。
     */
    public void reload() {
        loadSettings();
        loadWorlds();
        if (plotService != null) {
            plotService.setBlocksPerTick(getConfig().getInt("clear.blocks-per-tick", 40000));
        }
        if (displayLimit != null) {
            displayLimit.setPerPlot(getConfig().getInt("displays.per-plot", 100));
        }
    }

    // ---------------------------------------------------------------- 存取器

    public PlotManager plotManager() {
        return plotManager;
    }

    public PlotService plotService() {
        return plotService;
    }

    public PlotPermissions permissions() {
        return permissions;
    }

    public Messages messages() {
        return messages;
    }

    public boolean globalLimit() {
        return globalLimit;
    }

    public int maxPlotsPermission() {
        return maxPlotsPermission;
    }

    public DisplayLimit displayLimit() {
        return displayLimit;
    }

    public PlotEffectsListener effects() {
        return effects;
    }

    /**
     * 沒有 plots.merge.<數字> 權限時，合併群組最多幾塊地皮。
     */
    public int mergeDefaultMax() {
        return getConfig().getInt("merge.default-max-plots", 4);
    }

    public int fixRoadsMaxRadius() {
        return getConfig().getInt("fix-roads.max-radius", 128);
    }

    public boolean teleportOnClaim() {
        return teleportOnClaim;
    }

}
