package com.yeemo.yeeplot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/**
 * 讀取 config.yml 的 messages 區段，支援 &amp; 色碼與 {key} 佔位符。
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

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
        return fill(raw(key), placeholders);
    }

    private String raw(String key) {
        String raw = section == null ? null : section.getString(key);
        return raw == null ? key : raw;
    }

    private static String fill(String raw, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private String prefix() {
        return ChatColor.translateAlternateColorCodes('&', section == null ? "" : section.getString("prefix", ""));
    }

    public Component component(String key, String... placeholders) {
        return LEGACY.deserialize(format(key, placeholders));
    }

    /**
     * 一段可以點擊的文字：點擊執行 command，滑鼠移上去顯示 hoverKey 的內容。
     */
    public Component clickable(String key, String hoverKey, String command, String... placeholders) {
        return component(key, placeholders)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(component(hoverKey, placeholders)));
    }

    public void sendClickable(CommandSender sender, String key, String hoverKey, String command, String... placeholders) {
        sendLine(sender, clickable(key, hoverKey, command, placeholders));
    }

    /**
     * 送出一行由多段文字組成的訊息，前面加上 prefix。每段各自帶顏色，不會延續 prefix 的顏色。
     */
    public void sendLine(CommandSender sender, Component... parts) {
        TextComponent.Builder line = Component.text().append(LEGACY.deserialize(prefix()));
        for (Component part : parts) {
            line.append(part);
        }
        sender.sendMessage(line.build());
    }

    /**
     * 與 send 相同，但 components 裡的佔位符換成元件（例如可點擊的按鈕）。
     */
    public void sendRich(CommandSender sender, String key, Map<String, Component> components, String... placeholders) {
        String prefix = prefix();
        // 先在樣板上找出元件的位置再填入文字，避免別名之類的文字剛好含有 {trusted} 而被換掉
        for (String line : raw(key).split("\n")) {
            TextComponent.Builder builder = Component.text();
            String rest = line;
            // prefix 與元件後面的文字要延續前面的顏色，所以接在下一段文字前面一起轉換
            String carry = prefix;
            while (true) {
                int at = -1;
                String found = null;
                for (String name : components.keySet()) {
                    int index = rest.indexOf("{" + name + "}");
                    if (index >= 0 && (at < 0 || index < at)) {
                        at = index;
                        found = name;
                    }
                }
                if (found == null) {
                    builder.append(LEGACY.deserialize(carry + fill(rest, placeholders)));
                    break;
                }
                String before = carry + fill(rest.substring(0, at), placeholders);
                builder.append(LEGACY.deserialize(before));
                builder.append(components.get(found));
                carry = ChatColor.getLastColors(before);
                rest = rest.substring(at + found.length() + 2);
            }
            sender.sendMessage(builder.build());
        }
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        String prefix = prefix();
        String message = format(key, placeholders);
        for (String line : message.split("\n")) {
            sender.sendMessage(prefix + line);
        }
    }

}
