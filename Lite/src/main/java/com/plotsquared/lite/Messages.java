package com.plotsquared.lite;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 讀取 config.yml 的 messages 區段，支援 &amp; 色碼與 {key} 佔位符。
 */
public final class Messages {

    private ConfigurationSection section;

    public Messages(ConfigurationSection section) {
        this.section = section;
    }

    /**
     * /plot reload 時替換訊息內容，讓已持有此物件的監聽器也能拿到新訊息。
     */
    public void update(ConfigurationSection section) {
        this.section = section;
    }

    public String format(String key, String... placeholders) {
        String raw = section == null ? null : section.getString(key);
        if (raw == null) {
            raw = key;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        String prefix = section == null ? "" : section.getString("prefix", "");
        String message = format(key, placeholders);
        for (String line : message.split("\n")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix) + line);
        }
    }

}
