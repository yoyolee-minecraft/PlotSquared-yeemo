package com.yeemo.yeeplot.plot;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 尚未完成的清除／刪除／修復道路工作，存在 pending-regen.yml。
 * 伺服器在途中關閉時，下次啟動會從頭再做一次（重鋪是冪等的，重做不會出錯），避免地形只清一半。
 */
public final class RegenJobs {

    public enum Mode {
        /**
         * 清除地皮：範圍內全部鋪成地皮地板。
         */
        CLEAR,
        /**
         * 刪除地皮：範圍內照生成器原樣還原道路、圍牆與地皮。
         */
        DELETE,
        /**
         * 修復道路：只處理範圍內不屬於任何地皮的道路與圍牆。
         */
        FIX_ROADS
    }

    public record Job(String id, String world, Mode mode, List<int[]> rects) {
    }

    private final File file;
    private final Logger logger;
    private final List<Job> jobs = new ArrayList<>();

    public RegenJobs(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("jobs");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            try {
                String world = section.getString(id + ".world");
                Mode mode = Mode.valueOf(section.getString(id + ".mode", ""));
                List<int[]> rects = new ArrayList<>();
                for (String rect : section.getStringList(id + ".rects")) {
                    String[] parts = rect.split(",");
                    rects.add(new int[]{
                            Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())
                    });
                }
                if (world != null && !rects.isEmpty()) {
                    jobs.add(new Job(id, world, mode, rects));
                }
            } catch (RuntimeException e) {
                logger.warning("略過 pending-regen.yml 中無法讀取的工作 " + id);
            }
        }
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Job job : jobs) {
            String path = "jobs." + job.id();
            config.set(path + ".world", job.world());
            config.set(path + ".mode", job.mode().name());
            List<String> rects = new ArrayList<>();
            for (int[] rect : job.rects()) {
                rects.add(rect[0] + "," + rect[1] + "," + rect[2] + "," + rect[3]);
            }
            config.set(path + ".rects", rects);
        }
        try {
            config.save(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "無法儲存 pending-regen.yml", e);
        }
    }

    public Job add(String world, Mode mode, List<int[]> rects) {
        Job job = new Job(UUID.randomUUID().toString(), world, mode, rects);
        jobs.add(job);
        save();
        return job;
    }

    public void complete(Job job) {
        if (jobs.remove(job)) {
            save();
        }
    }

    public List<Job> pending() {
        return new ArrayList<>(jobs);
    }

}
