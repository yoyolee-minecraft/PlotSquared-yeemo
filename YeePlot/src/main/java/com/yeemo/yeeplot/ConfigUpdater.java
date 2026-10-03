package com.yeemo.yeeplot;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 把舊版 config.yml 補成新版：缺少的設定加上預設值與註解，缺少新欄位的 info、help 樣板換成新版。
 * 其他已經存在的設定一律保留。
 */
public final class ConfigUpdater {

    /**
     * {設定路徑, 新版樣板一定會有的內容}；舊樣板沒有這段內容時換成新版（自訂過的內容會被覆蓋）。
     * 例如 1.3.1 起列表優先顯示別名，舊的 list-entry 樣板沒有 {name}，會換成新版。
     */
    private static final String[][] TEMPLATES = {
            {"messages.info", "{displays}"},
            {"messages.help", "[編號|別名]"},
            {"messages.list-entry", "{name}"}
    };

    private ConfigUpdater() {
    }

    /**
     * @return 新增或更新的設定路徑；空的代表不需要存檔
     */
    public static List<String> update(FileConfiguration config) {
        List<String> updated = new ArrayList<>();
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return updated;
        }
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || config.isSet(key)) {
                continue;
            }
            config.set(key, defaults.get(key));
            List<String> comments = defaults.getComments(key);
            if (!comments.isEmpty()) {
                config.setComments(key, comments);
            }
            updated.add(key);
        }
        for (String[] template : TEMPLATES) {
            String current = config.getString(template[0]);
            if (current != null && config.isSet(template[0]) && !current.contains(template[1])
                    && !updated.contains(template[0])) {
                config.set(template[0], defaults.getString(template[0]));
                updated.add(template[0]);
            }
        }
        return updated;
    }

}
