package com.yeemo.yeeplot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    /**
     * 送出一行可以點擊的訊息：點擊執行 command，滑鼠移上去顯示 hoverKey 的內容。
     */
    public void sendClickable(CommandSender sender, String key, String hoverKey, String command, String... placeholders) {
        String prefix = section == null ? "" : section.getString("prefix", "");
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
        Component line = serializer.deserialize(ChatColor.translateAlternateColorCodes('&', prefix) + format(key, placeholders))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(serializer.deserialize(format(hoverKey, placeholders))));
        sender.sendMessage(line);
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        String prefix = section == null ? "" : section.getString("prefix", "");
        String message = format(key, placeholders);
        for (String line : message.split("\n")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix) + line);
        }
    }

}
